package com.slg.exclusivearenas;

import de.marcely.bedwars.api.BedwarsAPI;
import de.marcely.bedwars.api.arena.Arena;
import de.marcely.bedwars.api.arena.ArenaStatus;
import org.bukkit.scheduler.BukkitRunnable;

import java.time.Duration;
import java.time.Instant;

/**
 * Periodic task (every 30 seconds) that ends sessions which have been abandoned or gone stale.
 *
 * Abandon: host left the lobby and has not returned within host_abandon_timeout_minutes.
 * Stale:   session was created more than stale_session_hours ago and the arena is not running.
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

        Instant now = Instant.now();

        for (PrivateSession session : sessions.getAllSessions()) {
            Arena arena = BedwarsAPI.getGameAPI().getArenaByExactName(session.getArenaName());

            // --- Abandon check ---
            Instant leftAt = session.getHostLeftAt();
            if (leftAt != null && Duration.between(leftAt, now).toMillis() > abandonMs) {
                if (arena != null) {
                    arena.broadcast(ItemUtil.color(plugin.getEaConfig().str(
                            "messages.session_abandoned",
                            "&cPrivate match ended: the host did not return in time.")));
                }
                sessions.endSession(session);
                plugin.getNetworkBus().broadcastEnd(session.getSessionId());
                continue;
            }

            // --- Stale check ---
            if (Duration.between(session.getCreatedAt(), now).toMillis() > staleMs) {
                if (arena == null || arena.getStatus() == ArenaStatus.STOPPED
                        || arena.getStatus() == ArenaStatus.RESETTING) {
                    plugin.getLogger().info("Ending stale session for arena "
                            + session.getArenaName() + " (created " + session.getCreatedAt() + ")");
                    sessions.endSession(session);
                    plugin.getNetworkBus().broadcastEnd(session.getSessionId());
                }
            }
        }
    }
}
