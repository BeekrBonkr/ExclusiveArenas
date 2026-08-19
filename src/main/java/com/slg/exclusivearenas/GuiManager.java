package com.slg.exclusivearenas;

import de.marcely.bedwars.api.BedwarsAPI;
import de.marcely.bedwars.api.arena.Arena;
import de.marcely.bedwars.api.arena.ArenaStatus;
import de.marcely.bedwars.api.arena.ArenaTimeType;
import de.marcely.bedwars.api.arena.ArenaWeatherType;
import de.marcely.bedwars.api.arena.Team;
import de.marcely.bedwars.api.game.shop.ShopItem;
import de.marcely.bedwars.api.game.shop.ShopPage;
import de.marcely.bedwars.api.game.shop.price.ShopPrice;
import de.marcely.bedwars.api.remote.RemoteAPI;
import de.marcely.bedwars.api.remote.RemoteArena;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MenuType;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.view.AnvilView;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Builds every ExclusiveArenas menu. All looks — titles, sizes, slots, materials, names,
 * lore, glints — come from guis.yml through {@link GuiStyle}; this class only decides
 * WHICH buttons/templates appear and computes their live placeholder values.
 *
 * Context (which menu / session / page) travels on the {@link GuiHolder}, never the title.
 */
public final class GuiManager {

    public static final String ADMIN_PERM  = "exclusivearenas.admin";
    public static final String BYPASS_PERM  = "exclusivearenas.bypass";

