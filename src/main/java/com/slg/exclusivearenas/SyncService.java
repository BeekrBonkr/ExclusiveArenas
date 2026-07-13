package com.slg.exclusivearenas;

import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;
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
                    long staleBefore = System.currentTimeMillis() - deadServerStaleMillis;
                    for (String deadServerId : db.findDeadServers(staleBefore)) {
                        plugin.getLogger().warning("ExclusiveArenas: server '" + deadServerId
                                + "' hasn't sent a heartbeat in over " + (deadServerStaleMillis / 1000)
                                + "s — treating it as crashed and purging its sessions.");
                        db.purgeDeadServer(deadServerId);
                    }
                } catch (Throwable t) {
                    plugin.getLogger().log(Level.WARNING, "Dead-server sweep failed: " + t.getMessage());
                }
            }, deadServerSweepTicks, deadServerSweepTicks);
        }
    }

    public void stop() {
        if (sessionTask != null) sessionTask.cancel();
        if (ticketTask != null) ticketTask.cancel();
        if (commandTask != null) commandTask.cancel();
        if (deadServerTask != null) deadServerTask.cancel();
    }
}
