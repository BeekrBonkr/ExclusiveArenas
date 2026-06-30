package com.slg.exclusivearenas;

import de.marcely.bedwars.api.arena.Arena;
import de.marcely.bedwars.api.event.arena.ArenaStatusChangeEvent;
import de.marcely.bedwars.api.event.arena.RoundEndEvent;
import de.marcely.bedwars.api.event.player.PlayerQuitArenaEvent;
import de.marcely.bedwars.api.event.player.SpectatorQuitArenaEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import java.time.Instant;

/**
 * Manages the lifecycle of private sessions in response to arena state changes.
 *
 * Sessions end immediately when a round ends (game over). When a lobby empties
 * we do NOT end the session immediately — the SessionCleanupTask handles the
 * host-abandon timeout so the host can leave and return freely.
 */
public final class PrivacyLifecycleListener implements Listener {

    private final ExclusiveArenasPlugin plugin;
    private final PrivateSessionService sessions;

    public PrivacyLifecycleListener(ExclusiveArenasPlugin plugin, PrivateSessionService sessions) {
        this.plugin = plugin;
        this.sessions = sessions;
    }

    @EventHandler
    public void onRoundEnd(RoundEndEvent event) {
        PrivateSession s = sessions.getByArena(event.getArena());
        if (s == null) return;
        sessions.endSession(s);
        plugin.getNetworkBus().broadcastEnd(s.getSessionId());
    }

    @EventHandler
    public void onStatusChange(ArenaStatusChangeEvent event) {
        PrivateSession s = sessions.getByArena(event.getArena());
        if (s == null) return;

        if (event.getNewStatus() != null && event.getNewStatus().isLobby()) {
            plugin.pauseLobbyCountdownIfNeeded(event.getArena(), s);
            // Countdown resets on status change back to lobby, so allow host to re-start it
            s.setCountdownStarted(false);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitArenaEvent event) {
        markHostLeftIfApplicable(event.getArena(), event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onSpectatorQuit(SpectatorQuitArenaEvent event) {
        // spectators leaving don't affect host-abandon tracking
    }

    private void markHostLeftIfApplicable(Arena arena, java.util.UUID leavingPlayer) {
        if (arena == null) return;
        PrivateSession s = sessions.getByArena(arena);
        if (s == null) return;
        if (!arena.getStatus().isLobby()) return;

        if (leavingPlayer.equals(s.getOwner()) && s.getHostLeftAt() == null) {
            s.setHostLeftAt(Instant.now());
        }
    }
}
