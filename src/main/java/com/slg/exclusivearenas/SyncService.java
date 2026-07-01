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
        this.sessionTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            try {
                List<Database.SessionRow> rows = db.loadSessions();
                plugin.debug("session poll: " + rows.size() + " row(s)");
                Bukkit.getScheduler().runTask(plugin, () -> sessions.reconcile(rows));
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
    }

    public void stop() {
        if (sessionTask != null) sessionTask.cancel();
        if (ticketTask != null) ticketTask.cancel();
        if (commandTask != null) commandTask.cancel();
    }
}
