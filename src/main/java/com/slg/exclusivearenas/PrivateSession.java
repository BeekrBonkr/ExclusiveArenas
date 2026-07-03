package com.slg.exclusivearenas;

import de.marcely.bedwars.api.arena.Arena;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PrivateSession {

    private final UUID sessionId;
    private final UUID owner;
    private final String arenaName;
    private final JoinPolicy joinPolicy;
    private String joinCode;
    private boolean isPublic; // CODE policy only: when false, no one can join even with code
    private boolean autoSummon; // pull new party members into the arena automatically
    private final Instant createdAt;

    // Set when the host leaves during lobby; cleared when they return. Used for abandon timeout.
    private volatile Instant hostLeftAt = null;

    // Set the moment the arena's lobby has zero active players; cleared the moment it doesn't.
    // Used by SessionCleanupTask's inactivity warning/close (local-only, never persisted).
    private volatile Instant inactiveSince = null;
    private volatile boolean inactivityWarned = false;

    // The arena's min-players requirement before we relaxed it to 1 for this private match;
    // restored once the match ends. Null while untouched (local-only, never persisted).
    private volatile Integer originalMinPlayers = null;

    // The arena's own players-per-team value before any host override was applied; restored
    // once the match ends. Null while untouched (local-only, never persisted).
    private volatile Integer originalPlayersPerTeam = null;

    // Timestamp of each player's last authorised join, kept only long enough to recognise
    // MBedwars' "kicked right back out immediately after joining" bug (local-only, never persisted).
    private final Map<UUID, Instant> recentJoins = new ConcurrentHashMap<>();

    // Host customizations (event timeline, shop overrides); persisted as JSON network-wide.
    private volatile SessionSettings settings = new SessionSettings();

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

    public boolean isAutoSummon() { return autoSummon; }
    public void setAutoSummon(boolean autoSummon) { this.autoSummon = autoSummon; }

    public SessionSettings getSettings() { return settings; }
    public void setSettings(SessionSettings settings) {
        if (settings != null) this.settings = settings;
    }

    public Instant getCreatedAt() { return createdAt; }

    public Instant getHostLeftAt() { return hostLeftAt; }
    public void setHostLeftAt(Instant hostLeftAt) { this.hostLeftAt = hostLeftAt; }

    public Instant getInactiveSince() { return inactiveSince; }
    public void setInactiveSince(Instant inactiveSince) { this.inactiveSince = inactiveSince; }

    public boolean isInactivityWarned() { return inactivityWarned; }
    public void setInactivityWarned(boolean inactivityWarned) { this.inactivityWarned = inactivityWarned; }

    public Integer getOriginalMinPlayers() { return originalMinPlayers; }
    public void setOriginalMinPlayers(Integer originalMinPlayers) { this.originalMinPlayers = originalMinPlayers; }

    public Integer getOriginalPlayersPerTeam() { return originalPlayersPerTeam; }
    public void setOriginalPlayersPerTeam(Integer originalPlayersPerTeam) { this.originalPlayersPerTeam = originalPlayersPerTeam; }

    /** Records that this player was just authorised into the arena (owner, ticket, or party). */
    public void markRecentJoin(UUID playerId) {
        recentJoins.put(playerId, Instant.now());
    }

    /** True if {@link #markRecentJoin} fired for this player within the given window. */
    public boolean wasRecentJoin(UUID playerId, Duration within) {
        Instant t = recentJoins.get(playerId);
        return t != null && !Duration.between(t, Instant.now()).minus(within).isPositive();
    }

    public boolean matchesArena(Arena arena) {
        return arena != null && arena.getName() != null
                && arena.getName().equalsIgnoreCase(arenaName);
    }
}
