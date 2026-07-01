package com.slg.exclusivearenas;

import de.marcely.bedwars.api.arena.Arena;

import java.time.Instant;
import java.util.UUID;

public final class PrivateSession {

    private final UUID sessionId;
    private final UUID owner;
    private final String arenaName;
    private volatile JoinPolicy joinPolicy;
    private String joinCode;
    private boolean isPublic; // CODE policy only: when false, no one can join even with code
    private boolean autoSummon; // pull new party members into the arena automatically
    private final Instant createdAt;

    // Set when the host leaves during lobby; cleared when they return. Used for abandon timeout.
    private volatile Instant hostLeftAt = null;

    // The arena's min-players requirement before we relaxed it to 1 for this private match;
    // restored once the match ends. Null while untouched (local-only, never persisted).
    private volatile Integer originalMinPlayers = null;

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
    public void setJoinPolicy(JoinPolicy joinPolicy) { if (joinPolicy != null) this.joinPolicy = joinPolicy; }

    public String getJoinCode() { return joinCode; }
    public void setJoinCode(String joinCode) { this.joinCode = joinCode; }

    public boolean isPublic() { return isPublic; }
    public void setPublic(boolean isPublic) { this.isPublic = isPublic; }

    public boolean isAutoSummon() { return autoSummon; }
    public void setAutoSummon(boolean autoSummon) { this.autoSummon = autoSummon; }

    public Instant getCreatedAt() { return createdAt; }

    public Instant getHostLeftAt() { return hostLeftAt; }
    public void setHostLeftAt(Instant hostLeftAt) { this.hostLeftAt = hostLeftAt; }

    public Integer getOriginalMinPlayers() { return originalMinPlayers; }
    public void setOriginalMinPlayers(Integer originalMinPlayers) { this.originalMinPlayers = originalMinPlayers; }

    public boolean matchesArena(Arena arena) {
        return arena != null && arena.getName() != null
                && arena.getName().equalsIgnoreCase(arenaName);
    }
}
