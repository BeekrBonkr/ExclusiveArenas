package com.slg.exclusivearenas;

import de.marcely.bedwars.api.arena.Arena;
import de.marcely.bedwars.api.arena.KickReason;
import de.marcely.bedwars.api.event.arena.ArenaLobbyCountdownStartEvent;
import de.marcely.bedwars.api.event.arena.ArenaStatusChangeEvent;
import de.marcely.bedwars.api.event.arena.RoundEndEvent;
import de.marcely.bedwars.api.event.player.PlayerQuitArenaEvent;
import de.marcely.bedwars.api.event.player.SpectatorQuitArenaEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;

/**
 * Manages the lifecycle of private sessions in response to arena state changes.
 *
 * Sessions end immediately when a round ends (game over). When a lobby empties
 * we do NOT end the session immediately — the SessionCleanupTask handles the
 * host-abandon timeout so the host can leave and return freely.
 */
public final class PrivacyLifecycleListener implements Listener {

    private static final String BYPASS_PERM = "exclusivearenas.bypass";

    // Quit reasons that represent a legitimate end/transition, not an unwanted removal —
    // rejoining the player here would be pointless or actively wrong.
    private static final Set<KickReason> IGNORED_QUIT_REASONS = EnumSet.of(
            KickReason.SERVER_DISCONNECT, KickReason.ARENA_STOP, KickReason.PLUGIN_STOP,
            KickReason.GAME_LOSE, KickReason.GAME_END, KickReason.VOTING_SWITCH_ARENA,
            KickReason.FORCE_SWITCH_ARENA, KickReason.SPECTATE, KickReason.SPECTATE_ITEM_NEXT_ROUND);

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
        plugin.restoreArenaMinPlayers(s, event.getArena());
        sessions.endSession(s);
    }

    /**
     * There is no pre-game timer for a private match any more — MBedwars' own automatic lobby
     * countdown is unconditionally suppressed; the match only ever starts via the host's
     * explicit Start button/command ({@link ExclusiveArenasPlugin#startMatchNow}).
     */
    @EventHandler
    public void onLobbyCountdownStart(ArenaLobbyCountdownStartEvent event) {
        if (sessions.getByArena(event.getArena()) != null) event.setCancelled(true);
    }

    @EventHandler
    public void onStatusChange(ArenaStatusChangeEvent event) {
        PrivateSession s = sessions.getByArena(event.getArena());
        if (s == null) return;

        if (event.getNewStatus() != null && event.getNewStatus().isLobby()) {
            plugin.prepareLobby(event.getArena(), s);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitArenaEvent event) {
        Arena arena = event.getArena();
        Player player = event.getPlayer();
        markHostLeftIfApplicable(arena, player.getUniqueId());
        rejoinPartyMemberIfStillPartied(arena, player, event.getReason());
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

    /**
     * MBedwars has an unrelated bug where a party member can get kicked out of the arena right
     * after joining it. {@link PlayerQuitArenaEvent} can't be cancelled (it fires after the fact),
     * so this patches around it: if the player is still in the host's party, send them straight
     * back in — regardless of why they left — so leaving the match effectively isn't possible
     * while they remain partied with the host.
     */
    private void rejoinPartyMemberIfStillPartied(Arena arena, Player player, KickReason reason) {
        if (arena == null || IGNORED_QUIT_REASONS.contains(reason)) return;

        PrivateSession session = sessions.getByArena(arena);
        if (session == null) return;
        if (session.getJoinPolicy() != JoinPolicy.PARTY) return;
        if (player.getUniqueId().equals(session.getOwner())) return;
        if (player.hasPermission(BYPASS_PERM)) return;

        PartyResolver.isInLeadersParty(player, session.getOwner(), allowed -> {
            if (!allowed) return; // they left the party too — let them go

            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) return;
                if (sessions.getById(session.getSessionId()) == null) return;

                plugin.forceSummon(session, player.getUniqueId(), () -> player.sendMessage(ItemUtil.color(
                        "&eYou were sent back to &f" + session.getArenaName()
                                + "&e because you're in the host's party.")));
            });
        });
    }
}
