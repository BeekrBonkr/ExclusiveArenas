package com.slg.exclusivearenas;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Short-lived tokens that allow a specific player to pass gating for one join attempt.
 * Tickets are consumed on use and expire after the configured TTL regardless.
 *
 * On a network the tickets are mirrored through the shared database so that whichever
 * backend hosts the arena sees the grant. The in-memory map remains the read path used
 * by the (synchronous) join-gate listener; the DB is write-through + polled.
 */
public final class JoinTicketService {

    private static final long DEFAULT_TTL_SECONDS = 30;

    private static final class Ticket {
        final UUID sessionId;
        final String arenaName;
        final long expiresAt; // epoch millis

        Ticket(UUID sessionId, String arenaName, long expiresAt) {
            this.sessionId = sessionId;
            this.arenaName = arenaName;
            this.expiresAt = expiresAt;
        }
    }

    private final Map<UUID, Ticket> tickets = new ConcurrentHashMap<>();

    // Players whose ticket was consumed locally but whose async DB DELETE hasn't been confirmed
    // by a poll yet. Without this, a poll whose SELECT ran before that delete commits would
    // re-insert the consumed ticket into the cache, letting it be spent a second time — the
    // same race PrivateSessionService guards with its pendingDelete set.
    private final Set<UUID> pendingDelete = ConcurrentHashMap.newKeySet();

    private long ttlSeconds = DEFAULT_TTL_SECONDS;
    private Database db; // null when running single-server

    public void setDatabase(Database db) {
        this.db = db;
    }

    public void setTtlSeconds(long ttlSeconds) {
        if (ttlSeconds > 0) this.ttlSeconds = ttlSeconds;
    }

    public void grant(UUID player, UUID sessionId, String arenaName) {
        // The local copy uses this server's clock (self-consistent for local reads); the DB row
        // is anchored to the DATABASE clock instead, so the server that hosts the arena judges
        // expiry against the one clock every server shares rather than inheriting our skew.
        long expiresAt = System.currentTimeMillis() + ttlSeconds * 1000L;
        tickets.put(player, new Ticket(sessionId, arenaName, expiresAt));
        pendingDelete.remove(player); // a fresh grant supersedes any not-yet-confirmed consume
        if (db != null && arenaName != null) {
            db.upsertTicket(new Database.TicketRow(player, sessionId, arenaName, expiresAt),
                    ttlSeconds * 1000L);
        }
    }

    /**
     * Atomically checks and consumes a ticket: the check and the removal happen inside one
     * {@code computeIfPresent} call so two concurrent callers (e.g. a local join racing a
     * network join for the same player) can never both observe the same ticket as valid.
     */
    public boolean consumeIfValid(UUID player, UUID sessionId, String arenaName) {
        if (arenaName == null) return false;
        boolean[] consumed = {false};
        tickets.computeIfPresent(player, (id, t) -> {
            boolean expired = System.currentTimeMillis() > t.expiresAt;
            boolean matches = !expired && t.sessionId.equals(sessionId)
                    && t.arenaName != null && t.arenaName.equalsIgnoreCase(arenaName);
            if (matches) consumed[0] = true;
            return (expired || matches) ? null : t; // drop on expiry or a successful consume, else keep
        });
        if (consumed[0] && db != null) {
            pendingDelete.add(player);
            db.deleteTicket(player);
        }
        return consumed[0];
    }

    /** Returns true if the player has any non-expired ticket (used for network pre-join check). */
    public boolean hasValidTicket(UUID player, UUID sessionId, String arenaName) {
        Ticket t = tickets.get(player);
        if (t == null) return false;
        if (System.currentTimeMillis() > t.expiresAt) {
            tickets.remove(player);
            return false;
        }
        return t.sessionId.equals(sessionId) && arenaName != null
                && t.arenaName != null && t.arenaName.equalsIgnoreCase(arenaName);
    }

    /**
     * Merges valid tickets from the DB into the cache and drops locally-expired ones.
     * This is a merge, not a wipe: a locally-granted ticket whose write-through has not
     * flushed yet is kept, and an incoming row only replaces a local one if it is newer.
     * A player pending a local delete is never re-added, so a poll that raced ahead of that
     * delete's commit can't resurrect a ticket that was already spent.
     */
    public void reconcile(List<Database.TicketRow> rows) {
        long now = System.currentTimeMillis();
        Set<UUID> present = new HashSet<>();
        for (Database.TicketRow row : rows) present.add(row.player());
        pendingDelete.removeIf(player -> !present.contains(player)); // confirmed gone from the DB

        for (Database.TicketRow row : rows) {
            if (pendingDelete.contains(row.player())) continue; // consumed locally; DELETE in flight
            // Row expiry is DB-clock time; comparing it to our clock assumes NTP-level skew —
            // safe here, since the SQL read already filtered against the DB clock.
            if (row.expiresAt() <= now) continue;
            Ticket existing = tickets.get(row.player());
            if (existing == null || existing.expiresAt < row.expiresAt()) {
                tickets.put(row.player(), new Ticket(row.sessionId(), row.arenaName(), row.expiresAt()));
            }
        }
        tickets.entrySet().removeIf(e -> e.getValue().expiresAt <= now);
    }
}
