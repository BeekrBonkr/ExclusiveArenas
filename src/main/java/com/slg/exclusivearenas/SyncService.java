package com.slg.exclusivearenas;

import de.marcely.bedwars.api.BedwarsAPI;
import de.marcely.bedwars.api.arena.Arena;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Background reconciler that keeps the in-memory caches in step with the shared database.
 *
 * Three async repeating tasks load the authoritative rows off the main thread, then bounce
 * the actual cache mutation back onto the main thread via {@code runTask} so game logic
 * (the synchronous join-gate listener in particular) never observes a half-updated view.
 */
public final class SyncService {

    private final ExclusiveArenasPlugin plugin;
    private final Database db;
    private final PrivateSessionService sessions;
    private final JoinTicketService tickets;
    private final RemoteCommandService commands;

    private BukkitTask sessionTask;
    private BukkitTask ticketTask;
    private BukkitTask commandTask;
    private BukkitTask deadServerTask;

    public SyncService(ExclusiveArenasPlugin plugin, Database db,
                       PrivateSessionService sessions, JoinTicketService tickets,
                       RemoteCommandService commands) {
        this.plugin = plugin;
        this.db = db;
        this.sessions = sessions;
        this.tickets = tickets;
        this.commands = commands;
    }

    /** Starts all three pollers. Intervals are in ticks (20 ticks = 1 second). */
    public void start(long sessionPollTicks, long ticketPollTicks, long commandPollTicks) {
        start(sessionPollTicks, ticketPollTicks, commandPollTicks, 0, 0);
    }

    /**
     * Starts all three pollers, plus (when {@code deadServerSweepTicks > 0}) a periodic sweep
     * that notices another backend has stopped sending heartbeats and purges its orphaned
     * sessions/tickets/commands from the shared database — see {@link Database#heartbeat()} /
     * {@link Database#findDeadServers}/{@link Database#purgeDeadServer}. Intervals are in ticks
     * (20 ticks = 1 second).
     */
    public void start(long sessionPollTicks, long ticketPollTicks, long commandPollTicks,
                      long deadServerSweepTicks, long deadServerStaleMillis) {
        this.sessionTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            try {
                List<Database.SessionRow> rows = db.loadSessions();
                plugin.debug("session poll: " + rows.size() + " row(s)");
                Bukkit.getScheduler().runTask(plugin, () -> sessions.reconcile(rows));
                // Piggybacked here rather than its own task — this poll already runs on exactly
                // the cadence a heartbeat needs, so a separate scheduled task would be redundant.
                db.heartbeat();
            } catch (Throwable t) {
                plugin.getLogger().log(Level.WARNING, "Session sync failed: " + t.getMessage());
            }
        }, sessionPollTicks, sessionPollTicks);

        this.ticketTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            try {
                List<Database.TicketRow> rows = db.loadValidTickets();
                plugin.debug("ticket poll: " + rows.size() + " row(s)");
                Bukkit.getScheduler().runTask(plugin, () -> tickets.reconcile(rows));
            } catch (Throwable t) {
                plugin.getLogger().log(Level.WARNING, "Ticket sync failed: " + t.getMessage());
            }
        }, ticketPollTicks, ticketPollTicks);

        this.commandTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            try {
                List<Database.CommandRow> rows = db.loadCommands();
                if (!rows.isEmpty()) plugin.debug("command poll: " + rows.size() + " row(s)");
                Bukkit.getScheduler().runTask(plugin, () -> commands.reconcile(rows));
            } catch (Throwable t) {
                plugin.getLogger().log(Level.WARNING, "Command sync failed: " + t.getMessage());
            }
        }, commandPollTicks, commandPollTicks);

        if (deadServerSweepTicks > 0) {
            this.deadServerTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
                try {
                    List<String> deadServers = db.findDeadServers(deadServerStaleMillis);
                    if (deadServers.isEmpty()) return;
                    // A row's server_id is stamped by whichever server last WROTE it — a match
                    // created/edited from a hub carries the hub's id while its arena runs on a
                    // backend. So a dead server's rows can't just be deleted wholesale: load
                    // them here (still async), then vet each one on the main thread against
                    // MBedwars' own (remote-aware) arena status before purging.
                    List<Database.SessionRow> rows = db.loadSessions();
                    Bukkit.getScheduler().runTask(plugin, () -> sweepDeadServers(deadServers, rows));
                } catch (Throwable t) {
                    plugin.getLogger().log(Level.WARNING, "Dead-server sweep failed: " + t.getMessage());
                }
            }, deadServerSweepTicks, deadServerSweepTicks);
        }
    }

    /**
     * Main-thread half of the dead-server sweep: decides, per session row attributed to a
     * dead server, whether it is truly orphaned. A session whose arena is still active
     * anywhere on the network (per {@link ArenaNames#isActiveStatus}) is never purged — and
     * when that arena is active HERE, the row is adopted (re-written under our own server_id)
     * so it gets a live owner. Everything else is handed to
     * {@link Database#purgeDeadServer}, which also only drops the heartbeat row once no
     * sessions remain attributed to the dead server — so rows skipped now are retried on a
     * later sweep.
     */
    private void sweepDeadServers(List<String> deadServers, List<Database.SessionRow> rows) {
        for (String deadServerId : deadServers) {
            List<UUID> purgeIds = new ArrayList<>();
            List<String> purgeArenas = new ArrayList<>();
            int spared = 0;
            for (Database.SessionRow row : rows) {
                if (!deadServerId.equals(row.serverId())) continue;
                if (ArenaNames.isActiveStatus(row.arenaName())) {
                    spared++;
                    Arena local = BedwarsAPI.getGameAPI()
                            .getArenaByExactName(ArenaNames.canonical(row.arenaName()));
                    if (local != null && local.exists()) sessions.adoptSession(row.sessionId());
                    continue;
                }
                purgeIds.add(row.sessionId());
                purgeArenas.add(row.arenaName());
            }
            if (!purgeIds.isEmpty() || spared == 0) {
                plugin.getLogger().warning("ExclusiveArenas: server '" + deadServerId
                        + "' has stopped sending heartbeats — treating it as crashed; purging "
                        + purgeIds.size() + " orphaned session(s)"
                        + (spared > 0 ? " (" + spared + " spared: arena still active)" : "") + ".");
            } else {
                // Everything it wrote is still live — nothing to purge yet, so don't warn on
                // every sweep; the heartbeat row stays until its sessions end (or get adopted).
                plugin.debug("dead server '" + deadServerId + "': all " + spared
                        + " session(s) still active — purge deferred");
            }
            db.purgeDeadServer(deadServerId, purgeIds, purgeArenas);
        }
    }

    public void stop() {
        if (sessionTask != null) sessionTask.cancel();
        if (ticketTask != null) ticketTask.cancel();
        if (commandTask != null) commandTask.cancel();
        if (deadServerTask != null) deadServerTask.cancel();
    }
}
