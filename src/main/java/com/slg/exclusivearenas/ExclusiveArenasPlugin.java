package com.slg.exclusivearenas;

import de.marcely.bedwars.api.BedwarsAPI;
import de.marcely.bedwars.api.arena.Arena;
import de.marcely.bedwars.api.arena.ArenaStatus;
import de.marcely.bedwars.api.arena.ArenaTimeType;
import de.marcely.bedwars.api.arena.ArenaWeatherType;
import de.marcely.bedwars.api.arena.Team;
import de.marcely.bedwars.api.game.scoreboard.ScoreboardUpdateCause;
import de.marcely.bedwars.api.game.spectator.KickSpectatorReason;
import de.marcely.bedwars.api.game.spectator.SpectateReason;
import de.marcely.bedwars.api.hook.PartiesHook;
import de.marcely.bedwars.api.remote.RemoteAPI;
import de.marcely.bedwars.api.remote.RemoteArena;
import de.marcely.bedwars.api.remote.RemotePlayer;
import de.marcely.bedwars.api.remote.RemotePlayerAddResult;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class ExclusiveArenasPlugin extends JavaPlugin {

    private ExclusiveArenasAddon addon;
    private EaConfig eaConfig;
    private VersionedYaml langYaml;
    private VersionedYaml guisYaml;
    private DraftService draftService;
    /** Players with a create-and-join in flight (past the async party check) — guards against a
     *  double-click or double-command firing two overlapping creations for the same player.
     *  Keyed to a per-attempt token (rather than a plain Set) so a timed-out attempt's late
     *  callback can never clobber a newer attempt that started after the timeout released it. */
    private final Map<UUID, Object> creatingSessionFor = new java.util.concurrent.ConcurrentHashMap<>();
    private PrivateSessionService sessionService;
    private JoinTicketService ticketService;
    private GuiManager guiManager;
    private TimelineService timelineService;
    private EventTimelineEngine timelineEngine;
    private QuickActionsService quickActions;
    private TweaksTimelineBridge tweaksBridge; // null unless MBedwarsTweaks provides the timeline
    private PresetService presetService;
    private org.bukkit.scheduler.BukkitTask guiRefreshTask;
    private Database database;   // null when running single-server (database.enabled = false)
    // Bumped by every setupDatabase()/teardownDatabase() call; lets a setupDatabase() attempt
    // still connecting asynchronously recognize it's been superseded and discard its result
    // instead of clobbering newer state (e.g. two /ea reload calls in quick succession).
    private volatile long dbSetupGeneration = 0;
    private SyncService syncService;
    private RemoteCommandService remoteCommandService;
    private org.bukkit.scheduler.BukkitTask autoSummonTask;
    private org.bukkit.scheduler.BukkitTask partyIntegrityTask;
    private PrivacyConditionVariable privacyConditionVariable;
    private ArenaBossBarTask bossBarTask;
    private org.bukkit.scheduler.BukkitTask bossBarSchedulerTask;
    private SpectateOnStartHandler spectateOnStartHandler;
    private SpectatorRejoinHandler spectatorRejoinHandler;
    private PartySummonLobbyItemHandler partySummonLobbyItemHandler;
    private TeamLockListener teamLockListener;
    private MatchControlsLobbyItemHandler matchControlsLobbyItemHandler;

    @Override
    public void onEnable() {
        // Everything depends on MBedwars (and, on a network, its RemoteAPI) being ready.
        BedwarsAPI.onReady(this::init);
    }

    private void init() {
        this.addon = new ExclusiveArenasAddon(this);
        this.addon.register();

        this.eaConfig = new EaConfig(this, addon.getDataFolder());
        this.eaConfig.load();
        loadLangAndGuis();

        this.draftService   = new DraftService();
        this.sessionService = new PrivateSessionService();
        this.ticketService  = new JoinTicketService();
        this.remoteCommandService = new RemoteCommandService(this, sessionService);
        this.timelineService = new TimelineService(getLogger());
        this.timelineService.load(eaConfig);
        this.guiManager     = new GuiManager(this, draftService, sessionService);
        this.quickActions   = new QuickActionsService(this);
        this.timelineEngine = new EventTimelineEngine(this, sessionService, timelineService);
        this.presetService  = new PresetService(this, addon.getDataFolder());
        setupTweaksBridge();

        applyTunables();

        // Shared cross-server state lives in our own database. When disabled (or unreachable)
        // the plugin runs single-server in-memory exactly as before.
        setupDatabase();

        // Command
        EaCommand cmd = new EaCommand(this, draftService, sessionService, ticketService, guiManager);
        getCommand("ea").setExecutor(cmd);
        getCommand("ea").setTabCompleter(cmd);

        // Listeners
        Bukkit.getPluginManager().registerEvents(
                new JoinListener(this, sessionService, ticketService), this);
        Bukkit.getPluginManager().registerEvents(
                new PrivacyLifecycleListener(this, sessionService), this);
        Bukkit.getPluginManager().registerEvents(
                new GuiListener(this, draftService, sessionService, guiManager), this);
        Bukkit.getPluginManager().registerEvents(timelineEngine, this);
        Bukkit.getPluginManager().registerEvents(quickActions, this);
        Bukkit.getPluginManager().registerEvents(
                new ShopRulesListener(this, sessionService), this);
        Bukkit.getPluginManager().registerEvents(
                new ArenaModifiersListener(this, sessionService), this);
        this.teamLockListener = new TeamLockListener(this, sessionService);
        Bukkit.getPluginManager().registerEvents(teamLockListener, this);

        // Periodic cleanup (every 30 seconds)
        new SessionCleanupTask(this, sessionService).runTaskTimer(this, 600L, 600L);

        // Continuously checks live sessions against MBedwars' actual arena state and self-heals
        // drift (stuck sessions, stuck matches, spawner desync, arena config issues).
        long healthTicks = Math.max(200L, eaConfig.intNum("stability.health_check_seconds", 30) * 20L);
        new ArenaHealthMonitorTask(this, sessionService).runTaskTimer(this, healthTicks, healthTicks);

        // Finishes the join for anyone who ends up physically inside a private arena (e.g. after
        // a cross-server transfer) without MBedwars having actually registered them as playing.
        // Runs at the same cadence as the ticket poller (database.ticket_poll_seconds, default
        // 1s) — that poll is the dominant source of the race this recovers from, so sweeping
        // any slower would just add avoidable extra delay on top of it. The sweep itself is a
        // pure in-memory scan (no I/O), so the tighter interval costs nothing.
        long entryGuardTicks = Math.max(20L, eaConfig.intNum("database.ticket_poll_seconds", 1) * 20L);
        new ArenaEntryGuardTask(sessionService, ticketService).runTaskTimer(this, entryGuardTicks, entryGuardTicks);

        startAutoSummon();
        startPartyMonitor();
        startBossBar();
        startGuiRefresh();
        registerConditionVariable();
        registerLobbyItemHandlers();
        logEnvironment();

        getLogger().info("ExclusiveArenas v" + getDescription().getVersion() + " enabled ("
                + (database != null ? "database mode" : "single-server mode") + ").");
    }

    /**
     * Detects how the surrounding MBedwars setup is put together — parties hook, RemoteAPI,
     * MBedwarsTweaks — and logs one clear summary so an admin can see at a glance which
     * features are live. The plugin also adapts at runtime: with no parties hook everything
     * is join-code gated, and remote arenas are hidden from the map selector whenever the
     * shared database (the only way to gate/control them network-wide) isn't connected.
     */
    private void logEnvironment() {
        boolean parties = PartyResolver.hasPartiesHook();
        boolean remoteApi = isRemoteApiActive();
        boolean tweaks = Bukkit.getPluginManager().getPlugin("MBedwarsTweaks") != null;

        getLogger().info("MBedwars environment: parties hook "
                + (parties ? "present" : "absent (every private match will be join-code gated)")
                + ", RemoteAPI " + (remoteApi ? "active" : "inactive")
                + ", MBedwarsTweaks " + (tweaks ? ("present (timeline backend: "
                        + (tweaksBridge != null ? "tweaks" : "internal") + ")") : "absent") + ".");

        if (remoteApi && !eaConfig.bool("database.enabled", false)) {
            getLogger().warning("MBedwars' RemoteAPI is active but ExclusiveArenas' shared database is "
                    + "disabled — a private match on a remote arena could not be gated on the server that "
                    + "actually hosts it, so remote arenas are hidden from the map selector. Enable "
                    + "database.* in config.yml to host private matches across the network.");
        }
    }

    /** True when MBedwars' RemoteAPI (proxy/network mode) is up on this server. */
    public boolean isRemoteApiActive() {
        try {
            RemoteAPI api = BedwarsAPI.getRemoteAPI();
            return api != null && api.isAPIActive();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Registers our custom MBedwars lobby hotbar items. Registering only makes the handler
     * available by id — an admin still has to add an entry referencing it to MBedwars' own
     * lobby-hotbar.yml (slot/icon/name are entirely up to them):
     *   - "exclusivearenas:open_controls"    → visible only to the match's host; opens Match Controls.
     *   - "exclusivearenas:toggle_spectate"  → any active player; opts out of playing.
     *   - "exclusivearenas:rejoin_as_player" → any spectator; rejoins as a player (lobby only).
     *   - "exclusivearenas:summon_party"     → host only, party-gated matches; summons the party.
     */
    private void registerLobbyItemHandlers() {
        try {
            this.matchControlsLobbyItemHandler = new MatchControlsLobbyItemHandler(this, sessionService, guiManager);
            BedwarsAPI.getGameAPI().registerLobbyItemHandler(matchControlsLobbyItemHandler);

            this.spectateOnStartHandler = new SpectateOnStartHandler(this, sessionService);
            BedwarsAPI.getGameAPI().registerLobbyItemHandler(spectateOnStartHandler);

            this.spectatorRejoinHandler = new SpectatorRejoinHandler(this, sessionService);
            BedwarsAPI.getGameAPI().registerLobbyItemHandler(spectatorRejoinHandler);

            this.partySummonLobbyItemHandler = new PartySummonLobbyItemHandler(this, sessionService);
            BedwarsAPI.getGameAPI().registerLobbyItemHandler(partySummonLobbyItemHandler);
        } catch (Throwable t) {
            getLogger().warning("Could not register lobby item handlers: " + t.getMessage());
        }
    }

    private void unregisterLobbyItemHandlers() {
        try {
            if (matchControlsLobbyItemHandler != null) {
                BedwarsAPI.getGameAPI().unregisterLobbyItemHandler(matchControlsLobbyItemHandler);
            }
            if (spectateOnStartHandler != null) {
                BedwarsAPI.getGameAPI().unregisterLobbyItemHandler(spectateOnStartHandler);
            }
            if (spectatorRejoinHandler != null) {
                BedwarsAPI.getGameAPI().unregisterLobbyItemHandler(spectatorRejoinHandler);
            }
            if (partySummonLobbyItemHandler != null) {
                BedwarsAPI.getGameAPI().unregisterLobbyItemHandler(partySummonLobbyItemHandler);
            }
        } catch (Throwable ignored) {
            // best effort on shutdown
        }
        matchControlsLobbyItemHandler = null;
        spectateOnStartHandler = null;
        spectatorRejoinHandler = null;
        partySummonLobbyItemHandler = null;
    }

    /**
     * Registers a custom MBedwars arena-picker condition variable, "exclusivearenas_private"
     * (1 while an arena hosts one of our private matches, else 0). Server admins can reference
     * it in their ArenasGUI layout's "condition" field, e.g. "[exclusivearenas_private=0]", to
     * keep reserved private lobbies out of the public arena-picker menu.
     */
    private void registerConditionVariable() {
        try {
            this.privacyConditionVariable = new PrivacyConditionVariable(this, sessionService);
            BedwarsAPI.getArenaPickerAPI().registerConditionVariable(privacyConditionVariable);
        } catch (Throwable t) {
            getLogger().warning("Could not register the ArenasGUI condition variable: " + t.getMessage());
            this.privacyConditionVariable = null;
        }
    }

    private void unregisterConditionVariable() {
        if (privacyConditionVariable == null) return;
        try {
            BedwarsAPI.getArenaPickerAPI().unregisterConditionVariable(privacyConditionVariable);
        } catch (Throwable ignored) {
            // best effort on shutdown
        }
        privacyConditionVariable = null;
    }

    private void startBossBar() {
        stopBossBar();
        if (!eaConfig.bool("private.bossbar_enabled", true)) return;
        this.bossBarTask = new ArenaBossBarTask(sessionService);
        this.bossBarSchedulerTask = bossBarTask.runTaskTimer(this, 20L, 20L);
    }

    private void stopBossBar() {
        if (bossBarSchedulerTask != null) {
            bossBarSchedulerTask.cancel();
            bossBarSchedulerTask = null;
        }
        if (bossBarTask != null) {
            bossBarTask.shutdown();
            bossBarTask = null;
        }
    }

    /** Applies config-driven tunables that can be re-read on reload. */
    private void applyTunables() {
        ticketService.setTtlSeconds(eaConfig.intNum("private.ticket_ttl_seconds", 30));
        sessionService.setCodeLength(eaConfig.intNum("private.join_code_length", 6));
    }

    /** Loads lang.yml and guis.yml (versioned, self-healing) and points the static accessors at them. */
    private void loadLangAndGuis() {
        this.langYaml = new VersionedYaml(this, addon.getDataFolder(), "lang.yml", 4, (config, fromVersion) -> {
            boolean changed = false;
            if (fromVersion < 2) {
                // v2 retired the toggle-spectate item's two-state (on/off) rendering — it's now
                // a single static "spectate" icon, and the "rejoin as a player" half of its job
                // moved to the new dedicated spectator-rejoin.* item/messages. These keys are
                // dead; their replacements are added automatically below since they're plain new
                // keys.
                for (String dead : new String[] {
                        "spectate.item-name-on", "spectate.item-name-off",
                        "spectate.item-desc-on", "spectate.item-desc-off",
                        "spectate.rejoin-failed", "spectate.now-playing"}) {
                    config.set(dead, null);
                }
                changed = true;
            }
            if (fromVersion < 3) {
                // v3: rejoin-as-player now dispatches MBedwars' own "/bw join" command instead
                // of reimplementing the spectator→player transition, which reports its own
                // feedback — these three messages are dead.
                for (String dead : new String[] {
                        "spectator-rejoin.lobby-only", "spectator-rejoin.rejoin-failed",
                        "spectator-rejoin.now-playing"}) {
                    config.set(dead, null);
                }
                changed = true;
            }
            // v4 only ADDS keys (the team lock's messages, the timeline editor's new
            // operations) — VersionedYaml restores those by itself, so there is nothing to
            // transform for that step beyond the version stamp.
            return changed;
        });
        this.langYaml.load();
        Lang.init(langYaml);

        this.guisYaml = new VersionedYaml(this, addon.getDataFolder(), "guis.yml", 5, (config, fromVersion) -> {
            boolean changed = false;
            if (fromVersion < 2) {
                // v2 grew the Help menu a row (command reference cards for the new /ea
                // subcommands). Only move values still at their v1 defaults — a server
                // that re-laid-out its help menu keeps its layout (new cards land where
                // the bundled default puts them; overlaps are theirs to arrange).
                if (config.getInt("help.size", 27) == 27) {
                    config.set("help.size", 36);
                    changed = true;
                }
                if (config.getInt("help.buttons.back.slot", 22) == 22) {
                    config.set("help.buttons.back.slot", 31);
                    changed = true;
                }
            }
            if (fromVersion < 3) {
                // v3 reorganized Match Controls into clean 4/4-column rows plus a Kick All /
                // End Match "danger zone", and centered Quick Actions' bottom pair. Same rule
                // as above: only move values still sitting at their old (v2 and earlier)
                // defaults — a server that re-laid-out these menus keeps its own arrangement.
                changed |= moveIfDefault(config, "controls.buttons.settings.slot", 13, 10);
                changed |= moveIfDefault(config, "controls.buttons.manage-teams.slot", 15, 12);
                changed |= moveIfDefault(config, "controls.buttons.policy.slot", 19, 14);
                changed |= moveIfDefault(config, "controls.buttons.quick-actions.slot", 34, 16);
                changed |= moveIfDefault(config, "controls.buttons.start-lobby.slot", 20, 19);
                changed |= moveIfDefault(config, "controls.buttons.start-running.slot", 20, 19);
                changed |= moveIfDefault(config, "controls.buttons.public-on.slot", 22, 21);
                changed |= moveIfDefault(config, "controls.buttons.public-off.slot", 22, 21);
                changed |= moveIfDefault(config, "controls.buttons.summon-party.slot", 22, 21);
                changed |= moveIfDefault(config, "controls.buttons.regenerate-code.slot", 24, 23);
                changed |= moveIfDefault(config, "controls.buttons.go-to-arena.slot", 30, 25);
                changed |= moveIfDefault(config, "controls.buttons.kick-all.slot", 31, 30);
                changed |= moveIfDefault(config, "quick-actions.buttons.clear-items.slot", 20, 21);
                changed |= moveIfDefault(config, "quick-actions.buttons.skip-event.slot", 24, 23);
            }
            if (fromVersion < 4) {
                // v4 removed the "Cosmetics (Unavailable)" stub — MBedwars exposes no cosmetics
                // API to hook into, so there was nothing an admin could ever configure there.
                config.set("arena-config.buttons.cosmetics-unavailable", null);
                changed = true;
            }
            if (fromVersion < 5) {
                // v5 replaced Add Event's "run this command instead" note with a real
                // build-your-own-event wizard (timeline-add.buttons.create-custom, restored
                // automatically as a new key), and gave the timeline editor a third content row
                // — summary card, selection card, Clear All Events. Only move buttons still
                // sitting at their v4 defaults, so a re-laid-out editor keeps its arrangement.
                config.set("timeline-add.buttons.custom-info", null);
                changed = true;
            }
            return changed;
        });
        this.guisYaml.load();
        GuiStyle.init(guisYaml);
        GuiStyle.warnIfPairedSlotsMismatched(getLogger());
    }

    /** Moves {@code path} from {@code oldSlot} to {@code newSlot}, but only if it's still there. */
    private static boolean moveIfDefault(org.bukkit.configuration.file.YamlConfiguration config,
                                         String path, int oldSlot, int newSlot) {
        if (config.getInt(path, oldSlot) != oldSlot) return false;
        config.set(path, newSlot);
        return true;
    }

    /**
     * Hooks MBedwarsTweaks' gen tiers as the timeline backend when that plugin is present:
     * the editor's defaults come from the Tweaks gen-tier config, and per-match custom
     * timings are applied by rewriting Tweaks' own scheduling — which is what makes the
     * scoreboard's next-event timer show them correctly. Set timeline.backend: internal
     * to force the built-in engine even with Tweaks installed.
     */
    private void setupTweaksBridge() {
        if ("internal".equalsIgnoreCase(eaConfig.str("timeline.backend", "auto"))
                || Bukkit.getPluginManager().getPlugin("MBedwarsTweaks") == null) {
            teardownTweaksBridge();
            return;
        }

        if (tweaksBridge != null) {
            // A bridge is already active (e.g. this is a /ea reload, not the initial enable) —
            // just re-read Tweaks' gen-tier config into the timeline defaults. Tearing down and
            // rebuilding a fresh instance here would wipe every in-flight arena's per-round
            // schedule state, causing already-fired events to replay for any match currently
            // RUNNING. Keep the existing instance, and its live queues, in place.
            if (!tweaksBridge.rebuildDefaults()) teardownTweaksBridge();
            return;
        }

        TweaksTimelineBridge bridge = TweaksTimelineBridge.tryCreate(this, sessionService, timelineService);
        if (bridge != null) {
            this.tweaksBridge = bridge;
            Bukkit.getPluginManager().registerEvents(bridge, this);
            getLogger().info("MBedwarsTweaks detected — its gen tiers now provide the default "
                    + "event timeline, and custom timings will show on the scoreboard.");
        }
    }

    private void teardownTweaksBridge() {
        if (tweaksBridge == null) return;
        org.bukkit.event.HandlerList.unregisterAll(tweaksBridge);
        tweaksBridge.shutdown();
        tweaksBridge = null;
    }

    /** Re-renders open menus whose lore shows live data (status cards, timers) every second. */
    private void startGuiRefresh() {
        stopGuiRefresh();
        this.guiRefreshTask = new GuiRefreshTask(this, sessionService, guiManager)
                .runTaskTimer(this, 20L, 20L);
    }

    private void stopGuiRefresh() {
        if (guiRefreshTask != null) {
            guiRefreshTask.cancel();
            guiRefreshTask = null;
        }
    }

    /**
     * Auto-summon is retired from the menus but kept in the code for a future release —
     * the background sync task only runs when private.auto_summon_enabled is set.
     */
    private void startAutoSummon() {
        stopAutoSummon();
        if (!eaConfig.bool("private.auto_summon_enabled", false)) return;
        long period = Math.max(20L, eaConfig.intNum("private.auto_summon_poll_seconds", 5) * 20L);
        this.autoSummonTask = new AutoSummonTask(this, sessionService)
                .runTaskTimer(this, period, period);
    }

    private void stopAutoSummon() {
        if (autoSummonTask != null) {
            autoSummonTask.cancel();
            autoSummonTask = null;
        }
    }

    /**
     * Starts the periodic check that every PARTY-gated session's host still leads a party —
     * converting the session to a CODE-gated one when they don't (see {@link PartyIntegrityTask}).
     */
    private void startPartyMonitor() {
        stopPartyMonitor();
        long period = Math.max(20L, eaConfig.intNum("private.party_check_seconds", 5) * 20L);
        this.partyIntegrityTask = new PartyIntegrityTask(this, sessionService)
                .runTaskTimer(this, period, period);
    }

    private void stopPartyMonitor() {
        if (partyIntegrityTask != null) {
            partyIntegrityTask.cancel();
            partyIntegrityTask = null;
        }
    }

    /**
     * Converts a (still-live) PARTY session to CODE gating and tells the host and the arena.
     * Called on the main thread by {@link PartyIntegrityTask} once the async party lookup
     * confirmed the host no longer leads a party; re-validates by id since the session may
     * have ended (or already been converted) while that lookup was in flight.
     */
    public void convertSessionToCode(UUID sessionId) {
        PrivateSession session = sessionService.getById(sessionId);
        if (session == null || session.getJoinPolicy() != JoinPolicy.PARTY) return;

        String code = sessionService.convertToCodePolicy(session);
        if (code == null) return;
        getLogger().info("Party-gated match on '" + session.getArenaName() + "' converted to a "
                + "join-code gate — its host no longer leads a party.");

        Player host = Bukkit.getPlayer(session.getOwner());
        if (host != null && host.isOnline()) {
            host.sendMessage(Lang.msg("party.converted-to-code", "%arena%", session.getArenaName()));
            host.sendMessage(Lang.msg("create.code-line", "%code%", code));
        }
        Arena arena = BedwarsAPI.getGameAPI().getArenaByExactName(session.getArenaName());
        if (arena != null && arena.exists()) {
            arena.broadcast(Lang.msg("party.converted-broadcast"));
        }
    }

    /**
     * Connects to the database and runs schema setup off the main thread — opening the
     * connection pool and running DDL can block for the full connection timeout if the DB host
     * is slow or unreachable, and this runs on every plugin enable and every {@code /ea reload}.
     * The actual field wiring and sync-task startup happen back on the main thread once the
     * connection attempt finishes (success or failure).
     */
    private void setupDatabase() {
        if (!eaConfig.bool("database.enabled", false)) return;

        String serverId = eaConfig.str("server_id", "server-1");
        Database.Settings settings = new Database.Settings(
                eaConfig.str("database.host", "localhost"),
                eaConfig.intNum("database.port", 3306),
                eaConfig.str("database.database", "exclusivearenas"),
                eaConfig.str("database.user", "root"),
                eaConfig.str("database.password", ""),
                eaConfig.str("database.table_prefix", "ea_"),
                eaConfig.bool("database.use_ssl", false),
                serverId);
        boolean verbose = isVerbose();
        long generation = ++dbSetupGeneration;

        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            Database db = null;
            Throwable failure = null;
            try {
                db = new Database(getLogger(), settings, verbose);
                db.initSchema();
            } catch (Throwable t) {
                failure = t;
            }
            Database connectedDb = db;
            Throwable connectFailure = failure;
            Bukkit.getScheduler().runTask(this,
                    () -> finishDatabaseSetup(generation, connectedDb, connectFailure));
        });
    }

    private void finishDatabaseSetup(long generation, Database db, Throwable failure) {
        if (generation != dbSetupGeneration) {
            // A later setupDatabase()/teardownDatabase() call superseded this attempt while it
            // was connecting — discard the result instead of clobbering newer state.
            if (db != null) db.shutdown();
            return;
        }

        if (failure != null) {
            getLogger().severe("Could not connect to the ExclusiveArenas database — falling back to "
                    + "single-server in-memory mode. Cause: " + failure.getMessage());
            this.database = null;
            this.syncService = null;
            sessionService.setDatabase(null);
            ticketService.setDatabase(null);
            remoteCommandService.setDatabase(null);
            return;
        }

        this.database = db;
        sessionService.setDatabase(db);
        ticketService.setDatabase(db);
        remoteCommandService.setDatabase(db);

        long sessionTicks = Math.max(20L, eaConfig.intNum("database.session_poll_seconds", 4) * 20L);
        long ticketTicks  = Math.max(20L, eaConfig.intNum("database.ticket_poll_seconds", 1) * 20L);
        long commandTicks = Math.max(20L, eaConfig.intNum("database.command_poll_seconds", 2) * 20L);
        long deadServerSweepTicks = Math.max(200L, eaConfig.intNum("database.dead_server_sweep_seconds", 120) * 20L);
        long deadServerStaleMillis = Math.max(30_000L,
                eaConfig.intNum("database.dead_server_after_seconds", 90) * 1000L);
        this.syncService = new SyncService(this, db, sessionService, ticketService, remoteCommandService);
        this.syncService.start(sessionTicks, ticketTicks, commandTicks,
                deadServerSweepTicks, deadServerStaleMillis);

        // Push current in-memory state back to the (possibly just-(re)connected) database so a
        // poll does not evict live matches that predate the connection. Harmless no-op on the
        // very first connect, since there's nothing in-memory yet at that point.
        sessionService.resyncAll();
    }

    /** Cleanly tears down the database + sync tasks (safe to call when already down). */
    private void teardownDatabase() {
        dbSetupGeneration++; // invalidate any setupDatabase() attempt still connecting
        if (syncService != null) {
            syncService.stop();
            syncService = null;
        }
        if (database != null) {
            database.shutdown();
            database = null;
        }
        sessionService.setDatabase(null);
        ticketService.setDatabase(null);
        remoteCommandService.setDatabase(null);
    }

    /**
     * Reloads configuration and rebuilds the database/sync layer cleanly, without dropping
     * active in-memory sessions. Any open menus are closed so nobody acts on stale state.
     */
    public void reload() {
        getLogger().info("Reloading ExclusiveArenas…");
        teardownDatabase();

        eaConfig.load();
        loadLangAndGuis();
        timelineService.load(eaConfig);
        setupTweaksBridge(); // re-applies Tweaks-derived defaults over the config ones
        applyTunables();
        setupDatabase(); // connects asynchronously; resyncAll() runs once it lands (see finishDatabaseSetup)
        startAutoSummon(); // restart to pick up a changed poll interval
        startPartyMonitor(); // restart to pick up a changed check interval
        startBossBar();    // restart to pick up a changed enabled/disabled setting
        startGuiRefresh();

        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.getOpenInventory() != null
                    && online.getOpenInventory().getTopInventory().getHolder() instanceof GuiHolder) {
                online.closeInventory();
            }
        }
        getLogger().info("ExclusiveArenas reloaded.");
    }

    @Override
    public void onDisable() {
        stopAutoSummon();
        stopPartyMonitor();
        stopBossBar();
        stopGuiRefresh();
        teardownTweaksBridge();
        if (timelineEngine != null) timelineEngine.shutdown();
        unregisterConditionVariable();
        unregisterLobbyItemHandlers();
        teardownDatabase();
        if (addon != null && addon.isRegistered()) addon.unregister();
        getLogger().info("ExclusiveArenas disabled.");
    }

    // ── Public API for listeners / commands ───────────────────────────────────

    public EaConfig getEaConfig()                   { return eaConfig; }
    public DraftService getDraftService()           { return draftService; }
    public PrivateSessionService getSessionService(){ return sessionService; }
    public JoinTicketService getTicketService()     { return ticketService; }
    public GuiManager getGuiManager()               { return guiManager; }
    public TimelineService getTimelineService()     { return timelineService; }
    public EventTimelineEngine getTimelineEngine()  { return timelineEngine; }
    public QuickActionsService getQuickActions()    { return quickActions; }
    public TweaksTimelineBridge getTweaksBridge()   { return tweaksBridge; }
    public PresetService getPresetService()         { return presetService; }
    public Database getDatabase()                   { return database; }
    public RemoteCommandService getRemoteCommandService() { return remoteCommandService; }

    /**
     * Runs a host action against the session's arena: directly when the arena is on this
     * server, otherwise relayed to whichever server hosts it via the shared database.
     */
    public void runArenaAction(Player actor, PrivateSession session, RemoteCommandService.Type type) {
        runArenaAction(actor, session, type, null);
    }

    /** @param payload extra action detail, relayed verbatim cross-server (e.g. KICK_ALL "keep"). */
    public void runArenaAction(Player actor, PrivateSession session, RemoteCommandService.Type type,
                               String payload) {
        Arena local = BedwarsAPI.getGameAPI().getArenaByExactName(session.getArenaName());
        if (local != null && local.exists()) {
            switch (type) {
                case START_MATCH -> startMatchNow(actor, local, session);
                case END_MATCH -> endMatch(session);
                case KICK_ALL -> quickActions.kickAll(actor, session, local,
                        RemoteCommandService.PAYLOAD_KEEP_HOST.equals(payload));
                case QUICK_REGEN -> quickActions.regenerateKeepingPlayers(actor, session, local);
                case QUICK_HEAL -> quickActions.healAll(actor, session, local);
                case QUICK_DROP -> quickActions.dropAllSpawners(actor, session, local);
                case QUICK_BEDS -> quickActions.destroyAllBeds(actor, session, local);
                case QUICK_CLEAR -> quickActions.clearGroundItems(actor, session, local);
                case QUICK_SKIP_EVENT -> quickActions.skipToNextEvent(actor, session, local);
                case QUICK_FORCE_WIN -> {
                    Team team = teamByName(local, payload);
                    if (team != null) quickActions.forceWin(actor, session, local, team);
                }
                case QUICK_SWAP_TEAMS -> {
                    String[] parts = payload == null ? new String[0] : payload.split(":", 2);
                    if (parts.length == 2) {
                        quickActions.swapTeams(actor, session, local,
                                teamByName(local, parts[0]), teamByName(local, parts[1]));
                    }
                }
                case QUICK_BALANCE_TEAMS -> quickActions.balanceTeams(actor, session, local);
                case QUICK_TRIGGER_TRAP -> quickActions.triggerRandomTrap(actor, session, local);
                case QUICK_CLEAR_TRAPS -> quickActions.clearAllTrapQueues(actor, session, local);
                case QUICK_RESET_UPGRADES -> quickActions.resetAllTeamUpgrades(actor, session, local);
                case QUICK_GRANT_EFFECT -> {
                    String[] parts = payload == null ? new String[0] : payload.split(":");
                    if (parts.length >= 3) {
                        var potionType = org.bukkit.potion.PotionEffectType.getByName(parts[0]);
                        try {
                            if (potionType != null) {
                                quickActions.grantEffect(actor, session, local, potionType,
                                        Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
                            }
                        } catch (NumberFormatException ignored) {
                            // malformed payload — nothing to apply
                        }
                    }
                }
                case QUICK_TOGGLE_FREEZE -> quickActions.toggleFreeze(actor, session, local);
                case QUICK_FORCE_REJOIN -> quickActions.forceRejoinDisconnected(actor, session, local);
                case QUICK_ADJUST_TIMER -> {
                    try {
                        quickActions.adjustMatchTimer(actor, session, local, Integer.parseInt(payload));
                    } catch (NumberFormatException ignored) {
                        // malformed payload — nothing to apply
                    }
                }
                case QUICK_TOGGLE_PVP -> quickActions.togglePvp(actor, session, local);
                case QUICK_STRIP_INVENTORIES -> quickActions.stripInventories(actor, session, local);
                case QUICK_COMEBACK_BUFF -> quickActions.comebackBuff(actor, session, local);
                case QUICK_RANDOM_SCATTER -> quickActions.randomScatter(actor, session, local);
                case QUICK_KICK_AFK -> quickActions.kickAfkPlayers(actor, session, local);
                case QUICK_RESET_SHOP_PRICES -> quickActions.resetShopPrices(actor, session, local);
                case QUICK_GIVE_COMPASS -> quickActions.giveTrackingCompass(actor, session, local);
                case QUICK_ANNOUNCE_STATS -> quickActions.announceStats(actor, session, local);
                case QUICK_TOGGLE_PAUSE -> quickActions.togglePause(actor, session, local);
            }
            return;
        }
        if (!remoteCommandService.isAvailable()) {
            actor.sendMessage(Lang.msg("general.arena-other-server"));
            return;
        }
        remoteCommandService.enqueue(type, session, payload);
        actor.sendMessage(Lang.msg("quick.sent", "%arena%", session.getArenaName()));
    }

    private static Team teamByName(Arena arena, String name) {
        if (name == null) return null;
        for (Team team : arena.getEnabledTeams()) {
            if (team.name().equalsIgnoreCase(name)) return team;
        }
        return null;
    }

    // ── Debug / limits ──────────────────────────────────────────────────────────

    public boolean isVerbose() {
        return eaConfig != null && eaConfig.bool("debug", false);
    }

    /** Logs a short debug line only when verbose mode is enabled. Errors log unconditionally. */
    public void debug(String msg) {
        if (isVerbose()) getLogger().info("[Debug] " + msg);
    }

    /**
     * Maximum simultaneous private matches this player may host. Admins are unlimited;
     * otherwise the highest {@code exclusivearenas.limit.<n>} permission wins, falling back
     * to {@code private.default_arena_limit}.
     */
    public int getArenaLimit(Player player) {
        if (player.hasPermission("exclusivearenas.admin")
                || player.hasPermission("exclusivearenas.limit.unlimited")) {
            return Integer.MAX_VALUE;
        }
        int limit = -1;
        String prefix = "exclusivearenas.limit.";
        for (var info : player.getEffectivePermissions()) {
            if (!info.getValue()) continue;
            String perm = info.getPermission().toLowerCase(java.util.Locale.ROOT);
            if (!perm.startsWith(prefix)) continue;
            try {
                limit = Math.max(limit, Integer.parseInt(perm.substring(prefix.length())));
            } catch (NumberFormatException ignored) {
                // non-numeric suffix (e.g. a wildcard); skip
            }
        }
        return limit >= 0 ? limit : eaConfig.intNum("private.default_arena_limit", 1);
    }

    // ── Create & join ──────────────────────────────────────────────────────────

    /**
     * Opens the match builder, first resolving the join policy from the player's current party
     * leadership — a party leader always builds a Party-policy match, someone with no party
     * always builds a Join Code match, and a party member who isn't its leader can't build one
     * at all (they should join their leader's match instead; see {@code /ea join}).
     */
    public void openBuilderMenu(Player p) {
        resolveDraftPolicy(p, reason -> {
            guiManager.openBuilder(p);
            if (reason != DraftPrivateMatch.BlockReason.NONE) {
                p.sendMessage(Lang.msg(blockReasonKey(reason)));
            }
        });
    }

    private static String blockReasonKey(DraftPrivateMatch.BlockReason reason) {
        return reason == DraftPrivateMatch.BlockReason.MEMBER_HOSTING
                ? "create.member-hosting" : "create.party-blocked";
    }

    /**
     * Resolves the host's join policy from their current party leadership and applies it to
     * their draft — shared by the builder GUI and the headless {@code /ea create <map>}
     * command so both derive the same Party/Join-Code/blocked outcome the same way.
     *
     * Blocked outcomes: a non-leader party member can't host at all (they should join their
     * leader's match instead), and a leader whose party already contains someone hosting a
     * match of their own is blocked until those members' matches end.
     *
     * @param then receives the block reason ({@code NONE} when creation may proceed), run on
     *             the main thread
     */
    private void resolveDraftPolicy(Player p, java.util.function.Consumer<DraftPrivateMatch.BlockReason> then) {
        PartyResolver.getPartyMember(p, opt -> {
            boolean inParty = opt.isPresent();
            boolean isLeader = inParty && opt.get().getParty().getLeaders().stream()
                    .anyMatch(leader -> leader.getUniqueId().equals(p.getUniqueId()));

            // Snapshot the member ids here — the hook object must not be touched again once
            // we've hopped threads below.
            java.util.Set<UUID> memberIds = new java.util.HashSet<>();
            if (isLeader) {
                for (PartiesHook.Member m : opt.get().getParty().getMembers(true)) {
                    memberIds.add(m.getUniqueId());
                }
            }

            Bukkit.getScheduler().runTask(this, () -> {
                DraftPrivateMatch draft = draftService.getOrCreate(p.getUniqueId());
                DraftPrivateMatch.BlockReason reason = DraftPrivateMatch.BlockReason.NONE;
                if (inParty && !isLeader) {
                    reason = DraftPrivateMatch.BlockReason.NOT_LEADER;
                } else if (isLeader && isAnyPartyMemberHosting(p.getUniqueId(), memberIds)) {
                    reason = DraftPrivateMatch.BlockReason.MEMBER_HOSTING;
                }
                draft.setBlockReason(reason);
                if (reason == DraftPrivateMatch.BlockReason.NONE) {
                    draft.setJoinPolicy(isLeader ? JoinPolicy.PARTY : JoinPolicy.CODE);
                    if (draft.getJoinPolicy() == JoinPolicy.CODE) {
                        draft.setAutoSummon(false); // only meaningful for Party policy
                        if (draft.getJoinCode() == null || draft.getJoinCode().isBlank()) {
                            draft.setJoinCode(sessionService.generateCode());
                        }
                    }
                }
                then.accept(reason);
            });
        });
    }

    /** True when anyone in the party other than the leader currently hosts a private match. */
    private boolean isAnyPartyMemberHosting(UUID leader, java.util.Set<UUID> memberIds) {
        for (UUID id : memberIds) {
            if (!id.equals(leader) && sessionService.countByOwner(id) > 0) return true;
        }
        return false;
    }

    /**
     * Headless equivalent of the builder GUI's "select map" + "Create & Join": resolves the
     * host's join policy exactly as the builder menu does, points a fresh draft at
     * {@code mapName}, and creates + joins immediately. Backs {@code /ea create <map>}.
     */
    public void createAndJoinByMapName(Player host, String mapName, boolean joinAfterCreate) {
        resolveDraftPolicy(host, reason -> {
            if (reason != DraftPrivateMatch.BlockReason.NONE) {
                host.sendMessage(Lang.msg(blockReasonKey(reason)));
                return;
            }
            DraftPrivateMatch draft = draftService.getOrCreate(host.getUniqueId());
            draft.setArenaName(mapName);
            createAndJoin(host, draft, joinAfterCreate);
        });
    }

    /**
     * Creates the private session described by the host's draft and immediately sends the
     * host into the chosen arena — local or on another server. Replaces the old two-step
     * "creation mode" flow with a single action from the builder.
     */
    public void createAndJoin(Player host, DraftPrivateMatch draft) {
        createAndJoin(host, draft, true);
    }

    /**
     * @param joinAfterCreate false to create the session without sending the host in (used for
     *                        a shift-click on Create & Join) — a ticket is still granted, so
     *                        "Go to Arena" in Match Controls works whenever they're ready.
     */
    public void createAndJoin(Player host, DraftPrivateMatch draft, boolean joinAfterCreate) {
        if (draft == null || !draft.isReadyToCreate()) {
            host.sendMessage(Lang.msg("create.select-map-first"));
            return;
        }

        Arena currentArena = BedwarsAPI.getGameAPI().getArenaByPlayer(host);
        if (currentArena != null && sessionService.getByArena(currentArena) != null) {
            host.sendMessage(Lang.msg("create.leave-current-first"));
            return;
        }

        // Guards a double-click on Create & Join (or a repeated /ea create) from racing two
        // overlapping creations while the first is still waiting on the async party check below.
        // Keyed to a token rather than just present/absent so a timed-out attempt's late
        // callback (below) can't clobber a newer attempt that started after the timeout.
        Object token = new Object();
        if (creatingSessionFor.putIfAbsent(host.getUniqueId(), token) != null) return;

        // Safety net: if the party hook throws before registering its callback, or simply never
        // calls back, the guard above would otherwise never clear and this player could never
        // create a match again without a server restart.
        Bukkit.getScheduler().runTaskLater(this,
                () -> creatingSessionFor.remove(host.getUniqueId(), token), 20L * 15);

        // Party-membership rules depend on an async party lookup, so validate that first and
        // only continue on to the actual creation once it's confirmed OK. The lookup itself may
        // resolve off the main thread, so every branch below (including the failure messages)
        // is hopped onto the main thread rather than assuming the callback already is.
        JoinPolicy policy = draft.getJoinPolicy() == null ? JoinPolicy.PARTY : draft.getJoinPolicy();
        PartyResolver.getPartyMember(host, opt -> Bukkit.getScheduler().runTask(this, () -> {
            // The safety net above may have already released this attempt's guard (and let a
            // newer one claim it) by the time this callback finally arrives — if so, this
            // callback is stale and must not act.
            if (creatingSessionFor.get(host.getUniqueId()) != token) return;
            try {
                if (policy == JoinPolicy.CODE && opt.isPresent()) {
                    host.sendMessage(Lang.msg("create.code-while-in-party"));
                    return;
                }
                if (policy == JoinPolicy.PARTY) {
                    boolean isLeader = opt.isPresent() && opt.get().getParty().getLeaders().stream()
                            .anyMatch(leader -> leader.getUniqueId().equals(host.getUniqueId()));
                    if (!isLeader) {
                        host.sendMessage(Lang.msg("create.must-be-leader"));
                        return;
                    }
                    // A leader whose party already contains someone hosting their own match may
                    // not create one until those members' matches end.
                    java.util.Set<UUID> memberIds = new java.util.HashSet<>();
                    for (PartiesHook.Member m : opt.get().getParty().getMembers(true)) {
                        memberIds.add(m.getUniqueId());
                    }
                    if (isAnyPartyMemberHosting(host.getUniqueId(), memberIds)) {
                        host.sendMessage(Lang.msg("create.member-hosting"));
                        return;
                    }
                }
                finishCreateAndJoin(host, draft, joinAfterCreate);
            } finally {
                creatingSessionFor.remove(host.getUniqueId(), token);
            }
        }));
    }

    private void finishCreateAndJoin(Player host, DraftPrivateMatch draft, boolean joinAfterCreate) {
        String arenaName = draft.getArenaName();

        int limit = getArenaLimit(host);
        if (sessionService.countByOwner(host.getUniqueId()) >= limit) {
            host.sendMessage(Lang.msg("create.limit-reached", "%limit%", String.valueOf(limit)));
            return;
        }

        // Hosting several matches at once is only allowed while every one of them (including
        // the one being created) is CODE-gated — a PARTY-gated match is always the host's only
        // match, since one party can't meaningfully gate two arenas at once.
        List<PrivateSession> owned = sessionService.getSessionsByOwner(host.getUniqueId());
        if (!owned.isEmpty()) {
            JoinPolicy newPolicy = draft.getJoinPolicy() == null ? JoinPolicy.PARTY : draft.getJoinPolicy();
            if (newPolicy == JoinPolicy.PARTY) {
                host.sendMessage(Lang.msg("create.party-single"));
                return;
            }
            for (PrivateSession existing : owned) {
                if (existing.getJoinPolicy() != JoinPolicy.CODE) {
                    host.sendMessage(Lang.msg("create.multi-code-only"));
                    return;
                }
            }
        }
        if (sessionService.isArenaReserved(arenaName, host.getUniqueId())) {
            host.sendMessage(Lang.msg("create.arena-reserved"));
            return;
        }
        if (!isArenaJoinable(arenaName)) {
            host.sendMessage(Lang.msg("create.arena-unavailable", "%arena%", arenaName));
            return;
        }

        PrivateSession session = sessionService.createSession(draft);
        session.setSettings(draft.getSettings()); // carries over any Arena Modifiers chosen pre-creation
        sessionService.releaseDraftArena(arenaName, host.getUniqueId()); // now reserved for real
        draftService.clear(host.getUniqueId());

        // Use the canonical name the session stored (not the raw draft name, which may carry
        // the '@' remote marker) so the ticket matches the arena on its host server.
        String canonical = session.getArenaName();

        // createSession + grant write through to the shared DB; every backend will mirror
        // this session/ticket from its poll. Authorise the host, then route them below.
        ticketService.grant(host.getUniqueId(), session.getSessionId(), canonical);

        Arena local = BedwarsAPI.getGameAPI().getArenaByExactName(canonical);
        if (local != null) prepareLobby(local, session);

        host.sendMessage(Lang.msg(joinAfterCreate ? "create.created-joining" : "create.created", "%arena%", canonical));
        if (session.getJoinPolicy() == JoinPolicy.CODE) {
            host.sendMessage(Lang.msg("create.code-line", "%code%", session.getJoinCode()));
        } else {
            host.sendMessage(Lang.msg("create.party-line"));
        }

        if (joinAfterCreate) {
            sendPlayerToArena(host, canonical);
        } else {
            host.sendMessage(Lang.msg("create.created-not-joining"));
        }
    }

    /**
     * Adds a player to an arena. If the arena lives on this server we add them directly;
     * otherwise we dispatch the configured proxy join command (default {@code bw join %arena%})
     * to transfer them to the backend that hosts it. The join ticket has already been written
     * to the shared DB, so that backend authorises the join once its poll observes the ticket.
     */
    public void sendPlayerToArena(Player player, String arenaName) {
        String canonical = ArenaNames.canonical(arenaName);
        Arena local = BedwarsAPI.getGameAPI().getArenaByExactName(canonical);
        if (local != null && local.exists()) {
            addToArenaWithRetry(player, local, sessionService.getByArenaName(canonical), 3);
            return;
        }

        // On a hub, MBedwars identifies a backend arena by its '@'-prefixed remote name, so the
        // join command needs that form. On the backend itself the arena is local (handled above).
        String routeName = eaConfig.bool("is_hub_server", false) ? "@" + canonical : canonical;
        String template = eaConfig.str("network.join_command_template", "bw join %arena%");
        String command = template.replace("%arena%", routeName).replace("%player%", player.getName());
        try {
            Bukkit.dispatchCommand(player, command);
        } catch (Throwable t) {
            getLogger().warning("Failed to dispatch join command for " + arenaName + ": " + t.getMessage());
            player.sendMessage(Lang.msg("route.arena-unavailable", "%arena%", arenaName));
        }
    }

    /**
     * Adds a player to a LOCAL arena, retrying a few times over ~1.5s if MBedwars doesn't
     * actually register them the first time. Sometimes — most often right after an arena is
     * freshly reserved — a join call ends up teleporting the player in without properly
     * registering them as playing, and nothing else follows up on that except the next sweep
     * of {@link ArenaEntryGuardTask}. This reacts immediately instead of waiting for that.
     *
     * A fresh ticket is granted before every attempt (when {@code session} is known) — tickets
     * are consumed on use by the join gate, so without this a retry would be gated exactly like
     * an unauthorised join and fail every time, which made the original retry a no-op for Code
     * policy in particular.
     */
    private void addToArenaWithRetry(Player player, Arena arena, PrivateSession session, int attemptsLeft) {
        if (!player.isOnline()) return;
        boolean registered = arena.getPlayers().contains(player) || arena.isSpectating(player);
        if (!registered) {
            if (session != null) {
                ticketService.grant(player.getUniqueId(), session.getSessionId(), arena.getName());
            }
            if (arena.getStatus() == ArenaStatus.RUNNING) {
                arena.addSpectator(player, SpectateReason.ENTER);
            } else {
                arena.addPlayer(player);
            }
        }
        if (attemptsLeft <= 0) return;
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!player.isOnline()) return;
            boolean nowRegistered = arena.getPlayers().contains(player) || arena.isSpectating(player);
            if (!nowRegistered) addToArenaWithRetry(player, arena, session, attemptsLeft - 1);
        }, 10L);
    }

    /** True if the arena exists (locally or remotely) and is an empty lobby ready to be reserved. */
    private boolean isArenaJoinable(String arenaName) {
        Arena local = BedwarsAPI.getGameAPI().getArenaByExactName(ArenaNames.canonical(arenaName));
        if (local != null && local.exists()) {
            return local.getStatus().isLobby() && local.getPlayers().isEmpty();
        }
        // A remote arena can only be reserved when the shared database is connected — without
        // it the server actually hosting that arena would never learn a session exists there,
        // leaving the "private" match completely ungated on its own server.
        if (database == null) return false;
        RemoteArena ra = ArenaNames.findRemote(arenaName);
        if (ra != null) {
            return ra.getStatus().isLobby() && ra.getPlayersCount() == 0;
        }
        return false;
    }

    /**
     * Starts the match right now if its arena is local, or relays the request to whichever
     * server actually hosts it — so the host can control the match from another arena server
     * or from a hub, not just from the arena itself.
     */
    public void requestStartMatch(Player actor, PrivateSession session) {
        Arena local = BedwarsAPI.getGameAPI().getArenaByExactName(session.getArenaName());
        if (local != null && local.exists()) {
            startMatchNow(actor, local, session);
            return;
        }
        if (!remoteCommandService.isAvailable()) {
            actor.sendMessage(Lang.msg("general.arena-other-server"));
            return;
        }
        remoteCommandService.enqueue(RemoteCommandService.Type.START_MATCH, session);
        actor.sendMessage(Lang.msg("match.start-sent", "%arena%", session.getArenaName()));
    }

    /**
     * Ends the match, cleaning up locally if it's hosted here, or relays the request to
     * whichever server hosts it so players there actually get kicked and the arena's
     * min-players requirement gets restored — not just the shared session dropped.
     */
    public void requestEndMatch(Player actor, PrivateSession session) {
        Arena local = BedwarsAPI.getGameAPI().getArenaByExactName(session.getArenaName());
        if (local != null && local.exists()) {
            endMatch(session);
            actor.sendMessage(Lang.msg("match.ended", "%arena%", session.getArenaName()));
            return;
        }
        if (!remoteCommandService.isAvailable()) {
            actor.sendMessage(Lang.msg("general.arena-other-server"));
            return;
        }
        remoteCommandService.enqueue(RemoteCommandService.Type.END_MATCH, session);
        actor.sendMessage(Lang.msg("match.end-sent", "%arena%", session.getArenaName()));
    }

    /** Ends a match: removes the shared session state and, if the arena is local, clears it. */
    public void endMatch(PrivateSession session) {
        if (session == null) return;
        Arena arena = BedwarsAPI.getGameAPI().getArenaByExactName(session.getArenaName());
        restoreArenaMinPlayers(session, arena);
        restoreArenaPlayersPerTeam(session, arena);
        resetArenaEnvironment(session, arena);
        sessionService.endSession(session);
        if (arena != null && arena.exists()) {
            arena.broadcast(Lang.msg("match.ended-broadcast"));
            arena.kickAllPlayers();
            arena.kickAllSpectators(KickSpectatorReason.PLUGIN_STOP); // kickAllPlayers() alone leaves spectators behind
        }
    }

    // ── Lobby / session helpers ─────────────────────────────────────────────────

    /**
     * Prepares a private match's arena while it sits in its lobby: relaxes the min-players
     * requirement so a small party isn't fought by MBedwars' own lobby logic, and pins the
     * lobby timer well out of reach. There is no pre-game timer any more — the match only ever
     * begins when the host explicitly starts it (see {@link #startMatchNow}). MBedwars' own
     * automatic lobby countdown is cancelled outright when it tries to start fresh (see the
     * {@code ArenaLobbyCountdownStartEvent} guard in {@link PrivacyLifecycleListener}), but that
     * only stops a fresh auto-start — MBedwars can also shorten an already-ticking countdown on
     * its own (e.g. once the lobby fills up), which isn't a "start" and so isn't cancellable.
     * Re-pinning the remaining time here (called on every join, so it keeps re-applying) keeps
     * that from sneaking a round start in early.
     */
    public void prepareLobby(Arena arena, PrivateSession session) {
        if (arena == null || session == null) return;
        if (!arena.getStatus().isLobby()) return;
        relaxMinPlayers(arena, session);
        applyPlayersPerTeamOverride(arena, session);
        applyEnvironmentOverride(arena, session);
        try {
            arena.setLobbyTimeRemaining(3600, false);
        } catch (Throwable ignored) {
            // best effort
        }
    }

    /**
     * Force-starts a private match right now, on the host's command. MBedwars exposes no public
     * API for an immediate arena start, so this uses its own debug command, which reliably skips
     * straight past the lobby into a running round.
     *
     * {@code actor} may be null — this also runs as the executing side of a relayed
     * {@link RemoteCommandService} command, issued by a host who isn't on this server. In that
     * case there's no local player to message, so feedback goes to the arena instead.
     */
    public void startMatchNow(Player actor, Arena arena, PrivateSession session) {
        if (arena == null || session == null) return;
        if (!arena.getStatus().isLobby()) {
            tell(actor, arena, Lang.raw("match.already-begun"));
            return;
        }
        if (arena.getPlayers().size() < 2) {
            tell(actor, arena, Lang.raw("match.need-more-players"));
            return;
        }

        try {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "bw debug 13 " + arena.getName());
        } catch (Throwable t) {
            getLogger().warning("Could not force-start arena " + arena.getName() + ": " + t.getMessage());
            tell(actor, arena, Lang.raw("match.start-failed"));
            return;
        }
        arena.broadcast(Lang.msg("match.host-started"));
    }

    /** Messages the actor directly if present (local click), else falls back to an arena broadcast
     *  (a relayed command has no local player to message). */
    private void tell(Player actor, Arena arena, String message) {
        if (actor != null) {
            actor.sendMessage(ItemUtil.color(message));
        } else if (arena != null) {
            arena.broadcast(ItemUtil.color(message));
        }
    }

    /**
     * Lowers the arena's minimum-player requirement to 1 while a private match is active.
     * MBedwars' own lobby logic otherwise fights our manual pause/start calls whenever a small
     * party is below the arena's usual minimum — it can silently refuse to actually tick the
     * countdown, or auto-(re)start its own countdown once the real minimum is met, undoing
     * whatever pause/start state the host asked for. The original value is restored once the
     * match ends via {@link #restoreArenaMinPlayers}.
     */
    private void relaxMinPlayers(Arena arena, PrivateSession session) {
        if (session.getOriginalMinPlayers() != null) return;
        session.setOriginalMinPlayers(arena.getMinPlayers());
        if (arena.getMinPlayers() > 1) arena.setMinPlayers(1);
    }

    /** Restores an arena's original min-players requirement once its private match ends. */
    public void restoreArenaMinPlayers(PrivateSession session, Arena arena) {
        if (session == null) return;
        Integer original = session.getOriginalMinPlayers();
        if (original == null || arena == null || !arena.exists()) return;
        try {
            arena.setMinPlayers(original);
        } catch (Throwable ignored) {
            // best effort — the arena will pick its configured value back up on the next reset anyway
        }
    }

    /**
     * Team size is changeable for the whole lobby phase, not just while the host is alone —
     * only actually starting the match closes the window. A change while players already hold
     * teams unassigns everyone (see {@link #applyPlayersPerTeamOverride}) rather than leaving a
     * roster that may no longer fit the new cap.
     */
    public boolean canChangeTeamSize(Arena arena) {
        return arena != null && arena.exists() && arena.getStatus().isLobby();
    }

    /**
     * Applies the session's players-per-team override (Arena Modifiers → Team Size) to the live
     * arena, snapshotting its original value on first use so it can be restored once the match
     * ends via {@link #restoreArenaPlayersPerTeam}. A no-op override just keeps the arena at its
     * original value — mirrors {@link #relaxMinPlayers}, including being safe to call repeatedly.
     * A genuine change to the value unassigns every current player from their team (see
     * {@link #unassignAllTeams}) — this only fires when the arena's actual value is about to
     * change, not on every routine lobby-prepare call, so an unrelated player joining doesn't
     * repeatedly bounce everyone else off their teams.
     */
    public void applyPlayersPerTeamOverride(Arena arena, PrivateSession session) {
        if (session.getOriginalPlayersPerTeam() == null) {
            session.setOriginalPlayersPerTeam(arena.getPlayersPerTeam());
        }
        Integer override = session.getSettings().getPlayersPerTeam();
        int target = override != null ? override : session.getOriginalPlayersPerTeam();
        if (arena.getPlayersPerTeam() != target) {
            try {
                arena.setPlayersPerTeam(target);
                unassignAllTeams(arena);
                // MBedwars' scoreboard only redraws itself off specific events (a player's team
                // changing, joining, etc.) — unassignAllTeams only fires those when a player
                // actually HAD a team, so a second size change in a row (everyone already sits
                // at team=null from the first one) changes the backing value but leaves the
                // scoreboard showing the stale team-size line. Force a redraw unconditionally.
                BedwarsAPI.getGameAPI().getDefaultScoreboardHandler()
                        .update(ScoreboardUpdateCause.COMPLETE_REFRESH, arena, null);
            } catch (Throwable ignored) {
                // best effort
            }
        }
    }

    /**
     * Clears every current player's team assignment — used when the team-size cap changes,
     * since an existing roster (picked under the old cap) may no longer make sense under the
     * new one. Players stay in the lobby and simply need to pick a team again.
     */
    private void unassignAllTeams(Arena arena) {
        for (Player player : arena.getPlayers()) {
            try {
                if (arena.getPlayerTeam(player) == null) continue;
                runHostTeamAction(() -> arena.setPlayerTeam(player, null));
                player.sendMessage(Lang.msg("teamsize.reassign"));
            } catch (Throwable t) {
                // One player failing to unassign shouldn't leave everyone else stuck on a
                // roster built for the old team cap.
                getLogger().warning("Could not unassign " + player.getName() + " from their team in "
                        + arena.getName() + ": " + t.getMessage());
            }
        }
    }

    /** Restores an arena's original players-per-team value once its private match ends. */
    public void restoreArenaPlayersPerTeam(PrivateSession session, Arena arena) {
        if (session == null) return;
        Integer original = session.getOriginalPlayersPerTeam();
        if (original == null || arena == null || !arena.exists()) return;
        try {
            arena.setPlayersPerTeam(original);
        } catch (Throwable ignored) {
            // best effort — the arena will pick its configured value back up on the next reset anyway
        }
    }

    // ── Environment (time / weather) ─────────────────────────────────────────────

    /**
     * Applies the session's time/weather override (Arena Modifiers → Environment) to the live
     * arena. Unlike team size, there's no "original value" to snapshot/restore — UNTOUCHED
     * (MBedwars' own neutral default) is itself the un-set state, so ending a match just leaves
     * nothing further to undo. {@code setWeatherType}/{@code setTimeType} push to everyone
     * currently viewing the arena; a player joining later needs {@link #syncPlayerClimate}
     * separately, since they wouldn't have received that original push.
     */
    public void applyEnvironmentOverride(Arena arena, PrivateSession session) {
        ArenaWeatherType weather = parseWeatherType(session.getSettings().getWeatherType());
        ArenaTimeType time = parseTimeType(session.getSettings().getTimeType());
        try {
            if (arena.getWeatherType() != weather) arena.setWeatherType(weather);
            if (arena.getTimeType() != time) arena.setTimeType(time);
        } catch (Throwable ignored) {
            // best effort
        }
    }

    /** Re-sends the arena's current time/weather to one player — for a player joining after
     *  the arena-wide push in {@link #applyEnvironmentOverride} already happened. */
    public void syncPlayerClimate(Arena arena, Player player) {
        try {
            arena.applyPlayerClimate(player);
        } catch (Throwable ignored) {
            // best effort
        }
    }

    /**
     * Puts the arena's time/weather back to UNTOUCHED once a private match ends — unlike team
     * size there's no prior "arena default" to restore, but leaving a host's RAINING/NIGHT
     * choice in place would otherwise leak into whatever match (private or public) uses this
     * arena next.
     */
    private void resetArenaEnvironment(PrivateSession session, Arena arena) {
        if (arena == null || !arena.exists()) return;
        if (session.getSettings().getWeatherType() == null && session.getSettings().getTimeType() == null) return;
        try {
            arena.setWeatherType(ArenaWeatherType.UNTOUCHED);
            arena.setTimeType(ArenaTimeType.UNTOUCHED);
        } catch (Throwable ignored) {
            // best effort
        }
    }

    public static ArenaWeatherType parseWeatherType(String name) {
        if (name == null) return ArenaWeatherType.UNTOUCHED;
        try {
            return ArenaWeatherType.valueOf(name);
        } catch (IllegalArgumentException e) {
            return ArenaWeatherType.UNTOUCHED;
        }
    }

    public static ArenaTimeType parseTimeType(String name) {
        if (name == null) return ArenaTimeType.UNTOUCHED;
        try {
            return ArenaTimeType.valueOf(name);
        } catch (IllegalArgumentException e) {
            return ArenaTimeType.UNTOUCHED;
        }
    }

    public void regenerateJoinCode(PrivateSession session) {
        if (session == null || session.getJoinPolicy() != JoinPolicy.CODE) return;
        String newCode = sessionService.regenerateJoinCode(session);
        if (newCode == null) return;
        Arena arena = BedwarsAPI.getGameAPI().getArenaByExactName(session.getArenaName());
        if (arena != null) arena.broadcast(Lang.msg("match.code-regenerated", "%code%", newCode));
    }

    /**
     * Summons the host's whole party into the session's arena. Doesn't need the arena to be
     * local — {@link #forceSummon} already places each member cross-server via RemoteAPI when
     * needed, so this works just as well when the host is controlling the match remotely.
     */
    public void summonPartyToArena(Player host, PrivateSession session) {
        if (host == null || session == null) return;

        PartyResolver.getPartyMember(host, opt -> {
            if (opt.isEmpty()) {
                host.sendMessage(Lang.msg("summon.not-in-party"));
                return;
            }
            PartiesHook.Party party = opt.get().getParty();
            // The hook callback may be async; do the actual summon on the main thread.
            Bukkit.getScheduler().runTask(this, () -> {
                int count = 0;
                for (PartiesHook.Member m : party.getMembers(true)) {
                    UUID uuid = m.getUniqueId();
                    if (uuid.equals(host.getUniqueId())) continue;
                    forceSummon(session, uuid);
                    count++;
                }
                host.sendMessage(count > 0
                        ? Lang.msg("summon.summoning", "%count%", String.valueOf(count))
                        : Lang.msg("summon.no-members"));
            });
        });
    }

    /**
     * Runs a host-initiated team change with {@link TeamLockListener}'s lock backstop
     * suspended, so a locked lobby still lets the host move people around. Safe to call before
     * the listener exists (it simply runs the action as-is).
     */
    public void runHostTeamAction(Runnable action) {
        if (teamLockListener != null) teamLockListener.hostAction(action);
        else action.run();
    }

    /**
     * Locks/unlocks team switching for a match's lobby (Manage Teams → Lock Teams) and tells
     * everyone in the arena. The flag lives on the session's replicated settings, so the server
     * actually hosting the arena enforces it even when it was toggled from elsewhere.
     */
    public void setTeamsLocked(PrivateSession session, boolean locked) {
        if (session == null || session.getSettings().isTeamsLocked() == locked) return;
        session.getSettings().setTeamsLocked(locked);
        sessionService.saveSettings(session);

        Arena arena = BedwarsAPI.getGameAPI().getArenaByExactName(session.getArenaName());
        if (arena != null && arena.exists()) {
            arena.broadcast(Lang.msg(locked ? "teams.locked-broadcast" : "teams.unlocked-broadcast"));
        }
    }

    /**
     * Moves a batch of players (already in the arena) onto {@code team}, while the arena is
     * still in its lobby. Re-checks the team's remaining capacity as it goes, so a stale GUI
     * selection can never overfill a team even if arena state changed underneath it.
     */
    public void moveArenaPlayersToTeam(Player actor, PrivateSession session, Team team, Set<UUID> playerIds) {
        Arena arena = BedwarsAPI.getGameAPI().getArenaByExactName(session.getArenaName());
        if (arena == null || !arena.exists()) {
            actor.sendMessage(Lang.msg("general.arena-other-server"));
            return;
        }
        if (!arena.getStatus().isLobby()) {
            actor.sendMessage(Lang.msg("teams.move-lobby-only"));
            return;
        }

        int cap = arena.getPlayersPerTeam();
        int[] counts = new int[2]; // [moved, skipped] — mutable across the host-action lambda
        runHostTeamAction(() -> {
            for (UUID id : playerIds) {
                Player target = Bukkit.getPlayer(id);
                if (target == null || !target.isOnline() || !arena.getPlayers().contains(target)) continue;
                if (arena.getPlayersInTeam(team).size() >= cap) { counts[1]++; continue; }
                if (arena.moveToTeamDuringLobby(target, team)) counts[0]++; else counts[1]++;
            }
        });
        int moved = counts[0], skipped = counts[1];

        if (moved > 0) {
            actor.sendMessage(Lang.msg("teams.moved", "%count%", String.valueOf(moved), "%team%", team.getDisplayName()));
        }
        if (skipped > 0) {
            actor.sendMessage(Lang.msg("teams.move-skipped", "%count%", String.valueOf(skipped)));
        }
    }

    /**
     * Evenly spreads every player currently in the arena's lobby across its enabled teams —
     * shuffled first, then handed out round-robin, so it's not always alphabetical/join-order —
     * respecting each team's capacity. Re-shuffles everyone, including players already on a
     * team, for a clean, fully-balanced result every time it's clicked.
     */
    public void distributePlayersToTeams(Player actor, PrivateSession session) {
        Arena arena = BedwarsAPI.getGameAPI().getArenaByExactName(session.getArenaName());
        if (arena == null || !arena.exists()) {
            actor.sendMessage(Lang.msg("general.arena-other-server"));
            return;
        }
        if (!arena.getStatus().isLobby()) {
            actor.sendMessage(Lang.msg("teams.move-lobby-only"));
            return;
        }

        List<Team> teams = new ArrayList<>(arena.getEnabledTeams());
        if (teams.isEmpty()) return;
        teams.sort(Comparator.comparing(Team::name));

        List<Player> players = new ArrayList<>(arena.getPlayers());
        Collections.shuffle(players);

        // Free every seat first — a player keeping their old team would otherwise block the
        // even round-robin below (their still-occupied seat makes a team look full to the
        // capacity check even though this pass is about to redistribute it).
        for (Player target : players) {
            try {
                if (arena.getPlayerTeam(target) != null) {
                    runHostTeamAction(() -> arena.setPlayerTeam(target, null));
                }
            } catch (Throwable t) {
                getLogger().warning("Could not unassign " + target.getName() + " before distributing in "
                        + arena.getName() + ": " + t.getMessage());
            }
        }

        int cap = arena.getPlayersPerTeam();
        int moved = 0, idx = 0;
        for (Player target : players) {
            Team assigned = null;
            for (int i = 0; i < teams.size(); i++) {
                Team candidate = teams.get((idx + i) % teams.size());
                if (arena.getPlayersInTeam(candidate).size() < cap) {
                    assigned = candidate;
                    idx += i + 1;
                    break;
                }
            }
            if (assigned == null) break; // every team is at capacity
            Team destination = assigned;
            boolean[] ok = new boolean[1];
            runHostTeamAction(() -> ok[0] = arena.moveToTeamDuringLobby(target, destination));
            if (ok[0]) moved++;
        }

        actor.sendMessage(Lang.msg("teams.distributed", "%count%", String.valueOf(moved)));
    }

    /**
     * Forces a single player into the session's arena using MBedwars. If the player and arena
     * are both on this server we add them directly; otherwise the RemoteAPI pulls them in from
     * whatever backend they're on (this is how cross-server party members are summoned). A join
     * ticket is granted first so gating lets them through. Must run on the main thread.
     */
    public void forceSummon(PrivateSession session, UUID memberId) {
        forceSummon(session, memberId, null);
    }

    /**
     * Same as {@link #forceSummon(PrivateSession, UUID)}, but {@code onSuccess} — if given —
     * runs only once the player has actually landed in the arena: immediately for a same-server
     * add, or after the network transfer genuinely completes for a cross-server one. Never runs
     * if the move failed. Use this instead of messaging the player right away, since a cross-
     * server summon can take a moment and a player told "you've been moved" before they actually
     * have been is just confusing.
     */
    public void forceSummon(PrivateSession session, UUID memberId, Runnable onSuccess) {
        if (session == null || memberId == null) return;
        String arenaName = session.getArenaName();
        ticketService.grant(memberId, session.getSessionId(), arenaName);

        Arena localArena = BedwarsAPI.getGameAPI().getArenaByExactName(arenaName);
        Player localPlayer = Bukkit.getPlayer(memberId);

        // Both here → add directly (skip if they're already in this arena).
        if (localArena != null && localArena.exists() && localPlayer != null && localPlayer.isOnline()) {
            addToArenaWithRetry(localPlayer, localArena, session, 3);
            if (onSuccess != null) onSuccess.run(); // synchronous local add — they're already there
            return;
        }

        // Elsewhere on the network → let MBedwars resolve and transfer them.
        try {
            RemoteAPI api = BedwarsAPI.getRemoteAPI();
            if (api != null && api.isAPIActive()) {
                RemotePlayer rp = api.getOnlinePlayer(memberId);
                RemoteArena ra = ArenaNames.findRemote(arenaName);
                if (rp != null && rp.isOnline() && !rp.isPlaying() && ra != null) {
                    if (ra.getStatus() == ArenaStatus.RUNNING) {
                        ra.addSpectator(rp, SpectateReason.ENTER, result -> {
                            if (onSuccess != null && result != null && result.wasSuccessful()) {
                                Bukkit.getScheduler().runTask(this, onSuccess);
                            }
                        });
                    } else {
                        ra.addPlayer(rp, result -> {
                            if (onSuccess == null || result == null) return;
                            RemotePlayerAddResult.PlayerResult pr = result.getPlayerResult(memberId);
                            if (pr == RemotePlayerAddResult.PlayerResult.SUCCESS
                                    || pr == RemotePlayerAddResult.PlayerResult.SUCCESS_SPECTATE) {
                                Bukkit.getScheduler().runTask(this, onSuccess);
                            }
                        });
                    }
                    return;
                }
            }
        } catch (Throwable t) {
            getLogger().warning("Remote summon failed for " + memberId + ": " + t.getMessage());
        }

        // Last resort: the player is here but the arena isn't reachable via RemoteAPI.
        if (localPlayer != null && localPlayer.isOnline()) {
            sendPlayerToArena(localPlayer, arenaName);
            if (onSuccess != null) onSuccess.run(); // best effort — this path has no completion callback
        }
    }
}
