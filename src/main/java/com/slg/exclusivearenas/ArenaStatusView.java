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
 * Builds human-readable live-status lore for a private session's arena (text from lang.yml's
 * {@code status.*} keys). Prefers the rich local {@link Arena} view; falls back to a
 * best-effort {@link RemoteArena} snapshot when the arena is hosted on another backend, and
 * to session-only info if neither is reachable.
 */
public final class ArenaStatusView {

    private ArenaStatusView() {}

    /** Compact status lines suitable for an item's lore in a list menu. */
    public static List<String> lore(PrivateSession session) {
        List<String> lines = new ArrayList<>();

        Arena local = BedwarsAPI.getGameAPI().getArenaByExactName(session.getArenaName());
        if (local != null && local.exists()) {
            lines.add(Lang.raw("status.state", "%state%", statusLabel(local.getStatus())));
            lines.add(timingLine(local));
            lines.add(Lang.raw("status.players",
                    "%current%", String.valueOf(local.getPlayers().size()),
                    "%max%", String.valueOf(local.getMaxPlayers())));
            if (local.getStatus() == ArenaStatus.RUNNING) {
                lines.add(Lang.raw("status.teams-alive",
                        "%alive%", String.valueOf(local.getAliveTeams().size()),
                        "%total%", String.valueOf(countAlivePlayers(local))));
            }
        } else {
            RemoteArena remote = remote(session.getArenaName());
            if (remote != null) {
                lines.add(Lang.raw("status.state-remote", "%state%", statusLabel(remote.getStatus())));
                lines.add(Lang.raw("status.players",
                        "%current%", String.valueOf(remote.getPlayersCount()),
                        "%max%", String.valueOf(remote.getMaxPlayers())));
            } else {
                lines.add(Lang.raw("status.state-unavailable"));
            }
        }

        lines.add("&8");
        lines.add(policyLine(session));
        return lines;
    }

    /** Fuller status block for the controls menu info item. */
    public static List<String> detail(PrivateSession session) {
        List<String> lines = new ArrayList<>();
        lines.add(Lang.raw("status.arena", "%arena%", session.getArenaName()));
        lines.addAll(lore(session));
        return lines;
    }

    public static String statusLabel(ArenaStatus status) {
        if (status == null) return Lang.raw("status.label-unknown");
        return switch (status) {
            case LOBBY -> Lang.raw("status.label-lobby");
            case RUNNING -> Lang.raw("status.label-running");
            case END_LOBBY -> Lang.raw("status.label-ending");
            case RESETTING -> Lang.raw("status.label-resetting");
            case STOPPED -> Lang.raw("status.label-stopped");
        };
    }

    public static String policyLine(PrivateSession session) {
        if (session.getJoinPolicy() == JoinPolicy.CODE) {
            return Lang.raw("status.access-code",
                    "%state%", Lang.raw(session.isPublic() ? "status.label-public" : "status.label-locked"),
                    "%code%", safeCode(session));
        }
        return Lang.raw("status.access-party");
    }

    private static String timingLine(Arena arena) {
        if (arena.getStatus() == ArenaStatus.RUNNING) {
            return Lang.raw("status.running", "%time%", formatDuration(arena.getRunningTime()));
        }
        if (arena.getStatus() == ArenaStatus.LOBBY) {
            double remaining = arena.getLobbyTimeRemaining();
            return Lang.raw("status.lobby", "%time%", remaining > 0
                    ? (int) Math.ceil(remaining) + "s" : Lang.raw("status.label-waiting"));
        }
        return Lang.raw("status.elapsed", "%time%", formatDuration(arena.getRunningTime()));
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
