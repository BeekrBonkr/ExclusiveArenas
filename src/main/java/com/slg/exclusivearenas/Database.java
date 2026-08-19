package com.slg.exclusivearenas;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Standalone HikariCP-pooled JDBC store that is the network-wide source of truth for
 * active private matches ({@code <prefix>sessions}) and pending join tickets
 * ({@code <prefix>tickets}).
 *
 * This is deliberately our OWN database rather than MBedwars': MBedwars exposes no
 * public DB API, may run on SQLite (not network-shared), and its schema/migrations are
 * internal. We bundle our own MariaDB driver (shaded + relocated) instead.
 *
 * <p>Threading contract:
 * <ul>
 *   <li>Mutations ({@code upsert*}/{@code delete*}) are fire-and-forget on a single
 *       daemon writer thread — callers never block; a failed write is retried once
 *       (re-queued at the tail) and then logged and dropped.</li>
 *   <li>Reads ({@link #loadSessions()}, {@link #loadValidTickets()}) are synchronous and
 *       must be invoked from an async scheduler thread, never the main thread.</li>
 * </ul>
 */
public final class Database {

    /** Connection + layout settings, resolved from config. */
    public record Settings(String host, int port, String database, String user, String password,
                           String tablePrefix, boolean useSsl, String serverId) {}

    /** A row of {@code <prefix>sessions}. {@code settings} is the JSON blob of host customizations. */
    public record SessionRow(UUID sessionId, UUID owner, String arenaName, String policy,
                             String joinCode, boolean isPublic, boolean autoSummon,
                             String settings, String serverId, long createdAt, long updatedAt) {}

    /** A row of {@code <prefix>tickets}. */
    public record TicketRow(UUID player, UUID sessionId, String arenaName, long expiresAt) {}

    /**
     * A row of {@code <prefix>commands}: a one-shot action queued against an arena by a host who
     * isn't on the server that hosts it. Whichever backend actually has that arena loaded claims
     * the row (by deleting it) and executes it — see {@link RemoteCommandService}.
     */
    public record CommandRow(UUID id, UUID sessionId, String arenaName, String type,
                             String payload, long createdAt) {}

    private final Logger logger;
    private final Settings settings;
    private final boolean verbose;
    private final String sessionsTable;
    private final String ticketsTable;
    private final String commandsTable;
    private final String presetsTable;
    private final String serversTable;
    private final HikariDataSource dataSource;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ExclusiveArenas-DB-Writer");
        t.setDaemon(true);
        return t;
    });

    // Rough depth of the writer's queue: incremented on submit, decremented when the write
    // finishes. Crossing the threshold logs one WARNING (re-armed once the backlog drains
    // below half) so a database that's falling behind is visible instead of silently queueing.
    private final java.util.concurrent.atomic.AtomicInteger queueDepth =
            new java.util.concurrent.atomic.AtomicInteger();
    private volatile boolean queueDepthWarned;
    private static final int QUEUE_DEPTH_WARN_THRESHOLD = 64;
    private static final long RETRY_DELAY_MS = 2_000L;

    public Database(Logger logger, Settings settings, boolean verbose) {
        this.logger = logger;
        this.settings = settings;
        this.verbose = verbose;
        this.sessionsTable = settings.tablePrefix() + "sessions";
        this.ticketsTable = settings.tablePrefix() + "tickets";
        this.commandsTable = settings.tablePrefix() + "commands";
        this.presetsTable = settings.tablePrefix() + "presets";
        this.serversTable = settings.tablePrefix() + "servers";

        HikariConfig cfg = new HikariConfig();
        cfg.setPoolName("ExclusiveArenas-Hikari");
        // Set the driver class EXPLICITLY. In a plugin, DriverManager's ServiceLoader
        // discovery runs in the server's classloader, not ours, so it never finds the
        // shaded driver's META-INF/services entry (→ "Failed to get driver instance").
        // Naming the class makes Hikari load it directly via our own classloader.
        // The class literal is rewritten by shadow to the relocated driver, so getName()
        // returns the relocated FQCN at runtime.
        cfg.setDriverClassName(org.mariadb.jdbc.Driver.class.getName());
        // useSsl is passed as a datasource property rather than appended as a URL query string,
        // so a configured database name containing '?' or '&' can't be read as (or clobber) a
        // connection parameter.
        cfg.setJdbcUrl("jdbc:mariadb://" + settings.host() + ":" + settings.port() + "/" + settings.database());
        cfg.addDataSourceProperty("useSsl", String.valueOf(settings.useSsl()));
        cfg.setUsername(settings.user());
        cfg.setPassword(settings.password());
        cfg.setMaximumPoolSize(5);
        cfg.setMinimumIdle(1);
        cfg.setConnectionTimeout(8_000L);
        this.dataSource = new HikariDataSource(cfg);
    }

    public String serverId() {
        return settings.serverId();
    }

    // ── Schema ───────────────────────────────────────────────────────────────────

    /** Verifies connectivity and creates the tables if they do not yet exist. */
    public void initSchema() throws SQLException {
        verbose("Opening pool to " + settings.host() + ":" + settings.port()
                + "/" + settings.database() + " (serverId=" + settings.serverId() + ")");
        try (Connection c = dataSource.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "CREATE TABLE IF NOT EXISTS `" + sessionsTable + "` ("
                            + "session_id CHAR(36) NOT NULL PRIMARY KEY,"
                            + "owner CHAR(36) NULL,"
                            + "arena_name VARCHAR(128) NOT NULL UNIQUE,"
                            + "policy VARCHAR(16) NOT NULL,"
                            + "join_code VARCHAR(96) NULL,"
                            + "is_public TINYINT(1) NOT NULL DEFAULT 0,"
                            + "auto_summon TINYINT(1) NOT NULL DEFAULT 0,"
                            + "settings TEXT NULL,"
                            + "server_id VARCHAR(64) NOT NULL,"
                            + "created_at BIGINT NOT NULL,"
                            + "updated_at BIGINT NOT NULL DEFAULT 0"
                            + ")")) {
                ps.executeUpdate();
            }
            // Bring older tables up to date (columns added after first deploy).
            addColumnIfMissing(c, sessionsTable, "is_public", "TINYINT(1) NOT NULL DEFAULT 0");
            addColumnIfMissing(c, sessionsTable, "auto_summon", "TINYINT(1) NOT NULL DEFAULT 0");
            addColumnIfMissing(c, sessionsTable, "settings", "TEXT NULL");
            // Lets callers tell a fresh row apart from one a lagging poll read before their own
            // more recent write committed — see PrivateSessionService's pendingWriteAt guard.
            addColumnIfMissing(c, sessionsTable, "updated_at", "BIGINT NOT NULL DEFAULT 0");
            try (PreparedStatement ps = c.prepareStatement(
                    "CREATE TABLE IF NOT EXISTS `" + ticketsTable + "` ("
                            + "player CHAR(36) NOT NULL PRIMARY KEY,"
                            + "session_id CHAR(36) NOT NULL,"
                            + "arena_name VARCHAR(128) NOT NULL,"
                            + "expires_at BIGINT NOT NULL"
                            + ")")) {
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "CREATE TABLE IF NOT EXISTS `" + commandsTable + "` ("
                            + "id CHAR(36) NOT NULL PRIMARY KEY,"
                            + "session_id CHAR(36) NOT NULL,"
                            + "arena_name VARCHAR(128) NOT NULL,"
                            + "type VARCHAR(32) NOT NULL,"
                            + "payload TEXT NULL,"
                            + "created_at BIGINT NOT NULL"
                            + ")")) {
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "CREATE TABLE IF NOT EXISTS `" + presetsTable + "` ("
                            + "owner CHAR(36) NOT NULL,"
                            + "name VARCHAR(32) NOT NULL,"
                            + "settings TEXT NULL,"
                            + "updated_at BIGINT NOT NULL,"
                            + "PRIMARY KEY (owner, name)"
                            + ")")) {
                ps.executeUpdate();
            }
            // Liveness heartbeat — see heartbeat()/findDeadServers()/purgeDeadServer(). Lets any
            // backend notice another one has crashed (no heartbeat in a while) and clean up its
            // orphaned sessions/tickets/commands, instead of them sitting in the shared DB forever.
            try (PreparedStatement ps = c.prepareStatement(
                    "CREATE TABLE IF NOT EXISTS `" + serversTable + "` ("
                            + "server_id VARCHAR(64) NOT NULL PRIMARY KEY,"
                            + "last_heartbeat BIGINT NOT NULL"
                            + ")")) {
                ps.executeUpdate();
            }
        }
        logger.info("ExclusiveArenas connected to database; tables '" + sessionsTable
                + "' / '" + ticketsTable + "' / '" + commandsTable + "' ready.");
    }

    private void addColumnIfMissing(Connection c, String table, String column, String definition) {
        // Probe information_schema instead of relying on ADD COLUMN IF NOT EXISTS: that syntax
        // is MariaDB-only, and on MySQL the swallowed failure would leave the column missing —
        // making every later write that references it fail forever.
        try {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT 1 FROM information_schema.columns "
                            + "WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?")) {
                ps.setString(1, table);
                ps.setString(2, column);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return; // already up to date
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "ALTER TABLE `" + table + "` ADD COLUMN " + column + " " + definition)) {
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            logger.warning("ExclusiveArenas: could not add column '" + column + "' to '" + table
                    + "': " + e.getMessage() + " — writes touching that column will fail until "
                    + "it is added manually.");
        }
    }

    // ── Async write-through (fire-and-forget) ──────────────────────────────────────

    public void upsertSession(SessionRow row) {
        submit("upsert session " + row.arenaName(), c -> {
            // arena_name carries its own UNIQUE index alongside the session_id primary key, so a
            // new session for an arena whose old row hasn't been deleted yet (a session-end and a
            // fresh create racing across servers) collides on arena_name, not session_id. Without
            // updating session_id here too, that row would keep the OLD session's id paired with
            // the NEW session's data — a mismatch that lets the old session's later DELETE (keyed
            // by its session_id) wipe out the new session's row out from under it. Updating
            // session_id on conflict means whichever session wins the row also fully claims its
            // identity, so a stale DELETE for the old id becomes a harmless no-op instead.
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO `" + sessionsTable + "` "
                            + "(session_id, owner, arena_name, policy, join_code, is_public, auto_summon, "
                            + "settings, server_id, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?) "
                            + "ON DUPLICATE KEY UPDATE session_id=VALUES(session_id), owner=VALUES(owner), "
                            + "arena_name=VALUES(arena_name), policy=VALUES(policy), join_code=VALUES(join_code), "
                            + "is_public=VALUES(is_public), auto_summon=VALUES(auto_summon), "
                            + "settings=VALUES(settings), server_id=VALUES(server_id), "
                            + "updated_at=VALUES(updated_at)")) {
                ps.setString(1, row.sessionId().toString());
                ps.setString(2, row.owner() == null ? null : row.owner().toString());
                ps.setString(3, row.arenaName());
                ps.setString(4, row.policy());
                ps.setString(5, row.joinCode());
                ps.setBoolean(6, row.isPublic());
                ps.setBoolean(7, row.autoSummon());
                ps.setString(8, row.settings());
                ps.setString(9, settings.serverId()); // this server owns/stamps the write
                ps.setLong(10, row.createdAt());
                ps.setLong(11, row.updatedAt());
                ps.executeUpdate();
            }
        });
    }

    public void deleteSession(UUID sessionId) {
        submit("delete session " + sessionId, c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM `" + sessionsTable + "` WHERE session_id=?")) {
                ps.setString(1, sessionId.toString());
                ps.executeUpdate();
            }
        });
    }

    /**
     * Writes the ticket with an expiry anchored to the DATABASE clock (DB now + {@code
     * ttlMillis}) rather than {@code row.expiresAt()}. The validity check happens on whichever
     * server hosts the arena — anchoring both write and read to the one clock every server
     * shares means a skewed backend can neither shorten nor stretch another server's tickets.
     */
    public void upsertTicket(TicketRow row, long ttlMillis) {
        submit("upsert ticket " + row.player(), c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO `" + ticketsTable + "` (player, session_id, arena_name, expires_at) "
                            + "VALUES (?,?,?,ROUND(UNIX_TIMESTAMP(NOW(3))*1000)+?) "
                            + "ON DUPLICATE KEY UPDATE session_id=VALUES(session_id), "
                            + "arena_name=VALUES(arena_name), expires_at=VALUES(expires_at)")) {
                ps.setString(1, row.player().toString());
                ps.setString(2, row.sessionId().toString());
                ps.setString(3, row.arenaName());
                ps.setLong(4, ttlMillis);
                ps.executeUpdate();
            }
        });
    }

    public void deleteTicket(UUID player) {
        submit("delete ticket " + player, c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM `" + ticketsTable + "` WHERE player=?")) {
                ps.setString(1, player.toString());
                ps.executeUpdate();
            }
        });
    }

    public void insertCommand(CommandRow row) {
        submit("insert command " + row.type() + " for " + row.arenaName(), c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO `" + commandsTable + "` (id, session_id, arena_name, type, payload, created_at) "
                            + "VALUES (?,?,?,?,?,?)")) {
                ps.setString(1, row.id().toString());
                ps.setString(2, row.sessionId().toString());
                ps.setString(3, row.arenaName());
                ps.setString(4, row.type());
                ps.setString(5, row.payload());
                ps.setLong(6, row.createdAt());
                ps.executeUpdate();
            }
        });
    }

    public void upsertPreset(UUID owner, String name, String settingsJson) {
        upsertPreset(owner, name, settingsJson, null);
    }

    /**
     * Saves a preset, then reports whether it actually succeeded — on the writer thread, not
     * the main thread; callers touching Bukkit API from {@code callback} must hop back
     * themselves. Lets a save that silently failed (bad connection, schema issue, …) tell the
     * player honestly instead of claiming success while nothing was actually persisted.
     */
    public void upsertPreset(UUID owner, String name, String settingsJson, java.util.function.Consumer<Boolean> callback) {
        writer.execute(() -> {
            boolean ok = true;
            try (Connection c = dataSource.getConnection();
                 PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO `" + presetsTable + "` (owner, name, settings, updated_at) "
                            + "VALUES (?,?,?,?) ON DUPLICATE KEY UPDATE settings=VALUES(settings), "
                            + "updated_at=VALUES(updated_at)")) {
                ps.setString(1, owner.toString());
                ps.setString(2, name);
                ps.setString(3, settingsJson);
                ps.setLong(4, System.currentTimeMillis());
                ps.executeUpdate();
                verbose("write ok: upsert preset " + name);
            } catch (Throwable t) {
                ok = false;
                logger.log(Level.WARNING, "ExclusiveArenas DB write failed (upsert preset " + name + "): " + t.getMessage());
            }
            if (callback != null) callback.accept(ok);
        });
    }

    public void deletePreset(UUID owner, String name) {
        deletePreset(owner, name, null);
    }

    /**
     * Deletes a preset and reports whether the DELETE actually landed — the callback runs on
     * the WRITER thread (mirroring {@link #upsertPreset(UUID, String, String,
     * java.util.function.Consumer)}), so callers must hop to the main thread themselves.
     */
    public void deletePreset(UUID owner, String name, java.util.function.Consumer<Boolean> callback) {
        writer.execute(() -> {
            boolean ok = true;
            try (Connection c = dataSource.getConnection();
                 PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM `" + presetsTable + "` WHERE owner=? AND name=?")) {
                ps.setString(1, owner.toString());
                ps.setString(2, name);
                ps.executeUpdate();
                verbose("write ok: delete preset " + name);
            } catch (Throwable t) {
                ok = false;
                logger.log(Level.WARNING, "ExclusiveArenas DB write failed (delete preset " + name + "): " + t.getMessage());
            }
            if (callback != null) callback.accept(ok);
        });
    }

    /** A player's saved presets, name → settings JSON, ordered by name. Call off-thread. */
    public java.util.LinkedHashMap<String, String> loadPresets(UUID owner) throws SQLException {
        java.util.LinkedHashMap<String, String> out = new java.util.LinkedHashMap<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT name, settings FROM `" + presetsTable + "` WHERE owner=? ORDER BY name")) {
            ps.setString(1, owner.toString());
            try (var rs = ps.executeQuery()) {
                while (rs.next()) out.put(rs.getString(1), rs.getString(2));
            }
        }
        return out;
    }

    public void deleteCommand(UUID id) {
        submit("delete command " + id, c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM `" + commandsTable + "` WHERE id=?")) {
                ps.setString(1, id.toString());
                ps.executeUpdate();
            }
        });
    }

    // ── Synchronous reads (call from an async thread) ──────────────────────────────

    public List<SessionRow> loadSessions() throws SQLException {
        List<SessionRow> out = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT session_id, owner, arena_name, policy, join_code, is_public, auto_summon, "
                             + "settings, server_id, created_at, updated_at FROM `" + sessionsTable + "`");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String owner = rs.getString("owner");
                out.add(new SessionRow(
                        UUID.fromString(rs.getString("session_id")),
                        owner == null ? null : UUID.fromString(owner),
                        rs.getString("arena_name"),
                        rs.getString("policy"),
                        rs.getString("join_code"),
                        rs.getBoolean("is_public"),
                        rs.getBoolean("auto_summon"),
                        rs.getString("settings"),
                        rs.getString("server_id"),
                        rs.getLong("created_at"),
                        rs.getLong("updated_at")));
            }
        }
        return out;
    }

    /**
     * Purges expired rows, then returns the still-valid tickets. Expiry is judged against the
     * DATABASE clock, matching how {@link #upsertTicket} writes it — never this server's.
     */
    public List<TicketRow> loadValidTickets() throws SQLException {
        List<TicketRow> out = new ArrayList<>();
        try (Connection c = dataSource.getConnection()) {
            try (PreparedStatement del = c.prepareStatement(
                    "DELETE FROM `" + ticketsTable
                            + "` WHERE expires_at < ROUND(UNIX_TIMESTAMP(NOW(3))*1000)")) {
                del.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT player, session_id, arena_name, expires_at FROM `" + ticketsTable
                            + "` WHERE expires_at >= ROUND(UNIX_TIMESTAMP(NOW(3))*1000)")) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new TicketRow(
                                UUID.fromString(rs.getString("player")),
                                UUID.fromString(rs.getString("session_id")),
                                rs.getString("arena_name"),
                                rs.getLong("expires_at")));
                    }
                }
            }
        }
        return out;
    }

    /** Loads every pending command network-wide; callers filter to arenas they actually host. */
    public List<CommandRow> loadCommands() throws SQLException {
        List<CommandRow> out = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT id, session_id, arena_name, type, payload, created_at FROM `" + commandsTable + "`");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(new CommandRow(
                        UUID.fromString(rs.getString("id")),
                        UUID.fromString(rs.getString("session_id")),
                        rs.getString("arena_name"),
                        rs.getString("type"),
                        rs.getString("payload"),
                        rs.getLong("created_at")));
            }
        }
        return out;
    }

    // ── Server liveness / crash cleanup ─────────────────────────────────────────────

    /**
     * Upserts this server's own heartbeat row, stamped with the DATABASE clock. Call regularly
     * (piggybacks on the session poll). Anchoring to the DB clock means liveness is one
     * server's DB-time write compared against DB-time in {@link #findDeadServers} — a backend
     * with a skewed wall clock can neither look dead nor keep looking alive.
     */
    public void heartbeat() {
        submit("heartbeat", c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO `" + serversTable + "` (server_id, last_heartbeat) "
                            + "VALUES (?,ROUND(UNIX_TIMESTAMP(NOW(3))*1000)) "
                            + "ON DUPLICATE KEY UPDATE last_heartbeat=VALUES(last_heartbeat)")) {
                ps.setString(1, settings.serverId());
                ps.executeUpdate();
            }
        });
    }

    /**
     * Server ids (excluding this one, as a safety guard against ever self-purging) whose last
     * heartbeat is more than {@code staleMillis} behind the DATABASE clock — the same clock
     * {@link #heartbeat()} writes with. Call from an async thread.
     */
    public List<String> findDeadServers(long staleMillis) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT server_id FROM `" + serversTable
                             + "` WHERE last_heartbeat < ROUND(UNIX_TIMESTAMP(NOW(3))*1000) - ? "
                             + "AND server_id <> ?")) {
            ps.setLong(1, staleMillis);
            ps.setString(2, settings.serverId());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getString(1));
            }
        }
        return out;
    }

    /**
     * Removes a dead server's vetted leftovers from the shared database. The caller (the
     * sweep in {@link SyncService}) decides WHICH of the dead server's sessions are truly
     * orphaned — a row's server_id is stamped by whichever server last WROTE it, so e.g. a
     * hub-edited match still running on a live backend must not be purged with the hub.
     *
     * Guards, so a concurrent revival can't be clobbered:
     * <ul>
     *   <li>each session row is only deleted while still attributed to the dead server (a row
     *       adopted by a live server in the meantime is left alone);</li>
     *   <li>tickets/commands for an arena are only dropped once NO session row references that
     *       arena any more — a new live session created for the same arena keeps its rows;</li>
     *   <li>the heartbeat row only goes once no sessions remain attributed to the dead server,
     *       so skipped/orphaned rows are retried on later sweeps.</li>
     * </ul>
     * Safe to call from multiple servers concurrently — every statement is a guarded DELETE,
     * so a second call finding nothing left just does nothing.
     */
    public void purgeDeadServer(String deadServerId,
                                List<UUID> sessionIds, List<String> arenaNames) {
        submit("purge dead server " + deadServerId, c -> {
            int removed = 0;
            if (!sessionIds.isEmpty()) {
                try (PreparedStatement ps = c.prepareStatement(
                        "DELETE FROM `" + sessionsTable + "` WHERE session_id=? AND server_id=?")) {
                    for (UUID sessionId : sessionIds) {
                        ps.setString(1, sessionId.toString());
                        ps.setString(2, deadServerId);
                        removed += ps.executeUpdate();
                    }
                }
            }
            if (!arenaNames.isEmpty()) {
                try (PreparedStatement ps = c.prepareStatement(
                        "DELETE FROM `" + ticketsTable + "` WHERE arena_name=? AND NOT EXISTS "
                                + "(SELECT 1 FROM `" + sessionsTable + "` WHERE arena_name=?)")) {
                    for (String arenaName : arenaNames) {
                        ps.setString(1, arenaName);
                        ps.setString(2, arenaName);
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "DELETE FROM `" + commandsTable + "` WHERE arena_name=? AND NOT EXISTS "
                                + "(SELECT 1 FROM `" + sessionsTable + "` WHERE arena_name=?)")) {
                    for (String arenaName : arenaNames) {
                        ps.setString(1, arenaName);
                        ps.setString(2, arenaName);
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM `" + serversTable + "` WHERE server_id=? AND NOT EXISTS "
                            + "(SELECT 1 FROM `" + sessionsTable + "` WHERE server_id=?)")) {
                ps.setString(1, deadServerId);
                ps.setString(2, deadServerId);
                ps.executeUpdate();
            }
            logger.info("ExclusiveArenas: purged dead server '" + deadServerId + "' from the "
                    + "shared database (" + removed + " orphaned session(s) removed).");
        });
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────────────

    public void shutdown() {
        writer.shutdown();
        try {
            if (!writer.awaitTermination(5, TimeUnit.SECONDS)) writer.shutdownNow();
        } catch (InterruptedException e) {
            writer.shutdownNow();
            Thread.currentThread().interrupt();
        }
        dataSource.close();
    }

    // ── Internals ────────────────────────────────────────────────────────────────

    private void submit(String what, SqlTask task) {
        submit(what, task, true);
    }

    /**
     * Queues a write on the single writer thread. A failed write gets one delayed retry —
     * re-submitted to the TAIL of the same queue, never run in place, so it can't jump ahead
     * of writes queued after it and break the upsert/delete sequencing for the same key. The
     * retry's own failure logs at WARNING and drops the write for good (a dropped
     * deleteSession would otherwise leave a ghost session network-wide with no trace).
     */
    private void submit(String what, SqlTask task, boolean retryable) {
        int depth = queueDepth.incrementAndGet();
        if (depth >= QUEUE_DEPTH_WARN_THRESHOLD) {
            if (!queueDepthWarned) {
                queueDepthWarned = true;
                logger.warning("ExclusiveArenas: DB write queue depth reached " + depth
                        + " — the database is falling behind (slow or unreachable?).");
            }
        } else if (queueDepthWarned && depth <= QUEUE_DEPTH_WARN_THRESHOLD / 2) {
            queueDepthWarned = false; // backlog drained — re-arm the warning
        }
        writer.execute(() -> {
            try (Connection c = dataSource.getConnection()) {
                task.run(c);
                verbose("write ok: " + what);
            } catch (Throwable t) {
                if (retryable) {
                    logger.info("ExclusiveArenas DB write failed (" + what + "): "
                            + t.getMessage() + " — retrying once in " + RETRY_DELAY_MS + "ms.");
                    retryLater(what, task);
                } else {
                    logger.log(Level.WARNING, "ExclusiveArenas DB write failed twice, dropping ("
                            + what + "): " + t.getMessage());
                }
            } finally {
                queueDepth.decrementAndGet();
            }
        });
    }

    private void retryLater(String what, SqlTask task) {
        java.util.concurrent.CompletableFuture
                .delayedExecutor(RETRY_DELAY_MS, TimeUnit.MILLISECONDS)
                .execute(() -> {
                    try {
                        submit(what + " [retry]", task, false);
                    } catch (java.util.concurrent.RejectedExecutionException e) {
                        // shut down while the retry was waiting — nothing left to write to
                    }
                });
    }

    private void verbose(String msg) {
        if (verbose) logger.info("[EA-DB] " + msg);
    }

    @FunctionalInterface
    private interface SqlTask {
        void run(Connection c) throws SQLException;
    }
}
