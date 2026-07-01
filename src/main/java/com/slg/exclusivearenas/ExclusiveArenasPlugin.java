package com.slg.exclusivearenas;

import de.marcely.bedwars.api.BedwarsAPI;
import de.marcely.bedwars.api.arena.Arena;
import de.marcely.bedwars.api.arena.ArenaStatus;
import de.marcely.bedwars.api.arena.Team;
import de.marcely.bedwars.api.game.spectator.SpectateReason;
import de.marcely.bedwars.api.hook.PartiesHook;
import de.marcely.bedwars.api.remote.RemoteAPI;
import de.marcely.bedwars.api.remote.RemoteArena;
import de.marcely.bedwars.api.remote.RemotePlayer;
import de.marcely.bedwars.api.remote.RemotePlayerAddResult;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Set;
import java.util.UUID;

public final class ExclusiveArenasPlugin extends JavaPlugin {

    private ExclusiveArenasAddon addon;
    private EaConfig eaConfig;
    private DraftService draftService;
    private PrivateSessionService sessionService;
    private JoinTicketService ticketService;
    private GuiManager guiManager;
    private Database database;   // null when running single-server (database.enabled = false)
    private SyncService syncService;
    private RemoteCommandService remoteCommandService;
    private org.bukkit.scheduler.BukkitTask autoSummonTask;
    private PrivacyConditionVariable privacyConditionVariable;
    private ArenaBossBarTask bossBarTask;
    private org.bukkit.scheduler.BukkitTask bossBarSchedulerTask;
    private SpectateOnStartHandler spectateOnStartHandler;
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

        this.draftService   = new DraftService();
        this.sessionService = new PrivateSessionService();
        this.ticketService  = new JoinTicketService();
        this.remoteCommandService = new RemoteCommandService(this, sessionService);
        this.guiManager     = new GuiManager(this, draftService, sessionService);

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

        // Periodic cleanup (every 30 seconds)
        new SessionCleanupTask(this, sessionService).runTaskTimer(this, 600L, 600L);

        // Finishes the join for anyone who ends up physically inside a private arena (e.g. after
        // a cross-server transfer) without MBedwars having actually registered them as playing.
        new ArenaEntryGuardTask(sessionService).runTaskTimer(this, 60L, 60L);

        startAutoSummon();
        startBossBar();
        registerConditionVariable();
        registerLobbyItemHandlers();

