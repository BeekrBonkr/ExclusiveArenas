package com.slg.exclusivearenas;

import de.marcely.bedwars.api.BedwarsAPI;
import de.marcely.bedwars.api.arena.Arena;
import de.marcely.bedwars.api.arena.ArenaStatus;
import de.marcely.bedwars.api.arena.Team;
import de.marcely.bedwars.api.game.shop.ShopItem;
import de.marcely.bedwars.api.game.shop.ShopPage;
import de.marcely.bedwars.api.game.shop.price.ShopPrice;
import de.marcely.bedwars.api.remote.RemoteAPI;
import de.marcely.bedwars.api.remote.RemoteArena;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Comparator;
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
        accentDividers(inv, 11, 13, 15);

        String hosting = String.valueOf(sessions.countByOwner(p.getUniqueId()));
        String limit = limitLabel(plugin.getArenaLimit(p));

        GuiStyle.place(inv, "main.buttons.arena-management", "%hosting%", hosting, "%limit%", limit);
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
        renderSessionListInto(p, holder, inv, menu, list, pg, pages, allowCreate);
        p.openInventory(inv);
    }

    /** Fills a session-list page — also called by the live refresh to re-render in place. */
    private void renderSessionListInto(Player p, GuiHolder holder, Inventory inv, String menu,
                                       List<PrivateSession> list, int pg, int pages, boolean allowCreate) {
        int perPage = LIST_SLOTS.length;
        holder.clearSlotMaps();
        inv.clear();
        frame(inv);

        int start = pg * perPage;
        int end = Math.min(list.size(), start + perPage);
        for (int i = start; i < end; i++) {
            PrivateSession session = list.get(i);
            int slot = LIST_SLOTS[i - start];
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
                renderSessionListInto(p, holder, inv, "arena-list", owned, pg, pages, true);
            }
            case ADMIN_LIST -> {
                List<PrivateSession> all = new ArrayList<>(sessions.getAllSessions());
                all.sort(Comparator.comparing(PrivateSession::getArenaName, String.CASE_INSENSITIVE_ORDER));
                int pages = Math.max(1, (int) Math.ceil(all.size() / (double) LIST_SLOTS.length));
                int pg = Math.max(0, Math.min(holder.page(), pages - 1));
                holder.page(pg);
                renderSessionListInto(p, holder, inv, "admin-list", all, pg, pages, false);
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

        GuiStyle.place(inv, lobbyNow ? "controls.buttons.start-lobby" : "controls.buttons.start-running",
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
        GuiStyle.place(inv, "controls.buttons.kick-all");
        GuiStyle.place(inv, "controls.buttons.end-match");
        GuiStyle.place(inv, "controls.buttons.quick-actions");

        GuiStyle.place(inv, "controls.buttons.back", "%back_hint%",
                adminView ? "&8Return to the admin list." : "&8Return to your matches.");
        GuiStyle.place(inv, "controls.buttons.close");
    }

    // ── Quick actions ─────────────────────────────────────────────────────────────

    public void openQuickActions(Player p, PrivateSession session, boolean adminView) {
        GuiHolder holder = new GuiHolder(GuiHolder.Type.QUICK_ACTIONS)
                .sessionId(session.getSessionId()).adminView(adminView);
        Inventory inv = create(holder, GuiStyle.size("quick-actions", 36),
                GuiStyle.title("quick-actions", "%arena%", session.getArenaName()));
        frame(inv);
        fillInteriorRow(inv, 9, accentMaterial());
        fillInteriorRow(inv, 18, accentMaterial());

        GuiStyle.place(inv, "quick-actions.buttons.regenerate-map");
        GuiStyle.place(inv, "quick-actions.buttons.heal-all");
        GuiStyle.place(inv, "quick-actions.buttons.drop-spawners");
        GuiStyle.place(inv, "quick-actions.buttons.destroy-beds");
        GuiStyle.place(inv, "quick-actions.buttons.clear-items");
        if (plugin.getTimelineService().isEnabled()) {
            GuiStyle.place(inv, "quick-actions.buttons.skip-event");
        }
        GuiStyle.place(inv, "quick-actions.buttons.back");
        p.openInventory(inv);
    }

    // ── Arena settings hub ────────────────────────────────────────────────────────

    public void openArenaConfig(Player p, PrivateSession session, boolean adminView) {
        GuiHolder holder = new GuiHolder(GuiHolder.Type.ARENA_CONFIG)
                .sessionId(session.getSessionId()).adminView(adminView);
        Inventory inv = create(holder, GuiStyle.size("arena-config", 27),
                GuiStyle.title("arena-config", "%arena%", session.getArenaName()));
        frame(inv);
        accentDividers(inv, 11, 13, 15);

        if (plugin.getTimelineService().isEnabled()) {
            GuiStyle.place(inv, "arena-config.buttons.event-timeline");
        }
        GuiStyle.place(inv, "arena-config.buttons.shop-config");
        GuiStyle.place(inv, "arena-config.buttons.presets");
        GuiStyle.place(inv, "arena-config.buttons.team-size");
        GuiStyle.place(inv, "arena-config.buttons.back");
        p.openInventory(inv);
    }

    /** Bounds on the players-per-team override — generous for every real BedWars team format. */
    static final int MIN_PLAYERS_PER_TEAM = 1;
    static final int MAX_PLAYERS_PER_TEAM = 8;

    public void openTeamSize(Player p, PrivateSession session, boolean adminView) {
        GuiHolder holder = new GuiHolder(GuiHolder.Type.TEAM_SIZE)
                .sessionId(session.getSessionId()).adminView(adminView);
        Inventory inv = create(holder, GuiStyle.size("team-size", 27),
                GuiStyle.title("team-size", "%arena%", session.getArenaName()));
        frame(inv);
        accentDividers(inv, 12, 14);

        Arena arena = BedwarsAPI.getGameAPI().getArenaByExactName(session.getArenaName());
        if (arena == null || !arena.exists()) {
            GuiStyle.place(inv, "team-size.buttons.unavailable",
                    "%reason%", "&7This arena isn't loaded on this server.");
            GuiStyle.place(inv, "team-size.buttons.back");
            p.openInventory(inv);
            return;
        }

        Integer original = session.getOriginalPlayersPerTeam();
        int fallback = original != null ? original : arena.getPlayersPerTeam();
        Integer override = session.getSettings().getPlayersPerTeam();
        int amount = override != null ? override : fallback;
        boolean locked = !plugin.canChangeTeamSize(arena);

        GuiStyle.place(inv, "team-size.buttons.display", "%amount%", String.valueOf(amount),
                "%locked%", locked ? "&cLocked — a player has already joined." : "");
        if (!locked) {
            GuiStyle.place(inv, "team-size.buttons.minus-one");
            GuiStyle.place(inv, "team-size.buttons.plus-one");
            GuiStyle.place(inv, "team-size.buttons.reset");
        }
        GuiStyle.place(inv, "team-size.buttons.back");
        p.openInventory(inv);
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
                            "%priced%", String.valueOf(priced)));
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

    /** Opens the anvil prompt where the host types a name for the preset they're about to save. */
    public void openPresetNamePrompt(Player p, PrivateSession session, boolean adminView,
                                     Map<String, String> presets) {
        java.util.LinkedHashMap<String, String> snapshot = new java.util.LinkedHashMap<>(presets);
        GuiHolder holder = new GuiHolder(GuiHolder.Type.PRESET_NAME)
                .sessionId(session.getSessionId()).adminView(adminView).presets(snapshot);
        Inventory inv = Bukkit.createInventory(holder, InventoryType.ANVIL,
                GuiStyle.title("preset-name", "%arena%", session.getArenaName()));
        holder.setInventory(inv);

        String suggested = PresetService.nextFreeName(snapshot);
        inv.setItem(0, GuiStyle.item("preset-name.buttons.icon", "%name%", suggested));
        p.openInventory(inv);
    }

    // ── Event timeline editor ─────────────────────────────────────────────────────

    public void openTimeline(Player p, PrivateSession session, boolean adminView, String selectedEventId) {
        TimelineService timelines = plugin.getTimelineService();
        List<SessionSettings.TimelineEntry> timeline = timelines.effectiveTimeline(session);

        // A selection that no longer exists (deleted, reset) silently clears.
        String wanted = selectedEventId;
        if (wanted != null && timeline.stream().noneMatch(e -> e.id().equals(wanted))) {
            selectedEventId = null;
        }

        GuiHolder holder = new GuiHolder(GuiHolder.Type.TIMELINE)
                .sessionId(session.getSessionId()).adminView(adminView).selectedEvent(selectedEventId);
        Inventory inv = create(holder, GuiStyle.size("timeline", 54),
                GuiStyle.title("timeline", "%arena%", session.getArenaName()));
        frame(inv);

        GuiStyle.place(inv, "timeline.buttons.info");

        for (int i = 0; i < timeline.size() && i < STRIP_SLOTS.length; i++) {
            SessionSettings.TimelineEntry entry = timeline.get(i);
            TimelineService.Definition def = timelines.definition(entry.id());
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
                            "%effect%", def.description()));
            if (GuiStyle.glint(template)) ItemUtil.glint(item);

            int slot = STRIP_SLOTS[i];
            inv.setItem(slot, item);
            holder.mapKeySlot(slot, entry.id());
        }

        if (selectedEventId != null) {
            TimelineService.Definition def = timelines.definition(selectedEventId);
            String eventName = def != null ? def.name() : selectedEventId;
            GuiStyle.place(inv, "timeline.buttons.minus-minute", "%event%", eventName);
            GuiStyle.place(inv, "timeline.buttons.minus-seconds", "%event%", eventName);
            if (def != null && def.type() != TimelineService.Type.MATCH_END) {
                GuiStyle.place(inv, "timeline.buttons.delete", "%event%", eventName);
            }
            GuiStyle.place(inv, "timeline.buttons.plus-seconds", "%event%", eventName);
            GuiStyle.place(inv, "timeline.buttons.plus-minute", "%event%", eventName);
        }

        GuiStyle.place(inv, "timeline.buttons.reset");
        GuiStyle.place(inv, "timeline.buttons.back");
        GuiStyle.place(inv, "timeline.buttons.close");
        p.openInventory(inv);
    }

    // ── Shop configuration ────────────────────────────────────────────────────────

    public void openShopPages(Player p, PrivateSession session, boolean adminView) {
        GuiHolder holder = new GuiHolder(GuiHolder.Type.SHOP_PAGES)
                .sessionId(session.getSessionId()).adminView(adminView);
        Inventory inv = create(holder, GuiStyle.size("shop-pages", 36),
                GuiStyle.title("shop-pages", "%arena%", session.getArenaName()));
        frame(inv);

        GuiStyle.place(inv, "shop-pages.buttons.info");

        List<ShopPage> pages = new ArrayList<>(BedwarsAPI.getGameAPI().getShopPages());
        for (int i = 0; i < pages.size() && i < STRIP_SLOTS.length; i++) {
            ShopPage page = pages.get(i);
            List<String> itemIds = page.getItems().stream().map(ShopItem::getId).toList();
            long disabled = session.getSettings().countDisabled(itemIds);

            ItemStack icon = ItemUtil.icon(page.getIcon(), Material.CHEST,
                    GuiStyle.name("shop-pages.items.page", "%page%", plainName(page.getDisplayName())),
                    GuiStyle.lore("shop-pages.items.page",
                            "%page%", plainName(page.getDisplayName()),
                            "%total%", String.valueOf(itemIds.size()),
                            "%disabled%", String.valueOf(disabled)));

            int slot = STRIP_SLOTS[i];
            inv.setItem(slot, icon);
            holder.mapKeySlot(slot, page.getName());
        }

        GuiStyle.place(inv, "shop-pages.buttons.reset");
        GuiStyle.place(inv, "shop-pages.buttons.back");
        p.openInventory(inv);
    }

    public void openShopItems(Player p, PrivateSession session, boolean adminView, String pageName, int page) {
        ShopPage shopPage = findShopPage(pageName);
        if (shopPage == null) {
            openShopPages(p, session, adminView);
            return;
        }

        List<ShopItem> items = new ArrayList<>(shopPage.getItems());
        int perPage = LIST_SLOTS.length;
        int pages = Math.max(1, (int) Math.ceil(items.size() / (double) perPage));
        int pg = Math.max(0, Math.min(page, pages - 1));

        GuiHolder holder = new GuiHolder(GuiHolder.Type.SHOP_ITEMS)
                .sessionId(session.getSessionId()).adminView(adminView)
                .shopPage(pageName).page(pg);
        Inventory inv = create(holder, GuiStyle.size("shop-items", 54),
                GuiStyle.title("shop-items",
                        "%arena%", session.getArenaName(),
                        "%page%", plainName(shopPage.getDisplayName()),
                        "%pagenum%", String.valueOf(pg + 1),
                        "%pages%", String.valueOf(pages)));
        frame(inv);

        int start = pg * perPage;
        int end = Math.min(items.size(), start + perPage);
        for (int i = start; i < end; i++) {
            ShopItem item = items.get(i);
            int slot = LIST_SLOTS[i - start];
            inv.setItem(slot, shopItemEntry(session, item));
            holder.mapKeySlot(slot, item.getId());
        }

        if (pg > 0) GuiStyle.place(inv, "shop-items.buttons.previous-page", "%target%", String.valueOf(pg));
        if (pg < pages - 1) GuiStyle.place(inv, "shop-items.buttons.next-page", "%target%", String.valueOf(pg + 2));
        GuiStyle.place(inv, "shop-items.buttons.back");
        p.openInventory(inv);
    }

    public void openShopPrice(Player p, PrivateSession session, boolean adminView,
                              String pageName, String itemId) {
        ShopItem item = BedwarsAPI.getGameAPI().getShopItemById(itemId);
        if (item == null) {
            openShopItems(p, session, adminView, pageName, 0);
            return;
        }

        SessionSettings.ShopOverride override = session.getSettings().getShopOverride(itemId);
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

        GuiHolder holder = new GuiHolder(GuiHolder.Type.SHOP_PRICE)
                .sessionId(session.getSessionId()).adminView(adminView)
                .shopPage(pageName).shopItem(itemId);
        Inventory inv = create(holder, GuiStyle.size("shop-price", 45),
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
    private ItemStack shopItemEntry(PrivateSession session, ShopItem item) {
        SessionSettings.ShopOverride override = session.getSettings().getShopOverride(item.getId());
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
            GuiStyle.place(inv, "builder.buttons.party-blocked");
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
            boolean reserved = sessions.isArenaReserved(entry.name());
            String template = "arena-select.items." + (reserved ? "arena-reserved" : "arena");
            Material fallback = reserved ? Material.GRAY_CONCRETE : Material.GRASS_BLOCK;

            inv.setItem(slot, ItemUtil.icon(entry.icon(), fallback,
                    GuiStyle.name(template, "%arena%", entry.name()),
                    GuiStyle.lore(template,
                            "%arena%", entry.name(),
                            "%location%", entry.remote() ? "Remote" : "Local",
                            "%teams%", String.valueOf(entry.teamCount()),
                            "%per_team%", String.valueOf(entry.playersPerTeam()))));
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
        };
    }

    private String ownerName(PrivateSession session) {
        if (session.getOwner() == null) return "?";
        var off = Bukkit.getOfflinePlayer(session.getOwner());
        return off.getName() != null ? off.getName() : "?";
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
