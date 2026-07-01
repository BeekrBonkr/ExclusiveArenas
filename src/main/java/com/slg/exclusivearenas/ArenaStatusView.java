package com.slg.exclusivearenas;

import de.marcely.bedwars.api.BedwarsAPI;
import de.marcely.bedwars.api.arena.Arena;
import de.marcely.bedwars.api.arena.ArenaStatus;
import de.marcely.bedwars.api.arena.Team;
import de.marcely.bedwars.api.remote.RemoteArena;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds human-readable live-status lore for a private session's arena. Prefers the rich
 * local {@link Arena} view; falls back to a best-effort {@link RemoteArena} snapshot when
 * the arena is hosted on another backend, and to session-only info if neither is reachable.
 */
public final class ArenaStatusView {

    private ArenaStatusView() {}

    /** Compact status lines suitable for an item's lore in a list menu. */
    public static List<String> lore(PrivateSession session) {
        List<String> lines = new ArrayList<>();

        Arena local = BedwarsAPI.getGameAPI().getArenaByExactName(session.getArenaName());
        if (local != null && local.exists()) {
            lines.add("&7State: " + statusLabel(local.getStatus()));
            lines.add(timingLine(local));
            lines.add("&7Players: &f" + local.getPlayers().size() + "&7/&f" + local.getMaxPlayers());
            if (local.getStatus() == ArenaStatus.RUNNING) {
                lines.add("&7Teams alive: &f" + local.getAliveTeams().size()
                        + " &7• Players alive: &f" + countAlivePlayers(local));
            }
        } else {
            RemoteArena remote = remote(session.getArenaName());
            if (remote != null) {
                lines.add("&7State: " + statusLabel(remote.getStatus()) + " &8[remote]");
                lines.add("&7Players: &f" + remote.getPlayersCount() + "&7/&f" + remote.getMaxPlayers());
            } else {
                lines.add("&7State: &8unavailable");
            }
        }

        lines.add("&8");
        lines.add(policyLine(session));
        return lines;
    }

    /** Fuller status block for the controls menu info item. */
    public static List<String> detail(PrivateSession session) {
        List<String> lines = new ArrayList<>();
        lines.add("&7Arena: &f" + session.getArenaName());
        lines.addAll(lore(session));
        return lines;
    }

    public static String statusLabel(ArenaStatus status) {
        if (status == null) return "&8Unknown";
        return switch (status) {
            case LOBBY -> "&aLobby";
            case RUNNING -> "&2Running";
            case END_LOBBY -> "&eEnding";
            case RESETTING -> "&6Resetting";
            case STOPPED -> "&cStopped";
        };
    }

    public static String policyLine(PrivateSession session) {
        if (session.getJoinPolicy() == JoinPolicy.CODE) {
            String state = session.isPublic() ? "&aPublic" : "&cLocked";
            return "&7Access: &dCode &8(" + state + "&8) &7• &f" + safeCode(session);
        }
        return "&7Access: &bParty only";
    }

    private static String timingLine(Arena arena) {
        if (arena.getStatus() == ArenaStatus.RUNNING) {
            return "&7Running: &f" + formatDuration(arena.getRunningTime());
        }
        if (arena.getStatus() == ArenaStatus.LOBBY) {
            double remaining = arena.getLobbyTimeRemaining();
            return "&7Lobby: &f" + (remaining > 0 ? (int) Math.ceil(remaining) + "s" : "waiting");
        }
        return "&7Elapsed: &f" + formatDuration(arena.getRunningTime());
    }

    private static int countAlivePlayers(Arena arena) {
        int alive = 0;
        for (Team team : arena.getAliveTeams()) {
            alive += arena.getPlayersInTeam(team).size();
        }
        return alive;
    }

    public static String formatDuration(Duration d) {
        if (d == null) return "0:00";
        long total = Math.max(0, d.getSeconds());
        long minutes = total / 60;
        long seconds = total % 60;
        if (minutes >= 60) {
            long hours = minutes / 60;
            minutes %= 60;
            return String.format("%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format("%d:%02d", minutes, seconds);
    }

    private static String safeCode(PrivateSession session) {
        return session.getJoinCode() != null ? session.getJoinCode() : "—";
    }

    private static RemoteArena remote(String arenaName) {
        return ArenaNames.findRemote(arenaName);
    }
}
