package com.slg.exclusivearenas;

import de.marcely.bedwars.api.BedwarsAPI;
import de.marcely.bedwars.api.arena.Arena;
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
            RemoteAPI api = BedwarsAPI.getRemoteAPI();
            if (api == null || !api.isAPIActive()) return null;
            String canonical = canonical(name);
            RemoteArena ra = api.getArenaByExactName(canonical);
            if (ra == null || !ra.exists()) ra = api.getArenaByExactName("@" + canonical);
            return (ra != null && ra.exists()) ? ra : null;
        } catch (Throwable t) {
            return null;
        }
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
}
