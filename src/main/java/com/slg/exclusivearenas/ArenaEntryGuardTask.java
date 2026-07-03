package com.slg.exclusivearenas;

import de.marcely.bedwars.api.BedwarsAPI;
import de.marcely.bedwars.api.arena.Arena;
import de.marcely.bedwars.api.arena.ArenaStatus;
import de.marcely.bedwars.api.game.spectator.SpectateReason;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * Occasionally a player ends up physically standing inside a private arena's bounds (most
 * often right after a cross-server transfer, racing the async ticket write-through) without
 * MBedwars ever actually registering them as a player or spectator of the match. This sweeps
 * every active private arena and finishes the join for anyone caught in that limbo — by the
 * time they are physically inside a reserved arena's bounds, our own gating has already
 * approved them getting there.
 */
public final class ArenaEntryGuardTask extends BukkitRunnable {

    private final PrivateSessionService sessions;
    private final JoinTicketService tickets;

    public ArenaEntryGuardTask(PrivateSessionService sessions, JoinTicketService tickets) {
        this.sessions = sessions;
        this.tickets = tickets;
    }

    @Override
    public void run() {
        for (PrivateSession session : sessions.getAllSessions()) {
            Arena arena = BedwarsAPI.getGameAPI().getArenaByExactName(session.getArenaName());
            if (arena == null || !arena.exists()) continue;

            World world = arena.getGameWorld();
            if (world == null) continue;

            for (Player player : world.getPlayers()) {
                if (!arena.isInside(player.getLocation())) continue;
                if (arena.getPlayers().contains(player) || arena.isSpectating(player)) continue;
                if (BedwarsAPI.getGameAPI().getArenaByPlayer(player) != null) continue; // registered elsewhere

                // The join gate (JoinListener) requires a valid ticket for Code-policy sessions,
                // and the original ticket was already consumed by whichever attempt got them
                // physically in here without registering — without a fresh one this add is
                // gated right back out as an unauthorised join, every sweep, forever.
                tickets.grant(player.getUniqueId(), session.getSessionId(), session.getArenaName());

                if (arena.getStatus() == ArenaStatus.RUNNING) {
                    arena.addSpectator(player, SpectateReason.ENTER);
                } else {
                    arena.addPlayer(player);
                }
            }
        }
    }
}
