package com.slg.exclusivearenas;

import de.marcely.bedwars.api.BedwarsAPI;
import de.marcely.bedwars.api.arena.Arena;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.time.Duration;
import java.time.Instant;

/**
 * Periodic task (every 30 seconds) that ends sessions which have been abandoned, gone stale,
 * or sat inactive.
 *
 * Abandon:    host left the lobby and has not returned within host_abandon_timeout_minutes.
 * Stale:      session was created more than stale_session_hours ago and the arena is not running.
 * Inactivity: the lobby has had zero active players for inactivity_warning_minutes — everyone
 *             who's online is warned once, then the session is ended after an additional
 *             inactivity_close_grace_minutes with still nobody actively playing.
 */
public final class SessionCleanupTask extends BukkitRunnable {

    private final ExclusiveArenasPlugin plugin;
    private final PrivateSessionService sessions;

    public SessionCleanupTask(ExclusiveArenasPlugin plugin,
                              PrivateSessionService sessions) {
        this.plugin = plugin;
        this.sessions = sessions;
    }

    @Override
    public void run() {
        long abandonMs = (long)(plugin.getEaConfig().num(
                "private.host_abandon_timeout_minutes", 5) * 60_000L);
        long staleMs = (long)(plugin.getEaConfig().num(
                "private.stale_session_hours", 12) * 3_600_000L);
        long inactivityWarnMs = (long)(plugin.getEaConfig().num(
                "private.inactivity_warning_minutes", 10) * 60_000L);
        long inactivityGraceMs = (long)(plugin.getEaConfig().num(
                "private.inactivity_close_grace_minutes", 5) * 60_000L);

        Instant now = Instant.now();

        for (PrivateSession session : sessions.getAllSessions()) {
            Arena arena = BedwarsAPI.getGameAPI().getArenaByExactName(session.getArenaName());

            // --- Abandon check ---
            Instant leftAt = session.getHostLeftAt();
            if (leftAt != null && Duration.between(leftAt, now).toMillis() > abandonMs) {
                if (arena != null) {
                    arena.broadcast(Lang.msg("cleanup.host-abandoned"));
                }
                sessions.endSession(session);
                continue;
            }

            // --- Stale check ---
            if (Duration.between(session.getCreatedAt(), now).toMillis() > staleMs) {
                // Remote-aware on purpose: getArenaByExactName is local-only, so on a hub every
                // backend-hosted arena is null — treating that like STOPPED would kill a match
                // still RUNNING on its backend. Only stale-kill when the arena is not active
                // anywhere on the network.
                if (!ArenaNames.isActiveStatus(session.getArenaName())) {
                    plugin.getLogger().info("Ending stale session for arena "
                            + session.getArenaName() + " (created " + session.getCreatedAt() + ")");
                    sessions.endSession(session);
                    continue;
                }
            }

            // --- Inactivity check (lobby sitting with nobody actually playing) ---
            if (inactivityWarnMs > 0 && arena != null && arena.getStatus().isLobby()) {
                if (!arena.getPlayers().isEmpty()) {
                    session.setInactiveSince(null);
                    session.setInactivityWarned(false);
                } else if (session.getInactiveSince() == null) {
                    session.setInactiveSince(now);
                } else {
                    long inactiveMs = Duration.between(session.getInactiveSince(), now).toMillis();
                    if (!session.isInactivityWarned() && inactiveMs > inactivityWarnMs) {
                        warnInactive(session, arena, Math.max(1, inactivityGraceMs / 60_000L));
                        session.setInactivityWarned(true);
                    } else if (session.isInactivityWarned()
                            && inactiveMs > inactivityWarnMs + inactivityGraceMs) {
                        plugin.getLogger().info("Ending inactive session for arena "
                                + session.getArenaName() + " (no active players for "
                                + (inactiveMs / 60_000L) + " minutes)");
                        arena.broadcast(Lang.msg("cleanup.inactivity-closed"));
                        sessions.endSession(session);
                    }
                }
            }
        }
    }

    /** Warns whoever's actually around — anyone in the arena, plus the host if online elsewhere. */
    private void warnInactive(PrivateSession session, Arena arena, long graceMinutes) {
        String message = Lang.msg("cleanup.inactivity-warning", "%minutes%", String.valueOf(graceMinutes));
        arena.broadcast(message);
        Player host = Bukkit.getPlayer(session.getOwner());
        if (host != null && host.isOnline() && !arena.getPlayers().contains(host)
                && !arena.isSpectating(host)) {
            host.sendMessage(message);
        }
    }
}
