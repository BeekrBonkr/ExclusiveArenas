package com.slg.exclusivearenas;

import de.marcely.bedwars.api.BedwarsAPI;
import de.marcely.bedwars.api.arena.Arena;
import de.marcely.bedwars.api.arena.Team;
import de.marcely.bedwars.api.event.player.PlayerJoinArenaEvent;
import de.marcely.bedwars.api.event.player.PlayerOpenUIEvent;
import de.marcely.bedwars.api.event.player.PlayerTeamChangeEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Enforces the host's "Lock Teams" switch (Manage Teams → Lock Teams, stored on the session's
 * replicated {@link SessionSettings#isTeamsLocked()}): while a private match's lobby is locked,
 * players may not pick or change their own team.
 *
 * Two layers, because MBedwars exposes no single cancellable "player wants a different team"
 * hook:
 * <ul>
 *   <li>the team-selection UI is refused before it opens ({@link PlayerOpenUIEvent}), which is
 *       how virtually every switch is actually attempted, and</li>
 *   <li>{@link PlayerTeamChangeEvent} — which is <em>not</em> cancellable — is used as a
 *       backstop that puts a player who still got moved some other way straight back on their
 *       previous team.</li>
 * </ul>
 *
 * The host's own team management (Manage Teams, Distribute Players, a team-size change
 * unassigning everyone) runs through {@link #hostAction(Runnable)} so the backstop never fights
 * it, and a player joining the arena for the first time is never bounced — being assigned a
 * team you didn't have isn't "switching".
 */
public final class TeamLockListener implements Listener {

    /**
     * Nesting depth of an in-progress host-initiated team move. Plain int rather than an
     * atomic/thread-local: every mutation of arena team state runs on the main thread, and the
     * events it fires are dispatched synchronously inside that same call.
     */
    private int hostActionDepth;

    /** Player -> epoch ms of the last "teams are locked" notice, so one attempt says it once. */
    private final Map<UUID, Long> lastNotice = new ConcurrentHashMap<>();
    private static final long NOTICE_COOLDOWN_MS = 1500L;

    private final ExclusiveArenasPlugin plugin;
    private final PrivateSessionService sessions;

    public TeamLockListener(ExclusiveArenasPlugin plugin, PrivateSessionService sessions) {
        this.plugin = plugin;
        this.sessions = sessions;
    }

    /**
     * Runs a host-initiated team change with the lock backstop suspended. Must only be called
     * from the main thread, and {@code action} must do its work synchronously.
     */
    public void hostAction(Runnable action) {
        hostActionDepth++;
        try {
            action.run();
        } finally {
            hostActionDepth--;
        }
    }

    // ── Layer 1: refuse the team-selection UI ────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onOpenUI(PlayerOpenUIEvent event) {
        if (event.getType() != PlayerOpenUIEvent.UIType.LOBBY_SELECT_TEAM) return;

        Player player = event.getPlayer();
        Arena arena = BedwarsAPI.getGameAPI().getArenaByPlayer(player);
        PrivateSession session = sessions.getByArena(arena);
        if (!isLockedFor(session, arena, player)) return;

        event.setCancelled(true);
        notifyLocked(player);
    }

    // ── Layer 2: backstop any switch that still happened ─────────────────────────

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTeamChange(PlayerTeamChangeEvent event) {
        if (hostActionDepth > 0) return; // the host moved them — that's always allowed

        // Only a real switch is reverted: a player being given their first team (join, or the
        // host's own assignment) has nothing to switch back to.
        Team oldTeam = event.getOldTeam();
        if (oldTeam == null) return;

        Player player = event.getPlayer();
        Arena arena = event.getArena();
        PrivateSession session = sessions.getByArena(arena);
        if (!isLockedFor(session, arena, player)) return;

        notifyLocked(player);
        // Reverting inside the event itself would fight whatever is mid-way through applying
        // the change; do it once that has finished.
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline() || !arena.exists() || !arena.getStatus().isLobby()) return;
            if (!arena.getPlayers().contains(player)) return;   // left the arena entirely
            if (oldTeam.equals(arena.getPlayerTeam(player))) return; // something already put them back
            hostAction(() -> {
                try {
                    arena.moveToTeamDuringLobby(player, oldTeam);
                } catch (Throwable t) {
                    plugin.debug("Could not restore " + player.getName() + " to "
                            + oldTeam.name() + " in " + arena.getName() + ": " + t.getMessage());
                }
            });
        });
    }

    // ── Telling players where they stand ─────────────────────────────────────────

    /** Lets someone walking into a locked lobby know before they go looking for the team menu. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoinArena(PlayerJoinArenaEvent event) {
        Player player = event.getPlayer();
        Arena arena = event.getArena();
        PrivateSession session = sessions.getByArena(arena);
        if (!isLockedFor(session, arena, player)) return;

        // A tick later, so the notice lands after MBedwars' own join messages rather than
        // being scrolled away by them.
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) return;
            PrivateSession live = sessions.getByArena(arena);
            if (isLockedFor(live, arena, player)) player.sendMessage(Lang.msg("teams.locked-notice"));
        });
    }

    /**
     * True when {@code player} is subject to this session's team lock: the arena is a locked
     * private match still in its lobby, and they are neither its host nor an admin.
     */
    private boolean isLockedFor(PrivateSession session, Arena arena, Player player) {
        if (session == null || arena == null || !arena.exists()) return false;
        if (!session.getSettings().isTeamsLocked()) return false;
        if (!arena.getStatus().isLobby()) return false;
        if (player.getUniqueId().equals(session.getOwner())) return false;
        return !player.hasPermission(GuiManager.ADMIN_PERM) && !player.hasPermission(GuiManager.BYPASS_PERM);
    }

    /** Sends the "teams are locked" refusal, at most once per {@link #NOTICE_COOLDOWN_MS}. */
    private void notifyLocked(Player player) {
        long now = System.currentTimeMillis();
        Long last = lastNotice.get(player.getUniqueId());
        if (last != null && now - last < NOTICE_COOLDOWN_MS) return;
        lastNotice.put(player.getUniqueId(), now);

        // The map only ever holds players who actually tried something; sweep the stale
        // entries opportunistically so it can't grow for the server's whole uptime.
        if (lastNotice.size() > 128) {
            lastNotice.values().removeIf(t -> now - t > NOTICE_COOLDOWN_MS * 10);
        }
        player.sendMessage(Lang.msg("teams.locked-switch"));
    }
}
