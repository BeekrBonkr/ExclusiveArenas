package com.slg.exclusivearenas;

import de.marcely.bedwars.api.arena.Arena;

import java.time.Instant;
import java.util.UUID;

public final class PrivateSession {

    private final UUID sessionId;
    private final UUID owner;
    private final String arenaName;
    private final JoinPolicy joinPolicy;
    private String joinCode;
    private boolean isPublic; // CODE policy only: when false, no one can join even with code
    private final Instant createdAt;

    private volatile boolean countdownStarted = false;

    // Set when the host leaves during lobby; cleared when they return. Used for abandon timeout.
    private volatile Instant hostLeftAt = null;

    public PrivateSession(UUID sessionId, UUID owner, String arenaName,
                          JoinPolicy joinPolicy, String joinCode, boolean isPublic) {
        this.sessionId = sessionId;
        this.owner = owner;
        this.arenaName = arenaName;
        this.joinPolicy = joinPolicy;
        this.joinCode = joinCode;
        this.isPublic = isPublic;
        this.createdAt = Instant.now();
    }

    public UUID getSessionId() { return sessionId; }
    public UUID getOwner() { return owner; }
    public String getArenaName() { return arenaName; }
    public JoinPolicy getJoinPolicy() { return joinPolicy; }

    public String getJoinCode() { return joinCode; }
    public void setJoinCode(String joinCode) { this.joinCode = joinCode; }

    public boolean isPublic() { return isPublic; }
    public void setPublic(boolean isPublic) { this.isPublic = isPublic; }

    public Instant getCreatedAt() { return createdAt; }

    public boolean isCountdownStarted() { return countdownStarted; }
    public void setCountdownStarted(boolean countdownStarted) { this.countdownStarted = countdownStarted; }

    public Instant getHostLeftAt() { return hostLeftAt; }
    public void setHostLeftAt(Instant hostLeftAt) { this.hostLeftAt = hostLeftAt; }

    public boolean matchesArena(Arena arena) {
        return arena != null && arena.getName() != null
                && arena.getName().equalsIgnoreCase(arenaName);
    }
}
