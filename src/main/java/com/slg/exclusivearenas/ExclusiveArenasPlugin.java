package com.slg.exclusivearenas;

import de.marcely.bedwars.api.BedwarsAPI;
import de.marcely.bedwars.api.arena.Arena;
import de.marcely.bedwars.api.arena.ArenaStatus;
import de.marcely.bedwars.api.arena.Team;
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
import java.util.Set;
import java.util.UUID;

public final class ExclusiveArenasPlugin extends JavaPlugin {

    private ExclusiveArenasAddon addon;
    private EaConfig eaConfig;
    private VersionedYaml langYaml;
    private VersionedYaml guisYaml;
    private DraftService draftService;
    /** Players with a create-and-join in flight (past the async party check) — guards against a
     *  double-click or double-command firing two overlapping creations for the same player. */
    private final Set<UUID> creatingSessionFor = java.util.concurrent.ConcurrentHashMap.newKeySet();
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
    private SyncService syncService;
    private RemoteCommandService remoteCommandService;
    private org.bukkit.scheduler.BukkitTask autoSummonTask;
    private PrivacyConditionVariable privacyConditionVariable;
    private ArenaBossBarTask bossBarTask;
    private org.bukkit.scheduler.BukkitTask bossBarSchedulerTask;
    private SpectateOnStartHandler spectateOnStartHandler;
    private SpectatorRejoinHandler spectatorRejoinHandler;
    private PartySummonLobbyItemHandler partySummonLobbyItemHandler;
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

        // Periodic cleanup (every 30 seconds)
        new SessionCleanupTask(this, sessionService).runTaskTimer(this, 600L, 600L);

        // Finishes the join for anyone who ends up physically inside a private arena (e.g. after
        // a cross-server transfer) without MBedwars having actually registered them as playing.
        // Runs at the same cadence as the ticket poller (database.ticket_poll_seconds, default
        // 1s) — that poll is the dominant source of the race this recovers from, so sweeping
        // any slower would just add avoidable extra delay on top of it. The sweep itself is a
        // pure in-memory scan (no I/O), so the tighter interval costs nothing.
        long entryGuardTicks = Math.max(20L, eaConfig.intNum("database.ticket_poll_seconds", 1) * 20L);
        new ArenaEntryGuardTask(sessionService, ticketService).runTaskTimer(this, entryGuardTicks, entryGuardTicks);

        startAutoSummon();
        startBossBar();
        startGuiRefresh();
        registerConditionVariable();
        registerLobbyItemHandlers();

        getLogger().info("ExclusiveArenas v" + getDescription().getVersion() + " enabled ("
                + (database != null ? "database mode" : "single-server mode") + ").");
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
        this.langYaml = new VersionedYaml(this, addon.getDataFolder(), "lang.yml", 3, (config, fromVersion) -> {
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
            return changed;
        });
        this.langYaml.load();
        Lang.init(langYaml);

        this.guisYaml = new VersionedYaml(this, addon.getDataFolder(), "guis.yml", 4, (config, fromVersion) -> {
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
            return changed;
        });
        this.guisYaml.load();
        GuiStyle.init(guisYaml);
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
        teardownTweaksBridge();
        if ("internal".equalsIgnoreCase(eaConfig.str("timeline.backend", "auto"))) return;
        if (Bukkit.getPluginManager().getPlugin("MBedwarsTweaks") == null) return;

        this.tweaksBridge = TweaksTimelineBridge.tryCreate(this, sessionService, timelineService);
        if (tweaksBridge != null) {
            Bukkit.getPluginManager().registerEvents(tweaksBridge, this);
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

        try {
            Database db = new Database(getLogger(), settings, isVerbose());
            db.initSchema();
            this.database = db;

            sessionService.setDatabase(db);
            ticketService.setDatabase(db);
            remoteCommandService.setDatabase(db);

            long sessionTicks = Math.max(20L, eaConfig.intNum("database.session_poll_seconds", 4) * 20L);
            long ticketTicks  = Math.max(20L, eaConfig.intNum("database.ticket_poll_seconds", 1) * 20L);
            long commandTicks = Math.max(20L, eaConfig.intNum("database.command_poll_seconds", 2) * 20L);
            this.syncService = new SyncService(this, db, sessionService, ticketService, remoteCommandService);
            this.syncService.start(sessionTicks, ticketTicks, commandTicks);
        } catch (Throwable t) {
            getLogger().severe("Could not connect to the ExclusiveArenas database — falling back to "
                    + "single-server in-memory mode. Cause: " + t.getMessage());
            this.database = null;
            this.syncService = null;
            sessionService.setDatabase(null);
            ticketService.setDatabase(null);
            remoteCommandService.setDatabase(null);
        }
    }