        getLogger().info("ExclusiveArenas v" + getDescription().getVersion() + " enabled ("
                + (database != null ? "database mode" : "single-server mode") + ").");
    }

    /**
     * Registers our custom MBedwars lobby hotbar items. Registering only makes the handler
     * available by id — an admin still has to add an entry referencing it to MBedwars' own
     * lobby-hotbar.yml (slot/icon/name are entirely up to them):
     *   - "exclusivearenas:open_controls"  → visible only to the match's host; opens Match Controls.
     *   - "exclusivearenas:toggle_spectate" → any player; opts out of playing, spectates at round start.
     */
    private void registerLobbyItemHandlers() {
        try {
            this.matchControlsLobbyItemHandler = new MatchControlsLobbyItemHandler(this, sessionService, guiManager);
            BedwarsAPI.getGameAPI().registerLobbyItemHandler(matchControlsLobbyItemHandler);

            this.spectateOnStartHandler = new SpectateOnStartHandler(this, sessionService);
            BedwarsAPI.getGameAPI().registerLobbyItemHandler(spectateOnStartHandler);
            Bukkit.getPluginManager().registerEvents(spectateOnStartHandler, this);
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
        } catch (Throwable ignored) {
            // best effort on shutdown
        }
        matchControlsLobbyItemHandler = null;
        spectateOnStartHandler = null;
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

    private void startAutoSummon() {
        stopAutoSummon();
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
        applyTunables();
        setupDatabase();
        startAutoSummon(); // restart to pick up a changed poll interval
        startBossBar();    // restart to pick up a changed enabled/disabled setting

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
     * Creates the private session described by the host's draft and immediately sends the
     * host into the chosen arena — local or on another server. Replaces the old two-step
     * "creation mode" flow with a single action from the builder.
     */
    public void createAndJoin(Player host, DraftPrivateMatch draft) {
        if (draft == null || !draft.isReadyToCreate()) {
            host.sendMessage(ItemUtil.color("&cSelect a map first."));
            return;
        }

        // Party-membership rules depend on an async party lookup, so validate that first and
        // only continue on to the actual creation once it's confirmed OK.
        JoinPolicy policy = draft.getJoinPolicy() == null ? JoinPolicy.PARTY : draft.getJoinPolicy();
        PartyResolver.getPartyMember(host, opt -> {
            if (policy == JoinPolicy.CODE && opt.isPresent()) {
                host.sendMessage(ItemUtil.color("&cYou can't host a Join Code match while you're in a party "
                        + "— use Party policy instead, or leave your party first."));
                return;
            }
            if (policy == JoinPolicy.PARTY) {
                boolean isLeader = opt.isPresent() && opt.get().getParty().getLeaders().stream()
                        .anyMatch(leader -> leader.getUniqueId().equals(host.getUniqueId()));
                if (!isLeader) {
                    host.sendMessage(ItemUtil.color("&cYou must be the leader of a party to host a Party-policy match."));
                    return;
                }
            }
            Bukkit.getScheduler().runTask(this, () -> finishCreateAndJoin(host, draft));
        });
    }

    private void finishCreateAndJoin(Player host, DraftPrivateMatch draft) {
        String arenaName = draft.getArenaName();

        int limit = getArenaLimit(host);
        if (sessionService.countByOwner(host.getUniqueId()) >= limit) {
            host.sendMessage(ItemUtil.color("&cYou already host the maximum of &f" + limit
                    + "&c private match" + (limit == 1 ? "" : "es") + "."));
            return;
        }
        if (sessionService.isArenaReserved(arenaName)) {
            host.sendMessage(ItemUtil.color("&cThat arena is already reserved by another private match."));
            return;
        }
        if (!isArenaJoinable(arenaName)) {
            host.sendMessage(ItemUtil.color("&c&f" + arenaName
                    + "&c is not available right now (it must be empty and in its lobby)."));
            return;
        }

        PrivateSession session = sessionService.createSession(draft);
        draftService.clear(host.getUniqueId());

        // Use the canonical name the session stored (not the raw draft name, which may carry
        // the '@' remote marker) so the ticket matches the arena on its host server.
        String canonical = session.getArenaName();

        // createSession + grant write through to the shared DB; every backend will mirror
        // this session/ticket from its poll. Authorise the host, then route them below.
        ticketService.grant(host.getUniqueId(), session.getSessionId(), canonical);

        Arena local = BedwarsAPI.getGameAPI().getArenaByExactName(canonical);
        if (local != null) prepareLobby(local, session);

        host.sendMessage(ItemUtil.color("&aCreated your private match on &f" + canonical + "&a! Sending you in…"));
        if (session.getJoinPolicy() == JoinPolicy.CODE) {
            host.sendMessage(ItemUtil.color("&7Join code: &f" + session.getJoinCode()
                    + " &7— share with &f/ea join " + session.getJoinCode()));
        } else {
            host.sendMessage(ItemUtil.color("&7Gating: &aParty members only."));
        }

        sendPlayerToArena(host, canonical);
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
            if (local.getStatus() == ArenaStatus.RUNNING && !local.isPlaying(player)) {
                local.addSpectator(player, SpectateReason.ENTER);
            } else {
                local.addPlayer(player);
            }
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
            player.sendMessage(ItemUtil.color("&cArena &f" + arenaName + " &cis unavailable."));
        }
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
            actor.sendMessage(ItemUtil.color("&cThe arena is on another server."));
            return;
        }
        remoteCommandService.enqueue(RemoteCommandService.Type.START_MATCH, session);
        actor.sendMessage(ItemUtil.color("&aSent the start request to &f" + session.getArenaName() + "&a."));
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
            actor.sendMessage(ItemUtil.color("&aEnded the private match on &f" + session.getArenaName() + "&a."));
            return;
        }
        remoteCommandService.enqueue(RemoteCommandService.Type.END_MATCH, session);
        actor.sendMessage(ItemUtil.color("&aSent the request to end the match on &f" + session.getArenaName() + "&a."));
    }

    /** Ends a match: removes the shared session state and, if the arena is local, clears it. */
    public void endMatch(PrivateSession session) {
        if (session == null) return;
        Arena arena = BedwarsAPI.getGameAPI().getArenaByExactName(session.getArenaName());
        restoreArenaMinPlayers(session, arena);
        sessionService.endSession(session);
        if (arena != null && arena.exists()) {
            arena.broadcast(ItemUtil.color("&cThe private match has been ended."));
            arena.kickAllPlayers();
        }
    }

    // ── Lobby / session helpers ─────────────────────────────────────────────────

    /**
     * Prepares a private match's arena while it sits in its lobby: relaxes the min-players
     * requirement so a small party isn't fought by MBedwars' own lobby logic. There is no
     * pre-game timer any more — the match only ever begins when the host explicitly starts it
     * (see {@link #startMatchNow}); MBedwars' own automatic lobby countdown is unconditionally
     * cancelled for private arenas (see the {@code ArenaLobbyCountdownStartEvent} guard in
     * {@link PrivacyLifecycleListener}).
     */
    public void prepareLobby(Arena arena, PrivateSession session) {
        if (arena == null || session == null) return;
        if (!arena.getStatus().isLobby()) return;
        relaxMinPlayers(arena, session);
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
            tell(actor, arena, "&cThe match has already begun.");
            return;
        }
        if (arena.getPlayers().size() < 2) {
            tell(actor, arena, "&cYou need at least one other player in the arena before you can start.");
            return;
        }

        try {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "bw debug 13 " + arena.getName());
        } catch (Throwable t) {
            getLogger().warning("Could not force-start arena " + arena.getName() + ": " + t.getMessage());
            tell(actor, arena, "&cFailed to start the match — see console.");
            return;
        }
        arena.broadcast(ItemUtil.color("&aThe host started the match!"));
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

    public void regenerateJoinCode(PrivateSession session) {
        if (session == null || session.getJoinPolicy() != JoinPolicy.CODE) return;
        String newCode = sessionService.regenerateJoinCode(session);
        if (newCode == null) return;
        Arena arena = BedwarsAPI.getGameAPI().getArenaByExactName(session.getArenaName());
        if (arena != null) arena.broadcast(ItemUtil.color("&eJoin code regenerated: &f" + newCode
                + " &e— use &f/ea join " + newCode));
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
                host.sendMessage(ItemUtil.color("&cYou are not in a party."));
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
                host.sendMessage(ItemUtil.color(count > 0
                        ? "&aSummoning &f" + count + " &aparty member(s)…"
                        : "&7You have no other party members to summon."));
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
            actor.sendMessage(ItemUtil.color("&cThe arena is on another server."));
            return;
        }
        if (!arena.getStatus().isLobby()) {
            actor.sendMessage(ItemUtil.color("&cTeams can only be changed while the arena is in its lobby."));
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
            actor.sendMessage(ItemUtil.color("&aMoved &f" + moved + "&a player(s) to " + team.getDisplayName() + "&a."));
        }
        if (skipped > 0) {
            actor.sendMessage(ItemUtil.color("&c" + skipped + " player(s) could not be moved (team full)."));
        }
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

        // Both here → add directly (skip if they're already in this arena). A match already
        // RUNNING can't accept new players, so pull them in as a spectator instead.
        if (localArena != null && localArena.exists() && localPlayer != null && localPlayer.isOnline()) {
            boolean alreadyThere = localArena.getPlayers().contains(localPlayer)
                    || localArena.isSpectating(localPlayer);
            if (!alreadyThere) {
                if (localArena.getStatus() == ArenaStatus.RUNNING) {
                    localArena.addSpectator(localPlayer, SpectateReason.ENTER);
                } else {
                    localArena.addPlayer(localPlayer);
                }
            }
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
