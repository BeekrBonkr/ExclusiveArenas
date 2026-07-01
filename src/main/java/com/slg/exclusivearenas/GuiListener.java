package com.slg.exclusivearenas;

import de.marcely.bedwars.api.BedwarsAPI;
import de.marcely.bedwars.api.arena.Arena;
import de.marcely.bedwars.api.arena.Team;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.InventoryHolder;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public final class GuiListener implements Listener {

    private final ExclusiveArenasPlugin plugin;
    private final DraftService drafts;
    private final PrivateSessionService sessions;
    private final GuiManager gui;

    public GuiListener(ExclusiveArenasPlugin plugin,
                       DraftService drafts,
                       PrivateSessionService sessions,
                       GuiManager gui) {
        this.plugin = plugin;
        this.drafts = drafts;
        this.sessions = sessions;
        this.gui = gui;
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;

        InventoryHolder holder = e.getView().getTopInventory().getHolder();
        if (!(holder instanceof GuiHolder gh)) return;

        // Any interaction with one of our menus is cancelled so items can never be taken.
        e.setCancelled(true);

        // Only act on clicks inside the menu itself (ignore the player's own inventory).
        if (e.getClickedInventory() == null || e.getClickedInventory() != e.getView().getTopInventory()) return;

        int slot = e.getSlot();
        ItemStack clicked = e.getCurrentItem();

        switch (gh.type()) {
            case MAIN        -> handleMain(p, slot);
            case ARENA_LIST  -> handleList(p, gh, slot, false);
            case ADMIN_LIST  -> handleList(p, gh, slot, true);
            case BUILDER     -> handleBuilder(p, slot, e.isShiftClick());
            case ARENA_SELECT-> handleArenaSelect(p, gh, slot, clicked);
            case CONTROLS    -> handleControls(p, gh, slot);
            case ARENA_CONFIG-> handleArenaConfig(p, gh, slot);
            case QUICK_ACTIONS-> handleQuickActions(p, gh, slot);
            case TEAM_SELECT -> handleTeamSelect(p, gh, slot);
            case TEAM_PLAYERS-> handleTeamPlayers(p, gh, slot, e.isShiftClick());
            case HELP        -> { if (slot == 22) gui.openMainMenu(p); }
        }
    }

    // ── Main menu ──────────────────────────────────────────────────────────────────

    private void handleMain(Player p, int slot) {
        switch (slot) {
            case 10 -> gui.openArenaList(p, 0);
            case 12 -> gui.openHelp(p);
            case 16 -> { if (p.hasPermission(GuiManager.ADMIN_PERM)) gui.openAdminList(p, 0); }
            case 22 -> p.closeInventory();
        }
    }

    // ── Session lists (player + admin) ───────────────────────────────────────────────

    private void handleList(Player p, GuiHolder gh, int slot, boolean admin) {
        int page = gh.page();
        switch (slot) {
            case 45 -> reopenList(p, admin, page - 1);
            case 53 -> reopenList(p, admin, page + 1);
            case 48 -> gui.openMainMenu(p);
            case 50 -> reopenList(p, admin, page); // refresh
            case 49 -> {
                if (admin) { p.closeInventory(); return; }        // "Close" on admin list
                plugin.openBuilderMenu(p);                         // "Create New Arena" on player list
            }
            default -> {
                UUID sessionId = gh.sessionAt(slot);
                if (sessionId == null) return;
                PrivateSession session = sessions.getById(sessionId);
                if (session == null) { p.sendMessage(color("&cThat match no longer exists.")); reopenList(p, admin, page); return; }
                gui.openControls(p, session, admin);
            }
        }
    }

    private void reopenList(Player p, boolean admin, int page) {
        if (admin) gui.openAdminList(p, page); else gui.openArenaList(p, page);
    }

    // ── Builder ──────────────────────────────────────────────────────────────────────

    private void handleBuilder(Player p, int slot, boolean shiftClick) {
        DraftPrivateMatch d = drafts.getOrCreate(p.getUniqueId());
        if (d.isPartyBlocked()) {
            if (slot == 18) { gui.openArenaList(p, 0); return; }
            if (slot == 26) { p.closeInventory(); return; }
            p.sendMessage(color("&cLeave your party before you can host your own private match."));
            return;
        }

        switch (slot) {
            case 10 -> gui.openArenaSelect(p, 0);
            case 13 -> {
                if (d.getJoinPolicy() == JoinPolicy.PARTY) {
                    d.setAutoSummon(!d.isAutoSummon());
                    gui.openBuilder(p);
                }
            }
            case 14 -> {
                if (d.getJoinPolicy() == JoinPolicy.CODE) {
                    d.setPublic(!d.isPublic());
                    gui.openBuilder(p);
                }
            }
            case 16 -> {
                p.closeInventory();
                plugin.createAndJoin(p, d, !shiftClick); // shift-click: create without joining
            }
            case 18 -> gui.openArenaList(p, 0);
            case 26 -> p.closeInventory();
        }
    }

    // ── Arena selector ────────────────────────────────────────────────────────────────

    private void handleArenaSelect(Player p, GuiHolder gh, int slot, ItemStack clicked) {
        int page = gh.page();
        int teamFilter = gh.teamFilter();
        int ppFilter = gh.playersPerTeamFilter();

        if (slot == 45) { gui.openArenaSelect(p, page - 1, teamFilter, ppFilter); return; }
        if (slot == 53) { gui.openArenaSelect(p, page + 1, teamFilter, ppFilter); return; }
        if (slot == 46) { gui.openArenaSelect(p, 0, gui.cycleTeamFilter(teamFilter), ppFilter); return; }
        if (slot == 47) { gui.openArenaSelect(p, 0, teamFilter, gui.cyclePlayersPerTeamFilter(ppFilter)); return; }
        if (slot == 49) { gui.openBuilder(p); return; }

        if (clicked == null || clicked.getType() == Material.AIR) return;
        if (clicked.getItemMeta() == null || clicked.getItemMeta().getDisplayName().isEmpty()) return;

        String name = ChatColor.stripColor(clicked.getItemMeta().getDisplayName());
        if (name == null || name.isBlank()) return;

        if (sessions.isArenaReserved(name)) {
            p.sendMessage(color("&cThat arena is already reserved."));
            return;
        }

        DraftPrivateMatch d = drafts.getOrCreate(p.getUniqueId());
        d.setArenaName(name);
        if (d.getJoinPolicy() == JoinPolicy.CODE && (d.getJoinCode() == null || d.getJoinCode().isBlank())) {
            d.setJoinCode(sessions.generateCode());
        }
        gui.openBuilder(p);
    }

    // ── Match controls ─────────────────────────────────────────────────────────────────

    private void handleControls(Player p, GuiHolder gh, int slot) {
        PrivateSession session = sessions.getById(gh.sessionId());
        if (session == null) {
            p.sendMessage(color("&cThat match no longer exists."));
            reopenList(p, gh.adminView(), 0);
            return;
        }

        boolean admin = p.hasPermission(GuiManager.ADMIN_PERM) || p.hasPermission(GuiManager.BYPASS_PERM);
        boolean owner = p.getUniqueId().equals(session.getOwner());
        if (!owner && !admin) {
            p.sendMessage(color("&cOnly the host can manage this match."));
            return;
        }

        Arena arena = BedwarsAPI.getGameAPI().getArenaByExactName(session.getArenaName());
        boolean onThisServer = arena != null && arena.exists();

        switch (slot) {
            case 13 -> gui.openArenaConfig(p, session, gh.adminView()); // Arena settings (planned)
            case 34 -> gui.openQuickActions(p, session, gh.adminView()); // Quick actions (planned)
            case 15 -> { // Manage teams (lobby only, requires the arena to be local)
                if (!onThisServer) { p.sendMessage(color("&cTeam management requires being on the arena's server.")); return; }
                if (!arena.getStatus().isLobby()) { p.sendMessage(color("&cTeams can only be managed while the arena is in its lobby.")); return; }
                gui.openTeamSelect(p, session, gh.adminView());
            }
            case 19 -> { // Toggle join policy (lobby only) — works even when the arena is remote
                if (!ArenaNames.isLobbyStatus(session.getArenaName())) {
                    p.sendMessage(color("&cYou can only change the join policy while the arena is in its lobby."));
                    return;
                }
                JoinPolicy next = session.getJoinPolicy() == JoinPolicy.PARTY ? JoinPolicy.CODE : JoinPolicy.PARTY;
                sessions.setSessionJoinPolicy(session, next);
                p.sendMessage(color(next == JoinPolicy.CODE
                        ? "&aJoin policy switched to &fJoin Code&a. Code: &f" + session.getJoinCode()
                        : "&aJoin policy switched to &fParty Only&a."));
                gui.openControls(p, session, gh.adminView());
            }
            case 20 -> { // Start the match right now — works remotely via the command relay
                plugin.requestStartMatch(p, session);
                gui.openControls(p, session, gh.adminView());
            }
            case 22 -> { // Public toggle (CODE) or Summon party (PARTY) — both work remotely
                if (session.getJoinPolicy() == JoinPolicy.CODE) {
                    sessions.setSessionPublic(session, !session.isPublic());
                    p.sendMessage(color(session.isPublic()
                            ? "&aMatch opened — anyone with the code may join."
                            : "&cMatch locked — joining is disabled."));
                } else {
                    plugin.summonPartyToArena(p, session);
                }
                gui.openControls(p, session, gh.adminView());
            }
            case 24 -> { // Regenerate code
                if (session.getJoinPolicy() == JoinPolicy.CODE) {
                    plugin.regenerateJoinCode(session);
                    gui.openControls(p, session, gh.adminView());
                }
            }
            case 29 -> { // Toggle auto-summon (Party policy only)
                if (session.getJoinPolicy() != JoinPolicy.PARTY) return;
                sessions.setSessionAutoSummon(session, !session.isAutoSummon());
                p.sendMessage(color(session.isAutoSummon()
                        ? "&aAuto-summon enabled — your party will be kept synced with the match."
                        : "&7Auto-summon disabled."));
                gui.openControls(p, session, gh.adminView());
            }
            case 30 -> { // Go to arena
                p.closeInventory();
                plugin.getTicketService().grant(p.getUniqueId(), session.getSessionId(), session.getArenaName());
                plugin.sendPlayerToArena(p, session.getArenaName());
            }
            case 32 -> { // End match — works remotely via the command relay
                plugin.requestEndMatch(p, session);
                reopenList(p, gh.adminView(), gh.page());
            }
            case 36 -> reopenList(p, gh.adminView(), 0);
            case 44 -> p.closeInventory();
        }
    }

    // ── Arena settings (planned feature — stubs only) ────────────────────────────────

    private void handleArenaConfig(Player p, GuiHolder gh, int slot) {
        if (slot != 22) return; // only the Back button does anything right now

        PrivateSession session = sessions.getById(gh.sessionId());
        if (session == null) {
            p.sendMessage(color("&cThat match no longer exists."));
            reopenList(p, gh.adminView(), 0);
            return;
        }
        gui.openControls(p, session, gh.adminView());
    }

    private void handleQuickActions(Player p, GuiHolder gh, int slot) {
        if (slot != 22) return; // only the Back button does anything right now

        PrivateSession session = sessions.getById(gh.sessionId());
        if (session == null) {
            p.sendMessage(color("&cThat match no longer exists."));
            reopenList(p, gh.adminView(), 0);
            return;
        }
        gui.openControls(p, session, gh.adminView());
    }

    // ── Team management ───────────────────────────────────────────────────────────────

    private void handleTeamSelect(Player p, GuiHolder gh, int slot) {
        PrivateSession session = sessions.getById(gh.sessionId());
        if (session == null) {
            p.sendMessage(color("&cThat match no longer exists."));
            reopenList(p, gh.adminView(), 0);
            return;
        }

        if (slot == 49) { gui.openControls(p, session, gh.adminView()); return; }

        Team team = gh.teamAt(slot);
        if (team == null) return;
        gui.openTeamPlayers(p, session, gh.adminView(), team, new HashSet<>(), false);
    }

    private void handleTeamPlayers(Player p, GuiHolder gh, int slot, boolean shiftClick) {
        PrivateSession session = sessions.getById(gh.sessionId());
        if (session == null) {
            p.sendMessage(color("&cThat match no longer exists."));
            reopenList(p, gh.adminView(), 0);
            return;
        }

        Team team = gh.targetTeam();
        if (team == null || slot == 49) { gui.openTeamSelect(p, session, gh.adminView()); return; }

        if (slot == 48) { // Confirm the staged batch move
            Set<UUID> selected = gh.selectedPlayers();
            if (!selected.isEmpty()) plugin.moveArenaPlayersToTeam(p, session, team, selected);
            gui.openTeamSelect(p, session, gh.adminView());
            return;
        }

        UUID targetId = gh.sessionAt(slot);
        if (targetId == null) return;

        Set<UUID> selected = new HashSet<>(gh.selectedPlayers());
        if (selected.contains(targetId)) {
            // Already staged — any click (shift or not) un-selects it.
            selected.remove(targetId);
            gui.openTeamPlayers(p, session, gh.adminView(), team, selected, false);
            return;
        }

        if (shiftClick || !selected.isEmpty()) {
            // Staging into a multi-select batch rather than moving immediately.
            Arena arena = BedwarsAPI.getGameAPI().getArenaByExactName(session.getArenaName());
            int remaining = arena == null ? 0
                    : Math.max(0, arena.getPlayersPerTeam() - arena.getPlayersInTeam(team).size());
            if (selected.size() >= remaining) {
                gui.openTeamPlayers(p, session, gh.adminView(), team, selected, true); // won't fit — warn
                return;
            }
            selected.add(targetId);
            gui.openTeamPlayers(p, session, gh.adminView(), team, selected, false);
            return;
        }

        // Plain click with nothing else staged — move this one player immediately.
        plugin.moveArenaPlayersToTeam(p, session, team, Set.of(targetId));
        gui.openTeamSelect(p, session, gh.adminView());
    }

    private static String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s == null ? "" : s);
    }
}
