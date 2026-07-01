package com.slg.exclusivearenas;

import java.util.List;
import java.util.Map;
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

    private long ttlSeconds = DEFAULT_TTL_SECONDS;
    private Database db; // null when running single-server

    public void setDatabase(Database db) {
        this.db = db;
    }

    public void setTtlSeconds(long ttlSeconds) {
        if (ttlSeconds > 0) this.ttlSeconds = ttlSeconds;
    }

    public void grant(UUID player, UUID sessionId, String arenaName) {
        long expiresAt = System.currentTimeMillis() + ttlSeconds * 1000L;
        tickets.put(player, new Ticket(sessionId, arenaName, expiresAt));
        if (db != null && arenaName != null) {
            db.upsertTicket(new Database.TicketRow(player, sessionId, arenaName, expiresAt));
        }
    }

    public boolean consumeIfValid(UUID player, UUID sessionId, String arenaName) {
        Ticket t = tickets.get(player);
        if (t == null) return false;
        if (System.currentTimeMillis() > t.expiresAt) {
            tickets.remove(player);
            return false;
        }
        if (!t.sessionId.equals(sessionId)) return false;
        if (arenaName == null || t.arenaName == null) return false;
        if (!t.arenaName.equalsIgnoreCase(arenaName)) return false;
        tickets.remove(player);
        if (db != null) db.deleteTicket(player);
        return true;
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
     */
    public void reconcile(List<Database.TicketRow> rows) {
        long now = System.currentTimeMillis();
        for (Database.TicketRow row : rows) {
            if (row.expiresAt() <= now) continue;
            Ticket existing = tickets.get(row.player());
            if (existing == null || existing.expiresAt < row.expiresAt()) {
                tickets.put(row.player(), new Ticket(row.sessionId(), row.arenaName(), row.expiresAt()));
            }
        }
        tickets.entrySet().removeIf(e -> e.getValue().expiresAt <= now);
    }
}
