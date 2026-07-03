package com.slg.exclusivearenas;

import java.util.UUID;

/**
 * In-progress configuration a player is building before committing to creation mode.
 * Held in DraftService until the session is created or the player logs out.
 */
public final class DraftPrivateMatch implements SettingsHolder {

    private final UUID owner;
    private String arenaName;
    private JoinPolicy joinPolicy = JoinPolicy.PARTY;
    private String joinCode;
    private boolean isPublic = true; // only relevant for CODE policy
    private boolean autoSummon = false; // auto-pull new party members into the arena

    // Event timeline / shop / team-size customizations chosen in the builder's Arena Settings
    // before the match even exists — copied onto the real PrivateSession once it's created.
    private SessionSettings settings = new SessionSettings();

    // Cached at builder-open time: true when the player is a member (not leader) of someone
    // else's party, in which case the whole creation menu is locked.
    private boolean partyBlocked = false;

    public DraftPrivateMatch(UUID owner) {
        this.owner = owner;
    }

    public UUID getOwner() { return owner; }

    public String getArenaName() { return arenaName; }
    public void setArenaName(String arenaName) { this.arenaName = arenaName; }

    @Override
    public SessionSettings getSettings() { return settings; }
    public void setSettings(SessionSettings settings) {
        if (settings != null) this.settings = settings;
    }

    public JoinPolicy getJoinPolicy() { return joinPolicy; }
    public void setJoinPolicy(JoinPolicy joinPolicy) {
        if (joinPolicy != null) this.joinPolicy = joinPolicy;
    }

    public String getJoinCode() { return joinCode; }
    public void setJoinCode(String joinCode) { this.joinCode = joinCode; }

    public boolean isPublic() { return isPublic; }
    public void setPublic(boolean isPublic) { this.isPublic = isPublic; }

    public boolean isAutoSummon() { return autoSummon; }
    public void setAutoSummon(boolean autoSummon) { this.autoSummon = autoSummon; }

    public boolean isPartyBlocked() { return partyBlocked; }
    public void setPartyBlocked(boolean partyBlocked) { this.partyBlocked = partyBlocked; }

    public boolean isReadyToCreate() {
        if (arenaName == null || arenaName.isBlank()) return false;
        if (joinPolicy == JoinPolicy.CODE && (joinCode == null || joinCode.isBlank())) return false;
        return true;
    }
}
