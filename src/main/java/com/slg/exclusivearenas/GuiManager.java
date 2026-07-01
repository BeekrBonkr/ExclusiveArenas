package com.slg.exclusivearenas;

import de.marcely.bedwars.api.BedwarsAPI;
import de.marcely.bedwars.api.arena.Arena;
import de.marcely.bedwars.api.arena.ArenaStatus;
import de.marcely.bedwars.api.arena.Team;
import de.marcely.bedwars.api.remote.RemoteAPI;
import de.marcely.bedwars.api.remote.RemoteArena;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Builds every ExclusiveArenas menu.
 *
 * Design conventions (kept consistent so the plugin reads as one cohesive UI):
 *   • Every menu is framed with a subtle grey-glass border; content sits in the interior.
 *   • Titles use a bold accent colour; item names a bright accent, values in white,
 *     hints in dark-grey prefixed with "▶".
 *   • Context (which menu / session / page) travels on the {@link GuiHolder}, never the title.
 */
public final class GuiManager {

    public static final String ADMIN_PERM  = "exclusivearenas.admin";
    public static final String BYPASS_PERM  = "exclusivearenas.bypass";

    private static final Material BORDER = Material.GRAY_STAINED_GLASS_PANE;
    private static final Material ACCENT = Material.CYAN_STAINED_GLASS_PANE;

