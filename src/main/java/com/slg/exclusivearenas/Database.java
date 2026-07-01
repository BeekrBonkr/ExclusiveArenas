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
 *       daemon writer thread — callers never block and failures are only logged.</li>
 *   <li>Reads ({@link #loadSessions()}, {@link #loadValidTickets()}) are synchronous and
 *       must be invoked from an async scheduler thread, never the main thread.</li>
 * </ul>
 */
public final class Database {

    /** Connection + layout settings, resolved from config. */
    public record Settings(String host, int port, String database, String user, String password,
                           String tablePrefix, boolean useSsl, String serverId) {}

    /** A row of {@code <prefix>sessions}. */
    public record SessionRow(UUID sessionId, UUID owner, String arenaName, String policy,
                             String joinCode, boolean isPublic, boolean autoSummon,
                             String serverId, long createdAt) {}

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
    private final HikariDataSource dataSource;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ExclusiveArenas-DB-Writer");
        t.setDaemon(true);
        return t;
    });

    public Database(Logger logger, Settings settings, boolean verbose) {
        this.logger = logger;
        this.settings = settings;
        this.verbose = verbose;
        this.sessionsTable = settings.tablePrefix() + "sessions";
        this.ticketsTable = settings.tablePrefix() + "tickets";
        this.commandsTable = settings.tablePrefix() + "commands";

        HikariConfig cfg = new HikariConfig();
        cfg.setPoolName("ExclusiveArenas-Hikari");
        // Set the driver class EXPLICITLY. In a plugin, DriverManager's ServiceLoader
        // discovery runs in the server's classloader, not ours, so it never finds the
        // shaded driver's META-INF/services entry (→ "Failed to get driver instance").
        // Naming the class makes Hikari load it directly via our own classloader.
        // The class literal is rewritten by shadow to the relocated driver, so getName()
        // returns the relocated FQCN at runtime.
        cfg.setDriverClassName(org.mariadb.jdbc.Driver.class.getName());
        cfg.setJdbcUrl("jdbc:mariadb://" + settings.host() + ":" + settings.port() + "/"
                + settings.database() + "?useSsl=" + settings.useSsl());
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
                            + "server_id VARCHAR(64) NOT NULL,"
                            + "created_at BIGINT NOT NULL"
                            + ")")) {
                ps.executeUpdate();
            }
            // Bring older tables up to date (columns added after first deploy). MariaDB
            // supports IF NOT EXISTS so this is a safe no-op when the column already exists.
            addColumnIfMissing(c, sessionsTable, "is_public", "TINYINT(1) NOT NULL DEFAULT 0");
            addColumnIfMissing(c, sessionsTable, "auto_summon", "TINYINT(1) NOT NULL DEFAULT 0");
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
        }
        logger.info("ExclusiveArenas connected to database; tables '" + sessionsTable
                + "' / '" + ticketsTable + "' / '" + commandsTable + "' ready.");
    }

    private void addColumnIfMissing(Connection c, String table, String column, String definition) {
        try (PreparedStatement ps = c.prepareStatement(
                "ALTER TABLE `" + table + "` ADD COLUMN IF NOT EXISTS " + column + " " + definition)) {
            ps.executeUpdate();
        } catch (SQLException e) {
            verbose("ALTER TABLE add " + column + " skipped: " + e.getMessage());
        }
    }

    // ── Async write-through (fire-and-forget) ──────────────────────────────────────

    public void upsertSession(SessionRow row) {
        submit("upsert session " + row.arenaName(), c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO `" + sessionsTable + "` "
                            + "(session_id, owner, arena_name, policy, join_code, is_public, auto_summon, "
                            + "server_id, created_at) VALUES (?,?,?,?,?,?,?,?,?) "
                            + "ON DUPLICATE KEY UPDATE owner=VALUES(owner), arena_name=VALUES(arena_name), "
                            + "policy=VALUES(policy), join_code=VALUES(join_code), is_public=VALUES(is_public), "
                            + "auto_summon=VALUES(auto_summon), "
                            + "server_id=VALUES(server_id)")) {
                ps.setString(1, row.sessionId().toString());
                ps.setString(2, row.owner() == null ? null : row.owner().toString());
                ps.setString(3, row.arenaName());
                ps.setString(4, row.policy());
                ps.setString(5, row.joinCode());
                ps.setBoolean(6, row.isPublic());
                ps.setBoolean(7, row.autoSummon());
                ps.setString(8, settings.serverId()); // this server owns/stamps the write
                ps.setLong(9, row.createdAt());
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

    public void upsertTicket(TicketRow row) {
        submit("upsert ticket " + row.player(), c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO `" + ticketsTable + "` (player, session_id, arena_name, expires_at) "
                            + "VALUES (?,?,?,?) ON DUPLICATE KEY UPDATE session_id=VALUES(session_id), "
                            + "arena_name=VALUES(arena_name), expires_at=VALUES(expires_at)")) {
                ps.setString(1, row.player().toString());
                ps.setString(2, row.sessionId().toString());
                ps.setString(3, row.arenaName());
                ps.setLong(4, row.expiresAt());
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
                             + "server_id, created_at FROM `" + sessionsTable + "`");
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
                        rs.getString("server_id"),
                        rs.getLong("created_at")));
            }
        }
        return out;
    }

    /** Purges expired rows, then returns the still-valid tickets. */
    public List<TicketRow> loadValidTickets() throws SQLException {
        long now = System.currentTimeMillis();
        List<TicketRow> out = new ArrayList<>();
        try (Connection c = dataSource.getConnection()) {
            try (PreparedStatement del = c.prepareStatement(
                    "DELETE FROM `" + ticketsTable + "` WHERE expires_at < ?")) {
                del.setLong(1, now);
                del.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT player, session_id, arena_name, expires_at FROM `" + ticketsTable
                            + "` WHERE expires_at >= ?")) {
                ps.setLong(1, now);
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
        writer.execute(() -> {
            try (Connection c = dataSource.getConnection()) {
                task.run(c);
                verbose("write ok: " + what);
            } catch (Throwable t) {
                logger.log(Level.WARNING, "ExclusiveArenas DB write failed (" + what + "): " + t.getMessage());
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
