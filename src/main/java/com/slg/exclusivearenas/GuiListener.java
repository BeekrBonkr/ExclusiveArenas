package com.slg.exclusivearenas;

import de.marcely.bedwars.api.BedwarsAPI;
import de.marcely.bedwars.api.arena.Arena;
import de.marcely.bedwars.api.arena.ArenaTimeType;
import de.marcely.bedwars.api.arena.ArenaWeatherType;
import de.marcely.bedwars.api.arena.Team;
import de.marcely.bedwars.api.game.shop.ShopItem;
import de.marcely.bedwars.api.game.spawner.DropType;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
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

    /**
     * A drag gesture is a separate event from a click and was never cancelled here — several
     * list/grid menus leave real AIR holes in their interior whenever the entry count is below
     * the slot template's capacity, and a drag spanning one of those holes and the player's own
     * inventory would silently swallow the dragged item when the (throwaway) menu closes.
     */
    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        InventoryHolder holder = e.getView().getTopInventory().getHolder();
        if (holder instanceof GuiHolder) e.setCancelled(true);
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

        switch (gh.type()) {
            case MAIN        -> handleMain(p, slot);
            case ARENA_LIST  -> handleList(p, gh, slot, false);
            case ADMIN_LIST  -> handleList(p, gh, slot, true);
            case BUILDER     -> handleBuilder(p, slot, e.isShiftClick());
            case ARENA_SELECT-> handleArenaSelect(p, gh, slot);
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
            case TEAM_SIZE   -> handleTeamSize(p, gh, slot);
            case BUILDER_SETTINGS -> handleBuilderSettings(p, slot);
            case ENVIRONMENT -> handleEnvironment(p, gh, slot);
            case TIMELINE_ADD -> handleTimelineAdd(p, gh, slot);
            case QUICK_FORCE_WIN -> handleForceWin(p, gh, slot);
            case QUICK_GRANT_EFFECT -> handleGrantEffect(p, gh, slot);
            case MATCH_RULES -> handleMatchRules(p, gh, slot);
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
        // arena-management / create-arena / match-controls are three looks of the same
        // context-sensitive button (they share a slot) — decide from live state at click
        // time, exactly mirroring the render branch in GuiManager.openMainMenu, rather than
        // trusting whichever template happened to be visible when the menu was drawn.
        if (slot == GuiStyle.slot("main.buttons.arena-management")
                || slot == GuiStyle.slot("main.buttons.create-arena")
                || slot == GuiStyle.slot("main.buttons.match-controls")) {
            java.util.List<PrivateSession> owned = sessions.getSessionsByOwner(p.getUniqueId());
            int limit = plugin.getArenaLimit(p);
            if (owned.isEmpty()) {
                plugin.openBuilderMenu(p);
            } else if (owned.size() == 1 && limit <= 1) {
                gui.openControls(p, owned.get(0), false);
            } else {
                gui.openArenaList(p, 0);
            }
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
        // GuiRefreshTask keeps an open Admin List re-rendering (and thus still navigable) for as
        // long as it stays open, regardless of whether the viewer's permission was revoked in
        // the meantime — re-check live here rather than trusting that opening it once was enough,
        // so a since-revoked admin can't keep browsing into other players' session details.
        if (admin && !p.hasPermission(GuiManager.ADMIN_PERM)) {
            p.closeInventory();
            return;
        }

        String menu = admin ? "admin-list" : "arena-list";
        int page = gh.page();

        if (slot == GuiStyle.slot(menu + ".buttons.previous-page")) { reopenList(p, admin, page - 1); return; }
        if (slot == GuiStyle.slot(menu + ".buttons.next-page")) { reopenList(p, admin, page + 1); return; }
        if (slot == GuiStyle.slot(menu + ".buttons.back")) { gui.openMainMenu(p); return; }
        if (slot == GuiStyle.slot(menu + ".buttons.refresh")) { reopenList(p, admin, page); return; }
        if (admin && slot == GuiStyle.slot("admin-list.buttons.close")) { p.closeInventory(); return; }
        if (!admin && slot == GuiStyle.slot("arena-list.buttons.create")) { plugin.openBuilderMenu(p); return; }

        UUID sessionId = gh.idAt(slot);
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
            p.sendMessage(Lang.msg(d.getBlockReason() == DraftPrivateMatch.BlockReason.MEMBER_HOSTING
                    ? "create.member-hosting-menu" : "create.party-blocked-menu"));
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
        } else if (slot == GuiStyle.slot("builder.buttons.arena-settings") && d.getArenaName() != null) {
            gui.openBuilderSettings(p, d);
        } else if (slot == GuiStyle.slot("builder.buttons.back")) {
            releaseDraftArena(p, d);
            gui.openArenaList(p, 0);
        } else if (slot == GuiStyle.slot("builder.buttons.close")) {
            releaseDraftArena(p, d);
            p.closeInventory();
        }
    }

    /** Gives up a drafted (not-yet-created) arena pick so someone else can select it. */
    private void releaseDraftArena(Player p, DraftPrivateMatch d) {
        if (d.getArenaName() != null) sessions.releaseDraftArena(d.getArenaName(), p.getUniqueId());
    }

    // ── Arena selector ──────────────────────────────────────────────────────────────

    private void handleArenaSelect(Player p, GuiHolder gh, int slot) {
        int page = gh.page();
        int teamFilter = gh.teamFilter();
        int ppFilter = gh.playersPerTeamFilter();

        if (slot == GuiStyle.slot("arena-select.buttons.previous-page")) { gui.openArenaSelect(p, page - 1, teamFilter, ppFilter); return; }
        if (slot == GuiStyle.slot("arena-select.buttons.next-page")) { gui.openArenaSelect(p, page + 1, teamFilter, ppFilter); return; }
        if (slot == GuiStyle.slot("arena-select.buttons.team-filter")) { gui.openArenaSelect(p, 0, gui.cycleTeamFilter(teamFilter), ppFilter); return; }
        if (slot == GuiStyle.slot("arena-select.buttons.players-filter")) { gui.openArenaSelect(p, 0, teamFilter, gui.cyclePlayersPerTeamFilter(ppFilter)); return; }
        if (slot == GuiStyle.slot("arena-select.buttons.back")) { gui.openBuilder(p); return; }

        // Resolved from the slot->name map built when this page was rendered, not the clicked
        // item's display text — the "no arenas match" empty-state pane sits in the same slot
        // family as real entries and isn't mapped, so clicking it is now correctly a no-op.
        String name = gh.keyAt(slot);
        if (name == null || name.isBlank()) return;

        if (!sessions.reserveDraftArena(name, p.getUniqueId())) {
            p.sendMessage(Lang.msg("create.arena-reserved"));
            return;
        }

        DraftPrivateMatch d = drafts.getOrCreate(p.getUniqueId());
        if (d.getArenaName() != null && !d.getArenaName().equalsIgnoreCase(name)) {
            sessions.releaseDraftArena(d.getArenaName(), p.getUniqueId());
        }
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
        } else if (slot == GuiStyle.slot("arena-config.buttons.team-size")) {
            gui.openTeamSize(p, session, gh.adminView());
        } else if (slot == GuiStyle.slot("arena-config.buttons.match-rules")) {
            gui.openMatchRules(p, session, gh.adminView());
        } else if (slot == GuiStyle.slot("arena-config.buttons.environment")) {
            gui.openEnvironment(p, session, gh.adminView());
        } else if (slot == GuiStyle.slot("arena-config.buttons.back")) {
            gui.openControls(p, session, gh.adminView());
        }
    }

    // ── Arena settings hub, during creation ─────────────────────────────────────────

    private void handleBuilderSettings(Player p, int slot) {
        DraftPrivateMatch draft = drafts.get(p.getUniqueId());
        if (draft == null || draft.getArenaName() == null) {
            p.sendMessage(Lang.msg("create.select-map-first"));
            gui.openBuilder(p);
            return;
        }

        if (slot == GuiStyle.slot("builder-settings.buttons.event-timeline")) {
            if (plugin.getTimelineService().isEnabled()) gui.openTimeline(p, draft, false, null);
        } else if (slot == GuiStyle.slot("builder-settings.buttons.shop-config")) {
            gui.openShopPages(p, draft, false);
        } else if (slot == GuiStyle.slot("builder-settings.buttons.team-size")) {
            gui.openTeamSize(p, draft, false);
        } else if (slot == GuiStyle.slot("builder-settings.buttons.match-rules")) {
            gui.openMatchRules(p, draft, false);
        } else if (slot == GuiStyle.slot("builder-settings.buttons.back")) {
            gui.openBuilder(p);
        }
    }

    // ── Team size editor ──────────────────────────────────────────────────────────────

    private void handleTeamSize(Player p, GuiHolder gh, int slot) {
        SettingsHolder holder = resolveSettingsHolder(p, gh);
        if (holder == null) return;
        boolean adminView = gh.adminView();

        if (slot == GuiStyle.slot("team-size.buttons.back")) {
            openSettingsHub(p, holder, adminView);
            return;
        }

        Arena arena = BedwarsAPI.getGameAPI().getArenaByExactName(holder.getArenaName());
        if (arena == null || !arena.exists()) return; // only "back" works on the unavailable view

        // A draft has no live match to disrupt yet — always editable, and there's nothing to
        // restore-to, unlike a live session's locked-once-someone-joins rule.
        boolean isDraft = !(holder instanceof PrivateSession);
        if (!isDraft && !plugin.canChangeTeamSize(arena)) {
            if (slot == GuiStyle.slot("team-size.buttons.minus-one")
                    || slot == GuiStyle.slot("team-size.buttons.plus-one")
                    || slot == GuiStyle.slot("team-size.buttons.reset")) {
                p.sendMessage(Lang.msg("teamsize.locked"));
            }
            return;
        }

        Integer original = holder instanceof PrivateSession session ? session.getOriginalPlayersPerTeam() : null;
        int fallback = original != null ? original : arena.getPlayersPerTeam();
        Integer override = holder.getSettings().getPlayersPerTeam();
        int current = override != null ? override : fallback;

        Integer next = null;
        boolean changed = false;
        if (slot == GuiStyle.slot("team-size.buttons.minus-one")) {
            next = Math.max(GuiManager.MIN_PLAYERS_PER_TEAM, current - 1);
            changed = true;
        } else if (slot == GuiStyle.slot("team-size.buttons.plus-one")) {
            next = Math.min(GuiManager.MAX_PLAYERS_PER_TEAM, current + 1);
            changed = true;
        } else if (slot == GuiStyle.slot("team-size.buttons.reset")) {
            next = null; // back to the arena's own default
            changed = true;
        }
        if (!changed) return;

        holder.getSettings().setPlayersPerTeam(next != null && next != fallback ? next : null);
        if (holder instanceof PrivateSession session) {
            sessions.saveSettings(session);
            plugin.applyPlayersPerTeamOverride(arena, session);
        }
        p.sendMessage(Lang.msg("teamsize.changed",
                "%amount%", String.valueOf(next != null ? next : fallback)));
        gui.openTeamSize(p, holder, adminView);
    }

    // ── Match rules ──────────────────────────────────────────────────────────────────

    private void handleMatchRules(Player p, GuiHolder gh, int slot) {
        SettingsHolder holder = resolveSettingsHolder(p, gh);
        if (holder == null) return;
        boolean adminView = gh.adminView();

        if (slot == GuiStyle.slot("match-rules.buttons.back")) {
            openSettingsHub(p, holder, adminView);
            return;
        }
        // Standing rules only take effect from the next round start onward — editing them
        // mid-match wouldn't visibly do anything for most of them, so gate the same as the
        // timeline editor (lobby-only for a live session; always open for a draft).
        if (!timelineEditable(p, holder)) return;

        SessionSettings.ArenaModifiers mods = holder.getSettings().getModifiers();

        if (slot == GuiStyle.slot("match-rules.buttons.reset-all")) {
            mods.setFriendlyFire(false);
            mods.setNoFallDamage(false);
            mods.setNoExplosionBlockDamage(false);
            mods.setKillBountyMultiplier(0);
            mods.setShopCurrencyMultiplier(1.0);
            mods.setBonusStartingKit(false);
            mods.setPvpGraceSeconds(0);
            mods.setHealthMultiplier(1.0);
            mods.setWorldBorderShrink(false);
            mods.setBedRespawnOnce(false);
            mods.setSpawnProtectionSeconds(0);
            mods.setKillGoal(0);
        } else if (slot == GuiStyle.slot("match-rules.buttons.friendly-fire")) {
            mods.setFriendlyFire(!mods.isFriendlyFire());
        } else if (slot == GuiStyle.slot("match-rules.buttons.fall-damage")) {
            mods.setNoFallDamage(!mods.isNoFallDamage());
        } else if (slot == GuiStyle.slot("match-rules.buttons.explosion-damage")) {
            mods.setNoExplosionBlockDamage(!mods.isNoExplosionBlockDamage());
        } else if (slot == GuiStyle.slot("match-rules.buttons.kill-bounty")) {
            mods.setKillBountyMultiplier((mods.getKillBountyMultiplier() + 1) % 4);
        } else if (slot == GuiStyle.slot("match-rules.buttons.shop-multiplier")) {
            mods.setShopCurrencyMultiplier(cycleDouble(mods.getShopCurrencyMultiplier(), 1.0, 0.5, 1.5, 2.0));
        } else if (slot == GuiStyle.slot("match-rules.buttons.bonus-kit")) {
            mods.setBonusStartingKit(!mods.isBonusStartingKit());
        } else if (slot == GuiStyle.slot("match-rules.buttons.pvp-grace")) {
            mods.setPvpGraceSeconds(cycleInt(mods.getPvpGraceSeconds(), 0, 15, 30, 60));
        } else if (slot == GuiStyle.slot("match-rules.buttons.health-multiplier")) {
            mods.setHealthMultiplier(cycleDouble(mods.getHealthMultiplier(), 1.0, 1.5, 2.0, 0.5));
        } else if (slot == GuiStyle.slot("match-rules.buttons.world-border")) {
            mods.setWorldBorderShrink(!mods.isWorldBorderShrink());
        } else if (slot == GuiStyle.slot("match-rules.buttons.bed-respawn")) {
            mods.setBedRespawnOnce(!mods.isBedRespawnOnce());
        } else if (slot == GuiStyle.slot("match-rules.buttons.spawn-protection")) {
            mods.setSpawnProtectionSeconds(cycleInt(mods.getSpawnProtectionSeconds(), 0, 5, 10, 20));
        } else if (slot == GuiStyle.slot("match-rules.buttons.kill-goal")) {
            mods.setKillGoal(cycleInt(mods.getKillGoal(), 0, 10, 20, 30));
        } else {
            return;
        }

        if (holder instanceof PrivateSession session) sessions.saveSettings(session);
        gui.openMatchRules(p, holder, adminView);
    }

    private static int cycleInt(int current, int... values) {
        for (int i = 0; i < values.length; i++) {
            if (values[i] == current) return values[(i + 1) % values.length];
        }
        return values[0];
    }

    private static double cycleDouble(double current, double... values) {
        for (int i = 0; i < values.length; i++) {
            if (values[i] == current) return values[(i + 1) % values.length];
        }
        return values[0];
    }

    // ── Environment (time / weather) ─────────────────────────────────────────────────

    private static final ArenaWeatherType[] WEATHER_CYCLE =
            {ArenaWeatherType.UNTOUCHED, ArenaWeatherType.CLEAR, ArenaWeatherType.RAINING};
    private static final ArenaTimeType[] TIME_CYCLE =
            {ArenaTimeType.UNTOUCHED, ArenaTimeType.NOON, ArenaTimeType.SUNSET, ArenaTimeType.NIGHT};

    private void handleEnvironment(Player p, GuiHolder gh, int slot) {
        PrivateSession session = requireManageable(p, gh);
        if (session == null) return;

        if (slot == GuiStyle.slot("environment.buttons.back")) {
            gui.openArenaConfig(p, session, gh.adminView());
            return;
        }

        Arena arena = BedwarsAPI.getGameAPI().getArenaByExactName(session.getArenaName());
        if (arena == null || !arena.exists()) return;

        if (slot == GuiStyle.slot("environment.buttons.weather")) {
            ArenaWeatherType current = ExclusiveArenasPlugin.parseWeatherType(session.getSettings().getWeatherType());
            ArenaWeatherType next = WEATHER_CYCLE[(indexOf(WEATHER_CYCLE, current) + 1) % WEATHER_CYCLE.length];
            session.getSettings().setWeatherType(next == ArenaWeatherType.UNTOUCHED ? null : next.name());
            sessions.saveSettings(session);
            plugin.applyEnvironmentOverride(arena, session);
        } else if (slot == GuiStyle.slot("environment.buttons.time")) {
            ArenaTimeType current = ExclusiveArenasPlugin.parseTimeType(session.getSettings().getTimeType());
            ArenaTimeType next = TIME_CYCLE[(indexOf(TIME_CYCLE, current) + 1) % TIME_CYCLE.length];
            session.getSettings().setTimeType(next == ArenaTimeType.UNTOUCHED ? null : next.name());
            sessions.saveSettings(session);
            plugin.applyEnvironmentOverride(arena, session);
        } else {
            return;
        }
        gui.openEnvironment(p, session, gh.adminView());
    }

    private static <T> int indexOf(T[] array, T value) {
        for (int i = 0; i < array.length; i++) if (array[i] == value) return i;
        return 0;
    }

    // ── Saved configurations (presets) ───────────────────────────────────────────────

    /**
     * Loads the session host's presets off-thread, then opens the menu with them. Always keyed
     * by the session's owner — not whichever player is clicking — so an admin managing someone
     * else's match sees (and saves into) that host's own presets, not their own.
     */
    private void openPresetsFor(Player p, GuiHolder gh) {
        PrivateSession session = sessions.getById(gh.sessionId());
        if (session == null) {
            p.sendMessage(Lang.msg("general.match-gone"));
            return;
        }
        plugin.getPresetService().list(session.getOwner(), presets -> {
            PrivateSession live = sessions.getById(gh.sessionId());
            if (live == null) {
                p.sendMessage(Lang.msg("general.match-gone"));
                return;
            }
            gui.openPresets(p, live, gh.adminView(), presets);
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
            plugin.getPresetService().delete(session.getOwner(), name);
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
        plugin.getPresetService().save(session.getOwner(), name, json, ok -> {
            if (!p.isOnline()) return;
            // Re-fetch rather than trusting the closure-captured session — the match may have
            // ended while this database-mode save was in flight (matches openPresetsFor's guard).
            PrivateSession live = sessions.getById(session.getSessionId());
            if (live == null) {
                p.sendMessage(Lang.msg("general.match-gone"));
                return;
            }
            if (!ok) {
                p.sendMessage(Lang.msg("presets.save-failed", "%name%", name));
                gui.openPresets(p, live, gh.adminView(), presets); // unchanged — nothing was persisted
                return;
            }
            p.sendMessage(Lang.msg("presets.saved", "%name%", name));
            presets.put(name, json);
            gui.openPresets(p, live, gh.adminView(), presets);
        });
    }

    // ── Quick actions ───────────────────────────────────────────────────────────────

    private void handleQuickActions(Player p, GuiHolder gh, int slot) {
        PrivateSession session = requireManageable(p, gh);
        if (session == null) return;

        if (slot == GuiStyle.slot("quick-actions.buttons.back")) {
            gui.openControls(p, session, gh.adminView());
            return;
        }
        if (slot == GuiStyle.slot("quick-actions.buttons.force-win")) {
            gui.openForceWin(p, session, gh.adminView());
            return;
        }
        if (slot == GuiStyle.slot("quick-actions.buttons.grant-effect")) {
            gui.openGrantEffect(p, session, gh.adminView());
            return;
        }
        if (slot == GuiStyle.slot("quick-actions.buttons.swap-teams-info")) {
            p.sendMessage(Lang.msg("quick.swap-teams-hint"));
            return;
        }
        if (slot == GuiStyle.slot("quick-actions.buttons.reveal-border")) {
            Arena arena = BedwarsAPI.getGameAPI().getArenaByExactName(session.getArenaName());
            if (arena == null || !arena.exists()) {
                p.sendMessage(Lang.msg("teams.requires-local"));
                return;
            }
            plugin.getQuickActions().revealBorder(p, arena);
            return;
        }
        if (slot == GuiStyle.slot("quick-actions.buttons.extend-timer")) {
            plugin.runArenaAction(p, session, RemoteCommandService.Type.QUICK_ADJUST_TIMER, "120");
            return;
        }
        if (slot == GuiStyle.slot("quick-actions.buttons.shorten-timer")) {
            plugin.runArenaAction(p, session, RemoteCommandService.Type.QUICK_ADJUST_TIMER, "-120");
            return;
        }

        RemoteCommandService.Type type = null;
        if (slot == GuiStyle.slot("quick-actions.buttons.regenerate-map")) type = RemoteCommandService.Type.QUICK_REGEN;
        else if (slot == GuiStyle.slot("quick-actions.buttons.heal-all")) type = RemoteCommandService.Type.QUICK_HEAL;
        else if (slot == GuiStyle.slot("quick-actions.buttons.drop-spawners")) type = RemoteCommandService.Type.QUICK_DROP;
        else if (slot == GuiStyle.slot("quick-actions.buttons.destroy-beds")) type = RemoteCommandService.Type.QUICK_BEDS;
        else if (slot == GuiStyle.slot("quick-actions.buttons.clear-items")) type = RemoteCommandService.Type.QUICK_CLEAR;
        else if (slot == GuiStyle.slot("quick-actions.buttons.skip-event")) type = RemoteCommandService.Type.QUICK_SKIP_EVENT;
        else if (slot == GuiStyle.slot("quick-actions.buttons.balance-teams")) type = RemoteCommandService.Type.QUICK_BALANCE_TEAMS;
        else if (slot == GuiStyle.slot("quick-actions.buttons.trigger-trap")) type = RemoteCommandService.Type.QUICK_TRIGGER_TRAP;
        else if (slot == GuiStyle.slot("quick-actions.buttons.clear-traps")) type = RemoteCommandService.Type.QUICK_CLEAR_TRAPS;
        else if (slot == GuiStyle.slot("quick-actions.buttons.reset-upgrades")) type = RemoteCommandService.Type.QUICK_RESET_UPGRADES;
        else if (slot == GuiStyle.slot("quick-actions.buttons.toggle-freeze")) type = RemoteCommandService.Type.QUICK_TOGGLE_FREEZE;
        else if (slot == GuiStyle.slot("quick-actions.buttons.force-rejoin")) type = RemoteCommandService.Type.QUICK_FORCE_REJOIN;
        else if (slot == GuiStyle.slot("quick-actions.buttons.toggle-pvp")) type = RemoteCommandService.Type.QUICK_TOGGLE_PVP;
        else if (slot == GuiStyle.slot("quick-actions.buttons.strip-inventories")) type = RemoteCommandService.Type.QUICK_STRIP_INVENTORIES;
        else if (slot == GuiStyle.slot("quick-actions.buttons.comeback-buff")) type = RemoteCommandService.Type.QUICK_COMEBACK_BUFF;
        else if (slot == GuiStyle.slot("quick-actions.buttons.random-scatter")) type = RemoteCommandService.Type.QUICK_RANDOM_SCATTER;
        else if (slot == GuiStyle.slot("quick-actions.buttons.kick-afk")) type = RemoteCommandService.Type.QUICK_KICK_AFK;
        else if (slot == GuiStyle.slot("quick-actions.buttons.reset-shop-prices")) type = RemoteCommandService.Type.QUICK_RESET_SHOP_PRICES;
        else if (slot == GuiStyle.slot("quick-actions.buttons.give-compass")) type = RemoteCommandService.Type.QUICK_GIVE_COMPASS;
        else if (slot == GuiStyle.slot("quick-actions.buttons.announce-stats")) type = RemoteCommandService.Type.QUICK_ANNOUNCE_STATS;
        else if (slot == GuiStyle.slot("quick-actions.buttons.toggle-pause")) type = RemoteCommandService.Type.QUICK_TOGGLE_PAUSE;
        if (type == null) return;

        if (type == RemoteCommandService.Type.QUICK_REGEN) {
            // Regen cycles the host through spectator and back to player; a menu left open
            // the whole time is the one thing that reliably kept the host from being reseated.
            p.closeInventory();
        }
        plugin.runArenaAction(p, session, type);
    }

    private void handleForceWin(Player p, GuiHolder gh, int slot) {
        PrivateSession session = requireManageable(p, gh);
        if (session == null) return;

        if (slot == GuiStyle.slot("quick-force-win.buttons.back")) {
            gui.openQuickActions(p, session, gh.adminView());
            return;
        }
        String teamName = gh.keyAt(slot);
        if (teamName == null) return;
        plugin.runArenaAction(p, session, RemoteCommandService.Type.QUICK_FORCE_WIN, teamName);
        p.closeInventory();
    }

    private void handleGrantEffect(Player p, GuiHolder gh, int slot) {
        PrivateSession session = requireManageable(p, gh);
        if (session == null) return;

        if (slot == GuiStyle.slot("quick-grant-effect.buttons.back")) {
            gui.openQuickActions(p, session, gh.adminView());
            return;
        }
        String payload = null;
        if (slot == GuiStyle.slot("quick-grant-effect.buttons.speed")) payload = "SPEED:1:60";
        else if (slot == GuiStyle.slot("quick-grant-effect.buttons.jump")) payload = "JUMP:1:60";
        else if (slot == GuiStyle.slot("quick-grant-effect.buttons.regen")) payload = "REGENERATION:1:60";
        else if (slot == GuiStyle.slot("quick-grant-effect.buttons.strength")) payload = "STRENGTH:1:60";
        if (payload == null) return;

        plugin.runArenaAction(p, session, RemoteCommandService.Type.QUICK_GRANT_EFFECT, payload);
        p.closeInventory();
    }

    // ── Event timeline editor ────────────────────────────────────────────────────────

    private void handleTimeline(Player p, GuiHolder gh, int slot) {
        SettingsHolder holder = resolveSettingsHolder(p, gh);
        if (holder == null) return;
        boolean adminView = gh.adminView();

        TimelineService timelines = plugin.getTimelineService();

        if (slot == GuiStyle.slot("timeline.buttons.back")) {
            openSettingsHub(p, holder, adminView);
            return;
        }
        if (slot == GuiStyle.slot("timeline.buttons.close")) {
            p.closeInventory();
            return;
        }
        if (slot == GuiStyle.slot("timeline.buttons.add-event")) {
            if (!timelineEditable(p, holder)) return;
            gui.openTimelineAdd(p, holder, adminView);
            return;
        }
        if (slot == GuiStyle.slot("timeline.buttons.reset")) {
            if (!timelineEditable(p, holder)) return;
            timelines.resetTimeline(holder.getSettings());
            persist(holder);
            p.sendMessage(Lang.msg("timeline.reset"));
            gui.openTimeline(p, holder, adminView, null);
            return;
        }

        // Selecting / deselecting an event on the strip.
        String clickedEvent = gh.keyAt(slot);
        if (clickedEvent != null) {
            String next = clickedEvent.equals(gh.selectedEvent()) ? null : clickedEvent;
            gui.openTimeline(p, holder, adminView, next);
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
            if (!timelineEditable(p, holder)) return;
            int newTime = timelines.moveEvent(holder.getSettings(), selected, delta);
            if (newTime >= 0) {
                persist(holder);
                TimelineService.Definition def = timelines.definitionFor(holder.getSettings(), selected);
                boolean isEnd = def != null && def.type() == TimelineService.Type.MATCH_END;
                p.sendMessage(Lang.msg(isEnd ? "timeline.end-moved" : "timeline.moved",
                        "%event%", def != null ? def.name() : selected,
                        "%time%", TimelineService.format(newTime)));
            }
            gui.openTimeline(p, holder, adminView, selected);
            return;
        }

        if (slot == GuiStyle.slot("timeline.buttons.delete")) {
            if (!timelineEditable(p, holder)) return;
            TimelineService.Definition def = timelines.definitionFor(holder.getSettings(), selected);
            if (def != null && def.type() == TimelineService.Type.MATCH_END) {
                p.sendMessage(Lang.msg("timeline.cannot-delete-end"));
                return;
            }
            if (timelines.deleteEvent(holder.getSettings(), selected)) {
                persist(holder);
                p.sendMessage(Lang.msg("timeline.deleted",
                        "%event%", def != null ? def.name() : selected));
            }
            gui.openTimeline(p, holder, adminView, null);
        }
    }

    /**
     * The timeline engine snapshots a match's schedule once at round start and never re-reads
     * it, so an edit made while the round is already RUNNING would silently have no effect
     * until the next round despite the GUI confirming success — same reasoning as team-select
     * being gated to the lobby. Always editable for a not-yet-created draft (no arena yet).
     */
    private boolean timelineEditable(Player p, SettingsHolder holder) {
        if (!(holder instanceof PrivateSession session)) return true;
        Arena arena = BedwarsAPI.getGameAPI().getArenaByExactName(session.getArenaName());
        if (arena != null && !arena.getStatus().isLobby()) {
            p.sendMessage(Lang.msg("timeline.lobby-only"));
            return false;
        }
        return true;
    }

    private void handleTimelineAdd(Player p, GuiHolder gh, int slot) {
        SettingsHolder holder = resolveSettingsHolder(p, gh);
        if (holder == null) return;
        boolean adminView = gh.adminView();

        if (slot == GuiStyle.slot("timeline-add.buttons.back")) {
            gui.openTimeline(p, holder, adminView, null);
            return;
        }
        if (!timelineEditable(p, holder)) return;

        String id = gh.keyAt(slot);
        if (id == null) return;

        TimelineService timelines = plugin.getTimelineService();
        TimelineService.Definition def = timelines.definition(id);
        int time = def != null ? def.defaultSeconds() : 60;
        if (!timelines.addEvent(holder.getSettings(), id, time)) {
            p.sendMessage(Lang.msg("timeline.add-failed"));
            return;
        }
        persist(holder);
        p.sendMessage(Lang.msg("timeline.added", "%event%", def != null ? def.name() : id));
        gui.openTimeline(p, holder, adminView, id);
    }

    /** Syncs a live session's settings across the network; a no-op for a not-yet-created draft. */
    private void persist(SettingsHolder holder) {
        if (holder instanceof PrivateSession session) sessions.saveSettings(session);
    }

    /** "Back" from Timeline/Shop/Team Size — the real Arena Modifiers hub, or the builder's, for a draft. */
    private void openSettingsHub(Player p, SettingsHolder holder, boolean adminView) {
        if (holder instanceof PrivateSession session) {
            gui.openArenaConfig(p, session, adminView);
        } else {
            gui.openBuilderSettings(p, (DraftPrivateMatch) holder);
        }
    }

    // ── Shop configuration ───────────────────────────────────────────────────────────

    private void handleShopPages(Player p, GuiHolder gh, int slot) {
        SettingsHolder holder = resolveSettingsHolder(p, gh);
        if (holder == null) return;
        boolean adminView = gh.adminView();

        if (slot == GuiStyle.slot("shop-pages.buttons.back")) {
            openSettingsHub(p, holder, adminView);
            return;
        }
        if (slot == GuiStyle.slot("shop-pages.buttons.reset")) {
            holder.getSettings().clearShopOverrides();
            persist(holder);
            p.sendMessage(Lang.msg("shop.reset-all"));
            gui.openShopPages(p, holder, adminView);
            return;
        }

        String pageName = gh.keyAt(slot);
        if (pageName != null) gui.openShopItems(p, holder, adminView, pageName, 0);
    }

    private void handleShopItems(Player p, GuiHolder gh, int slot, boolean shiftClick) {
        SettingsHolder holder = resolveSettingsHolder(p, gh);
        if (holder == null) return;
        boolean adminView = gh.adminView();

        if (slot == GuiStyle.slot("shop-items.buttons.back")) {
            gui.openShopPages(p, holder, adminView);
            return;
        }
        if (slot == GuiStyle.slot("shop-items.buttons.previous-page")) {
            gui.openShopItems(p, holder, adminView, gh.shopPage(), gh.page() - 1);
            return;
        }
        if (slot == GuiStyle.slot("shop-items.buttons.next-page")) {
            gui.openShopItems(p, holder, adminView, gh.shopPage(), gh.page() + 1);
            return;
        }

        String itemId = gh.keyAt(slot);
        if (itemId == null) return;
        ShopItem item = BedwarsAPI.getGameAPI().getShopItemById(itemId);
        if (item == null) return;

        if (shiftClick) {
            gui.openShopPrice(p, holder, adminView, gh.shopPage(), itemId);
            return;
        }

        SessionSettings.ShopOverride override = holder.getSettings().getOrCreateShopOverride(itemId);
        override.setDisabled(!override.isDisabled());
        boolean nowDisabled = override.isDisabled();
        holder.getSettings().pruneShopOverride(itemId);
        persist(holder);

        String itemName = ChatColor.stripColor(ItemUtil.color(item.getDisplayName()));
        p.sendMessage(Lang.msg(nowDisabled ? "shop.item-disabled" : "shop.item-enabled",
                "%item%", itemName));
        gui.openShopItems(p, holder, adminView, gh.shopPage(), gh.page());
    }

    private void handleShopPrice(Player p, GuiHolder gh, int slot) {
        SettingsHolder holder = resolveSettingsHolder(p, gh);
        if (holder == null) return;
        boolean adminView = gh.adminView();

        String itemId = gh.shopItem();
        ShopItem item = itemId != null ? BedwarsAPI.getGameAPI().getShopItemById(itemId) : null;
        if (item == null) {
            gui.openShopPages(p, holder, adminView);
            return;
        }
        String itemName = ChatColor.stripColor(ItemUtil.color(item.getDisplayName()));

        if (slot == GuiStyle.slot("shop-price.buttons.back")) {
            gui.openShopItems(p, holder, adminView, gh.shopPage(), 0);
            return;
        }
        if (slot == GuiStyle.slot("shop-price.buttons.reset")) {
            SessionSettings.ShopOverride override = holder.getSettings().getShopOverride(itemId);
            if (override != null) {
                override.setPrice(null, null);
                holder.getSettings().pruneShopOverride(itemId);
                persist(holder);
            }
            p.sendMessage(Lang.msg("shop.price-reset", "%item%", itemName));
            gui.openShopPrice(p, holder, adminView, gh.shopPage(), itemId);
            return;
        }

        // Current effective price (override or default) as the editing base.
        SessionSettings.ShopOverride override = holder.getSettings().getOrCreateShopOverride(itemId);
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
            holder.getSettings().pruneShopOverride(itemId);
            return;
        }

        override.setPrice(amount, currency);
        persist(holder);
        p.sendMessage(Lang.msg("shop.price-set",
                "%item%", itemName,
                "%amount%", String.valueOf(amount),
                "%currency%", GuiManager.currencyLabel(currency)));
        gui.openShopPrice(p, holder, adminView, gh.shopPage(), itemId);
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
        if (slot == GuiStyle.slot("team-select.buttons.distribute")) {
            plugin.distributePlayersToTeams(p, session);
            gui.openTeamSelect(p, session, gh.adminView());
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

        UUID targetId = gh.idAt(slot);
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
    /**
     * Resolves either a live session (via {@link #requireManageable}) or — when the menu was
     * opened from the builder, signalled by a null {@code sessionId} — the clicking player's own
     * in-progress draft. Messages the player and returns null if neither applies.
     */
    private SettingsHolder resolveSettingsHolder(Player p, GuiHolder gh) {
        if (gh.sessionId() != null) {
            return requireManageable(p, gh);
        }
        DraftPrivateMatch draft = drafts.get(p.getUniqueId());
        if (draft == null || draft.getArenaName() == null) {
            p.sendMessage(Lang.msg("create.select-map-first"));
            gui.openBuilder(p);
            return null;
        }
        return draft;
    }

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