    // Slot rings used for paginated lists (interior of a 54-slot menu).
    private static final int[] LIST_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43
    };

    private final ExclusiveArenasPlugin plugin;
    private final DraftService drafts;
    private final PrivateSessionService sessions;

    public GuiManager(ExclusiveArenasPlugin plugin, DraftService drafts, PrivateSessionService sessions) {
        this.plugin = plugin;
        this.drafts = drafts;
        this.sessions = sessions;
    }

    // ── Main menu ────────────────────────────────────────────────────────────────

    public void openMainMenu(Player p) {
        GuiHolder holder = new GuiHolder(GuiHolder.Type.MAIN);
        Inventory inv = create(holder, 27, "&1&lExclusiveArenas");
        frame(inv);

        int hosting = sessions.countByOwner(p.getUniqueId());
        int limit = plugin.getArenaLimit(p);

        inv.setItem(10, ItemUtil.button(Material.BEACON, "&b&lArena Management",
                "&7View, create and control your",
                "&7private matches.",
                "&8",
                "&7Hosting: &f" + hosting + "&7/&f" + limitLabel(limit),
                "&8",
                "&8▶ Click to open"));

        inv.setItem(12, ItemUtil.button(Material.KNOWLEDGE_BOOK, "&e&lHelp",
                "&7Learn how to use ExclusiveArenas.",
                "&8",
                "&8▶ Click to view"));

        inv.setItem(14, ItemUtil.stub("Tournaments", "Organise bracket-based tournaments."));

        if (p.hasPermission(ADMIN_PERM)) {
            inv.setItem(16, ItemUtil.button(Material.COMMAND_BLOCK, "&c&lAdmin Panel",
                    "&7View and control &fevery&7 active",
                    "&7private match on this server.",
                    "&8",
                    "&8▶ Click to open"));
        } else {
            inv.setItem(16, ItemUtil.pane(BORDER));
        }

        inv.setItem(22, ItemUtil.button(Material.BARRIER, "&c&lClose"));
        p.openInventory(inv);
    }

    // ── Player's arena list ────────────────────────────────────────────────────────

    public void openArenaList(Player p, int page) {
        List<PrivateSession> owned = sessions.getSessionsByOwner(p.getUniqueId());
        GuiHolder holder = new GuiHolder(GuiHolder.Type.ARENA_LIST).adminView(false);
        renderSessionList(p, holder, 54, "&1&lYour Private Matches", owned, page, true);
    }

    // ── Admin list (all arenas) ────────────────────────────────────────────────────

    public void openAdminList(Player p, int page) {
        List<PrivateSession> all = new ArrayList<>(sessions.getAllSessions());
        all.sort(Comparator.comparing(PrivateSession::getArenaName, String.CASE_INSENSITIVE_ORDER));
        GuiHolder holder = new GuiHolder(GuiHolder.Type.ADMIN_LIST).adminView(true);
        renderSessionList(p, holder, 54, "&4&lAdmin · All Matches", all, page, false);
    }

    /** Shared renderer for both the player and admin session lists. */
    private void renderSessionList(Player p, GuiHolder holder, int size, String title,
                                   List<PrivateSession> list, int page, boolean allowCreate) {
        int perPage = LIST_SLOTS.length;
        int pages = Math.max(1, (int) Math.ceil(list.size() / (double) perPage));
        int pg = Math.max(0, Math.min(page, pages - 1));
        holder.page(pg);

        Inventory inv = create(holder, size, title + " &8(" + (pg + 1) + "/" + pages + ")");
        frame(inv);

        int start = pg * perPage;
        int end = Math.min(list.size(), start + perPage);
        for (int i = start; i < end; i++) {
            PrivateSession session = list.get(i);
            int slot = LIST_SLOTS[i - start];
            inv.setItem(slot, sessionItem(session, holder.adminView()));
            holder.mapSlot(slot, session.getSessionId());
        }

        if (list.isEmpty()) {
            inv.setItem(22, ItemUtil.button(Material.LIGHT_GRAY_STAINED_GLASS_PANE,
                    "&7No active matches",
                    holder.adminView() ? "&8Nobody is hosting right now." : "&8You are not hosting anything yet.",
                    allowCreate ? "&8Use &fCreate New Arena&8 below." : null));
        }

        // Navigation row
        if (pg > 0) inv.setItem(45, navButton("&e◀ Previous Page", pg));
        if (pg < pages - 1) inv.setItem(53, navButton("&eNext Page ▶", pg + 2));

        inv.setItem(48, ItemUtil.button(Material.ARROW, "&7◀ Back", "&8Return to the main menu."));
        if (allowCreate) {
            inv.setItem(49, ItemUtil.button(Material.EMERALD, "&a&lCreate New Arena",
                    "&7Set up a new private match.",
                    "&8",
                    "&7Hosting: &f" + sessions.countByOwner(p.getUniqueId())
                            + "&7/&f" + limitLabel(plugin.getArenaLimit(p)),
                    "&8",
                    "&8▶ Click to build"));
        } else {
            inv.setItem(49, ItemUtil.button(Material.BARRIER, "&c&lClose"));
        }
        inv.setItem(50, ItemUtil.button(Material.SPYGLASS, "&b&lRefresh", "&8Update live status."));

        p.openInventory(inv);
    }

    // ── Match controls (for a specific session) ─────────────────────────────────────

    public void openControls(Player p, PrivateSession session, boolean adminView) {
        GuiHolder holder = new GuiHolder(GuiHolder.Type.CONTROLS)
                .sessionId(session.getSessionId()).adminView(adminView);
        Inventory inv = create(holder, 45, "&1&lMatch Controls &8· " + session.getArenaName());
        frame(inv);

        boolean codePolicy = session.getJoinPolicy() == JoinPolicy.CODE;
        Arena local = BedwarsAPI.getGameAPI().getArenaByExactName(session.getArenaName());
        boolean onThisServer = local != null && local.exists();
        boolean lobbyNow = ArenaNames.isLobbyStatus(session.getArenaName());

        // Live status card
        inv.setItem(4, ItemUtil.button(Material.FILLED_MAP, "&b&l" + session.getArenaName(),
                ArenaStatusView.detail(session)));

        // There is no pre-game timer any more — MBedwars' own automatic lobby countdown is
        // always suppressed for a private match, so the host starts it directly, whenever ready.
        // Works even when the arena is on another server — the request is relayed there.
        inv.setItem(20, ItemUtil.button(
                lobbyNow ? Material.LIME_CONCRETE : Material.CLOCK,
                lobbyNow ? "&a&lStart Match" : "&7&lStart Match",
                lobbyNow ? "&7Begin the match right now." : "&7The match has already begun.",
                onThisServer ? "" : "&8(controlling remotely)",
                lobbyNow ? "&8▶ Click" : null));

        // Join policy switch (Party <-> Code) — only while the arena is still in its lobby.
        // Also works remotely, since switching policy never needs to touch the live arena.
        inv.setItem(19, ItemUtil.button(Material.NAME_TAG,
                "&e&lJoin Policy: " + (codePolicy ? "&dJoin Code" : "&bParty Only"),
                codePolicy ? "&7Players join with &f/ea join <code>" : "&7Only your party members can join.",
                "&8",
                lobbyNow ? "&8▶ Click to switch" : "&cCan only change while the arena is in its lobby."));

        // Access control
        if (codePolicy) {
            inv.setItem(22, ItemUtil.button(
                    session.isPublic() ? Material.LIME_DYE : Material.RED_DYE,
                    session.isPublic() ? "&a&lPublic" : "&c&lLocked",
                    session.isPublic() ? "&7Anyone with the code can join." : "&7Joining is disabled.",
                    "&7Code: &f" + (session.getJoinCode() == null ? "—" : session.getJoinCode()),
                    "&8▶ Click to toggle"));
            inv.setItem(24, ItemUtil.button(Material.TRIPWIRE_HOOK, "&e&lRegenerate Code",
                    "&7Invalidate the current code and",
                    "&7generate a fresh one.",
                    "&8▶ Click"));
        } else {
            inv.setItem(22, ItemUtil.button(Material.ENDER_PEARL, "&e&lSummon Party",
                    "&7Pull your online party members",
                    "&7into this lobby.",
                    "&8▶ Click"));
        }

        // Manage teams — needs the arena's live roster, so this one does require being local.
        boolean teamsAvailable = onThisServer && lobbyNow;
        inv.setItem(15, ItemUtil.button(Material.WHITE_BANNER,
                teamsAvailable ? "&e&lManage Teams" : "&7&lManage Teams",
                "&7Move players between teams",
                "&7while the arena is in its lobby.",
                "&8",
                teamsAvailable ? "&8▶ Click to open"
                        : (onThisServer ? "&cOnly available while in the lobby."
                                : "&cRequires being on the arena's server.")));

        // Auto-summon toggle (Party policy only)
        if (session.getJoinPolicy() == JoinPolicy.PARTY) {
            inv.setItem(29, ItemUtil.button(
                    session.isAutoSummon() ? Material.LEAD : Material.STRING,
                    session.isAutoSummon() ? "&d&lAuto-Summon: On" : "&7&lAuto-Summon: Off",
                    "&7Automatically keep your party synced",
                    "&7with this match — pulling in members who",
                    "&7aren't here and removing anyone who",
                    "&7left your party.",
                    "&8▶ Click to toggle"));
        } else {
            inv.setItem(29, ItemUtil.pane(BORDER));
        }

        // Teleport to the arena
        inv.setItem(30, ItemUtil.button(Material.COMPASS, "&b&lGo to Arena",
                "&7Teleport / connect to this match.",
                "&8▶ Click"));

        // Arena settings (event timing / shop / cosmetics) — planned, not yet functional
        inv.setItem(13, ItemUtil.button(Material.CRAFTING_TABLE, "&e&lArena Settings",
                "&7Configure event timing, shop",
                "&7restrictions, and cosmetics.",
                "&8",
                "&8▶ Click to open"));

        // End match
        inv.setItem(32, ItemUtil.button(Material.TNT, "&c&lEnd Match",
                "&7Close this private match and",
                "&7release the arena.",
                "&8▶ Click"));

        // Quick actions (planned, not yet functional)
        inv.setItem(34, ItemUtil.button(Material.REDSTONE_TORCH, "&e&lQuick Actions",
                "&7Handy one-click shortcuts",
                "&7for managing this match.",
                "&8",
                "&8▶ Click to open"));

        inv.setItem(36, ItemUtil.button(Material.ARROW, "&7◀ Back",
                adminView ? "&8Return to the admin list." : "&8Return to your matches."));
        inv.setItem(44, ItemUtil.button(Material.BARRIER, "&c&lClose"));

        p.openInventory(inv);
    }

    // ── Team management (lobby only, arena must be local) ────────────────────────────

    /** Shows every enabled team, with a roster preview in its lore. */
    public void openTeamSelect(Player p, PrivateSession session, boolean adminView) {
        GuiHolder holder = new GuiHolder(GuiHolder.Type.TEAM_SELECT)
                .sessionId(session.getSessionId()).adminView(adminView);
        Inventory inv = create(holder, 54, "&1&lManage Teams &8· " + session.getArenaName());
        frame(inv);

        Arena arena = BedwarsAPI.getGameAPI().getArenaByExactName(session.getArenaName());
        if (arena == null || !arena.exists() || !arena.getStatus().isLobby()) {
            inv.setItem(31, ItemUtil.button(Material.BARRIER, "&cUnavailable",
                    (arena == null || !arena.exists())
                            ? "&7This arena isn't loaded on this server."
                            : "&7Teams can only be managed while the arena is in its lobby."));
            inv.setItem(49, ItemUtil.button(Material.ARROW, "&7◀ Back", "&8Return to match controls."));
            p.openInventory(inv);
            return;
        }

        List<Team> teams = new ArrayList<>(arena.getEnabledTeams());
        teams.sort(Comparator.comparing(Team::name));

        for (int i = 0; i < teams.size() && i < LIST_SLOTS.length; i++) {
            Team team = teams.get(i);
            int slot = LIST_SLOTS[i];
            List<Player> members = arena.getPlayersInTeam(team);
            int cap = arena.getPlayersPerTeam();

            List<String> lore = new ArrayList<>();
            lore.add("&7Players: &f" + members.size() + "&7/&f" + cap);
            lore.add("&8");
            if (members.isEmpty()) {
                lore.add("&8No one on this team yet.");
            } else {
                for (Player m : members) lore.add("&f● &7" + m.getName());
            }
            lore.add("&8");
            lore.add(members.size() >= cap ? "&cTeam is full." : "&8▶ Click to move players here");

            inv.setItem(slot, ItemUtil.icon(team.newItemInstance(), Material.WHITE_WOOL, team.getDisplayName(), lore));
            holder.mapTeamSlot(slot, team);
        }

        if (teams.isEmpty()) {
            inv.setItem(31, ItemUtil.button(Material.LIGHT_GRAY_STAINED_GLASS_PANE,
                    "&7No teams enabled", "&8This arena has no enabled teams."));
        }

        inv.setItem(49, ItemUtil.button(Material.ARROW, "&7◀ Back", "&8Return to match controls."));
        p.openInventory(inv);
    }

    /**
     * Lets the host fill {@code team} with players currently in the arena. A plain click with
     * nothing else staged moves that one player immediately; shift-click (or a plain click while
     * something is already staged) stages multiple players — shown with an enchant glint — for a
     * batch move via the confirm button. Clicking an already-staged head un-stages it.
     */
    public void openTeamPlayers(Player p, PrivateSession session, boolean adminView, Team team,
                                Set<UUID> selected, boolean warnOverflow) {
        GuiHolder holder = new GuiHolder(GuiHolder.Type.TEAM_PLAYERS)
                .sessionId(session.getSessionId()).adminView(adminView)
                .targetTeam(team).selectedPlayers(selected);

        Arena arena = BedwarsAPI.getGameAPI().getArenaByExactName(session.getArenaName());
        String teamName = ChatColor.stripColor(team.getDisplayName());
        String title = warnOverflow
                ? "&c&lUn-select a player first!"
                : "&1&lMove to " + teamName + (arena != null
                        ? " &8(" + arena.getPlayersInTeam(team).size() + "/" + arena.getPlayersPerTeam() + ")" : "");
        Inventory inv = create(holder, 54, title);
        frame(inv);

        if (arena == null || !arena.exists()) {
            inv.setItem(31, ItemUtil.button(Material.BARRIER, "&cArena unavailable",
                    "&7This arena isn't loaded on this server."));
            inv.setItem(49, ItemUtil.button(Material.ARROW, "&7◀ Back", "&8Return to team selection."));
            p.openInventory(inv);
            return;
        }

        int cap = arena.getPlayersPerTeam();
        int remaining = Math.max(0, cap - arena.getPlayersInTeam(team).size());

        List<Player> candidates = new ArrayList<>(arena.getPlayers());
        candidates.removeAll(arena.getPlayersInTeam(team)); // already on this team — nothing to do
        candidates.sort(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER));

        for (int i = 0; i < candidates.size() && i < LIST_SLOTS.length; i++) {
            Player target = candidates.get(i);
            int slot = LIST_SLOTS[i];
            boolean isSelected = selected.contains(target.getUniqueId());
            Team currentTeam = arena.getPlayerTeam(target);

            List<String> lore = new ArrayList<>();
            lore.add("&7Current team: " + (currentTeam != null ? currentTeam.getDisplayName() : "&8None"));
            lore.add("&8");
            if (isSelected) {
                lore.add("&a✔ Selected");
                lore.add("&8▶ Click to un-select");
            } else {
                lore.add("&8▶ Click to move here");
                lore.add("&8▶ Shift-click to select multiple");
            }

            inv.setItem(slot, ItemUtil.head(Bukkit.getOfflinePlayer(target.getUniqueId()),
                    (isSelected ? "&a&l" : "&f&l") + target.getName(), lore, isSelected));
            holder.mapSlot(slot, target.getUniqueId());
        }

        if (candidates.isEmpty()) {
            inv.setItem(31, ItemUtil.button(Material.LIGHT_GRAY_STAINED_GLASS_PANE,
                    "&7No players to move", "&8Everyone eligible is already on this team."));
        }

        inv.setItem(45, ItemUtil.button(Material.LIGHT_GRAY_STAINED_GLASS_PANE,
                "&7Remaining slots: &f" + remaining, "&8" + cap + " players max per team."));

        if (!selected.isEmpty()) {
            inv.setItem(48, ItemUtil.button(Material.EMERALD_BLOCK,
                    "&a&lMove " + selected.size() + " Selected Player(s)",
                    "&7Move everyone selected onto",
                    "&7" + team.getDisplayName() + "&7.",
                    "&8▶ Click to confirm"));
        }

        inv.setItem(49, ItemUtil.button(Material.ARROW, "&7◀ Back", "&8Return to team selection."));
        p.openInventory(inv);
    }

    // ── Arena settings (planned feature — stubs only) ────────────────────────────────

    /**
     * Placeholder settings menu for a planned feature: per-match control over generator
     * upgrade timing (Diamond II/III, Emerald II/III, ...), disabling specific shop items, and
     * toggling cosmetics. Nothing here is wired up to real behaviour yet — this just reserves
     * the navigation and layout so the feature can be built out incrementally later.
     */
    public void openArenaConfig(Player p, PrivateSession session, boolean adminView) {
        GuiHolder holder = new GuiHolder(GuiHolder.Type.ARENA_CONFIG)
                .sessionId(session.getSessionId()).adminView(adminView);
        Inventory inv = create(holder, 27, "&1&lArena Settings &8· " + session.getArenaName());
        frame(inv);

        inv.setItem(11, ItemUtil.stub("Event Timing",
                "Control when generator upgrades (Diamond II/III, Emerald II/III) happen."));
        inv.setItem(13, ItemUtil.stub("Shop Restrictions",
                "Disable specific items in the in-game shop."));
        inv.setItem(15, ItemUtil.stub("Cosmetics",
                "Enable or disable cosmetics for this match."));

        inv.setItem(22, ItemUtil.button(Material.ARROW, "&7◀ Back", "&8Return to match controls."));
        p.openInventory(inv);
    }

    // ── Quick actions (planned feature — stubs only) ─────────────────────────────────

    /**
     * Placeholder menu for one-click match shortcuts. Nothing here is wired up yet — just
     * reserving the navigation/layout so real actions can be dropped in later.
     */
    public void openQuickActions(Player p, PrivateSession session, boolean adminView) {
        GuiHolder holder = new GuiHolder(GuiHolder.Type.QUICK_ACTIONS)
                .sessionId(session.getSessionId()).adminView(adminView);
        Inventory inv = create(holder, 27, "&1&lQuick Actions &8· " + session.getArenaName());
        frame(inv);

        inv.setItem(10, ItemUtil.stub("Kick Non-Party Players", "Remove anyone not in your party from the match."));
        inv.setItem(11, ItemUtil.stub("Reset Arena", "Reset the arena without ending the match."));
        inv.setItem(12, ItemUtil.stub("Broadcast Message", "Send an announcement to everyone in the arena."));
        inv.setItem(14, ItemUtil.stub("Lock Teams", "Prevent further team changes for this match."));
        inv.setItem(15, ItemUtil.stub("Toggle PvP", "Enable or disable player damage in the lobby."));
        inv.setItem(16, ItemUtil.stub("Refill Chests", "Reset all chests without regenerating the arena."));

        inv.setItem(22, ItemUtil.button(Material.ARROW, "&7◀ Back", "&8Return to match controls."));
        p.openInventory(inv);
    }

    // ── Builder ───────────────────────────────────────────────────────────────────

    public void openBuilder(Player p) {
        DraftPrivateMatch d = drafts.getOrCreate(p.getUniqueId());
        GuiHolder holder = new GuiHolder(GuiHolder.Type.BUILDER);
        Inventory inv = create(holder, 27, "&1&lNew Private Match");
        frame(inv);

        String mapLabel = d.getArenaName() == null ? "&cNot selected" : "&a" + d.getArenaName();
        inv.setItem(10, ItemUtil.button(Material.FILLED_MAP, "&b&lSelect Map",
                "&7Current: " + mapLabel,
                "&8",
                "&8▶ Click to choose"));

        boolean isParty = d.getJoinPolicy() == JoinPolicy.PARTY;
        inv.setItem(12, ItemUtil.button(Material.NAME_TAG, "&e&lJoin Policy",
                "&7Mode: " + (isParty ? "&bParty Only" : "&dJoin Code"),
                "&8",
                isParty ? "&7Only your party members can join." : "&7Players join with &f/ea join <code>",
                "&8▶ Click to switch"));

        if (isParty) {
            inv.setItem(13, ItemUtil.button(
                    d.isAutoSummon() ? Material.LEAD : Material.STRING,
                    d.isAutoSummon() ? "&d&lAuto-Summon: On" : "&7&lAuto-Summon: Off",
                    "&7Automatically keep your party synced",
                    "&7with the arena as members join or leave.",
                    "&8▶ Click to toggle"));
        } else {
            inv.setItem(13, ItemUtil.pane(ACCENT));
        }

        if (d.getJoinPolicy() == JoinPolicy.CODE) {
            inv.setItem(14, ItemUtil.button(
                    d.isPublic() ? Material.LIME_DYE : Material.RED_DYE,
                    d.isPublic() ? "&a&lPublic" : "&c&lPrivate",
                    d.isPublic() ? "&7Anyone with the code can join." : "&7Joining is disabled until you open it.",
                    "&7Code: &f" + (d.getJoinCode() == null ? "—" : d.getJoinCode()),
                    "&8▶ Click to toggle"));
        } else {
            inv.setItem(14, ItemUtil.pane(ACCENT));
        }

        boolean ready = d.isReadyToCreate();
        inv.setItem(16, ItemUtil.button(
                ready ? Material.LIME_CONCRETE : Material.GRAY_CONCRETE,
                ready ? "&a&lCreate & Join" : "&7&lCreate & Join",
                ready ? "&7Create your match on &f" + d.getArenaName() : "&8Select a map first.",
                ready ? "&8▶ Click to create" : null));

        inv.setItem(18, ItemUtil.button(Material.ARROW, "&7◀ Back", "&8Return to your matches."));
        inv.setItem(26, ItemUtil.button(Material.BARRIER, "&c&lClose"));
        p.openInventory(inv);
    }

    // ── Arena selector ─────────────────────────────────────────────────────────────

    public void openArenaSelect(Player p, int page) {
        openArenaSelect(p, page, 0, 0);
    }

    /**
     * @param teamFilter         0 = any, else only arenas with exactly this many enabled teams
     * @param playersPerTeamFilter 0 = any, else only arenas with exactly this many players/team
     */
    public void openArenaSelect(Player p, int page, int teamFilter, int playersPerTeamFilter) {
        List<ArenaEntry> all = collectArenas();
        List<Integer> teamOptions = distinctSorted(all, ArenaEntry::teamCount);
        List<Integer> ppOptions = distinctSorted(all, ArenaEntry::playersPerTeam);

        List<ArenaEntry> entries = all.stream()
                .filter(e -> teamFilter <= 0 || e.teamCount() == teamFilter)
                .filter(e -> playersPerTeamFilter <= 0 || e.playersPerTeam() == playersPerTeamFilter)
                .toList();

        int perPage = LIST_SLOTS.length;
        int pages = Math.max(1, (int) Math.ceil(entries.size() / (double) perPage));
        int pg = Math.max(0, Math.min(page, pages - 1));

        GuiHolder holder = new GuiHolder(GuiHolder.Type.ARENA_SELECT).page(pg)
                .teamFilter(teamFilter).playersPerTeamFilter(playersPerTeamFilter);
        Inventory inv = create(holder, 54, "&1&lSelect Map &8(" + (pg + 1) + "/" + pages + ")");
        frame(inv);

        int start = pg * perPage;
        int end = Math.min(entries.size(), start + perPage);
        for (int i = start; i < end; i++) {
            ArenaEntry entry = entries.get(i);
            int slot = LIST_SLOTS[i - start];
            boolean reserved = sessions.isArenaReserved(entry.name());
            Material fallback = reserved ? Material.GRAY_CONCRETE : Material.GRASS_BLOCK;
            List<String> lore = new ArrayList<>();
            lore.add(entry.remote() ? "&8Location: Remote" : "&8Location: Local");
            lore.add("&7Teams: &f" + entry.teamCount() + " &8• &7Per team: &f" + entry.playersPerTeam());
            lore.add("&8");
            lore.add(reserved ? "&cAlready reserved." : "&8▶ Click to select");
            inv.setItem(slot, ItemUtil.icon(entry.icon(), fallback, (reserved ? "&7" : "&a") + entry.name(), lore));
        }

        if (entries.isEmpty()) {
            inv.setItem(22, ItemUtil.button(Material.LIGHT_GRAY_STAINED_GLASS_PANE,
                    "&7No arenas match", all.isEmpty() ? "&8There are no BedWars arenas to reserve."
                            : "&8Try clearing a filter below."));
        }

        if (pg > 0) inv.setItem(45, navButton("&e◀ Previous Page", pg));
        if (pg < pages - 1) inv.setItem(53, navButton("&eNext Page ▶", pg + 2));

        inv.setItem(46, filterButton(Material.IRON_SWORD, "Teams", teamFilter, teamOptions));
        inv.setItem(47, filterButton(Material.LEATHER_CHESTPLATE, "Players/Team", playersPerTeamFilter, ppOptions));

        inv.setItem(49, ItemUtil.button(Material.ARROW, "&7◀ Back", "&8Return to the builder."));
        p.openInventory(inv);
    }

    /** Advances the "enabled teams" filter to the next value discovered among real arenas. */
    public int cycleTeamFilter(int current) {
        return cycleFilter(current, ArenaEntry::teamCount);
    }

    /** Advances the "players per team" filter to the next value discovered among real arenas. */
    public int cyclePlayersPerTeamFilter(int current) {
        return cycleFilter(current, ArenaEntry::playersPerTeam);
    }

    private int cycleFilter(int current, java.util.function.ToIntFunction<ArenaEntry> selector) {
        List<Integer> options = distinctSorted(collectArenas(), selector);
        if (options.isEmpty()) return 0;
        int idx = options.indexOf(current);
        int next = idx + 1;
        return next >= options.size() ? 0 : options.get(next); // wraps back to "Any"
    }

    private List<Integer> distinctSorted(List<ArenaEntry> list, java.util.function.ToIntFunction<ArenaEntry> selector) {
        return list.stream().mapToInt(selector).filter(v -> v > 0).distinct().sorted().boxed().toList();
    }

    private ItemStack filterButton(Material mat, String label, int current, List<Integer> options) {
        return ItemUtil.button(mat, "&e&l" + label + " Filter",
                "&7Current: " + (current <= 0 ? "&fAny" : "&f" + current),
                "&8",
                options.isEmpty() ? "&8No arena data available" : "&8▶ Click to cycle");
    }

    // ── Help ────────────────────────────────────────────────────────────────────────

    public void openHelp(Player p) {
        GuiHolder holder = new GuiHolder(GuiHolder.Type.HELP);
        Inventory inv = create(holder, 27, "&1&lHelp");
        frame(inv);

        inv.setItem(10, ItemUtil.button(Material.BEACON, "&b/ea",
                "&7Open the main menu."));
        inv.setItem(11, ItemUtil.button(Material.EMERALD, "&b/ea create",
                "&7Jump straight to the match builder."));
        inv.setItem(12, ItemUtil.button(Material.CHEST, "&b/ea list",
                "&7Manage the matches you host."));
        inv.setItem(13, ItemUtil.button(Material.NAME_TAG, "&b/ea join &f<code>",
                "&7Join a private match by its code."));
        inv.setItem(14, ItemUtil.button(Material.LIME_CONCRETE, "&b/ea start",
                "&7Start the match right now (host)."));
        inv.setItem(15, ItemUtil.button(Material.TNT, "&b/ea end",
                "&7End your private match (host)."));
        inv.setItem(16, ItemUtil.button(Material.ENDER_PEARL, "&b/ea summon",
                "&7Summon your party (Party policy)."));

        inv.setItem(22, ItemUtil.button(Material.ARROW, "&7◀ Back", "&8Return to the main menu."));
        p.openInventory(inv);
    }

    // ── Item / layout helpers ────────────────────────────────────────────────────────

    private ItemStack sessionItem(PrivateSession session, boolean adminView) {
        List<String> lore = new ArrayList<>();
        if (adminView && session.getOwner() != null) {
            lore.add("&7Host: &f" + ownerName(session));
        }
        lore.addAll(ArenaStatusView.lore(session));
        lore.add("&8");
        lore.add("&8▶ Click to manage");

        Material mat = statusMaterial(session);
        String name = "&f&l" + session.getArenaName();
        if (adminView && session.getOwner() != null) {
            return ItemUtil.head(Bukkit.getOfflinePlayer(session.getOwner()), name, lore);
        }
        return ItemUtil.button(mat, name, lore);
    }

    private Material statusMaterial(PrivateSession session) {
        Arena local = BedwarsAPI.getGameAPI().getArenaByExactName(session.getArenaName());
        ArenaStatus status = local != null && local.exists() ? local.getStatus() : null;
        if (status == null) return Material.FILLED_MAP;
        return switch (status) {
            case RUNNING -> Material.LIME_CONCRETE;
            case LOBBY -> Material.LIGHT_BLUE_CONCRETE;
            case END_LOBBY, RESETTING -> Material.YELLOW_CONCRETE;
            case STOPPED -> Material.RED_CONCRETE;
        };
    }

    private ItemStack navButton(String name, int targetPageOneBased) {
        return ItemUtil.button(Material.ARROW, name, "&8Go to page " + targetPageOneBased + ".");
    }

    private String ownerName(PrivateSession session) {
        var off = Bukkit.getOfflinePlayer(session.getOwner());
        return off.getName() != null ? off.getName() : "?";
    }

    private String limitLabel(int limit) {
        return limit >= Integer.MAX_VALUE ? "∞" : String.valueOf(limit);
    }

    private Inventory create(GuiHolder holder, int size, String title) {
        Inventory inv = Bukkit.createInventory(holder, size, ItemUtil.color(title));
        holder.setInventory(inv);
        return inv;
    }

    /** Frames the border slots of any row-multiple inventory with grey glass. */
    private void frame(Inventory inv) {
        int size = inv.getSize();
        int rows = size / 9;
        ItemStack pane = ItemUtil.pane(BORDER);
        for (int i = 0; i < size; i++) {
            int r = i / 9, c = i % 9;
            if (r == 0 || r == rows - 1 || c == 0 || c == 8) inv.setItem(i, pane);
        }
    }

    private List<ArenaEntry> collectArenas() {
        List<ArenaEntry> list = new ArrayList<>();
        try {
            RemoteAPI remote = RemoteAPI.get();
            if (remote != null && remote.isAPIActive()) {
                for (RemoteArena ra : remote.getArenas()) {
                    if (ra != null && ra.exists()) {
                        list.add(new ArenaEntry(ra.getName(), !ra.isLocal(),
                                ra.getEnabledTeams().size(), ra.getPlayersPerTeam(), ra.getIcon()));
                    }
                }
                list.sort(Comparator.comparing(ArenaEntry::name, String.CASE_INSENSITIVE_ORDER));
                return list;
            }
        } catch (Throwable ignored) {
            // RemoteAPI not available; fall through to local
        }
        for (Arena a : BedwarsAPI.getGameAPI().getArenas()) {
            if (a != null && a.exists()) {
                list.add(new ArenaEntry(a.getName(), false,
                        a.getEnabledTeams().size(), a.getPlayersPerTeam(), a.getIcon()));
            }
        }
        list.sort(Comparator.comparing(ArenaEntry::name, String.CASE_INSENSITIVE_ORDER));
        return list;
    }

    private record ArenaEntry(String name, boolean remote, int teamCount, int playersPerTeam, ItemStack icon) {}
}