    /** Cleanly tears down the database + sync tasks (safe to call when already down). */
    private void teardownDatabase() {
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
        setupDatabase();
        startAutoSummon(); // restart to pick up a changed poll interval
        startBossBar();    // restart to pick up a changed enabled/disabled setting
        startGuiRefresh();

        // Push current in-memory state back to the (possibly reconnected) database so a poll
        // does not evict live matches that predate the reconnect.
        sessionService.resyncAll();

        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.getOpenInventory() != null
                    && online.getOpenInventory().getTopInventory().getHolder() instanceof GuiHolder) {
                online.closeInventory();
            }
        }
        getLogger().info("ExclusiveArenas reloaded ("
                + (database != null ? "database mode" : "single-server mode") + ").");
    }

    @Override
    public void onDisable() {
        stopAutoSummon();
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
        resolveDraftPolicy(p, blocked -> {
            guiManager.openBuilder(p);
            if (blocked) {
                p.sendMessage(Lang.msg("create.party-blocked"));
            }
        });
    }

    /**
     * Resolves the host's join policy from their current party leadership and applies it to
     * their draft — shared by the builder GUI and the headless {@code /ea create <map>}
     * command so both derive the same Party/Join-Code/blocked outcome the same way.
     *
     * @param then receives {@code true} if the player is a non-leader party member (creation
     *             blocked; they should join their leader's match instead), run on the main thread
     */
    private void resolveDraftPolicy(Player p, java.util.function.Consumer<Boolean> then) {
        PartyResolver.getPartyMember(p, opt -> {
            boolean inParty = opt.isPresent();
            boolean isLeader = inParty && opt.get().getParty().getLeaders().stream()
                    .anyMatch(leader -> leader.getUniqueId().equals(p.getUniqueId()));
            boolean blocked = inParty && !isLeader;

            Bukkit.getScheduler().runTask(this, () -> {
                DraftPrivateMatch draft = draftService.getOrCreate(p.getUniqueId());
                draft.setPartyBlocked(blocked);
                if (!blocked) {
                    draft.setJoinPolicy(isLeader ? JoinPolicy.PARTY : JoinPolicy.CODE);
                    if (draft.getJoinPolicy() == JoinPolicy.CODE) {
                        draft.setAutoSummon(false); // only meaningful for Party policy
                        if (draft.getJoinCode() == null || draft.getJoinCode().isBlank()) {
                            draft.setJoinCode(sessionService.generateCode());
                        }
                    }
                }
                then.accept(blocked);
            });
        });
    }

    /**
     * Headless equivalent of the builder GUI's "select map" + "Create & Join": resolves the
     * host's join policy exactly as the builder menu does, points a fresh draft at
     * {@code mapName}, and creates + joins immediately. Backs {@code /ea create <map>}.
     */
    public void createAndJoinByMapName(Player host, String mapName, boolean joinAfterCreate) {
        resolveDraftPolicy(host, blocked -> {
            if (blocked) {
                host.sendMessage(Lang.msg("create.party-blocked"));
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
        if (!creatingSessionFor.add(host.getUniqueId())) return;

        // Party-membership rules depend on an async party lookup, so validate that first and
        // only continue on to the actual creation once it's confirmed OK.
        JoinPolicy policy = draft.getJoinPolicy() == null ? JoinPolicy.PARTY : draft.getJoinPolicy();
        PartyResolver.getPartyMember(host, opt -> {
            if (policy == JoinPolicy.CODE && opt.isPresent()) {
                creatingSessionFor.remove(host.getUniqueId());
                host.sendMessage(Lang.msg("create.code-while-in-party"));
                return;
            }
            if (policy == JoinPolicy.PARTY) {
                boolean isLeader = opt.isPresent() && opt.get().getParty().getLeaders().stream()
                        .anyMatch(leader -> leader.getUniqueId().equals(host.getUniqueId()));
                if (!isLeader) {
                    creatingSessionFor.remove(host.getUniqueId());
                    host.sendMessage(Lang.msg("create.must-be-leader"));
                    return;
                }
            }
            Bukkit.getScheduler().runTask(this, () -> {
                try {
                    finishCreateAndJoin(host, draft, joinAfterCreate);
                } finally {
                    creatingSessionFor.remove(host.getUniqueId());
                }
            });
        });
    }

    private void finishCreateAndJoin(Player host, DraftPrivateMatch draft, boolean joinAfterCreate) {
        String arenaName = draft.getArenaName();

        int limit = getArenaLimit(host);
        if (sessionService.countByOwner(host.getUniqueId()) >= limit) {
            host.sendMessage(Lang.msg("create.limit-reached", "%limit%", String.valueOf(limit)));
            return;
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
        session.setSettings(draft.getSettings()); // carries over any Arena Settings chosen pre-creation
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
        if (local != null && local.exists() || !remoteCommandService.isAvailable()) {
            endMatch(session);
            actor.sendMessage(Lang.msg("match.ended", "%arena%", session.getArenaName()));
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

    /** Only the host is in the arena so far — the one point a team-size change can't disrupt anyone. */
    public boolean canChangeTeamSize(Arena arena) {
        return arena == null || !arena.exists() || arena.getPlayers().size() <= 1;
    }

    /**
     * Applies the session's players-per-team override (Arena Settings → Team Size) to the live
     * arena, snapshotting its original value on first use so it can be restored once the match
     * ends via {@link #restoreArenaPlayersPerTeam}. A no-op override just keeps the arena at its
     * original value — mirrors {@link #relaxMinPlayers}, including being safe to call repeatedly.
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
            } catch (Throwable ignored) {
                // best effort
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
        int moved = 0, skipped = 0;
        for (UUID id : playerIds) {
            Player target = Bukkit.getPlayer(id);
            if (target == null || !target.isOnline() || !arena.getPlayers().contains(target)) continue;
            if (arena.getPlayersInTeam(team).size() >= cap) { skipped++; continue; }
            if (arena.moveToTeamDuringLobby(target, team)) moved++; else skipped++;
        }

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
            if (arena.moveToTeamDuringLobby(target, assigned)) moved++;
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
