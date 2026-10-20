package com.slg.exclusivearenas;

import de.marcely.bedwars.api.BedwarsAPI;
import de.marcely.bedwars.api.arena.Arena;
import de.marcely.bedwars.api.arena.ArenaStatus;
import de.marcely.bedwars.api.remote.RemoteAPI;
import de.marcely.bedwars.api.remote.RemoteArena;

/**
 * Arena-name normalisation.
 *
 * MBedwars' RemoteAPI prefixes arena names with '@' when they are listed from another
 * server (e.g. the hub sees a backend arena as "@SkyPit"). That prefix is only a
 * remote-listing marker — the server actually hosting the arena, and every gameplay
 * event on it, use the bare name ("SkyPit").
 *
 * ExclusiveArenas therefore stores and matches sessions/tickets by the CANONICAL (bare)
 * name so a session created on the hub lines up with the local arena on the backend.
 * The '@' form is only ever needed to talk to the RemoteAPI back on the hub.
 */
public final class ArenaNames {

    private ArenaNames() {}

    /** Strips the leading remote marker, giving the arena's real (hosting-server) name. */
    public static String canonical(String name) {
        if (name == null) return null;
        return name.startsWith("@") ? name.substring(1) : name;
    }

    /** The RemoteAPI listing form of a canonical name. */
    public static String remoteForm(String name) {
        String canonical = canonical(name);
        return canonical == null ? null : "@" + canonical;
    }

    /**
     * Resolves a RemoteArena tolerant of either form, since callers may hold the canonical
     * name while the RemoteAPI keys it under the '@' form (or vice-versa).
     */
    public static RemoteArena findRemote(String name) {
        try {
            RemoteAPI api = remoteApi();
            if (api == null) return null;
            String canonical = canonical(name);
            RemoteArena ra = api.getArenaByExactName(canonical);
            if (ra == null || !ra.exists()) ra = api.getArenaByExactName("@" + canonical);
            return (ra != null && ra.exists()) ? ra : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * MBedwars' RemoteAPI when it is up on this server (proxy/network mode), else null. The
     * single place that knows the null + {@code isAPIActive()} rule; on a standalone server
     * this is always null and every caller falls back to purely local behaviour.
     */
    public static RemoteAPI remoteApi() {
        try {
            RemoteAPI api = BedwarsAPI.getRemoteAPI();
            return api != null && api.isAPIActive() ? api : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** True when this server is part of an MBedwars network (RemoteAPI active). */
    public static boolean remoteApiActive() {
        return remoteApi() != null;
    }

    /**
     * The name to hand MBedwars' join command to route a player to an arena that is not on
     * this server. Taken from the RemoteAPI's own listing (which carries the '@' remote marker
     * exactly as this server knows the arena), so it is right on a hub and on a sibling
     * backend alike without the admin having to set {@code is_hub_server}. That flag remains
     * the fallback when the RemoteAPI cannot resolve the arena.
     */
    public static String routeName(String name, boolean hubConfigured) {
        String canonical = canonical(name);
        RemoteArena ra = findRemote(canonical);
        if (ra != null && !ra.isLocal() && ra.getName() != null && !ra.getName().isBlank()) {
            return ra.getName();
        }
        return hubConfigured ? "@" + canonical : canonical;
    }

    /** What is known about an arena's lobby status from this server. */
    public enum LobbyState {
        /** In its lobby (local, or confirmed via RemoteAPI). */
        LOBBY,
        /** Running, resetting, stopped — anything but the lobby. */
        NOT_LOBBY,
        /** Neither local nor visible through the RemoteAPI (standalone server whose arena was
         *  unloaded, a backend not currently listed, RemoteAPI inactive, …). */
        UNKNOWN
    }

    /**
     * Tri-state version of {@link #isLobbyStatus}: callers that only need to refuse an action
     * once the round has actually started (editing replicated settings, for example) should
     * block on {@link LobbyState#NOT_LOBBY} only, instead of treating "can't see the arena" as
     * "the round began".
     */
    public static LobbyState lobbyState(String arenaName) {
        Arena local = BedwarsAPI.getGameAPI().getArenaByExactName(canonical(arenaName));
        if (local != null && local.exists()) {
            return local.getStatus().isLobby() ? LobbyState.LOBBY : LobbyState.NOT_LOBBY;
        }
        RemoteArena remote = findRemote(arenaName);
        if (remote == null) return LobbyState.UNKNOWN;
        return remote.getStatus().isLobby() ? LobbyState.LOBBY : LobbyState.NOT_LOBBY;
    }

    /**
     * True if the arena is currently in its lobby — checked locally when this server hosts it,
     * otherwise via RemoteAPI, so callers (menus, gating) don't need to care which server the
     * acting player happens to be standing on.
     */
    public static boolean isLobbyStatus(String arenaName) {
        Arena local = BedwarsAPI.getGameAPI().getArenaByExactName(canonical(arenaName));
        if (local != null && local.exists()) return local.getStatus().isLobby();
        RemoteArena remote = findRemote(arenaName);
        return remote != null && remote.getStatus().isLobby();
    }

    /**
     * True if the arena is genuinely still in play (lobby or running) rather than stopped or
     * being reset. Used as a fresher-than-our-own-DB-poll sanity check before acting on a
     * session that might have just ended elsewhere on the network — MBedwars' own arena status
     * (local or via RemoteAPI) reflects reality faster than our session cache can reconcile.
     */
    public static boolean isActiveStatus(String arenaName) {
        Arena local = BedwarsAPI.getGameAPI().getArenaByExactName(canonical(arenaName));
        if (local != null && local.exists()) return isActive(local.getStatus());
        RemoteArena remote = findRemote(arenaName);
        return remote != null && isActive(remote.getStatus());
    }

    private static boolean isActive(ArenaStatus status) {
        return status == ArenaStatus.LOBBY || status == ArenaStatus.RUNNING;
    }
}
