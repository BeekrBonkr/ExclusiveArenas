package com.slg.exclusivearenas;

import de.marcely.bedwars.api.BedwarsAPI;
import de.marcely.bedwars.api.arena.Arena;
import de.marcely.bedwars.api.arena.Team;
import de.marcely.bedwars.api.game.shop.ShopItem;
import de.marcely.bedwars.api.game.spawner.DropType;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.view.AnvilView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Routes clicks in every ExclusiveArenas menu. Button positions come from guis.yml via
 * {@link GuiStyle#slot(String)} — a button moved in the config moves its behaviour with
 * it, and one hidden with {@code slot: -1} stops matching entirely.
 */
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
            case CONTROLS    -> handleControls(p, gh, slot, e.isShiftClick());
            case ARENA_CONFIG-> handleArenaConfig(p, gh, slot);
            case PRESETS     -> handlePresets(p, gh, slot, e.isShiftClick());
            case QUICK_ACTIONS-> handleQuickActions(p, gh, slot);
            case TIMELINE    -> handleTimeline(p, gh, slot);
            case SHOP_PAGES  -> handleShopPages(p, gh, slot);
            case SHOP_ITEMS  -> handleShopItems(p, gh, slot, e.isShiftClick());
            case SHOP_PRICE  -> handleShopPrice(p, gh, slot);
            case TEAM_SELECT -> handleTeamSelect(p, gh, slot);
            case TEAM_PLAYERS-> handleTeamPlayers(p, gh, slot, e.isShiftClick());
            case HELP        -> { if (slot == GuiStyle.slot("help.buttons.back")) gui.openMainMenu(p); }
            case PRESET_NAME -> handlePresetName(p, gh, slot, e.getView());
        }
    }

    /**
     * Forces the anvil's output slot to always mirror the current rename text, regardless of
     * vanilla's normal recipe/repair-cost rules — the click handler never actually consumes it
     * (every click on one of our menus is cancelled), so no XP is ever spent either.
     */
    @EventHandler
    public void onPrepareAnvil(PrepareAnvilEvent e) {
        if (!(e.getInventory().getHolder() instanceof GuiHolder gh) || gh.type() != GuiHolder.Type.PRESET_NAME) return;

        String text = e.getView().getRenameText();
        if (text == null || text.isBlank()) {
            e.setResult(null);
            return;
        }
        ItemStack result = new ItemStack(Material.PAPER);
        ItemMeta meta = result.getItemMeta();
        meta.setDisplayName(text.trim());
        result.setItemMeta(meta);
        e.setResult(result);
    }

    // ── Main menu ─────────────────────────────────────────────────────────────────

    private void handleMain(Player p, int slot) {
        if (slot == GuiStyle.slot("main.buttons.arena-management")) {
            gui.openArenaList(p, 0);
        } else if (slot == GuiStyle.slot("main.buttons.help")) {
            gui.openHelp(p);
        } else if (slot == GuiStyle.slot("main.buttons.admin")) {
            if (p.hasPermission(GuiManager.ADMIN_PERM)) gui.openAdminList(p, 0);
        } else if (slot == GuiStyle.slot("main.buttons.close")) {
            p.closeInventory();
        }
    }

    // ── Session lists (player + admin) ─────────────────────────────────────────────

    private void handleList(Player p, GuiHolder gh, int slot, boolean admin) {
        String menu = admin ? "admin-list" : "arena-list";
        int page = gh.page();

        if (slot == GuiStyle.slot(menu + ".buttons.previous-page")) { reopenList(p, admin, page - 1); return; }
        if (slot == GuiStyle.slot(menu + ".buttons.next-page")) { reopenList(p, admin, page + 1); return; }
        if (slot == GuiStyle.slot(menu + ".buttons.back")) { gui.openMainMenu(p); return; }
        if (slot == GuiStyle.slot(menu + ".buttons.refresh")) { reopenList(p, admin, page); return; }
        if (admin && slot == GuiStyle.slot("admin-list.buttons.close")) { p.closeInventory(); return; }
        if (!admin && slot == GuiStyle.slot("arena-list.buttons.create")) { plugin.openBuilderMenu(p); return; }

        UUID sessionId = gh.sessionAt(slot);
        if (sessionId == null) return;
        PrivateSession session = sessions.getById(sessionId);
        if (session == null) {
            p.sendMessage(Lang.msg("general.match-gone"));
            reopenList(p, admin, page);
            return;
        }
        gui.openControls(p, session, admin);
    }

    private void reopenList(Player p, boolean admin, int page) {
        if (admin) gui.openAdminList(p, page); else gui.openArenaList(p, page);
    }

    // ── Builder ────────────────────────────────────────────────────────────────────

    private void handleBuilder(Player p, int slot, boolean shiftClick) {
        DraftPrivateMatch d = drafts.getOrCreate(p.getUniqueId());
        if (d.isPartyBlocked()) {
            if (slot == GuiStyle.slot("builder.buttons.back")) { gui.openArenaList(p, 0); return; }
            if (slot == GuiStyle.slot("builder.buttons.close")) { p.closeInventory(); return; }
            p.sendMessage(Lang.msg("create.party-blocked-menu"));
            return;
        }

        if (slot == GuiStyle.slot("builder.buttons.select-map")) {
            gui.openArenaSelect(p, 0);
        } else if (slot == GuiStyle.slot("builder.buttons.public-on")
                && d.getJoinPolicy() == JoinPolicy.CODE) {
            d.setPublic(!d.isPublic());
            gui.openBuilder(p);
        } else if (slot == GuiStyle.slot("builder.buttons.create-ready")) {
            p.closeInventory();
            plugin.createAndJoin(p, d, !shiftClick); // shift-click: create without joining
        } else if (slot == GuiStyle.slot("builder.buttons.back")) {
            gui.openArenaList(p, 0);
        } else if (slot == GuiStyle.slot("builder.buttons.close")) {
            p.closeInventory();
        }
    }

    // ── Arena selector ──────────────────────────────────────────────────────────────

    private void handleArenaSelect(Player p, GuiHolder gh, int slot, ItemStack clicked) {
        int page = gh.page();
        int teamFilter = gh.teamFilter();
        int ppFilter = gh.playersPerTeamFilter();

        if (slot == GuiStyle.slot("arena-select.buttons.previous-page")) { gui.openArenaSelect(p, page - 1, teamFilter, ppFilter); return; }
        if (slot == GuiStyle.slot("arena-select.buttons.next-page")) { gui.openArenaSelect(p, page + 1, teamFilter, ppFilter); return; }
        if (slot == GuiStyle.slot("arena-select.buttons.team-filter")) { gui.openArenaSelect(p, 0, gui.cycleTeamFilter(teamFilter), ppFilter); return; }
        if (slot == GuiStyle.slot("arena-select.buttons.players-filter")) { gui.openArenaSelect(p, 0, teamFilter, gui.cyclePlayersPerTeamFilter(ppFilter)); return; }
        if (slot == GuiStyle.slot("arena-select.buttons.back")) { gui.openBuilder(p); return; }

        if (clicked == null || clicked.getType() == Material.AIR) return;
        if (clicked.getItemMeta() == null || clicked.getItemMeta().getDisplayName().isEmpty()) return;

        String name = ChatColor.stripColor(clicked.getItemMeta().getDisplayName());
        if (name == null || name.isBlank()) return;

        if (sessions.isArenaReserved(name)) {
            p.sendMessage(Lang.msg("create.arena-reserved"));
            return;
        }

        DraftPrivateMatch d = drafts.getOrCreate(p.getUniqueId());
        d.setArenaName(name);
        if (d.getJoinPolicy() == JoinPolicy.CODE && (d.getJoinCode() == null || d.getJoinCode().isBlank())) {
            d.setJoinCode(sessions.generateCode());
        }
        gui.openBuilder(p);
    }

    // ── Match controls ──────────────────────────────────────────────────────────────

    private void handleControls(Player p, GuiHolder gh, int slot, boolean shiftClick) {
        PrivateSession session = requireManageable(p, gh);
        if (session == null) return;

        Arena arena = BedwarsAPI.getGameAPI().getArenaByExactName(session.getArenaName());
        boolean onThisServer = arena != null && arena.exists();

        if (slot == GuiStyle.slot("controls.buttons.settings")) {
            gui.openArenaConfig(p, session, gh.adminView());
        } else if (slot == GuiStyle.slot("controls.buttons.quick-actions")) {
            gui.openQuickActions(p, session, gh.adminView());
        } else if (slot == GuiStyle.slot("controls.buttons.manage-teams")) {
            if (!onThisServer) { p.sendMessage(Lang.msg("teams.requires-local")); return; }
            if (!arena.getStatus().isLobby()) { p.sendMessage(Lang.msg("teams.lobby-only")); return; }
            gui.openTeamSelect(p, session, gh.adminView());
        } else if (slot == GuiStyle.slot("controls.buttons.start-lobby")) {
            plugin.requestStartMatch(p, session);
            gui.openControls(p, session, gh.adminView());
        } else if (slot == GuiStyle.slot("controls.buttons.public-on")) {
            // Shared slot: public toggle (Code) or summon party (Party).
            if (session.getJoinPolicy() == JoinPolicy.CODE) {
                sessions.setSessionPublic(session, !session.isPublic());
                p.sendMessage(Lang.msg(session.isPublic() ? "match.public-opened" : "match.public-locked"));
            } else {
                plugin.summonPartyToArena(p, session);
            }
            gui.openControls(p, session, gh.adminView());
        } else if (slot == GuiStyle.slot("controls.buttons.regenerate-code")) {
            if (session.getJoinPolicy() == JoinPolicy.CODE) {
                plugin.regenerateJoinCode(session);
                gui.openControls(p, session, gh.adminView());
            }
        } else if (slot == GuiStyle.slot("controls.buttons.go-to-arena")) {
            p.closeInventory();
            plugin.getTicketService().grant(p.getUniqueId(), session.getSessionId(), session.getArenaName());
            plugin.sendPlayerToArena(p, session.getArenaName());
        } else if (slot == GuiStyle.slot("controls.buttons.kick-all")) {
            // Plain click clears the arena completely; shift-click spares the host.
            plugin.runArenaAction(p, session, RemoteCommandService.Type.KICK_ALL,
                    shiftClick ? RemoteCommandService.PAYLOAD_KEEP_HOST : null);
            gui.openControls(p, session, gh.adminView());
        } else if (slot == GuiStyle.slot("controls.buttons.end-match")) {
            plugin.requestEndMatch(p, session);
            reopenList(p, gh.adminView(), gh.page());
        } else if (slot == GuiStyle.slot("controls.buttons.back")) {
            reopenList(p, gh.adminView(), 0);
        } else if (slot == GuiStyle.slot("controls.buttons.close")) {
            p.closeInventory();
        }
    }

    // ── Arena settings hub ──────────────────────────────────────────────────────────

    private void handleArenaConfig(Player p, GuiHolder gh, int slot) {
        PrivateSession session = requireManageable(p, gh);
        if (session == null) return;

        if (slot == GuiStyle.slot("arena-config.buttons.event-timeline")) {
            if (plugin.getTimelineService().isEnabled()) gui.openTimeline(p, session, gh.adminView(), null);
        } else if (slot == GuiStyle.slot("arena-config.buttons.shop-config")) {
            gui.openShopPages(p, session, gh.adminView());
        } else if (slot == GuiStyle.slot("arena-config.buttons.presets")) {
            openPresetsFor(p, gh);
        } else if (slot == GuiStyle.slot("arena-config.buttons.back")) {
            gui.openControls(p, session, gh.adminView());
        }
    }

    // ── Saved configurations (presets) ───────────────────────────────────────────────

    /** Loads the player's presets off-thread, then opens the menu with them. */
    private void openPresetsFor(Player p, GuiHolder gh) {
        plugin.getPresetService().list(p.getUniqueId(), presets -> {
            PrivateSession session = sessions.getById(gh.sessionId());
            if (session == null) {
                p.sendMessage(Lang.msg("general.match-gone"));
                return;
            }
            gui.openPresets(p, session, gh.adminView(), presets);
        });
    }

    private void handlePresets(Player p, GuiHolder gh, int slot, boolean shiftClick) {
        PrivateSession session = requireManageable(p, gh);
        if (session == null) return;

        if (slot == GuiStyle.slot("presets.buttons.back")) {
            gui.openArenaConfig(p, session, gh.adminView());
            return;
        }

        java.util.LinkedHashMap<String, String> presets =
                gh.presets() == null ? new java.util.LinkedHashMap<>() : new java.util.LinkedHashMap<>(gh.presets());

        if (slot == GuiStyle.slot("presets.buttons.save-current")) {
            if (presets.size() >= PresetService.MAX_PRESETS) {
                p.sendMessage(Lang.msg("presets.limit", "%max%",
                        String.valueOf(PresetService.MAX_PRESETS)));
                return;
            }
            gui.openPresetNamePrompt(p, session, gh.adminView(), presets);
            return;
        }

        String name = gh.keyAt(slot);
        if (name == null || !presets.containsKey(name)) return;

        if (shiftClick) {
            plugin.getPresetService().delete(p.getUniqueId(), name);
            presets.remove(name);
            p.sendMessage(Lang.msg("presets.deleted", "%name%", name));
            gui.openPresets(p, session, gh.adminView(), presets);
            return;
        }

        session.setSettings(SessionSettings.fromJson(presets.get(name)));
        sessions.saveSettings(session);
        p.sendMessage(Lang.msg("presets.applied", "%name%", name, "%arena%", session.getArenaName()));
    }

    /** Confirms the anvil name prompt — only the result slot (2) saves; anything else is a no-op. */
    private void handlePresetName(Player p, GuiHolder gh, int slot, InventoryView view) {
        if (slot != 2) return;
        PrivateSession session = requireManageable(p, gh);
        if (session == null) return;

        LinkedHashMap<String, String> presets =
                gh.presets() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(gh.presets());

        String typed = view instanceof AnvilView anvil ? anvil.getRenameText() : null;
        String requested = typed == null || typed.isBlank() ? null : typed.trim();

        String name;
        if (requested == null) {
            name = PresetService.nextFreeName(presets);
        } else if (!PresetService.isValidName(requested)) {
            p.sendMessage(Lang.msg("cmd.preset-bad-name", "%max%", String.valueOf(PresetService.MAX_NAME_LENGTH)));
            return;
        } else {
            String existing = PresetService.existingName(presets, requested);
            name = existing != null ? existing : requested;
        }

        // Overwriting an existing preset is fine; only a brand-new name counts toward the cap.
        boolean isNew = name == null || !presets.containsKey(name);
        if (name == null || (isNew && presets.size() >= PresetService.MAX_PRESETS)) {
            p.sendMessage(Lang.msg("presets.limit", "%max%", String.valueOf(PresetService.MAX_PRESETS)));
            return;
        }

        String json = session.getSettings().toJson();
        plugin.getPresetService().save(p.getUniqueId(), name, json);
        p.sendMessage(Lang.msg("presets.saved", "%name%", name));
        presets.put(name, json);
        gui.openPresets(p, session, gh.adminView(), presets);
    }

    // ── Quick actions ───────────────────────────────────────────────────────────────

    private void handleQuickActions(Player p, GuiHolder gh, int slot) {
        PrivateSession session = requireManageable(p, gh);
        if (session == null) return;

        if (slot == GuiStyle.slot("quick-actions.buttons.back")) {
            gui.openControls(p, session, gh.adminView());
            return;
        }

        RemoteCommandService.Type type = null;
        if (slot == GuiStyle.slot("quick-actions.buttons.regenerate-map")) type = RemoteCommandService.Type.QUICK_REGEN;
        else if (slot == GuiStyle.slot("quick-actions.buttons.heal-all")) type = RemoteCommandService.Type.QUICK_HEAL;
        else if (slot == GuiStyle.slot("quick-actions.buttons.drop-spawners")) type = RemoteCommandService.Type.QUICK_DROP;
        else if (slot == GuiStyle.slot("quick-actions.buttons.destroy-beds")) type = RemoteCommandService.Type.QUICK_BEDS;
        else if (slot == GuiStyle.slot("quick-actions.buttons.clear-items")) type = RemoteCommandService.Type.QUICK_CLEAR;
        else if (slot == GuiStyle.slot("quick-actions.buttons.skip-event")) type = RemoteCommandService.Type.QUICK_SKIP_EVENT;
        if (type == null) return;

        if (type == RemoteCommandService.Type.QUICK_REGEN) {
            // Regen cycles the host through spectator and back to player; a menu left open
            // the whole time is the one thing that reliably kept the host from being reseated.
            p.closeInventory();
        }
        plugin.runArenaAction(p, session, type);
    }

    // ── Event timeline editor ────────────────────────────────────────────────────────

    private void handleTimeline(Player p, GuiHolder gh, int slot) {
        PrivateSession session = requireManageable(p, gh);
        if (session == null) return;

        TimelineService timelines = plugin.getTimelineService();

        if (slot == GuiStyle.slot("timeline.buttons.back")) {
            gui.openArenaConfig(p, session, gh.adminView());
            return;
        }
        if (slot == GuiStyle.slot("timeline.buttons.close")) {
            p.closeInventory();
            return;
        }
        if (slot == GuiStyle.slot("timeline.buttons.reset")) {
            timelines.resetTimeline(session);
            sessions.saveSettings(session);
            p.sendMessage(Lang.msg("timeline.reset"));
            gui.openTimeline(p, session, gh.adminView(), null);
            return;
        }

        // Selecting / deselecting an event on the strip.
        String clickedEvent = gh.keyAt(slot);
        if (clickedEvent != null) {
            String next = clickedEvent.equals(gh.selectedEvent()) ? null : clickedEvent;
            gui.openTimeline(p, session, gh.adminView(), next);
            return;
        }

        // Editor controls (only meaningful with a selection).
        String selected = gh.selectedEvent();
        if (selected == null) return;

        int delta = 0;
        if (slot == GuiStyle.slot("timeline.buttons.minus-minute")) delta = -60;
        else if (slot == GuiStyle.slot("timeline.buttons.minus-seconds")) delta = -5;
        else if (slot == GuiStyle.slot("timeline.buttons.plus-seconds")) delta = 5;
        else if (slot == GuiStyle.slot("timeline.buttons.plus-minute")) delta = 60;

        if (delta != 0) {
            int newTime = timelines.moveEvent(session, selected, delta);
            if (newTime >= 0) {
                sessions.saveSettings(session);
                TimelineService.Definition def = timelines.definition(selected);
                boolean isEnd = def != null && def.type() == TimelineService.Type.MATCH_END;
                p.sendMessage(Lang.msg(isEnd ? "timeline.end-moved" : "timeline.moved",
                        "%event%", def != null ? def.name() : selected,
                        "%time%", TimelineService.format(newTime)));
            }
            gui.openTimeline(p, session, gh.adminView(), selected);
            return;
        }

        if (slot == GuiStyle.slot("timeline.buttons.delete")) {
            TimelineService.Definition def = timelines.definition(selected);
            if (def != null && def.type() == TimelineService.Type.MATCH_END) {
                p.sendMessage(Lang.msg("timeline.cannot-delete-end"));
                return;
            }
            if (timelines.deleteEvent(session, selected)) {
                sessions.saveSettings(session);
                p.sendMessage(Lang.msg("timeline.deleted",
                        "%event%", def != null ? def.name() : selected));
            }
            gui.openTimeline(p, session, gh.adminView(), null);
        }
    }

    // ── Shop configuration ───────────────────────────────────────────────────────────

    private void handleShopPages(Player p, GuiHolder gh, int slot) {
        PrivateSession session = requireManageable(p, gh);
        if (session == null) return;

        if (slot == GuiStyle.slot("shop-pages.buttons.back")) {
            gui.openArenaConfig(p, session, gh.adminView());
            return;
        }
        if (slot == GuiStyle.slot("shop-pages.buttons.reset")) {
            session.getSettings().clearShopOverrides();
            sessions.saveSettings(session);
            p.sendMessage(Lang.msg("shop.reset-all"));
            gui.openShopPages(p, session, gh.adminView());
            return;
        }

        String pageName = gh.keyAt(slot);
        if (pageName != null) gui.openShopItems(p, session, gh.adminView(), pageName, 0);
    }

    private void handleShopItems(Player p, GuiHolder gh, int slot, boolean shiftClick) {
        PrivateSession session = requireManageable(p, gh);
        if (session == null) return;

        if (slot == GuiStyle.slot("shop-items.buttons.back")) {
            gui.openShopPages(p, session, gh.adminView());
            return;
        }
        if (slot == GuiStyle.slot("shop-items.buttons.previous-page")) {
            gui.openShopItems(p, session, gh.adminView(), gh.shopPage(), gh.page() - 1);
            return;
        }
        if (slot == GuiStyle.slot("shop-items.buttons.next-page")) {
            gui.openShopItems(p, session, gh.adminView(), gh.shopPage(), gh.page() + 1);
            return;
        }

        String itemId = gh.keyAt(slot);
        if (itemId == null) return;
        ShopItem item = BedwarsAPI.getGameAPI().getShopItemById(itemId);
        if (item == null) return;

        if (shiftClick) {
            gui.openShopPrice(p, session, gh.adminView(), gh.shopPage(), itemId);
            return;
        }

        SessionSettings.ShopOverride override = session.getSettings().getOrCreateShopOverride(itemId);
        override.setDisabled(!override.isDisabled());
        boolean nowDisabled = override.isDisabled();
        session.getSettings().pruneShopOverride(itemId);
        sessions.saveSettings(session);

        String itemName = ChatColor.stripColor(ItemUtil.color(item.getDisplayName()));
        p.sendMessage(Lang.msg(nowDisabled ? "shop.item-disabled" : "shop.item-enabled",
                "%item%", itemName));
        gui.openShopItems(p, session, gh.adminView(), gh.shopPage(), gh.page());
    }

    private void handleShopPrice(Player p, GuiHolder gh, int slot) {
        PrivateSession session = requireManageable(p, gh);
        if (session == null) return;

        String itemId = gh.shopItem();
        ShopItem item = itemId != null ? BedwarsAPI.getGameAPI().getShopItemById(itemId) : null;
        if (item == null) {
            gui.openShopPages(p, session, gh.adminView());
            return;
        }
        String itemName = ChatColor.stripColor(ItemUtil.color(item.getDisplayName()));

        if (slot == GuiStyle.slot("shop-price.buttons.back")) {
            gui.openShopItems(p, session, gh.adminView(), gh.shopPage(), 0);
            return;
        }
        if (slot == GuiStyle.slot("shop-price.buttons.reset")) {
            SessionSettings.ShopOverride override = session.getSettings().getShopOverride(itemId);
            if (override != null) {
                override.setPrice(null, null);
                session.getSettings().pruneShopOverride(itemId);
                sessions.saveSettings(session);
            }
            p.sendMessage(Lang.msg("shop.price-reset", "%item%", itemName));
            gui.openShopPrice(p, session, gh.adminView(), gh.shopPage(), itemId);
            return;
        }

        // Current effective price (override or default) as the editing base.
        SessionSettings.ShopOverride override = session.getSettings().getOrCreateShopOverride(itemId);
        int amount;
        String currency;
        if (override.hasPriceOverride()) {
            amount = override.getPrice();
            currency = override.getCurrency();
        } else {
            amount = GuiManager.defaultPriceAmount(item);
            currency = GuiManager.defaultPriceCurrency(item);
        }

        boolean changed = false;
        if (slot == GuiStyle.slot("shop-price.buttons.currency")) {
            currency = nextDropType(currency);
            changed = true;
        } else if (slot == GuiStyle.slot("shop-price.buttons.minus-ten")) {
            amount = Math.max(1, amount - 10); changed = true;
        } else if (slot == GuiStyle.slot("shop-price.buttons.minus-one")) {
            amount = Math.max(1, amount - 1); changed = true;
        } else if (slot == GuiStyle.slot("shop-price.buttons.plus-one")) {
            amount = Math.min(64, amount + 1); changed = true;
        } else if (slot == GuiStyle.slot("shop-price.buttons.plus-ten")) {
            amount = Math.min(64, amount + 10); changed = true;
        }

        if (!changed) {
            session.getSettings().pruneShopOverride(itemId);
            return;
        }

        override.setPrice(amount, currency);
        sessions.saveSettings(session);
        p.sendMessage(Lang.msg("shop.price-set",
                "%item%", itemName,
                "%amount%", String.valueOf(amount),
                "%currency%", GuiManager.currencyLabel(currency)));
        gui.openShopPrice(p, session, gh.adminView(), gh.shopPage(), itemId);
    }

    /** Cycles to the next registered MBedwars drop type (iron → gold → diamond → …). */
    private String nextDropType(String current) {
        List<DropType> types = new ArrayList<>(BedwarsAPI.getGameAPI().getDropTypes());
        if (types.isEmpty()) return current;
        for (int i = 0; i < types.size(); i++) {
            if (types.get(i).getId().equalsIgnoreCase(current)) {
                return types.get((i + 1) % types.size()).getId();
            }
        }
        return types.get(0).getId();
    }

    // ── Team management ────────────────────────────────────────────────────────────

    private void handleTeamSelect(Player p, GuiHolder gh, int slot) {
        PrivateSession session = sessions.getById(gh.sessionId());
        if (session == null) {
            p.sendMessage(Lang.msg("general.match-gone"));
            reopenList(p, gh.adminView(), 0);
            return;
        }

        if (slot == GuiStyle.slot("team-select.buttons.back")) {
            gui.openControls(p, session, gh.adminView());
            return;
        }

        Team team = gh.teamAt(slot);
        if (team == null) return;
        gui.openTeamPlayers(p, session, gh.adminView(), team, new HashSet<>(), false);
    }

    private void handleTeamPlayers(Player p, GuiHolder gh, int slot, boolean shiftClick) {
        PrivateSession session = sessions.getById(gh.sessionId());
        if (session == null) {
            p.sendMessage(Lang.msg("general.match-gone"));
            reopenList(p, gh.adminView(), 0);
            return;
        }

        Team team = gh.targetTeam();
        if (team == null || slot == GuiStyle.slot("team-players.buttons.back")) {
            gui.openTeamSelect(p, session, gh.adminView());
            return;
        }

        if (slot == GuiStyle.slot("team-players.buttons.confirm")) { // Confirm the staged batch move
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

    // ── Shared guards ──────────────────────────────────────────────────────────────

    /**
     * Resolves the menu's session and enforces that only its host (or an admin/bypass
     * holder) may act. Returns null — with the player already messaged — otherwise.
     */
    private PrivateSession requireManageable(Player p, GuiHolder gh) {
        PrivateSession session = sessions.getById(gh.sessionId());
        if (session == null) {
            p.sendMessage(Lang.msg("general.match-gone"));
            reopenList(p, gh.adminView(), 0);
            return null;
        }
        boolean admin = p.hasPermission(GuiManager.ADMIN_PERM) || p.hasPermission(GuiManager.BYPASS_PERM);
        boolean owner = p.getUniqueId().equals(session.getOwner());
        if (!owner && !admin) {
            p.sendMessage(Lang.msg("host.only-host-menu"));
            return null;
        }
        return session;
    }
}
