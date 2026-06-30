package com.slg.exclusivearenas;

import de.marcely.bedwars.api.BedwarsAPI;
import de.marcely.bedwars.api.arena.Arena;
import de.marcely.bedwars.api.hook.PartiesHook;
import de.marcely.bedwars.api.remote.RemoteAPI;
import de.marcely.bedwars.api.remote.RemoteArena;
import de.marcely.bedwars.api.remote.RemotePlayer;
import de.marcely.bedwars.api.remote.RemotePlayerAddResult;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.UUID;

public final class ExclusiveArenasPlugin extends JavaPlugin {

    private ExclusiveArenasAddon addon;
    private EaConfig eaConfig;
    private DraftService draftService;
    private PrivateSessionService sessionService;
    private JoinTicketService ticketService;
    private GuiManager guiManager;
    private NetworkBus networkBus;

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
        this.guiManager     = new GuiManager(this, draftService, sessionService);
        this.networkBus     = new NetworkBus(this, sessionService, ticketService);

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
        Bukkit.getPluginManager().registerEvents(networkBus, this);

        // Periodic cleanup (every 30 seconds)
        new SessionCleanupTask(this, sessionService).runTaskTimer(this, 600L, 600L);

        getLogger().info("ExclusiveArenas v" + getDescription().getVersion() + " enabled ("
                + (networkBus.isNetworkActive() ? "network mode" : "single-server mode") + ").");
    }

    @Override
    public void onDisable() {
        if (addon != null && addon.isRegistered()) addon.unregister();
        getLogger().info("ExclusiveArenas disabled.");
    }

    // ── Public API for listeners / commands ───────────────────────────────────

    public EaConfig getEaConfig()                   { return eaConfig; }
    public DraftService getDraftService()           { return draftService; }
    public PrivateSessionService getSessionService(){ return sessionService; }
    public JoinTicketService getTicketService()     { return ticketService; }
    public GuiManager getGuiManager()               { return guiManager; }
    public NetworkBus getNetworkBus()               { return networkBus; }

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
        String arenaName = draft.getArenaName();

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

        // Replicate to the network and authorise the host on the (possibly remote) arena server.
        networkBus.broadcastCreate(session);
        ticketService.grant(host.getUniqueId(), session.getSessionId(), arenaName);
        networkBus.broadcastTicket(host.getUniqueId(), session.getSessionId(), arenaName);

        Arena local = BedwarsAPI.getGameAPI().getArenaByExactName(arenaName);
        if (local != null) pauseLobbyCountdownIfNeeded(local, session);

        host.sendMessage(ItemUtil.color("&aCreated your private match on &f" + arenaName + "&a! Sending you in…"));
        if (session.getJoinPolicy() == JoinPolicy.CODE) {
            host.sendMessage(ItemUtil.color("&7Join code: &f" + session.getJoinCode()
                    + " &7— share with &f/ea join " + session.getJoinCode()));
        } else {
            host.sendMessage(ItemUtil.color("&7Gating: &aParty members only."));
        }

        sendPlayerToArena(host, arenaName);
    }

    /** Adds a player to an arena whether it lives on this server or another one on the network. */
    public void sendPlayerToArena(Player player, String arenaName) {
        Arena local = BedwarsAPI.getGameAPI().getArenaByExactName(arenaName);
        if (local != null && local.exists()) {
            local.addPlayer(player);
            return;
        }

        try {
            RemoteAPI remote = BedwarsAPI.getRemoteAPI();
            if (remote != null && remote.isAPIActive()) {
                RemoteArena ra = remote.getArenaByExactName(arenaName);
                RemotePlayer rp = remote.getOnlinePlayer(player);
                if (ra != null && rp != null) {
                    ra.addPlayer(rp, result -> {
                        if (result == null
                                || result.getGeneralResult() != RemotePlayerAddResult.GeneralResult.SUCCESS) {
                            player.sendMessage(ItemUtil.color("&cCould not send you to &f" + arenaName + "&c."));
                        }
                    });
                    return;
                }
            }
        } catch (Throwable t) {
            getLogger().warning("Failed to route player to remote arena " + arenaName + ": " + t.getMessage());
        }
        player.sendMessage(ItemUtil.color("&cArena &f" + arenaName + " &cis unavailable."));
    }

    /** True if the arena exists (locally or remotely) and is an empty lobby ready to be reserved. */
    private boolean isArenaJoinable(String arenaName) {
        Arena local = BedwarsAPI.getGameAPI().getArenaByExactName(arenaName);
        if (local != null && local.exists()) {
            return local.getStatus().isLobby() && local.getPlayers().isEmpty();
        }
        try {
            RemoteAPI remote = BedwarsAPI.getRemoteAPI();
            if (remote != null && remote.isAPIActive()) {
                RemoteArena ra = remote.getArenaByExactName(arenaName);
                if (ra != null && ra.exists()) {
                    return ra.getStatus().isLobby() && ra.getPlayersCount() == 0;
                }
            }
        } catch (Throwable ignored) {
            // fall through
        }
        return false;
    }

    // ── Lobby / session helpers ─────────────────────────────────────────────────

    public void pauseLobbyCountdownIfNeeded(Arena arena, PrivateSession session) {
        if (arena == null || session == null) return;
        if (!arena.getStatus().isLobby()) return;
        if (session.isCountdownStarted()) return;
        try {
            arena.setLobbyTimeRemaining(eaConfig.num("private.lobby_time_seconds", 120), false);
        } catch (Throwable t) {
            getLogger().warning("Could not pause lobby countdown for " + arena.getName() + ": " + t.getMessage());
        }
    }

    public void startLobbyCountdown(Arena arena, PrivateSession session) {
        if (arena == null || session == null) return;
        if (!arena.getStatus().isLobby()) return;
        if (session.isCountdownStarted()) return;

        session.setCountdownStarted(true);
        double remaining = arena.getLobbyTimeRemaining();
        if (remaining <= 0) remaining = eaConfig.num("private.lobby_time_seconds", 120);
        try {
            arena.setLobbyTimeRemaining(remaining, true);
            arena.broadcast(ItemUtil.color("&aThe host has started the match countdown."));
        } catch (Throwable t) {
            getLogger().warning("Could not start lobby countdown for " + arena.getName() + ": " + t.getMessage());
        }
    }

    public void regenerateJoinCode(PrivateSession session) {
        if (session == null || session.getJoinPolicy() != JoinPolicy.CODE) return;
        String newCode = sessionService.regenerateJoinCode(session);
        if (newCode == null) return;
        networkBus.broadcastUpdate(session);
        Arena arena = BedwarsAPI.getGameAPI().getArenaByExactName(session.getArenaName());
        if (arena != null) arena.broadcast(ItemUtil.color("&eJoin code regenerated: &f" + newCode
                + " &e— use &f/ea join " + newCode));
    }

    public void summonPartyToArena(Player host, Arena arena, PrivateSession session) {
        if (host == null || arena == null || session == null) return;

        PartyResolver.getPartyMember(host, opt -> {
            if (opt.isEmpty()) {
                host.sendMessage(ItemUtil.color("&cNo party system detected."));
                return;
            }
            PartiesHook.Party party = opt.get().getParty();
            int summoned = 0;
            for (PartiesHook.Member m : party.getMembers(true)) {
                UUID uuid = m.getUniqueId();
                Player online = Bukkit.getPlayer(uuid);
                if (online == null || !online.isOnline()) continue;
                if (arena.getPlayers().contains(online)) continue;
                ticketService.grant(uuid, session.getSessionId(), arena.getName());
                arena.addPlayer(online);
                summoned++;
            }
            host.sendMessage(ItemUtil.color("&aSummoned &f" + summoned + " &aparty member(s)."));
        });
    }
}
