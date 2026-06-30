package com.slg.exclusivearenas;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Short-lived tokens that allow a specific player to pass gating for one join attempt.
 * Tickets are consumed on use and expire after TTL_SECONDS regardless.
 */
public final class JoinTicketService {

    private static final long TTL_SECONDS = 20;

    private static final class Ticket {
        final UUID sessionId;
        final String arenaName;
        final Instant expiresAt;

        Ticket(UUID sessionId, String arenaName) {
            this.sessionId = sessionId;
            this.arenaName = arenaName;
            this.expiresAt = Instant.now().plusSeconds(TTL_SECONDS);
        }
    }

    private final Map<UUID, Ticket> tickets = new ConcurrentHashMap<>();

    public void grant(UUID player, UUID sessionId, String arenaName) {
        tickets.put(player, new Ticket(sessionId, arenaName));
    }

    public boolean consumeIfValid(UUID player, UUID sessionId, String arenaName) {
        Ticket t = tickets.get(player);
        if (t == null) return false;
        if (Instant.now().isAfter(t.expiresAt)) {
            tickets.remove(player);
            return false;
        }
        if (!t.sessionId.equals(sessionId)) return false;
        if (arenaName == null || t.arenaName == null) return false;
        if (!t.arenaName.equalsIgnoreCase(arenaName)) return false;
        tickets.remove(player);
        return true;
    }

    /** Returns true if the player has any non-expired ticket (used for network pre-join check). */
    public boolean hasValidTicket(UUID player, UUID sessionId, String arenaName) {
        Ticket t = tickets.get(player);
        if (t == null) return false;
        if (Instant.now().isAfter(t.expiresAt)) {
            tickets.remove(player);
            return false;
        }
        return t.sessionId.equals(sessionId) && arenaName != null
                && t.arenaName != null && t.arenaName.equalsIgnoreCase(arenaName);
    }
}
