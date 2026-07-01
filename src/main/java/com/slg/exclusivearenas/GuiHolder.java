package com.slg.exclusivearenas;

import de.marcely.bedwars.api.arena.Team;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Carries the context of an open ExclusiveArenas menu on the inventory itself, so the
 * click handler can identify the menu (and which session/page it is showing) reliably
 * via {@code inventory.getHolder()} — no brittle title parsing, no colour-code matching.
 */
public final class GuiHolder implements InventoryHolder {

    public enum Type {
        MAIN, ARENA_LIST, ADMIN_LIST, BUILDER, ARENA_SELECT, CONTROLS, ARENA_CONFIG,
        TEAM_SELECT, TEAM_PLAYERS, QUICK_ACTIONS, HELP
    }

    private final Type type;
    private Inventory inventory;

    private int page;
    private UUID sessionId;                       // CONTROLS: the session being managed
    private boolean adminView;                    // CONTROLS/lists opened by an admin
    private int teamFilter;                       // ARENA_SELECT: 0 = any
    private int playersPerTeamFilter;              // ARENA_SELECT: 0 = any
    private Team targetTeam;                       // TEAM_PLAYERS: which team is being filled
    private Set<UUID> selectedPlayers = Collections.emptySet(); // TEAM_PLAYERS: staged multi-select
    private final Map<Integer, UUID> slotToSession = new HashMap<>(); // list menus: slot -> session
    private final Map<Integer, Team> slotToTeam = new HashMap<>();    // TEAM_SELECT: slot -> team

    public GuiHolder(Type type) {
        this.type = type;
    }

    public Type type() { return type; }

    public int page() { return page; }
    public GuiHolder page(int page) { this.page = page; return this; }

    public UUID sessionId() { return sessionId; }
    public GuiHolder sessionId(UUID sessionId) { this.sessionId = sessionId; return this; }

    public boolean adminView() { return adminView; }
    public GuiHolder adminView(boolean adminView) { this.adminView = adminView; return this; }

    public int teamFilter() { return teamFilter; }
    public GuiHolder teamFilter(int teamFilter) { this.teamFilter = teamFilter; return this; }

    public int playersPerTeamFilter() { return playersPerTeamFilter; }
    public GuiHolder playersPerTeamFilter(int playersPerTeamFilter) { this.playersPerTeamFilter = playersPerTeamFilter; return this; }

    public Team targetTeam() { return targetTeam; }
    public GuiHolder targetTeam(Team targetTeam) { this.targetTeam = targetTeam; return this; }

    public Set<UUID> selectedPlayers() { return selectedPlayers; }
    public GuiHolder selectedPlayers(Set<UUID> selectedPlayers) {
        this.selectedPlayers = selectedPlayers == null ? Collections.emptySet() : new HashSet<>(selectedPlayers);
        return this;
    }

    public void mapSlot(int slot, UUID sessionId) { slotToSession.put(slot, sessionId); }
    public UUID sessionAt(int slot) { return slotToSession.get(slot); }

    public void mapTeamSlot(int slot, Team team) { slotToTeam.put(slot, team); }
    public Team teamAt(int slot) { return slotToTeam.get(slot); }

    void setInventory(Inventory inventory) { this.inventory = inventory; }

    @Override
    public Inventory getInventory() { return inventory; }
}