    // Slot rings used for paginated lists (interior of a 54-slot menu).
    static final int[] LIST_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43
    };

    // Interior of the top two content rows — the timeline strip and the shop page list.
    static final int[] STRIP_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25
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
        Inventory inv = create(holder, GuiStyle.size("main", 27), GuiStyle.title("main"));
        frame(inv);
        // Fill the whole row, not just the gaps — Admin is permission-gated, and without this
        // its slot would be a bare hole for anyone lacking the permission.
        fillInteriorRow(inv, 9, accentMaterial());

        // One context-sensitive slot, three looks: nothing hosted yet → jump straight into
        // creating; hosting their single allowed match → jump straight into its controls;
        // otherwise the full management list. GuiListener re-derives the same branch from
        // live state at click time, so the three templates may safely share a slot.
        List<PrivateSession> owned = sessions.getSessionsByOwner(p.getUniqueId());
        int limit = plugin.getArenaLimit(p);
        String hosting = String.valueOf(owned.size());
        String limitText = limitLabel(limit);
        if (owned.isEmpty()) {
            GuiStyle.place(inv, "main.buttons.create-arena", "%hosting%", hosting, "%limit%", limitText);
        } else if (owned.size() == 1 && limit <= 1) {
            GuiStyle.place(inv, "main.buttons.match-controls", "%arena%", owned.get(0).getArenaName());
        } else {
            GuiStyle.place(inv, "main.buttons.arena-management", "%hosting%", hosting, "%limit%", limitText);
        }
        GuiStyle.place(inv, "main.buttons.help");
        GuiStyle.place(inv, "main.buttons.tournaments-soon");
        if (p.hasPermission(ADMIN_PERM)) {
            GuiStyle.place(inv, "main.buttons.admin");
        }
        GuiStyle.place(inv, "main.buttons.close");
        p.openInventory(inv);
    }

    // ── Session lists (player + admin) ────────────────────────────────────────────

    public void openArenaList(Player p, int page) {
        List<PrivateSession> owned = sessions.getSessionsByOwner(p.getUniqueId());
        GuiHolder holder = new GuiHolder(GuiHolder.Type.ARENA_LIST).adminView(false);
        renderSessionList(p, holder, "arena-list", owned, page, true);
    }

    public void openAdminList(Player p, int page) {
        // Every route into the network-wide list funnels through here (main menu, "Back" from
        // Controls, error-path reopens), so a single live check closes them all for a viewer
        // whose admin permission was revoked while menus referencing it were still open.
        if (!p.hasPermission(ADMIN_PERM)) {
            openArenaList(p, 0);
            return;
        }
        List<PrivateSession> all = new ArrayList<>(sessions.getAllSessions());
        all.sort(Comparator.comparing(PrivateSession::getArenaName, String.CASE_INSENSITIVE_ORDER));
        GuiHolder holder = new GuiHolder(GuiHolder.Type.ADMIN_LIST).adminView(true);
        renderSessionList(p, holder, "admin-list", all, page, false);
    }

    private void renderSessionList(Player p, GuiHolder holder, String menu,
                                   List<PrivateSession> list, int page, boolean allowCreate) {
        int perPage = LIST_SLOTS.length;
        int pages = Math.max(1, (int) Math.ceil(list.size() / (double) perPage));
        int pg = Math.max(0, Math.min(page, pages - 1));
        holder.page(pg);

        Inventory inv = create(holder, GuiStyle.size(menu, 54),
                GuiStyle.title(menu, "%page%", String.valueOf(pg + 1), "%pages%", String.valueOf(pages)));
        renderSessionListInto(p, holder, inv, menu, list, pg, pages, allowCreate, false);
        p.openInventory(inv);
    }

    /**
     * Fills a session-list page — also called by the live refresh to re-render in place.
     *
     * @param preserveSlots true for the once-a-second in-place refresh: entries still present
     *                      keep the slot they already occupy (instead of compacting the page),
     *                      so an in-flight click can't land on a neighbour that just slid over
     *                      because an earlier entry vanished. Fresh opens and page turns pass
     *                      false and compact as usual.
     */
    private void renderSessionListInto(Player p, GuiHolder holder, Inventory inv, String menu,
                                       List<PrivateSession> list, int pg, int pages, boolean allowCreate,
                                       boolean preserveSlots) {
        int perPage = LIST_SLOTS.length;
        Map<Integer, UUID> previous = preserveSlots ? holder.slotIdSnapshot() : Map.of();
        holder.clearSlotMaps();
        inv.clear();
        frame(inv);

        int start = pg * perPage;
        int end = Math.min(list.size(), start + perPage);
        List<PrivateSession> pageList = list.subList(start, end);

        // First pass: pin every entry that was already on screen to its previous slot.
        Map<UUID, Integer> pinned = new HashMap<>();
        Set<Integer> usedSlots = new HashSet<>();
        for (PrivateSession session : pageList) {
            for (Map.Entry<Integer, UUID> prev : previous.entrySet()) {
                if (prev.getValue().equals(session.getSessionId())) {
                    pinned.put(session.getSessionId(), prev.getKey());
                    usedSlots.add(prev.getKey());
                    break;
                }
            }
        }
        // Second pass: place everything — pinned entries where they were, the rest (all of
        // them, on a fresh render) into the remaining template slots in order.
        int nextFree = 0;
        for (PrivateSession session : pageList) {
            Integer slot = pinned.get(session.getSessionId());
            if (slot == null) {
                while (nextFree < LIST_SLOTS.length && usedSlots.contains(LIST_SLOTS[nextFree])) nextFree++;
                if (nextFree >= LIST_SLOTS.length) break; // page holds at most LIST_SLOTS entries
                slot = LIST_SLOTS[nextFree];
                usedSlots.add(slot);
            }
            inv.setItem(slot, sessionItem(menu, session, holder.adminView()));
            holder.mapSlot(slot, session.getSessionId());
        }

        if (list.isEmpty()) GuiStyle.place(inv, menu + ".buttons.empty");

        if (pg > 0) GuiStyle.place(inv, menu + ".buttons.previous-page", "%target%", String.valueOf(pg));
        if (pg < pages - 1) GuiStyle.place(inv, menu + ".buttons.next-page", "%target%", String.valueOf(pg + 2));

        GuiStyle.place(inv, menu + ".buttons.back");
        if (allowCreate) {
            GuiStyle.place(inv, menu + ".buttons.create",
                    "%hosting%", String.valueOf(sessions.countByOwner(p.getUniqueId())),
                    "%limit%", limitLabel(plugin.getArenaLimit(p)));
        } else {
            GuiStyle.place(inv, menu + ".buttons.close");
        }
        GuiStyle.place(inv, menu + ".buttons.refresh");
    }

    /**
     * Re-renders a live-data menu into its existing inventory, without reopening it (no cursor
     * flicker). Called every second by {@link GuiRefreshTask} for menus whose lore shows
     * timers/state. Returns false when the menu's subject no longer exists — the caller
     * closes the menu.
     */
    public boolean refreshInPlace(Player p, GuiHolder holder) {
        Inventory inv = holder.getInventory();
        if (inv == null) return true;

        // A menu carrying admin view (the network-wide Admin List, or Controls opened from it
        // for a match this player doesn't own) must not keep passively re-rendering — and stay
        // navigable — after the viewer's admin permission is revoked while it's still open.
        boolean adminSurface = holder.type() == GuiHolder.Type.ADMIN_LIST
                || (holder.type() == GuiHolder.Type.CONTROLS && holder.adminView());
        if (adminSurface && !p.hasPermission(ADMIN_PERM)) return false;

        switch (holder.type()) {
            case CONTROLS -> {
                PrivateSession session = sessions.getById(holder.sessionId());
                if (session == null) return false;
                renderControls(inv, session, holder.adminView());
            }
            case ARENA_LIST -> {
                List<PrivateSession> owned = sessions.getSessionsByOwner(p.getUniqueId());
                int pages = Math.max(1, (int) Math.ceil(owned.size() / (double) LIST_SLOTS.length));
                int pg = Math.max(0, Math.min(holder.page(), pages - 1));
                holder.page(pg);
                renderSessionListInto(p, holder, inv, "arena-list", owned, pg, pages, true, true);
            }
            case ADMIN_LIST -> {
                List<PrivateSession> all = new ArrayList<>(sessions.getAllSessions());
                all.sort(Comparator.comparing(PrivateSession::getArenaName, String.CASE_INSENSITIVE_ORDER));
                int pages = Math.max(1, (int) Math.ceil(all.size() / (double) LIST_SLOTS.length));
                int pg = Math.max(0, Math.min(holder.page(), pages - 1));
                holder.page(pg);
                renderSessionListInto(p, holder, inv, "admin-list", all, pg, pages, false, true);
            }
            default -> { /* static menus don't need refreshing */ }
        }
        return true;
    }

    // ── Match controls ────────────────────────────────────────────────────────────

    public void openControls(Player p, PrivateSession session, boolean adminView) {
        GuiHolder holder = new GuiHolder(GuiHolder.Type.CONTROLS)
                .sessionId(session.getSessionId()).adminView(adminView);
        Inventory inv = create(holder, GuiStyle.size("controls", 45),
                GuiStyle.title("controls", "%arena%", session.getArenaName()));
        renderControls(inv, session, adminView);
        p.openInventory(inv);
    }

    /** Fills the controls menu — also called by the live refresh to re-render in place. */
    void renderControls(Inventory inv, PrivateSession session, boolean adminView) {
        inv.clear();
        frame(inv);
        // Row 1 (setup/navigation) and row 2 (match state/access) get the neutral accent;
        // row 3 — Kick All / End Match — gets the danger material as a "careful here" cue.
        fillInteriorRow(inv, 9, accentMaterial());
        fillInteriorRow(inv, 18, accentMaterial());
        fillInteriorRow(inv, 27, dangerMaterial());

        boolean codePolicy = session.getJoinPolicy() == JoinPolicy.CODE;
        Arena local = BedwarsAPI.getGameAPI().getArenaByExactName(session.getArenaName());
        boolean onThisServer = local != null && local.exists();
        boolean relayAvailable = plugin.getRemoteCommandService().isAvailable();
        boolean lobbyNow = ArenaNames.isLobbyStatus(session.getArenaName());

        // Live status card: configured name + the live status lines as lore.
        int statusSlot = GuiStyle.slot("controls.buttons.status-card");
        if (statusSlot >= 0 && statusSlot < inv.getSize()) {
            ItemStack card = GuiStyle.item("controls.buttons.status-card", "%arena%", session.getArenaName());
            appendLore(card, ArenaStatusView.detail(session));
            inv.setItem(statusSlot, card);
        }

        GuiStyle.place(inv, "controls.buttons.settings");

        String teamsNote = onThisServer && lobbyNow ? "&8▶ Click to open"
                : (onThisServer ? "&cOnly available while in the lobby."
                        : "&cRequires being on the arena's server.");
        GuiStyle.place(inv, "controls.buttons.manage-teams", "%availability%", teamsNote);

        GuiStyle.place(inv, "controls.buttons.policy",
                "%policy%", codePolicy ? "&dJoin Code" : "&bParty Only",
                "%policy_desc%", codePolicy
                        ? "&7Players join with &f/ea join <code>"
                        : "&7Only your party members can join.");

        int startSlot = GuiStyle.place(inv,
                lobbyNow ? "controls.buttons.start-lobby" : "controls.buttons.start-running",
                "%remote_note%", onThisServer ? "" : "&8(controlling remotely)");

        if (codePolicy) {
            GuiStyle.place(inv, session.isPublic()
                            ? "controls.buttons.public-on" : "controls.buttons.public-off",
                    "%code%", safeCode(session));
            GuiStyle.place(inv, "controls.buttons.regenerate-code");
        } else {
            GuiStyle.place(inv, "controls.buttons.summon-party");
        }

        GuiStyle.place(inv, "controls.buttons.go-to-arena");
        int kickSlot = GuiStyle.place(inv, "controls.buttons.kick-all");
        int endSlot = GuiStyle.place(inv, "controls.buttons.end-match");
        GuiStyle.place(inv, "controls.buttons.quick-actions");

        // Start / Kick All / End Match execute on the arena's own server — from here they need
        // the shared database to relay through; say so on the buttons instead of failing quietly.
        if (!onThisServer && !relayAvailable) {
            String note = relayUnavailableLore();
            appendLoreLine(inv, startSlot, note);
            appendLoreLine(inv, kickSlot, note);
            appendLoreLine(inv, endSlot, note);
        }

        GuiStyle.place(inv, "controls.buttons.back", "%back_hint%",
                adminView ? "&8Return to the admin list." : "&8Return to your matches.");
        GuiStyle.place(inv, "controls.buttons.close");
    }

    // ── Quick actions ─────────────────────────────────────────────────────────────

    /**
     * Quick actions that only work while standing on the arena's own server, even with a
     * network database connected — the border render is a local particle effect, and the
     * Force Win picker needs the local arena's team list to build its menu.
     */
    private static final Set<String> LOCAL_ONLY_QUICK_ACTIONS = Set.of("reveal-border", "force-win");

    public void openQuickActions(Player p, PrivateSession session, boolean adminView) {
        GuiHolder holder = new GuiHolder(GuiHolder.Type.QUICK_ACTIONS)
                .sessionId(session.getSessionId()).adminView(adminView);
        Inventory inv = create(holder, GuiStyle.size("quick-actions", 54),
                GuiStyle.title("quick-actions", "%arena%", session.getArenaName()));
        frame(inv);
        fillInteriorRow(inv, 9, accentMaterial());
        fillInteriorRow(inv, 18, accentMaterial());
        fillInteriorRow(inv, 27, accentMaterial());
        fillInteriorRow(inv, 36, accentMaterial());

        Arena local = BedwarsAPI.getGameAPI().getArenaByExactName(session.getArenaName());
        boolean onThisServer = local != null && local.exists();
        boolean relayAvailable = plugin.getRemoteCommandService().isAvailable();

        String[] actions = {
                "regenerate-map", "heal-all", "drop-spawners", "destroy-beds", "clear-items",
                "force-win", "swap-teams-info", "balance-teams", "trigger-trap", "clear-traps",
                "reset-upgrades", "grant-effect", "toggle-freeze", "force-rejoin", "reveal-border",
                "extend-timer", "shorten-timer", "toggle-pvp", "strip-inventories", "comeback-buff",
                "random-scatter", "kick-afk", "reset-shop-prices", "give-compass", "announce-stats",
                "toggle-pause"
        };
        for (String action : actions) {
            int slot = GuiStyle.place(inv, "quick-actions.buttons." + action);
            markIfRemoteUnavailable(inv, slot, action, onThisServer, relayAvailable);
        }
        if (plugin.getTimelineService().isEnabled()) {
            int slot = GuiStyle.place(inv, "quick-actions.buttons.skip-event");
            markIfRemoteUnavailable(inv, slot, "skip-event", onThisServer, relayAvailable);
        }
        GuiStyle.place(inv, "quick-actions.buttons.back");
        p.openInventory(inv);
    }

    /**
     * Appends the configured "can't do this from here" lore line to an action button when the
     * session's arena lives on another server: always for the strictly-local actions, and for
     * everything else only when there's no shared database to relay the click through.
     */
    private void markIfRemoteUnavailable(Inventory inv, int slot, String action,
                                         boolean onThisServer, boolean relayAvailable) {
        if (onThisServer || slot < 0) return;
        if (LOCAL_ONLY_QUICK_ACTIONS.contains(action)) {
            appendLoreLine(inv, slot, remoteUnavailableLore());
        } else if (!relayAvailable) {
            appendLoreLine(inv, slot, relayUnavailableLore());
        }
    }

    private static String remoteUnavailableLore() {
        return GuiStyle.rawString("global.remote-unavailable-lore",
                "&c✖ Unavailable — the arena is on another server.");
    }

    private static String relayUnavailableLore() {
        return GuiStyle.rawString("global.relay-unavailable-lore",
                "&c✖ Unavailable — no shared database to relay through.");
    }

    private static void appendLoreLine(Inventory inv, int slot, String line) {
        if (slot < 0 || slot >= inv.getSize()) return;
        ItemStack item = inv.getItem(slot);
        if (item == null) return;
        appendLore(item, List.of(line));
        inv.setItem(slot, item);
    }

    /** Quick Actions → Force Win: pick a team to instantly award the win to. */
    public void openForceWin(Player p, PrivateSession session, boolean adminView) {
        Arena arena = BedwarsAPI.getGameAPI().getArenaByExactName(session.getArenaName());
        GuiHolder gh = new GuiHolder(GuiHolder.Type.QUICK_FORCE_WIN)
                .sessionId(session.getSessionId()).adminView(adminView);
        Inventory inv = create(gh, GuiStyle.size("quick-force-win", 27),
                GuiStyle.title("quick-force-win", "%arena%", session.getArenaName()));
        frame(inv);
        fillInteriorRow(inv, 9, accentMaterial());

        if (arena != null && arena.exists()) {
            List<Team> teams = new ArrayList<>(arena.getEnabledTeams());
            for (int i = 0; i < teams.size() && i < STRIP_SLOTS.length; i++) {
                Team team = teams.get(i);
                int slot = STRIP_SLOTS[i];
                inv.setItem(slot, ItemUtil.button(Material.WHITE_BANNER,
                        GuiStyle.name("quick-force-win.items.team", "%team%", team.getDisplayName(null)),
                        GuiStyle.lore("quick-force-win.items.team", "%team%", team.getDisplayName(null))));
                gh.mapKeySlot(slot, team.name());
            }
        }
        GuiStyle.place(inv, "quick-force-win.buttons.back");
        p.openInventory(inv);
    }

    /** Quick Actions → Buff Everyone: a fixed short list of practice/highlight-match buffs. */
    public void openGrantEffect(Player p, PrivateSession session, boolean adminView) {
        GuiHolder gh = new GuiHolder(GuiHolder.Type.QUICK_GRANT_EFFECT)
                .sessionId(session.getSessionId()).adminView(adminView);
        Inventory inv = create(gh, GuiStyle.size("quick-grant-effect", 27),
                GuiStyle.title("quick-grant-effect", "%arena%", session.getArenaName()));
        frame(inv);
        fillInteriorRow(inv, 9, accentMaterial());

        GuiStyle.place(inv, "quick-grant-effect.buttons.speed");
        GuiStyle.place(inv, "quick-grant-effect.buttons.jump");
        GuiStyle.place(inv, "quick-grant-effect.buttons.regen");
        GuiStyle.place(inv, "quick-grant-effect.buttons.strength");
        GuiStyle.place(inv, "quick-grant-effect.buttons.back");
        p.openInventory(inv);
    }

    // ── Arena settings hub ────────────────────────────────────────────────────────

    public void openArenaConfig(Player p, PrivateSession session, boolean adminView) {
        GuiHolder holder = new GuiHolder(GuiHolder.Type.ARENA_CONFIG)
                .sessionId(session.getSessionId()).adminView(adminView);
        Inventory inv = create(holder, GuiStyle.size("arena-config", 27),
                GuiStyle.title("arena-config", "%arena%", session.getArenaName()));
        frame(inv);
        // Fill the whole row first, not just the gaps between buttons — event-timeline is
        // conditionally hidden (timeline.enabled: false), and without this its slot would be
        // left as a bare hole instead of reading as an intentional, evenly-filled row.
        fillInteriorRow(inv, 9, accentMaterial());

        // Every editor's button carries a live "what this match has changed so far" line, so the
        // hub answers "did I already set that?" without opening each editor to find out.
        if (plugin.getTimelineService().isEnabled()) {
            GuiStyle.place(inv, "arena-config.buttons.event-timeline", "%summary%", timelineSummary(session));
        }
        GuiStyle.place(inv, "arena-config.buttons.shop-config", "%summary%", shopSummary(session));
        GuiStyle.place(inv, "arena-config.buttons.presets", "%summary%", changeCountSummary(session));
        int teamSizeSlot = GuiStyle.place(inv, "arena-config.buttons.team-size",
                "%summary%", teamSizeSummary(session));
        GuiStyle.place(inv, "arena-config.buttons.match-rules", "%summary%", matchRulesSummary(session));
        int environmentSlot = GuiStyle.place(inv, "arena-config.buttons.environment",
                "%summary%", environmentSummary(session));
        GuiStyle.place(inv, "arena-config.buttons.back");

        // Team Size and Environment act on the live arena object directly, so they only work
        // from the server that hosts it — unlike the timeline/shop/preset editors, which edit
        // the session's replicated settings and work from anywhere.
        Arena local = BedwarsAPI.getGameAPI().getArenaByExactName(session.getArenaName());
        if (local == null || !local.exists()) {
            String note = remoteUnavailableLore();
            appendLoreLine(inv, teamSizeSlot, note);
            appendLoreLine(inv, environmentSlot, note);
        }
        p.openInventory(inv);
    }

    /** The same Arena Modifiers hub, but for a not-yet-created builder draft. */
    public void openBuilderSettings(Player p, DraftPrivateMatch draft) {
        GuiHolder holder = new GuiHolder(GuiHolder.Type.BUILDER_SETTINGS);
        Inventory inv = create(holder, GuiStyle.size("builder-settings", 27),
                GuiStyle.title("builder-settings", "%arena%", draft.getArenaName()));
        frame(inv);
        fillInteriorRow(inv, 9, accentMaterial());

        if (plugin.getTimelineService().isEnabled()) {
            GuiStyle.place(inv, "builder-settings.buttons.event-timeline", "%summary%", timelineSummary(draft));
        }
        GuiStyle.place(inv, "builder-settings.buttons.shop-config", "%summary%", shopSummary(draft));
        GuiStyle.place(inv, "builder-settings.buttons.team-size", "%summary%", teamSizeSummary(draft));
        GuiStyle.place(inv, "builder-settings.buttons.match-rules", "%summary%", matchRulesSummary(draft));
        GuiStyle.place(inv, "builder-settings.buttons.back");
        p.openInventory(inv);
    }

    // ── Arena Modifiers hub summaries ─────────────────────────────────────────────
    //
    // Each returns a single short, already-colored line for the matching hub button's
    // "%summary%" placeholder: what this match has actually changed, or the untouched default.

    private String timelineSummary(SettingsHolder holder) {
        return timelineSummaryOf(holder.getSettings());
    }

    private String timelineSummaryOf(SessionSettings settings) {
        TimelineService timelines = plugin.getTimelineService();
        List<SessionSettings.TimelineEntry> timeline = timelines.effectiveTimeline(settings);
        int events = Math.max(0, timeline.size() - 1); // Match End isn't one of the "events"
        String length = TimelineService.format(timelines.matchEndSeconds(settings));

        String state = settings.getTimeline() == null ? "&7Default schedule" : "&eCustomized";
        return state + " &8· &f" + events + "&7 event" + (events == 1 ? "" : "s")
                + "&7, ends &f" + length;
    }

    private String shopSummary(SettingsHolder holder) {
        return shopSummaryOf(holder.getSettings());
    }

    private String shopSummaryOf(SessionSettings settings) {
        long disabled = settings.getShopOverrides().values().stream()
                .filter(SessionSettings.ShopOverride::isDisabled).count();
        long priced = settings.getShopOverrides().values().stream()
                .filter(SessionSettings.ShopOverride::hasPriceOverride).count();
        if (disabled == 0 && priced == 0) return "&7No changes";
        return "&e" + disabled + "&7 disabled, &e" + priced + "&7 repriced";
    }

    private String teamSizeSummary(SettingsHolder holder) {
        Integer override = holder.getSettings().getPlayersPerTeam();
        Integer arenaDefault = arenaDefaultPlayersPerTeam(holder);
        if (override == null) {
            return arenaDefault == null ? "&7Arena default" : "&7Arena default &8(&f" + arenaDefault + "&8)";
        }
        return "&e" + override + "&7 per team"
                + (arenaDefault == null ? "" : " &8(default &f" + arenaDefault + "&8)");
    }

    /**
     * The arena's own players-per-team value, or null when it can't be read from here (the
     * arena lives on another server). For a live session the snapshot taken before any override
     * was applied is the truthful answer — the live arena object already carries the override.
     */
    private Integer arenaDefaultPlayersPerTeam(SettingsHolder holder) {
        if (holder instanceof PrivateSession session && session.getOriginalPlayersPerTeam() != null) {
            return session.getOriginalPlayersPerTeam();
        }
        Arena arena = BedwarsAPI.getGameAPI().getArenaByExactName(holder.getArenaName());
        return arena != null && arena.exists() ? arena.getPlayersPerTeam() : null;
    }

    private String environmentSummary(SettingsHolder holder) {
        return environmentSummaryOf(holder.getSettings());
    }

    private String environmentSummaryOf(SessionSettings settings) {
        ArenaWeatherType weather = ExclusiveArenasPlugin.parseWeatherType(settings.getWeatherType());
        ArenaTimeType time = ExclusiveArenasPlugin.parseTimeType(settings.getTimeType());
        if (weather == ArenaWeatherType.UNTOUCHED && time == ArenaTimeType.UNTOUCHED) return "&7Untouched";

        List<String> parts = new ArrayList<>(2);
        if (time != ArenaTimeType.UNTOUCHED) parts.add(timeLabel(time));
        if (weather != ArenaWeatherType.UNTOUCHED) parts.add(weatherLabel(weather));
        return String.join("&7, ", parts);
    }

    private String matchRulesSummary(SettingsHolder holder) {
        return matchRulesSummaryOf(holder.getSettings());
    }

    private String matchRulesSummaryOf(SessionSettings settings) {
        List<String> changed = changedRuleNames(settings.getModifiers());
        if (changed.isEmpty()) return "&7All default";

        // Naming the first few reads far better than a bare count, but the whole list would
        // blow the lore line apart — cap it and count the rest.
        String named = String.join("&7, ", changed.subList(0, Math.min(3, changed.size())));
        return "&e" + changed.size() + "&7 changed &8· &f" + named
                + (changed.size() > 3 ? " &8+" + (changed.size() - 3) + " more" : "");
    }

    /** The display names of every match rule currently away from its vanilla default. */
    private static List<String> changedRuleNames(SessionSettings.ArenaModifiers mods) {
        List<String> out = new ArrayList<>();
        if (mods.isFriendlyFire()) out.add("Friendly Fire");
        if (mods.isNoFallDamage()) out.add("No Fall Damage");
        if (mods.isNoExplosionBlockDamage()) out.add("No Explosion Damage");
        if (mods.getKillBountyMultiplier() != 0) out.add("Kill Bounty");
        if (mods.getShopCurrencyMultiplier() != 1.0) out.add("Shop Prices");
        if (mods.isBonusStartingKit()) out.add("Bonus Kit");
        if (mods.getPvpGraceSeconds() != 0) out.add("PvP Grace");
        if (mods.getHealthMultiplier() != 1.0) out.add("Health");
        if (mods.isWorldBorderShrink()) out.add("World Border");
        if (mods.isBedRespawnOnce()) out.add("Bed Respawn Once");
        if (mods.getSpawnProtectionSeconds() != 0) out.add("Spawn Protection");
        if (mods.getKillGoal() != 0) out.add("Kill Goal");
        return out;
    }

    /** How many of the six editors this match has actually changed — the Presets button's line. */
    private String changeCountSummary(SettingsHolder holder) {
        SessionSettings settings = holder.getSettings();
        int changed = 0;
        if (settings.getTimeline() != null) changed++;
        if (!settings.getShopOverrides().isEmpty()) changed++;
        if (settings.getPlayersPerTeam() != null) changed++;
        if (!settings.getModifiers().isDefault()) changed++;
        if (settings.getWeatherType() != null || settings.getTimeType() != null) changed++;
        return changed == 0
                ? "&7Nothing customized yet"
                : "&f" + changed + "&7 setting" + (changed == 1 ? "" : "s") + " customized";
    }

    /** Bounds on the players-per-team override — generous for every real BedWars team format. */
    static final int MIN_PLAYERS_PER_TEAM = 1;
    static final int MAX_PLAYERS_PER_TEAM = 8;

    public void openTeamSize(Player p, SettingsHolder holder, boolean adminView) {
        openTeamSize(p, holder, adminView, null);
    }

    /**
     * @param origin the menu to return to — {@code TEAM_SELECT} when opened from Manage Teams,
     *               null for the usual Arena Modifiers hub route.
     */
    public void openTeamSize(Player p, SettingsHolder holder, boolean adminView, GuiHolder.Type origin) {
        UUID sessionId = holder instanceof PrivateSession ps ? ps.getSessionId() : null;
        GuiHolder gh = new GuiHolder(GuiHolder.Type.TEAM_SIZE)
                .sessionId(sessionId).adminView(adminView).origin(origin);
        Inventory inv = create(gh, GuiStyle.size("team-size", 27),
                GuiStyle.title("team-size", "%arena%", holder.getArenaName()));
        frame(inv);
        // Fill both interior rows, not just the gaps between buttons — minus-one/plus-one/reset
        // are all conditionally hidden once locked, and without this those slots would be left
        // as bare holes instead of reading as an intentional, evenly-filled layout.
        fillInteriorRow(inv, 9, accentMaterial());
        fillInteriorRow(inv, 18, accentMaterial());

        Arena arena = BedwarsAPI.getGameAPI().getArenaByExactName(holder.getArenaName());
        if (arena == null || !arena.exists()) {
            GuiStyle.place(inv, "team-size.buttons.unavailable",
                    "%reason%", "&7This arena isn't loaded on this server.");
            GuiStyle.place(inv, "team-size.buttons.back");
            p.openInventory(inv);
            return;
        }

        // A not-yet-created draft has no "arena's original value" to remember (nothing has
        // touched the live arena yet) and no lock — nobody could have joined a match that
        // doesn't exist. Both only apply to a live session.
        Integer original = holder instanceof PrivateSession ps ? ps.getOriginalPlayersPerTeam() : null;
        int fallback = original != null ? original : arena.getPlayersPerTeam();
        Integer override = holder.getSettings().getPlayersPerTeam();
        int amount = override != null ? override : fallback;
        boolean locked = holder instanceof PrivateSession && !plugin.canChangeTeamSize(arena);

        String statusLine;
        if (locked) {
            statusLine = "&cLocked — the match is running.";
        } else if (holder instanceof PrivateSession && anyPlayerHasTeam(arena)) {
            statusLine = "&eChanging this removes everyone from their team.";
        } else {
            statusLine = "";
        }

        GuiStyle.place(inv, "team-size.buttons.display", "%amount%", String.valueOf(amount),
                "%locked%", statusLine);
        if (!locked) {
            GuiStyle.place(inv, "team-size.buttons.minus-one");
            GuiStyle.place(inv, "team-size.buttons.plus-one");
            GuiStyle.place(inv, "team-size.buttons.reset");
        }
        GuiStyle.place(inv, "team-size.buttons.back");
        p.openInventory(inv);
    }

    private static boolean anyPlayerHasTeam(Arena arena) {
        for (org.bukkit.entity.Player player : arena.getPlayers()) {
            if (arena.getPlayerTeam(player) != null) return true;
        }
        return false;
    }

    /**
     * Arena Modifiers → Environment: cycles the arena's time/weather. Live-only (a not-yet
     * -created draft has no arena to preview this on) and usable any time — unlike team size,
     * this is a purely cosmetic per-player effect with no gameplay-balance reason to lock it.
     */
    public void openEnvironment(Player p, PrivateSession session, boolean adminView) {
        GuiHolder gh = new GuiHolder(GuiHolder.Type.ENVIRONMENT)
                .sessionId(session.getSessionId()).adminView(adminView);
        Inventory inv = create(gh, GuiStyle.size("environment", 27),
                GuiStyle.title("environment", "%arena%", session.getArenaName()));
        frame(inv);
        fillInteriorRow(inv, 9, accentMaterial());

        ArenaWeatherType weather = ExclusiveArenasPlugin.parseWeatherType(session.getSettings().getWeatherType());
        ArenaTimeType time = ExclusiveArenasPlugin.parseTimeType(session.getSettings().getTimeType());

        GuiStyle.place(inv, "environment.buttons.weather", "%current%", weatherLabel(weather));
        GuiStyle.place(inv, "environment.buttons.time", "%current%", timeLabel(time));
        GuiStyle.place(inv, "environment.buttons.back");
        p.openInventory(inv);
    }

    private static String weatherLabel(ArenaWeatherType type) {
        return switch (type) {
            case CLEAR -> "&fClear Skies";
            case RAINING -> "&9Rain";
            default -> "&7Untouched (default)";
        };
    }

    private static String timeLabel(ArenaTimeType type) {
        return switch (type) {
            case NOON -> "&eNoon";
            case SUNSET -> "&6Sunset";
            case NIGHT -> "&9Night";
            default -> "&7Untouched (default)";
        };
    }

    // ── Match rules ──────────────────────────────────────────────────────────────

    public void openMatchRules(Player p, SettingsHolder holder, boolean adminView) {
        UUID sessionId = holder instanceof PrivateSession ps ? ps.getSessionId() : null;
        GuiHolder gh = new GuiHolder(GuiHolder.Type.MATCH_RULES)
                .sessionId(sessionId).adminView(adminView);
        Inventory inv = create(gh, GuiStyle.size("match-rules", 54),
                GuiStyle.title("match-rules", "%arena%", holder.getArenaName()));
        frame(inv);
        fillInteriorRow(inv, 9, accentMaterial());
        fillInteriorRow(inv, 18, accentMaterial());

        SessionSettings.ArenaModifiers mods = holder.getSettings().getModifiers();

        GuiStyle.place(inv, "match-rules.buttons.friendly-fire", "%current%", onOff(mods.isFriendlyFire()));
        GuiStyle.place(inv, "match-rules.buttons.fall-damage", "%current%", onOff(!mods.isNoFallDamage()));
        GuiStyle.place(inv, "match-rules.buttons.explosion-damage", "%current%", onOff(!mods.isNoExplosionBlockDamage()));
        GuiStyle.place(inv, "match-rules.buttons.kill-bounty", "%current%",
                mods.getKillBountyMultiplier() == 0 ? "&7Off" : "&6" + mods.getKillBountyMultiplier() + "x");
        GuiStyle.place(inv, "match-rules.buttons.shop-multiplier", "%current%", multiplierLabel(mods.getShopCurrencyMultiplier()));
        GuiStyle.place(inv, "match-rules.buttons.bonus-kit", "%current%", onOff(mods.isBonusStartingKit()));
        GuiStyle.place(inv, "match-rules.buttons.pvp-grace", "%current%",
                mods.getPvpGraceSeconds() == 0 ? "&7Off" : "&b" + mods.getPvpGraceSeconds() + "s");
        GuiStyle.place(inv, "match-rules.buttons.health-multiplier", "%current%", multiplierLabel(mods.getHealthMultiplier()));
        GuiStyle.place(inv, "match-rules.buttons.world-border", "%current%", onOff(mods.isWorldBorderShrink()));
        GuiStyle.place(inv, "match-rules.buttons.bed-respawn", "%current%", onOff(mods.isBedRespawnOnce()));
        GuiStyle.place(inv, "match-rules.buttons.spawn-protection", "%current%",
                mods.getSpawnProtectionSeconds() == 0 ? "&7Off" : "&9" + mods.getSpawnProtectionSeconds() + "s");
        GuiStyle.place(inv, "match-rules.buttons.kill-goal", "%current%",
                mods.getKillGoal() == 0 ? "&7Off" : "&e" + mods.getKillGoal() + " kills");
        GuiStyle.place(inv, "match-rules.buttons.reset-all");
        GuiStyle.place(inv, "match-rules.buttons.back");
        p.openInventory(inv);
    }

    private static String onOff(boolean v) {
        return v ? "&aON" : "&7OFF";
    }

    private static String multiplierLabel(double v) {
        if (v == 1.0) return "&7Normal (1x)";
        String num = v == Math.floor(v) ? String.valueOf((int) v) : String.valueOf(v);
        return "&e" + num + "x";
    }

    // ── Saved configurations (presets) ────────────────────────────────────────────

    /**
     * The preset manager for a session: every saved configuration as one item (click to
     * apply, shift-click to delete) plus a Save button snapshotting the session's current
     * settings. {@code presets} comes from {@link PresetService#list} — the caller loads
     * it asynchronously and opens this menu in the callback.
     */
    public void openPresets(Player p, PrivateSession session, boolean adminView,
                            java.util.LinkedHashMap<String, String> presets) {
        GuiHolder holder = new GuiHolder(GuiHolder.Type.PRESETS)
                .sessionId(session.getSessionId()).adminView(adminView).presets(presets);
        Inventory inv = create(holder, GuiStyle.size("presets", 54),
                GuiStyle.title("presets", "%arena%", session.getArenaName()));
        frame(inv);

        GuiStyle.place(inv, "presets.buttons.info");

        int i = 0;
        for (Map.Entry<String, String> entry : presets.entrySet()) {
            if (i >= LIST_SLOTS.length) break;
            int slot = LIST_SLOTS[i++];

            SessionSettings settings = SessionSettings.fromJson(entry.getValue());
            String timelineSummary = settings.getTimeline() == null
                    ? GuiStyle.rawString("presets.summary-default-timeline", "&8Default timeline")
                    : GuiStyle.rawString("presets.summary-custom-timeline", "&7Custom timeline")
                            .replace("%events%", String.valueOf(settings.getTimeline().size()));
            long disabled = settings.getShopOverrides().values().stream()
                    .filter(SessionSettings.ShopOverride::isDisabled).count();
            long priced = settings.getShopOverrides().values().stream()
                    .filter(SessionSettings.ShopOverride::hasPriceOverride).count();

            // A guis.yml whose "preset" name template is missing (or lost) the %name%
            // placeholder — e.g. a stale copy from before it was added — must never hide the
            // saved name entirely; fall back to just showing it plainly.
            String presetName = GuiStyle.name("presets.items.preset", "%name%", entry.getKey());
            String stripped = ChatColor.stripColor(presetName);
            if (stripped == null || !stripped.contains(entry.getKey())) {
                presetName = "&f&l" + entry.getKey();
            }

            ItemStack item = ItemUtil.button(
                    GuiStyle.material("presets.items.preset.material", Material.PAPER),
                    presetName,
                    GuiStyle.lore("presets.items.preset",
                            "%name%", entry.getKey(),
                            "%timeline%", timelineSummary,
                            "%disabled%", String.valueOf(disabled),
                            "%priced%", String.valueOf(priced),
                            "%teamsize%", settings.getPlayersPerTeam() == null
                                    ? "&8Arena default" : "&7" + settings.getPlayersPerTeam() + " per team",
                            "%rules%", changedRuleNames(settings.getModifiers()).isEmpty()
                                    ? "&8Default rules"
                                    : "&7" + changedRuleNames(settings.getModifiers()).size() + " rule change(s)"));
            if (GuiStyle.glint("presets.items.preset")) ItemUtil.glint(item);
            inv.setItem(slot, item);
            holder.mapKeySlot(slot, entry.getKey());
        }

        if (presets.isEmpty()) GuiStyle.place(inv, "presets.buttons.empty");

        if (presets.size() < PresetService.MAX_PRESETS) {
            GuiStyle.place(inv, "presets.buttons.save-current");
        }
        GuiStyle.place(inv, "presets.buttons.back");
        p.openInventory(inv);
    }

    /**
     * A read-only breakdown of one saved configuration — every editor's contents plus what would
     * actually change if it were applied — so a host can check a preset is the one they meant
     * before overwriting the match's current setup with it.
     */
    public void openPresetPreview(Player p, PrivateSession session, boolean adminView,
                                  java.util.LinkedHashMap<String, String> presets, String name) {
        SessionSettings preset = SessionSettings.fromJson(presets.get(name));

        GuiHolder holder = new GuiHolder(GuiHolder.Type.PRESET_PREVIEW)
                .sessionId(session.getSessionId()).adminView(adminView)
                .presets(presets).presetName(name);
        Inventory inv = create(holder, GuiStyle.size("preset-preview", 45),
                GuiStyle.title("preset-preview", "%name%", name, "%arena%", session.getArenaName()));
        frame(inv);
        fillInteriorRow(inv, 9, accentMaterial());
        fillInteriorRow(inv, 18, accentMaterial());

        GuiStyle.place(inv, "preset-preview.buttons.header", "%name%", name);
        GuiStyle.place(inv, "preset-preview.buttons.timeline",
                "%summary%", timelineSummaryOf(preset), "%detail%", timelineDetail(preset));
        GuiStyle.place(inv, "preset-preview.buttons.shop",
                "%summary%", shopSummaryOf(preset), "%detail%", shopDetail(preset));
        GuiStyle.place(inv, "preset-preview.buttons.team-size",
                "%summary%", preset.getPlayersPerTeam() == null
                        ? "&7Arena default" : "&e" + preset.getPlayersPerTeam() + "&7 per team");
        GuiStyle.place(inv, "preset-preview.buttons.match-rules",
                "%summary%", matchRulesSummaryOf(preset), "%detail%", matchRulesDetail(preset));
        GuiStyle.place(inv, "preset-preview.buttons.environment",
                "%summary%", environmentSummaryOf(preset));
        GuiStyle.place(inv, "preset-preview.buttons.diff",
                "%detail%", diffDetail(session.getSettings(), preset));

        GuiStyle.place(inv, "preset-preview.buttons.apply", "%name%", name);
        GuiStyle.place(inv, "preset-preview.buttons.delete", "%name%", name);
        GuiStyle.place(inv, "preset-preview.buttons.back");
        p.openInventory(inv);
    }

    /** Each event as one "&7m:ss &8— &fName" line, capped so the lore stays readable. */
    private String timelineDetail(SessionSettings settings) {
        TimelineService timelines = plugin.getTimelineService();
        List<SessionSettings.TimelineEntry> timeline = timelines.effectiveTimeline(settings);

        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (SessionSettings.TimelineEntry entry : timeline) {
            if (shown >= 10) {
                sb.append("\n&8… and ").append(timeline.size() - shown).append(" more");
                break;
            }
            TimelineService.Definition def = timelines.definitionFor(entry);
            if (sb.length() > 0) sb.append('\n');
            sb.append("&7").append(TimelineService.format(entry.seconds()))
                    .append(" &8— &f").append(def != null ? def.name() : entry.id());
            shown++;
        }
        return sb.toString();
    }

    /** Disabled / repriced shop items, by name, capped the same way. */
    private String shopDetail(SessionSettings settings) {
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        int total = settings.getShopOverrides().size();
        for (Map.Entry<String, SessionSettings.ShopOverride> e : settings.getShopOverrides().entrySet()) {
            if (shown >= 8) {
                sb.append("\n&8… and ").append(total - shown).append(" more");
                break;
            }
            SessionSettings.ShopOverride o = e.getValue();
            ShopItem item = BedwarsAPI.getGameAPI().getShopItemById(e.getKey());
            String label = item != null ? plainName(item.getDisplayName()) : e.getKey();
            if (sb.length() > 0) sb.append('\n');
            if (o.isDisabled()) {
                sb.append("&c✖ &7").append(label);
            } else {
                sb.append("&e● &7").append(label).append(" &8→ &f")
                        .append(o.getPrice()).append(' ').append(currencyLabel(o.getCurrency()));
            }
            shown++;
        }
        return sb.toString();
    }

    private String matchRulesDetail(SessionSettings settings) {
        List<String> changed = changedRuleNames(settings.getModifiers());
        StringBuilder sb = new StringBuilder();
        for (String rule : changed) {
            if (sb.length() > 0) sb.append('\n');
            sb.append("&e● &7").append(rule);
        }
        return sb.toString();
    }

    /**
     * Which editors this preset would actually change if applied over the match's current setup.
     * Compares the underlying values, not the cards rendered above them — those truncate long
     * lists, and a difference past the cut-off would otherwise read as "nothing would change".
     */
    private String diffDetail(SessionSettings current, SessionSettings preset) {
        List<String> differences = new ArrayList<>();
        if (!timelineSignature(current).equals(timelineSignature(preset))) differences.add("Event Timeline");
        if (!sameShopOverrides(current, preset)) differences.add("Shop Items");
        if (!java.util.Objects.equals(current.getPlayersPerTeam(), preset.getPlayersPerTeam())) {
            differences.add("Team Size");
        }
        if (!current.getModifiers().sameValuesAs(preset.getModifiers())) differences.add("Match Rules");
        if (!java.util.Objects.equals(current.getWeatherType(), preset.getWeatherType())
                || !java.util.Objects.equals(current.getTimeType(), preset.getTimeType())) {
            differences.add("Environment");
        }

        if (differences.isEmpty()) return "&a✔ Nothing would change — this is already the setup.";
        StringBuilder sb = new StringBuilder();
        for (String d : differences) {
            if (sb.length() > 0) sb.append('\n');
            sb.append("&6● &7").append(d);
        }
        return sb.toString();
    }

    /**
     * A comparable form of a schedule. Custom events are keyed by what they DO rather than by
     * their id — every save mints a fresh id, so identical schedules would otherwise never
     * compare equal.
     */
    private List<String> timelineSignature(SessionSettings settings) {
        List<String> out = new ArrayList<>();
        for (SessionSettings.TimelineEntry e : plugin.getTimelineService().effectiveTimeline(settings)) {
            out.add((e.isCustom() ? e.customType() + "|" + e.customValue() : e.id()) + "@" + e.seconds());
        }
        java.util.Collections.sort(out);
        return out;
    }

    private static boolean sameShopOverrides(SessionSettings a, SessionSettings b) {
        Map<String, SessionSettings.ShopOverride> left = a.getShopOverrides();
        Map<String, SessionSettings.ShopOverride> right = b.getShopOverrides();
        if (left.size() != right.size()) return false;
        for (Map.Entry<String, SessionSettings.ShopOverride> e : left.entrySet()) {
            SessionSettings.ShopOverride other = right.get(e.getKey());
            SessionSettings.ShopOverride mine = e.getValue();
            if (other == null
                    || other.isDisabled() != mine.isDisabled()
                    || !java.util.Objects.equals(other.getPrice(), mine.getPrice())
                    || !java.util.Objects.equals(other.getCurrency(), mine.getCurrency())) {
                return false;
            }
        }
        return true;
    }

    /** Opens the anvil prompt where the host types a name for the preset they're about to save. */
    public void openPresetNamePrompt(Player p, PrivateSession session, boolean adminView,
                                     Map<String, String> presets) {
        java.util.LinkedHashMap<String, String> snapshot = new java.util.LinkedHashMap<>(presets);
        GuiHolder holder = new GuiHolder(GuiHolder.Type.PRESET_NAME)
                .sessionId(session.getSessionId()).adminView(adminView).presets(snapshot);

        String suggested = PresetService.nextFreeName(snapshot);
        openAnvilPrompt(p, holder,
                GuiStyle.title("preset-name", "%arena%", session.getArenaName()),
                GuiStyle.item("preset-name.buttons.icon", "%name%", suggested));
    }

    // ── Anvil text prompts ────────────────────────────────────────────────────────
    //
    // Only a REAL anvil container view processes rename packets (and fires PrepareAnvilEvent) —
    // a Bukkit.createInventory(…, InventoryType.ANVIL) chest-alike silently drops them. Real
    // views can't carry a custom InventoryHolder, so the GuiHolder that routes a prompt's
    // clicks is parked here per player instead: registered when the prompt opens, consulted by
    // GuiListener's click/prepare handlers, and cleared when the view closes or the player quits.

    private final Map<UUID, GuiHolder> pendingPrompts = new HashMap<>();

    /** The anvil-prompt context awaiting this player's input, or null when none is open. */
    GuiHolder pendingPrompt(UUID playerId) {
        return pendingPrompts.get(playerId);
    }

    void clearPendingPrompt(UUID playerId) {
        pendingPrompts.remove(playerId);
    }

    /**
     * Opens a real anvil view carrying {@code gh} through {@link #pendingPrompt}. The input
     * item's name pre-fills the text field; the typed text comes back via
     * {@code AnvilView#getRenameText()} on click/prepare.
     */
    private void openAnvilPrompt(Player p, GuiHolder gh, String title, ItemStack input) {
        AnvilView view = MenuType.ANVIL.create(p,
                LegacyComponentSerializer.legacySection().deserialize(ItemUtil.color(title)));
        gh.setInventory(view.getTopInventory());
        // Register before opening: opening closes whatever menu was showing, and the close
        // handler only clears a prompt whose inventory matches, so this can't be swept away.
        pendingPrompts.put(p.getUniqueId(), gh);
        p.openInventory(view);
        view.getTopInventory().setItem(0, input);
        view.setRepairCost(0);
    }

    // ── Event timeline editor ─────────────────────────────────────────────────────

    public void openTimeline(Player p, SettingsHolder holder, boolean adminView, String selectedEventId) {
        openTimeline(p, holder, adminView, selectedEventId, 0);
    }

    /**
     * The schedule editor. The interior strip lists the match's events in order (paged, since a
     * host can add far more than one screen holds); selecting one reveals its detail card and
     * the move/delete/duplicate/edit controls beneath it.
     *
     * @param page which strip page to show — ignored while something is selected, since the
     *             editor always follows the selected event (a move can push it onto another page).
     */
    public void openTimeline(Player p, SettingsHolder holder, boolean adminView,
                             String selectedEventId, int page) {
        TimelineService timelines = plugin.getTimelineService();
        List<SessionSettings.TimelineEntry> timeline = timelines.effectiveTimeline(holder.getSettings());

        // A selection that no longer exists (deleted, reset) silently clears.
        String wanted = selectedEventId;
        if (wanted != null && timeline.stream().noneMatch(e -> e.id().equals(wanted))) {
            selectedEventId = null;
        }

        int perPage = STRIP_SLOTS.length;
        int pages = Math.max(1, (int) Math.ceil(timeline.size() / (double) perPage));
        int selectedIndex = indexOfEvent(timeline, selectedEventId);
        int pg = selectedIndex >= 0
                ? selectedIndex / perPage                       // always show what's being edited
                : Math.max(0, Math.min(page, pages - 1));

        UUID sessionId = holder instanceof PrivateSession ps ? ps.getSessionId() : null;
        GuiHolder gh = new GuiHolder(GuiHolder.Type.TIMELINE)
                .sessionId(sessionId).adminView(adminView).selectedEvent(selectedEventId).page(pg);
        Inventory inv = create(gh, GuiStyle.size("timeline", 54),
                GuiStyle.title("timeline", "%arena%", holder.getArenaName(),
                        "%page%", String.valueOf(pg + 1), "%pages%", String.valueOf(pages)));
        frame(inv);
        // Fill all four content rows first: the strip is rarely exactly full, and the editor row
        // is empty until something is selected — without this the menu reads as a grid of holes.
        fillInteriorRow(inv, 9, accentMaterial());
        fillInteriorRow(inv, 18, accentMaterial());
        fillInteriorRow(inv, 27, accentMaterial());
        fillInteriorRow(inv, 36, accentMaterial());

        boolean editable = isTimelineEditable(holder);
        String lockNote = editable ? "" : "&c✖ The round has started — edits apply from the next one.";

        GuiStyle.place(inv, "timeline.buttons.info");
        GuiStyle.place(inv, "timeline.buttons.add-event", "%locked%", lockNote);

        int start = pg * perPage;
        int end = Math.min(timeline.size(), start + perPage);
        for (int i = start; i < end; i++) {
            SessionSettings.TimelineEntry entry = timeline.get(i);
            TimelineService.Definition def = timelines.definitionFor(entry);
            if (def == null) continue;

            boolean isEnd = def.type() == TimelineService.Type.MATCH_END;
            boolean selected = entry.id().equals(selectedEventId);
            String template = "timeline.items." + (isEnd
                    ? (selected ? "match-end-selected" : "match-end")
                    : (selected ? "event-selected" : "event"));

            ItemStack item = ItemUtil.button(def.icon(),
                    GuiStyle.name(template, "%event%", def.name(),
                            "%time%", TimelineService.format(entry.seconds())),
                    GuiStyle.lore(template, "%event%", def.name(),
                            "%time%", TimelineService.format(entry.seconds()),
                            "%effect%", def.description(),
                            "%index%", String.valueOf(i + 1),
                            "%total%", String.valueOf(timeline.size()),
                            "%origin%", entry.isCustom() ? "&d&oYour own custom event" : ""));
            if (GuiStyle.glint(template)) ItemUtil.glint(item);

            int slot = STRIP_SLOTS[i - start];
            inv.setItem(slot, item);
            gh.mapKeySlot(slot, entry.id());
        }

        if (pg > 0) GuiStyle.place(inv, "timeline.buttons.previous-page", "%target%", String.valueOf(pg));
        if (pg < pages - 1) GuiStyle.place(inv, "timeline.buttons.next-page", "%target%", String.valueOf(pg + 2));

        int events = Math.max(0, timeline.size() - 1);
        GuiStyle.place(inv, "timeline.buttons.summary",
                "%events%", String.valueOf(events),
                "%length%", TimelineService.format(timelines.matchEndSeconds(holder.getSettings())),
                "%custom%", String.valueOf(timelines.customEventCount(holder.getSettings())),
                "%max_custom%", String.valueOf(TimelineService.MAX_CUSTOM_EVENTS),
                "%source%", holder.getSettings().getTimeline() == null
                        ? "&7Server defaults" : "&eCustomized for this match",
                "%backend%", tweaksBackendNote(),
                "%locked%", lockNote);

        // Nothing to clear on a schedule that's only Match End — the button would be a no-op
        // advertising "Remove all 0 events".
        if (events > 0) {
            GuiStyle.place(inv, "timeline.buttons.clear-all", "%events%", String.valueOf(events));
        }

        // Detail card + editor controls — "selected" and "no-selection" share a slot.
        if (selectedEventId != null) {
            SessionSettings.TimelineEntry entry = timeline.get(selectedIndex);
            TimelineService.Definition def = timelines.definitionFor(entry);
            String eventName = def != null ? def.name() : selectedEventId;
            boolean isEnd = def != null && def.type() == TimelineService.Type.MATCH_END;

            GuiStyle.place(inv, "timeline.buttons.selected",
                    "%event%", eventName,
                    "%time%", TimelineService.format(entry.seconds()),
                    "%effect%", def != null ? def.description() : "",
                    "%type%", def != null ? TimelineService.typeName(def.type()) : "?",
                    "%value%", valueOrWholeArena(eventValueLabel(def)));

            GuiStyle.place(inv, "timeline.buttons.minus-minute", "%event%", eventName);
            GuiStyle.place(inv, "timeline.buttons.minus-seconds", "%event%", eventName);
            GuiStyle.place(inv, "timeline.buttons.plus-seconds", "%event%", eventName);
            GuiStyle.place(inv, "timeline.buttons.plus-minute", "%event%", eventName);
            if (!isEnd) {
                GuiStyle.place(inv, "timeline.buttons.delete", "%event%", eventName);
            }
            // Only a host-authored event with a value of its own has anything to re-pick; a
            // catalog entry's semantics belong to config.yml, and the value-less types
            // (fireworks, heal pulse, …) have nothing but their time to change.
            if (entry.isCustom() && def != null && TimelineService.requiresValue(def.type())) {
                GuiStyle.place(inv, "timeline.buttons.edit-value", "%event%", eventName,
                        "%value%", eventValueLabel(def));
            }
            if (def != null && TimelineService.isCustomCreatable(def.type())) {
                GuiStyle.place(inv, "timeline.buttons.duplicate", "%event%", eventName);
            }
        } else {
            GuiStyle.place(inv, "timeline.buttons.no-selection");
        }

        GuiStyle.place(inv, "timeline.buttons.reset");
        GuiStyle.place(inv, "timeline.buttons.back");
        GuiStyle.place(inv, "timeline.buttons.close");
        p.openInventory(inv);
    }

    /**
     * With MBedwarsTweaks driving the timeline, its gen tiers run the schedule and event types
     * that have no gen-tier equivalent (buffs, weather, announcements, …) are skipped at runtime.
     * Say so where a host would otherwise build one and wonder why it never fired.
     */
    private String tweaksBackendNote() {
        return plugin.getTimelineService().isTweaksBackend()
                ? "&c⚠ MBedwarsTweaks runs this server's timeline — events it has no equivalent "
                        + "for (buffs, weather, announcements, …) won't fire."
                : "";
    }

    private static int indexOfEvent(List<SessionSettings.TimelineEntry> timeline, String id) {
        if (id == null) return -1;
        for (int i = 0; i < timeline.size(); i++) {
            if (timeline.get(i).id().equals(id)) return i;
        }
        return -1;
    }

    /**
     * Mirrors {@code GuiListener#timelineEditable} without messaging anyone — used to show the
     * "the round already started" state on the editor's own buttons rather than only saying so
     * after a click that turns out to do nothing.
     */
    private boolean isTimelineEditable(SettingsHolder holder) {
        if (!(holder instanceof PrivateSession session)) return true; // a draft has no round yet
        // Remote-aware on purpose: for a session hosted on another server the local lookup
        // resolves null, and treating that as "editable" would let a mid-round match be edited
        // from the hub — the save would look successful but never apply (the engine snapshots
        // the schedule at round start).
        return ArenaNames.isLobbyStatus(session.getArenaName());
    }

    /**
     * What an event with no value of its own "applies to" — several types simply act on the
     * whole arena, and an "Applies to:" line trailing off into nothing reads like a bug.
     */
    private static String valueOrWholeArena(String label) {
        return label == null || label.isBlank() ? "&7The whole arena" : label;
    }

    /** A human-readable rendering of an event's single type-specific value, or "" if it has none. */
    private static String eventValueLabel(TimelineService.Definition def) {
        if (def == null) return "";
        String value = def.dropTypeId();
        if (value == null || value.isBlank()) return "";
        return switch (def.type()) {
            case TEAM_BUFF -> buffLabel(value);
            case RESOURCE_BURST -> "&f" + currencyLabel(value);
            case SPAWNER_SPEED -> "&f" + currencyLabel(value) + " &7×" + def.multiplier();
            default -> "&f" + value;
        };
    }

    /** "SPEED:1:30" → "Speed II for 30s". */
    private static String buffLabel(String spec) {
        String[] parts = spec.split(":");
        String name = prettyEnumName(parts[0]);
        int amplifier = parts.length > 1 ? parseIntOr(parts[1], 0) : 0;
        int seconds = parts.length > 2 ? parseIntOr(parts[2], 30) : 30;
        return "&f" + name + " " + romanNumeral(amplifier + 1) + " &7for &f" + seconds + "s";
    }

    private static String prettyEnumName(String raw) {
        return TimelineService.pretty(raw);
    }

    private static String romanNumeral(int level) {
        return switch (Math.max(1, Math.min(5, level))) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            default -> "V";
        };
    }

    private static int parseIntOr(String raw, int fallback) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException | NullPointerException e) {
            return fallback;
        }
    }

    // ── Event timeline: Add Event ─────────────────────────────────────────────────

    /**
     * Catalog definitions not currently on this match's timeline — either never scheduled by
     * default ({@code default: false} in config.yml) or previously deleted by the host — plus the
     * entry point into the custom-event wizard for anything the catalog doesn't cover at all.
     */
    public void openTimelineAdd(Player p, SettingsHolder holder, boolean adminView, int page) {
        TimelineService timelines = plugin.getTimelineService();
        java.util.Set<String> scheduled = new java.util.HashSet<>();
        for (SessionSettings.TimelineEntry e : timelines.effectiveTimeline(holder.getSettings())) {
            scheduled.add(e.id());
        }

        List<TimelineService.Definition> available = new ArrayList<>();
        for (String id : timelines.definitionIds()) {
            TimelineService.Definition def = timelines.definition(id);
            if (def != null && def.type() != TimelineService.Type.MATCH_END && !scheduled.contains(id)) {
                available.add(def);
            }
        }

        int perPage = LIST_SLOTS.length;
        int pages = Math.max(1, (int) Math.ceil(available.size() / (double) perPage));
        int pg = Math.max(0, Math.min(page, pages - 1));

        UUID sessionId = holder instanceof PrivateSession ps ? ps.getSessionId() : null;
        GuiHolder gh = new GuiHolder(GuiHolder.Type.TIMELINE_ADD)
                .sessionId(sessionId).adminView(adminView).page(pg);
        Inventory inv = create(gh, GuiStyle.size("timeline-add", 54),
                GuiStyle.title("timeline-add", "%arena%", holder.getArenaName(),
                        "%page%", String.valueOf(pg + 1), "%pages%", String.valueOf(pages)));
        frame(inv);

        int start = pg * perPage;
        int end = Math.min(available.size(), start + perPage);
        for (int i = start; i < end; i++) {
            TimelineService.Definition def = available.get(i);
            int slot = LIST_SLOTS[i - start];
            inv.setItem(slot, ItemUtil.button(def.icon(),
                    GuiStyle.name("timeline-add.items.event", "%event%", def.name()),
                    GuiStyle.lore("timeline-add.items.event",
                            "%event%", def.name(),
                            "%effect%", def.description(),
                            "%time%", TimelineService.format(def.defaultSeconds()))));
            gh.mapKeySlot(slot, def.id());
        }
        if (available.isEmpty()) {
            GuiStyle.place(inv, "timeline-add.buttons.empty", "%reason%",
                    "&8Every catalog event is already scheduled.");
        }

        if (pg > 0) GuiStyle.place(inv, "timeline-add.buttons.previous-page", "%target%", String.valueOf(pg));
        if (pg < pages - 1) GuiStyle.place(inv, "timeline-add.buttons.next-page", "%target%", String.valueOf(pg + 2));

        int used = timelines.customEventCount(holder.getSettings());
        GuiStyle.place(inv, "timeline-add.buttons.create-custom",
                "%used%", String.valueOf(used),
                "%max%", String.valueOf(TimelineService.MAX_CUSTOM_EVENTS),
                "%full%", used >= TimelineService.MAX_CUSTOM_EVENTS
                        ? "&c✖ You've used every custom event slot." : "");
        GuiStyle.place(inv, "timeline-add.buttons.back");
        p.openInventory(inv);
    }

    // ── Event timeline: custom-event wizard ───────────────────────────────────────

    /**
     * Step 1 — pick what the event should DO. Only the self-contained types can be authored
     * from scratch (see {@link TimelineService#isCustomCreatable}); the rest need
     * admin-configured semantics and live in the catalog instead.
     *
     * @param editingEvent when non-null, the wizard is re-authoring that existing custom entry
     *                     rather than creating a new one.
     */
    public void openTimelineCustomType(Player p, SettingsHolder holder, boolean adminView, String editingEvent) {
        GuiHolder gh = wizardHolder(GuiHolder.Type.TIMELINE_CUSTOM_TYPE, holder, adminView, null)
                .editingEvent(editingEvent);
        Inventory inv = create(gh, GuiStyle.size("timeline-custom-type", 54),
                GuiStyle.title("timeline-custom-type", "%arena%", holder.getArenaName()));
        frame(inv);

        GuiStyle.place(inv, "timeline-custom-type.buttons.info", "%backend%", tweaksBackendNote());

        int i = 0;
        for (TimelineService.Type type : TimelineService.Type.values()) {
            if (!TimelineService.isCustomCreatable(type)) continue;
            if (i >= LIST_SLOTS.length) break;
            int slot = LIST_SLOTS[i++];
            inv.setItem(slot, ItemUtil.button(TimelineService.typeIcon(type),
                    GuiStyle.name("timeline-custom-type.items.type", "%type%", TimelineService.typeName(type)),
                    GuiStyle.lore("timeline-custom-type.items.type",
                            "%type%", TimelineService.typeName(type),
                            "%effect%", TimelineService.typeDescription(type),
                            "%needs_value%", TimelineService.requiresValue(type)
                                    ? "&8Next: pick what it applies to" : "&8Next: pick when it fires")));
            gh.mapKeySlot(slot, type.name());
        }

        GuiStyle.place(inv, "timeline-custom-type.buttons.back");
        p.openInventory(inv);
    }

    /**
     * Step 2 — pick the value the chosen type acts on (a resource, an effect, a weather/time
     * setting). Types that need no value skip straight past this; the announcement's free text
     * is typed into an anvil instead ({@link #openTimelineCustomText}).
     */
    public void openTimelineCustomValue(Player p, SettingsHolder holder, boolean adminView, GuiHolder state) {
        TimelineService.Type type = parseType(state.customType());
        if (type == null) {
            openTimelineCustomType(p, holder, adminView, state.editingEvent());
            return;
        }

        GuiHolder gh = wizardHolder(GuiHolder.Type.TIMELINE_CUSTOM_VALUE, holder, adminView, state);
        Inventory inv = create(gh, GuiStyle.size("timeline-custom-value", 54),
                GuiStyle.title("timeline-custom-value", "%type%", TimelineService.typeName(type)));
        frame(inv);

        GuiStyle.place(inv, "timeline-custom-value.buttons.info",
                "%type%", TimelineService.typeName(type),
                "%effect%", TimelineService.typeDescription(type));

        int i = 0;
        for (ValueOption option : valueOptions(type)) {
            if (i >= LIST_SLOTS.length) break;
            int slot = LIST_SLOTS[i++];
            inv.setItem(slot, ItemUtil.button(option.icon(),
                    GuiStyle.name("timeline-custom-value.items.option", "%option%", option.label()),
                    GuiStyle.lore("timeline-custom-value.items.option",
                            "%option%", option.label(), "%detail%", option.detail())));
            gh.mapKeySlot(slot, option.value());
        }
        if (i == 0) {
            GuiStyle.place(inv, "timeline-custom-value.buttons.empty", "%reason%",
                    "&8Nothing on this server can be picked for that event type.");
        }

        GuiStyle.place(inv, "timeline-custom-value.buttons.back");
        p.openInventory(inv);
    }

    /** Step 2b — the anvil where an announcement event's message is typed. */
    public void openTimelineCustomText(Player p, SettingsHolder holder, boolean adminView, GuiHolder state) {
        GuiHolder gh = wizardHolder(GuiHolder.Type.TIMELINE_CUSTOM_TEXT, holder, adminView, state);

        String suggested = state.customValue() == null || state.customValue().isBlank()
                ? GuiStyle.rawString("timeline-custom-text.suggested", "Good luck!")
                : state.customValue();
        openAnvilPrompt(p, gh,
                GuiStyle.title("timeline-custom-text", "%arena%", holder.getArenaName()),
                GuiStyle.item("timeline-custom-text.buttons.icon", "%text%", suggested));
    }

    /**
     * Step 3 — when it fires, plus the two extra dials a buff needs (strength and duration),
     * then confirm. Also the "pick a time first" landing spot for a shift-clicked catalog event,
     * in which case the holder carries a {@code catalogId} instead of a type/value.
     */
    public void openTimelineCustomTime(Player p, SettingsHolder holder, boolean adminView, GuiHolder state) {
        TimelineService timelines = plugin.getTimelineService();
        TimelineService.Type type = parseType(state.customType());
        TimelineService.Definition catalog = state.catalogId() == null
                ? null : timelines.definition(state.catalogId());
        if (type == null && catalog == null) {
            openTimelineCustomType(p, holder, adminView, state.editingEvent());
            return;
        }

        GuiHolder gh = wizardHolder(GuiHolder.Type.TIMELINE_CUSTOM_TIME, holder, adminView, state);
        // Clamp on the way in, so a match-end change made since the previous step can never
        // leave the wizard pointing past the end of the match.
        int seconds = timelines.clampEventTime(holder.getSettings(), state.customSeconds());
        gh.customSeconds(seconds);

        String label = catalog != null ? catalog.name() : TimelineService.typeName(type);
        Inventory inv = create(gh, GuiStyle.size("timeline-custom-time", 27),
                GuiStyle.title("timeline-custom-time", "%event%", label));
        frame(inv);
        fillInteriorRow(inv, 9, accentMaterial());
        fillInteriorRow(inv, 18, accentMaterial());

        String valueLabel = valueOrWholeArena(catalog != null
                ? eventValueLabel(catalog)
                : describeWizardValue(type, state));
        GuiStyle.place(inv, "timeline-custom-time.buttons.display",
                "%event%", label,
                "%time%", TimelineService.format(seconds),
                "%value%", valueLabel,
                "%effect%", catalog != null ? catalog.description() : TimelineService.typeDescription(type),
                "%length%", TimelineService.format(timelines.matchEndSeconds(holder.getSettings())));

        GuiStyle.place(inv, "timeline-custom-time.buttons.minus-minute");
        GuiStyle.place(inv, "timeline-custom-time.buttons.minus-seconds");
        GuiStyle.place(inv, "timeline-custom-time.buttons.plus-seconds");
        GuiStyle.place(inv, "timeline-custom-time.buttons.plus-minute");

        // A buff is the one type with more to say than "what" and "when".
        if (type == TimelineService.Type.TEAM_BUFF) {
            GuiStyle.place(inv, "timeline-custom-time.buttons.amplifier",
                    "%current%", romanNumeral(state.customAmplifier() + 1));
            GuiStyle.place(inv, "timeline-custom-time.buttons.duration",
                    "%current%", state.customDuration() + "s");
        }

        GuiStyle.place(inv, "timeline-custom-time.buttons.confirm",
                "%event%", label,
                "%time%", TimelineService.format(seconds),
                "%verb%", state.editingEvent() != null ? "Save changes" : "Add to timeline");
        GuiStyle.place(inv, "timeline-custom-time.buttons.back");
        p.openInventory(inv);
    }

    /** Carries the half-built event from one wizard step to the next. */
    private GuiHolder wizardHolder(GuiHolder.Type type, SettingsHolder holder, boolean adminView, GuiHolder from) {
        UUID sessionId = holder instanceof PrivateSession ps ? ps.getSessionId() : null;
        GuiHolder gh = new GuiHolder(type).sessionId(sessionId).adminView(adminView);
        if (from != null) {
            gh.customType(from.customType())
                    .customValue(from.customValue())
                    .customSeconds(from.customSeconds())
                    .customAmplifier(from.customAmplifier())
                    .customDuration(from.customDuration())
                    .catalogId(from.catalogId())
                    .editingEvent(from.editingEvent());
        }
        return gh;
    }

    static TimelineService.Type parseType(String name) {
        if (name == null) return null;
        try {
            return TimelineService.Type.valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** How the wizard's current value reads on the confirm screen. */
    private String describeWizardValue(TimelineService.Type type, GuiHolder state) {
        String value = state.customValue();
        if (type == null || !TimelineService.requiresValue(type)) return "";
        if (value == null || value.isBlank()) return "&8—";
        return switch (type) {
            case TEAM_BUFF -> "&f" + prettyEnumName(value) + " "
                    + romanNumeral(state.customAmplifier() + 1)
                    + " &7for &f" + state.customDuration() + "s";
            case RESOURCE_BURST -> "&f" + currencyLabel(value);
            case WEATHER_CHANGE, TIME_CHANGE -> "&f" + prettyEnumName(value);
            default -> "&f" + value;
        };
    }

    /** One pickable value on the wizard's step-2 grid. */
    private record ValueOption(String value, String label, String detail, Material icon) {}

    /** The values that make sense for {@code type} on this server, right now. */
    private List<ValueOption> valueOptions(TimelineService.Type type) {
        List<ValueOption> out = new ArrayList<>();
        switch (type) {
            case RESOURCE_BURST -> {
                for (de.marcely.bedwars.api.game.spawner.DropType drop : BedwarsAPI.getGameAPI().getDropTypes()) {
                    Material icon = Material.IRON_INGOT;
                    ItemStack[] materials = drop.getDroppingMaterials();
                    if (materials != null && materials.length > 0 && materials[0] != null) {
                        icon = materials[0].getType();
                    }
                    out.add(new ValueOption(drop.getId(), plainName(drop.getName()),
                            "&7One extra drop from every &f" + plainName(drop.getName()) + "&7 generator.", icon));
                }
            }
            case TEAM_BUFF -> {
                for (BuffOption buff : BUFF_OPTIONS) {
                    String resolved = buff.resolve();
                    if (resolved == null) continue; // this server's Bukkit doesn't know that effect
                    out.add(new ValueOption(resolved, buff.label(),
                            "&7Everyone in the arena gets it.", buff.icon()));
                }
            }
            case WEATHER_CHANGE -> {
                out.add(new ValueOption("CLEAR", "Clear Skies", "&7Stops any rain.", Material.SUNFLOWER));
                out.add(new ValueOption("RAINING", "Rain", "&7Starts raining.", Material.WATER_BUCKET));
                out.add(new ValueOption("UNTOUCHED", "Server Default",
                        "&7Hands the weather back to the world.", Material.BARRIER));
            }
            case TIME_CHANGE -> {
                out.add(new ValueOption("NOON", "Noon", "&7Broad daylight.", Material.CLOCK));
                out.add(new ValueOption("SUNSET", "Sunset", "&7Golden hour.", Material.ORANGE_DYE));
                out.add(new ValueOption("NIGHT", "Night", "&7Lights out.", Material.BLACK_DYE));
                out.add(new ValueOption("UNTOUCHED", "Server Default",
                        "&7Hands the time back to the world.", Material.BARRIER));
            }
            default -> { /* no value to pick */ }
        }
        return out;
    }

    /**
     * A buff the wizard offers. Bukkit renamed several potion effects over the years, so each
     * entry lists the names it may go by and resolves to whichever one this server knows —
     * an entry that resolves to none is simply not offered.
     */
    private record BuffOption(String label, Material icon, String... names) {
        String resolve() {
            for (String name : names) {
                if (org.bukkit.potion.PotionEffectType.getByName(name) != null) return name;
            }
            return null;
        }
    }

    private static final List<BuffOption> BUFF_OPTIONS = List.of(
            new BuffOption("Speed", Material.SUGAR, "SPEED"),
            new BuffOption("Jump Boost", Material.RABBIT_FOOT, "JUMP_BOOST", "JUMP"),
            new BuffOption("Regeneration", Material.GHAST_TEAR, "REGENERATION"),
            new BuffOption("Strength", Material.BLAZE_POWDER, "STRENGTH", "INCREASE_DAMAGE"),
            new BuffOption("Resistance", Material.IRON_CHESTPLATE, "RESISTANCE", "DAMAGE_RESISTANCE"),
            new BuffOption("Fire Resistance", Material.MAGMA_CREAM, "FIRE_RESISTANCE"),
            new BuffOption("Haste", Material.GOLDEN_PICKAXE, "HASTE", "FAST_DIGGING"),
            new BuffOption("Absorption", Material.GOLDEN_APPLE, "ABSORPTION"),
            new BuffOption("Night Vision", Material.GOLDEN_CARROT, "NIGHT_VISION"),
            new BuffOption("Water Breathing", Material.PUFFERFISH, "WATER_BREATHING"),
            new BuffOption("Invisibility", Material.GLASS_BOTTLE, "INVISIBILITY"),
            new BuffOption("Slowness", Material.SOUL_SAND, "SLOWNESS", "SLOW"),
            new BuffOption("Weakness", Material.FERMENTED_SPIDER_EYE, "WEAKNESS"),
            new BuffOption("Blindness", Material.INK_SAC, "BLINDNESS"));

    // ── Shop configuration ────────────────────────────────────────────────────────

    public void openShopPages(Player p, SettingsHolder holder, boolean adminView) {
        UUID sessionId = holder instanceof PrivateSession ps ? ps.getSessionId() : null;
        GuiHolder gh = new GuiHolder(GuiHolder.Type.SHOP_PAGES)
                .sessionId(sessionId).adminView(adminView);
        Inventory inv = create(gh, GuiStyle.size("shop-pages", 36),
                GuiStyle.title("shop-pages", "%arena%", holder.getArenaName()));
        frame(inv);

        GuiStyle.place(inv, "shop-pages.buttons.info");

        List<ShopPage> pages = new ArrayList<>(BedwarsAPI.getGameAPI().getShopPages());
        for (int i = 0; i < pages.size() && i < STRIP_SLOTS.length; i++) {
            ShopPage page = pages.get(i);
            List<String> itemIds = page.getItems().stream().map(ShopItem::getId).toList();
            long disabled = holder.getSettings().countDisabled(itemIds);

            ItemStack icon = ItemUtil.icon(page.getIcon(), Material.CHEST,
                    GuiStyle.name("shop-pages.items.page", "%page%", plainName(page.getDisplayName())),
                    GuiStyle.lore("shop-pages.items.page",
                            "%page%", plainName(page.getDisplayName()),
                            "%total%", String.valueOf(itemIds.size()),
                            "%disabled%", String.valueOf(disabled)));

            int slot = STRIP_SLOTS[i];
            inv.setItem(slot, icon);
            gh.mapKeySlot(slot, page.getName());
        }

        GuiStyle.place(inv, "shop-pages.buttons.reset");
        GuiStyle.place(inv, "shop-pages.buttons.back");
        p.openInventory(inv);
    }

    public void openShopItems(Player p, SettingsHolder holder, boolean adminView, String pageName, int page) {
        ShopPage shopPage = findShopPage(pageName);
        if (shopPage == null) {
            openShopPages(p, holder, adminView);
            return;
        }

        List<ShopItem> items = new ArrayList<>(shopPage.getItems());
        int perPage = LIST_SLOTS.length;
        int pages = Math.max(1, (int) Math.ceil(items.size() / (double) perPage));
        int pg = Math.max(0, Math.min(page, pages - 1));

        UUID sessionId = holder instanceof PrivateSession ps ? ps.getSessionId() : null;
        GuiHolder gh = new GuiHolder(GuiHolder.Type.SHOP_ITEMS)
                .sessionId(sessionId).adminView(adminView)
                .shopPage(pageName).page(pg);
        Inventory inv = create(gh, GuiStyle.size("shop-items", 54),
                GuiStyle.title("shop-items",
                        "%arena%", holder.getArenaName(),
                        "%page%", plainName(shopPage.getDisplayName()),
                        "%pagenum%", String.valueOf(pg + 1),
                        "%pages%", String.valueOf(pages)));
        frame(inv);

        int start = pg * perPage;
        int end = Math.min(items.size(), start + perPage);
        for (int i = start; i < end; i++) {
            ShopItem item = items.get(i);
            int slot = LIST_SLOTS[i - start];
            inv.setItem(slot, shopItemEntry(holder, item));
            gh.mapKeySlot(slot, item.getId());
        }

        if (pg > 0) GuiStyle.place(inv, "shop-items.buttons.previous-page", "%target%", String.valueOf(pg));
        if (pg < pages - 1) GuiStyle.place(inv, "shop-items.buttons.next-page", "%target%", String.valueOf(pg + 2));
        GuiStyle.place(inv, "shop-items.buttons.back");
        p.openInventory(inv);
    }

    public void openShopPrice(Player p, SettingsHolder holder, boolean adminView,
                              String pageName, String itemId) {
        ShopItem item = BedwarsAPI.getGameAPI().getShopItemById(itemId);
        if (item == null) {
            openShopItems(p, holder, adminView, pageName, 0);
            return;
        }

        SessionSettings.ShopOverride override = holder.getSettings().getShopOverride(itemId);
        int amount;
        String currencyId;
        if (override != null && override.hasPriceOverride()) {
            amount = override.getPrice();
            currencyId = override.getCurrency();
        } else {
            amount = defaultPriceAmount(item);
            currencyId = defaultPriceCurrency(item);
        }
        String currencyName = currencyLabel(currencyId);

        UUID sessionId = holder instanceof PrivateSession ps ? ps.getSessionId() : null;
        GuiHolder gh = new GuiHolder(GuiHolder.Type.SHOP_PRICE)
                .sessionId(sessionId).adminView(adminView)
                .shopPage(pageName).shopItem(itemId);
        Inventory inv = create(gh, GuiStyle.size("shop-price", 45),
                GuiStyle.title("shop-price", "%item%", plainName(item.getDisplayName())));
        frame(inv);

        // The edited item keeps its own icon; name/lore/glint come from the template.
        int displaySlot = GuiStyle.slot("shop-price.buttons.display");
        if (displaySlot >= 0 && displaySlot < inv.getSize()) {
            ItemStack icon = ItemUtil.icon(item.getIcon(), Material.CHEST,
                    GuiStyle.name("shop-price.buttons.display", "%item%", plainName(item.getDisplayName())),
                    GuiStyle.lore("shop-price.buttons.display",
                            "%item%", plainName(item.getDisplayName()),
                            "%default_price%", describeDefaultPrice(item)));
            if (GuiStyle.glint("shop-price.buttons.display")) ItemUtil.glint(icon);
            inv.setItem(displaySlot, icon);
        }

        GuiStyle.place(inv, "shop-price.buttons.currency", "%currency%", currencyName);
        GuiStyle.place(inv, "shop-price.buttons.reset");
        GuiStyle.place(inv, "shop-price.buttons.minus-ten");
        GuiStyle.place(inv, "shop-price.buttons.minus-one");
        GuiStyle.place(inv, "shop-price.buttons.amount",
                "%amount%", String.valueOf(amount), "%currency%", currencyName);
        GuiStyle.place(inv, "shop-price.buttons.plus-one");
        GuiStyle.place(inv, "shop-price.buttons.plus-ten");
        GuiStyle.place(inv, "shop-price.buttons.back");
        p.openInventory(inv);
    }

    /** The item entry shown in the shop-items grid, styled by its enabled/disabled state. */
    private ItemStack shopItemEntry(SettingsHolder holder, ShopItem item) {
        SessionSettings.ShopOverride override = holder.getSettings().getShopOverride(item.getId());
        boolean disabled = override != null && override.isDisabled();
        boolean customPrice = override != null && override.hasPriceOverride();

        String template = "shop-items.items." + (disabled ? "item-disabled" : "item-enabled");
        String name = plainName(item.getDisplayName());
        String price = customPrice
                ? override.getPrice() + " " + currencyLabel(override.getCurrency())
                : describeDefaultPrice(item);

        String itemName = GuiStyle.name(template, "%item%", name);
        List<String> lore = GuiStyle.lore(template,
                "%item%", name,
                "%price%", price,
                "%custom_note%", customPrice ? "&7(custom price — default: &f"
                        + describeDefaultPrice(item) + "&7)" : "");

        // Disabled entries render as red dye — same signal players see in the in-game shop.
        ItemStack icon = disabled
                ? ItemUtil.button(Material.RED_DYE, itemName, colored(lore))
                : ItemUtil.icon(item.getIcon(), Material.CHEST, itemName, lore);
        if (GuiStyle.glint(template)) ItemUtil.glint(icon);
        return icon;
    }

    // ── Shop helpers (also used by GuiListener) ───────────────────────────────────

    static ShopPage findShopPage(String name) {
        for (ShopPage page : BedwarsAPI.getGameAPI().getShopPages()) {
            if (page.getName().equalsIgnoreCase(name)) return page;
        }
        return null;
    }

    static int defaultPriceAmount(ShopItem item) {
        List<? extends ShopPrice> prices = item.getPrices();
        return prices.isEmpty() ? 1 : Math.max(1, prices.get(0).getGeneralAmount());
    }

    static String defaultPriceCurrency(ShopItem item) {
        for (ShopPrice price : item.getPrices()) {
            if (price instanceof de.marcely.bedwars.api.game.shop.price.SpawnerItemShopPrice sp) {
                return sp.getDropType().getId();
            }
        }
        var types = BedwarsAPI.getGameAPI().getDropTypes();
        return types.isEmpty() ? "iron" : types.iterator().next().getId();
    }

    static String currencyLabel(String dropTypeId) {
        var type = BedwarsAPI.getGameAPI().getDropTypeById(dropTypeId);
        return type != null ? plainName(type.getName()) : dropTypeId;
    }

    static String describeDefaultPrice(ShopItem item) {
        List<? extends ShopPrice> prices = item.getPrices();
        if (prices.isEmpty()) return "free";
        StringBuilder sb = new StringBuilder();
        for (ShopPrice price : prices) {
            if (sb.length() > 0) sb.append(" + ");
            sb.append(price.getGeneralAmount()).append(' ').append(plainName(price.getDisplayName()));
        }
        return sb.toString();
    }

    private static String plainName(String display) {
        String plain = ChatColor.stripColor(ItemUtil.color(display == null ? "" : display));
        return plain == null ? "" : plain;
    }

    // ── Team management ───────────────────────────────────────────────────────────

    public void openTeamSelect(Player p, PrivateSession session, boolean adminView) {
        GuiHolder holder = new GuiHolder(GuiHolder.Type.TEAM_SELECT)
                .sessionId(session.getSessionId()).adminView(adminView);
        Inventory inv = create(holder, GuiStyle.size("team-select", 54),
                GuiStyle.title("team-select", "%arena%", session.getArenaName()));
        frame(inv);

        Arena arena = BedwarsAPI.getGameAPI().getArenaByExactName(session.getArenaName());
        if (arena == null || !arena.exists() || !arena.getStatus().isLobby()) {
            GuiStyle.place(inv, "team-select.buttons.unavailable", "%reason%",
                    (arena == null || !arena.exists())
                            ? "&7This arena isn't loaded on this server."
                            : "&7Teams can only be managed while the arena is in its lobby.");
            GuiStyle.place(inv, "team-select.buttons.back");
            p.openInventory(inv);
            return;
        }

        boolean locked = session.getSettings().isTeamsLocked();
        List<Team> teams = new ArrayList<>(arena.getEnabledTeams());
        teams.sort(Comparator.comparing(Team::name));

        for (int i = 0; i < teams.size() && i < LIST_SLOTS.length; i++) {
            Team team = teams.get(i);
            int slot = LIST_SLOTS[i];
            List<Player> members = arena.getPlayersInTeam(team);
            int cap = arena.getPlayersPerTeam();

            StringBuilder roster = new StringBuilder();
            if (members.isEmpty()) {
                roster.append("&8No one on this team yet.");
            } else {
                for (Player m : members) {
                    if (roster.length() > 0) roster.append('\n');
                    roster.append("&f● &7").append(m.getName());
                }
            }

            inv.setItem(slot, ItemUtil.icon(team.newItemInstance(), Material.WHITE_WOOL,
                    GuiStyle.name("team-select.items.team", "%team%", team.getDisplayName()),
                    GuiStyle.lore("team-select.items.team",
                            "%team%", team.getDisplayName(),
                            "%current%", String.valueOf(members.size()),
                            "%cap%", String.valueOf(cap),
                            "%roster%", roster.toString(),
                            "%hint%", members.size() >= cap
                                    ? "&cTeam is full." : "&8▶ Click to move players here")));
            holder.mapTeamSlot(slot, team);
        }

        if (teams.isEmpty()) GuiStyle.place(inv, "team-select.buttons.empty");
        else GuiStyle.place(inv, "team-select.buttons.distribute");

        // Lock / Unlock are two looks of one control and share a slot (see guis.yml).
        GuiStyle.place(inv, locked ? "team-select.buttons.unlock-teams" : "team-select.buttons.lock-teams");

        // The same Team Size editor the Arena Modifiers hub offers, reachable from where a host
        // actually notices the cap is wrong. Its own screen explains the lobby-only rule.
        GuiStyle.place(inv, "team-select.buttons.team-size",
                "%amount%", String.valueOf(arena.getPlayersPerTeam()),
                "%availability%", plugin.canChangeTeamSize(arena)
                        ? "&8▶ Click to change"
                        : "&cLocked — the match is running.");

        GuiStyle.place(inv, "team-select.buttons.back");
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
                ? GuiStyle.rawString("team-players.title-overflow", "&c&lUn-select a player first!")
                : GuiStyle.title("team-players",
                        "%team%", teamName,
                        "%current%", arena != null ? String.valueOf(arena.getPlayersInTeam(team).size()) : "?",
                        "%cap%", arena != null ? String.valueOf(arena.getPlayersPerTeam()) : "?");
        Inventory inv = create(holder, GuiStyle.size("team-players", 54), title);
        frame(inv);

        if (arena == null || !arena.exists()) {
            GuiStyle.place(inv, "team-players.buttons.unavailable");
            GuiStyle.place(inv, "team-players.buttons.back");
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
            String template = "team-players.items." + (isSelected ? "candidate-selected" : "candidate");

            inv.setItem(slot, ItemUtil.head(Bukkit.getOfflinePlayer(target.getUniqueId()),
                    GuiStyle.name(template, "%player%", target.getName()),
                    GuiStyle.lore(template,
                            "%player%", target.getName(),
                            "%team%", currentTeam != null ? currentTeam.getDisplayName() : "&8None"),
                    GuiStyle.glint(template)));
            holder.mapSlot(slot, target.getUniqueId());
        }

        if (candidates.isEmpty()) GuiStyle.place(inv, "team-players.buttons.empty");

        GuiStyle.place(inv, "team-players.buttons.remaining",
                "%remaining%", String.valueOf(remaining), "%cap%", String.valueOf(cap));

        if (!selected.isEmpty()) {
            GuiStyle.place(inv, "team-players.buttons.confirm",
                    "%count%", String.valueOf(selected.size()),
                    "%team%", team.getDisplayName());
        }

        GuiStyle.place(inv, "team-players.buttons.back");
        p.openInventory(inv);
    }

    // ── Builder ───────────────────────────────────────────────────────────────────

    public void openBuilder(Player p) {
        DraftPrivateMatch d = drafts.getOrCreate(p.getUniqueId());
        GuiHolder holder = new GuiHolder(GuiHolder.Type.BUILDER);
        Inventory inv = create(holder, GuiStyle.size("builder", 27), GuiStyle.title("builder"));
        frame(inv);

        if (d.isPartyBlocked()) {
            GuiStyle.place(inv, d.getBlockReason() == DraftPrivateMatch.BlockReason.MEMBER_HOSTING
                    ? "builder.buttons.member-hosting-blocked" : "builder.buttons.party-blocked");
            GuiStyle.place(inv, "builder.buttons.back");
            GuiStyle.place(inv, "builder.buttons.close");
            p.openInventory(inv);
            return;
        }

        accentDividers(inv, 11, 13, 15);
        GuiStyle.place(inv, "builder.buttons.select-map",
                "%map%", d.getArenaName() == null ? "&cNot selected" : "&a" + d.getArenaName());

        boolean isParty = d.getJoinPolicy() == JoinPolicy.PARTY;
        GuiStyle.place(inv, "builder.buttons.policy",
                "%policy%", isParty ? "&bParty Only" : "&dJoin Code",
                "%policy_desc%", isParty
                        ? "&7Only your party members can join."
                        : "&7Players join with &f/ea join <code>",
                "%policy_reason%", isParty ? "&8(You lead a party)" : "&8(You aren't leading a party)");

        if (d.getJoinPolicy() == JoinPolicy.CODE) {
            GuiStyle.place(inv, d.isPublic() ? "builder.buttons.public-on" : "builder.buttons.public-off",
                    "%code%", d.getJoinCode() == null ? "—" : d.getJoinCode());
        } else {
            setAccentPane(inv, GuiStyle.slot("builder.buttons.public-on"));
        }

        boolean ready = d.isReadyToCreate();
        GuiStyle.place(inv, ready ? "builder.buttons.create-ready" : "builder.buttons.create-not-ready",
                "%map%", d.getArenaName() == null ? "?" : d.getArenaName());

        if (d.getArenaName() != null) {
            GuiStyle.place(inv, "builder.buttons.arena-settings");
        }

        GuiStyle.place(inv, "builder.buttons.back");
        GuiStyle.place(inv, "builder.buttons.close");
        p.openInventory(inv);
    }

    // ── Arena selector ────────────────────────────────────────────────────────────

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
        Inventory inv = create(holder, GuiStyle.size("arena-select", 54),
                GuiStyle.title("arena-select",
                        "%page%", String.valueOf(pg + 1), "%pages%", String.valueOf(pages)));
        frame(inv);

        int start = pg * perPage;
        int end = Math.min(entries.size(), start + perPage);
        for (int i = start; i < end; i++) {
            ArenaEntry entry = entries.get(i);
            int slot = LIST_SLOTS[i - start];
            boolean reserved = sessions.isArenaReserved(entry.name(), p.getUniqueId());
            String template = "arena-select.items." + (reserved ? "arena-reserved" : "arena");
            Material fallback = reserved ? Material.GRAY_CONCRETE : Material.GRASS_BLOCK;

            inv.setItem(slot, ItemUtil.icon(entry.icon(), fallback,
                    GuiStyle.name(template, "%arena%", entry.name()),
                    GuiStyle.lore(template,
                            "%arena%", entry.name(),
                            "%location%", entry.remote() ? "Remote" : "Local",
                            "%teams%", String.valueOf(entry.teamCount()),
                            "%per_team%", String.valueOf(entry.playersPerTeam()))));
            // Slot -> canonical name, so the click handler resolves the arena from this map
            // instead of parsing the clicked item's display text — which the "no arenas match"
            // empty-state pane (below) would otherwise satisfy just as well as a real entry.
            holder.mapKeySlot(slot, entry.name());
        }

        if (entries.isEmpty()) {
            GuiStyle.place(inv, "arena-select.buttons.empty", "%reason%",
                    all.isEmpty() ? "&8There are no BedWars arenas to reserve."
                            : "&8Try clearing a filter below.");
        }

        if (pg > 0) GuiStyle.place(inv, "arena-select.buttons.previous-page", "%target%", String.valueOf(pg));
        if (pg < pages - 1) GuiStyle.place(inv, "arena-select.buttons.next-page", "%target%", String.valueOf(pg + 2));

        GuiStyle.place(inv, "arena-select.buttons.team-filter",
                "%current%", teamFilter <= 0 ? "Any" : String.valueOf(teamFilter),
                "%hint%", teamOptions.isEmpty() ? "&8No arena data available" : "&8▶ Click to cycle");
        GuiStyle.place(inv, "arena-select.buttons.players-filter",
                "%current%", playersPerTeamFilter <= 0 ? "Any" : String.valueOf(playersPerTeamFilter),
                "%hint%", ppOptions.isEmpty() ? "&8No arena data available" : "&8▶ Click to cycle");

        GuiStyle.place(inv, "arena-select.buttons.back");
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

    // ── Help ──────────────────────────────────────────────────────────────────────

    public void openHelp(Player p) {
        GuiHolder holder = new GuiHolder(GuiHolder.Type.HELP);
        Inventory inv = create(holder, GuiStyle.size("help", 27), GuiStyle.title("help"));
        frame(inv);

        GuiStyle.place(inv, "help.buttons.cmd-menu");
        GuiStyle.place(inv, "help.buttons.cmd-create");
        GuiStyle.place(inv, "help.buttons.cmd-list");
        GuiStyle.place(inv, "help.buttons.cmd-join");
        GuiStyle.place(inv, "help.buttons.cmd-start");
        GuiStyle.place(inv, "help.buttons.cmd-end");
        GuiStyle.place(inv, "help.buttons.cmd-summon");
        GuiStyle.place(inv, "help.buttons.cmd-quick");
        GuiStyle.place(inv, "help.buttons.cmd-timeline");
        GuiStyle.place(inv, "help.buttons.cmd-shop");
        GuiStyle.place(inv, "help.buttons.cmd-preset");
        GuiStyle.place(inv, "help.buttons.cmd-team");
        GuiStyle.place(inv, "help.buttons.cmd-access");
        GuiStyle.place(inv, "help.buttons.back");
        p.openInventory(inv);
    }

    // ── Item / layout helpers ─────────────────────────────────────────────────────

    private ItemStack sessionItem(String menu, PrivateSession session, boolean adminView) {
        List<String> lore = new ArrayList<>(GuiStyle.lore(menu + ".items.session",
                "%arena%", session.getArenaName(),
                "%owner%", ownerName(session)));
        lore.addAll(ArenaStatusView.lore(session));
        lore.addAll(GuiStyle.lore("arena-list.items.session-footer"));

        String name = GuiStyle.name(menu + ".items.session", "%arena%", session.getArenaName());
        if (adminView && session.getOwner() != null) {
            return ItemUtil.head(Bukkit.getOfflinePlayer(session.getOwner()), name, colored(lore));
        }
        return ItemUtil.button(statusMaterial(session), name, colored(lore));
    }

    private static List<String> colored(List<String> lines) {
        List<String> out = new ArrayList<>(lines.size());
        for (String l : lines) out.add(ItemUtil.color(l));
        return out;
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
            // A status constant added by a future MBedwars must not throw mid-render — that
            // would break the initial open and be silently swallowed on every refresh.
            default -> Material.FILLED_MAP;
        };
    }

    private String ownerName(PrivateSession session) {
        if (session.getOwner() == null) return "?";
        return ItemUtil.offlineName(session.getOwner(), "?");
    }

    private String limitLabel(int limit) {
        return limit >= Integer.MAX_VALUE ? "∞" : String.valueOf(limit);
    }

    private static String safeCode(PrivateSession session) {
        return session.getJoinCode() == null ? "—" : session.getJoinCode();
    }

    private Inventory create(GuiHolder holder, int size, String title) {
        Inventory inv = Bukkit.createInventory(holder, size, ItemUtil.color(title));
        holder.setInventory(inv);
        return inv;
    }

    /** Frames the border slots of any row-multiple inventory with the configured pane. */
    private void frame(Inventory inv) {
        int size = inv.getSize();
        int rows = size / 9;
        ItemStack pane = ItemUtil.pane(GuiStyle.material("global.border-material",
                Material.GRAY_STAINED_GLASS_PANE));
        for (int i = 0; i < size; i++) {
            int r = i / 9, c = i % 9;
            if (r == 0 || r == rows - 1 || c == 0 || c == 8) inv.setItem(i, pane);
        }
    }

    private void setAccentPane(Inventory inv, int slot) {
        if (slot < 0 || slot >= inv.getSize()) return;
        inv.setItem(slot, ItemUtil.pane(GuiStyle.material("global.accent-material",
                Material.CYAN_STAINED_GLASS_PANE)));
    }

    /** Purely decorative accent panes at each of the given slots — call before placing buttons. */
    private void accentDividers(Inventory inv, int... slots) {
        for (int slot : slots) setAccentPane(inv, slot);
    }

    /**
     * Fills a hub row's 7 interior slots (between the frame's border columns) with one pane,
     * so buttons placed over some of them afterwards read as an evenly-spaced, gap-free row.
     */
    private void fillInteriorRow(Inventory inv, int rowFirstSlot, Material mat) {
        ItemStack pane = ItemUtil.pane(mat);
        for (int col = 1; col <= 7; col++) inv.setItem(rowFirstSlot + col, pane);
    }

    private Material accentMaterial() {
        return GuiStyle.material("global.accent-material", Material.CYAN_STAINED_GLASS_PANE);
    }

    private Material dangerMaterial() {
        return GuiStyle.material("global.danger-material", Material.RED_STAINED_GLASS_PANE);
    }

    /** Appends already-colored lines to an item's lore (used for live status cards). */
    private static void appendLore(ItemStack item, List<String> lines) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        List<String> lore = meta.getLore() != null ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
        for (String l : lines) lore.add(ItemUtil.color(l));
        meta.setLore(lore);
        item.setItemMeta(meta);
    }

    /** Names of every unreserved arena available to build in — used for {@code /ea create} tab completion. */
    public List<String> unreservedArenaNames() {
        return collectArenas().stream()
                .map(ArenaEntry::name)
                .filter(name -> !sessions.isArenaReserved(name))
                .toList();
    }

    private List<ArenaEntry> collectArenas() {
        List<ArenaEntry> list = new ArrayList<>();
        // Without the shared database, a session on a remote arena would never reach the
        // server actually hosting it — the "private" match would sit completely ungated
        // there. Only offer remote arenas when the network store is actually connected.
        boolean networkStoreReady = plugin.getDatabase() != null;
        try {
            RemoteAPI remote = RemoteAPI.get();
            if (remote != null && remote.isAPIActive()) {
                for (RemoteArena ra : remote.getArenas()) {
                    if (ra != null && ra.exists() && (ra.isLocal() || networkStoreReady)) {
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
