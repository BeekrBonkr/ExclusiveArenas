package com.slg.exclusivearenas;

import java.util.UUID;

/**
 * In-progress configuration a player is building before committing to creation mode.
 * Held in DraftService until the session is created or the player logs out.
 */
public final class DraftPrivateMatch {

    private final UUID owner;
    private String arenaName;
    private JoinPolicy joinPolicy = JoinPolicy.PARTY;
    private String joinCode;
    private boolean isPublic = true; // only relevant for CODE policy

    public DraftPrivateMatch(UUID owner) {
        this.owner = owner;
    }

    public UUID getOwner() { return owner; }

    public String getArenaName() { return arenaName; }
    public void setArenaName(String arenaName) { this.arenaName = arenaName; }

    public JoinPolicy getJoinPolicy() { return joinPolicy; }
    public void setJoinPolicy(JoinPolicy joinPolicy) {
        if (joinPolicy != null) this.joinPolicy = joinPolicy;
    }

    public String getJoinCode() { return joinCode; }
    public void setJoinCode(String joinCode) { this.joinCode = joinCode; }

    public boolean isPublic() { return isPublic; }
    public void setPublic(boolean isPublic) { this.isPublic = isPublic; }

    public boolean isReadyToCreate() {
        if (arenaName == null || arenaName.isBlank()) return false;
        if (joinPolicy == JoinPolicy.CODE && (joinCode == null || joinCode.isBlank())) return false;
        return true;
    }
}
