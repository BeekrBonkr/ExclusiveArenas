package com.slg.exclusivearenas;

import de.marcely.bedwars.api.BedwarsAPI;
import de.marcely.bedwars.api.arena.Arena;
import de.marcely.bedwars.api.event.ShopGUIPostProcessEvent;
import de.marcely.bedwars.api.event.player.PlayerBuyInShopEvent;
import de.marcely.bedwars.api.event.player.PlayerOpenShopEvent;
import de.marcely.bedwars.api.game.shop.ShopItem;
import de.marcely.bedwars.api.game.shop.price.ShopPrice;
import de.marcely.bedwars.api.game.spawner.DropType;
import de.marcely.bedwars.tools.gui.ClickableGUI;
import de.marcely.bedwars.tools.gui.GUI;
import de.marcely.bedwars.tools.gui.GUIItem;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Map;

/**
 * Enforces and displays a private match's shop overrides.
 *
 * Display: every normal shop-page open hands us a per-open CLONE of the page
 * ({@link PlayerOpenShopEvent#getClonedPage()}), so disabled items can be re-skinned as
 * red dye (or removed entirely — shop.disabled_display in config.yml) and price overrides
 * shown as the real price, without ever touching the global shop definitions. MBedwars'
 * Quick Buy grid (the shop's home screen) does NOT go through that clone — it's built from a
 * separate, fresh set of {@code ShopItem}s pulled straight from the global registry, so
 * {@link #onShopOpen} never sees (or re-skins) those instances. {@link #onShopGuiPostProcess}
 * catches that: it runs after MBedwars has fully built the GUI for either screen and rewrites
 * any still-untouched disabled item directly in the built inventory.
 *
 * Enforcement: purchases are still independently checked at buy time on the buy event's own
 * per-transaction clone — the display passes are cosmetic, the buy pass is the guarantee.
 */
public final class ShopRulesListener implements Listener {

    private final ExclusiveArenasPlugin plugin;
    private final PrivateSessionService sessions;

    /** Blocks the purchase and tells the buyer the host disabled the item. */
    private final PlayerBuyInShopEvent.Problem disabledProblem = new PlayerBuyInShopEvent.Problem() {
        @Override
        public Plugin getPlugin() {
            return plugin;
        }

        @Override
        public void handleNotification(PlayerBuyInShopEvent event) {
            event.getPlayer().sendMessage(Lang.msg("shop.blocked"));
        }
    };

    public ShopRulesListener(ExclusiveArenasPlugin plugin, PrivateSessionService sessions) {
        this.plugin = plugin;
        this.sessions = sessions;
    }

    /** Re-skins the opened shop page so the host's overrides are visible before buying. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onShopOpen(PlayerOpenShopEvent event) {
        PrivateSession session = sessions.getByArena(event.getArena());
        if (session == null) return;
        Map<String, SessionSettings.ShopOverride> overrides = session.getSettings().getShopOverrides();
        if (overrides.isEmpty()) return;

        // The Quick Buy home screen fires this event with a null cloned page (getItems() then
        // returns null too) — nothing to re-skin here on that screen; onShopGuiPostProcess
        // below covers it instead, after MBedwars has actually built that screen's GUI.
        if (event.getItems() == null) return;

        boolean removeDisabled = "remove".equalsIgnoreCase(
                plugin.getEaConfig().str("shop.disabled_display", "dye"));

        for (ShopItem item : new ArrayList<>(event.getItems())) {
            SessionSettings.ShopOverride override = overrides.get(item.getId());
            if (override == null) continue;

            try {
                if (override.isDisabled()) {
                    if (removeDisabled) {
                        event.removeShopItem(item);
                        continue;
                    }
                    String plainName = ChatColor.stripColor(ItemUtil.color(item.getDisplayName()));
                    item.setIcon(new ItemStack(Material.RED_DYE));
                    item.setName(Lang.raw("shop.disabled-name", "%item%", plainName));
                    item.setConfigDescription(Lang.raw("shop.disabled-lore"));
                    continue;
                }
                if (override.hasPriceOverride()) {
                    DropType currency = BedwarsAPI.getGameAPI().getDropTypeById(override.getCurrency());
                    if (currency == null) continue; // buy-time pass logs this case
                    for (ShopPrice price : new ArrayList<>(item.getPrices())) {
                        item.removePrice(price);
                    }
                    item.addPriceSpawner(currency, Math.max(1, override.getPrice()));
                }
            } catch (Throwable t) {
                plugin.debug("Shop display override failed for '" + item.getId() + "': " + t.getMessage());
            }
        }
    }

    /**
     * Catches disabled items on screens {@link #onShopOpen} can't reach — chiefly the Quick Buy
     * home screen, whose items are a separate clone from the one {@link PlayerOpenShopEvent}
     * exposes and so never pass through the re-skin pass above. This fires after MBedwars has
     * fully built the GUI for any screen, so instead of mutating a {@code ShopItem} (too late —
     * its icon was already read to build what's in the GUI) it rewrites the built
     * {@link GUIItem} in place. Items {@link #onShopOpen} already re-skinned are skipped (still
     * showing red dye), so normal pages aren't redundantly rebuilt.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onShopGuiPostProcess(ShopGUIPostProcessEvent event) {
        GUI gui = event.getGUI();
        if (!(gui instanceof ClickableGUI clickable)) return;

        Player player = event.getPlayer();
        Arena arena = BedwarsAPI.getGameAPI().getArenaByPlayer(player);
        PrivateSession session = sessions.getByArena(arena);
        if (session == null) return;
        Map<String, SessionSettings.ShopOverride> overrides = session.getSettings().getShopOverrides();
        if (overrides.isEmpty()) return;

        boolean removeDisabled = "remove".equalsIgnoreCase(
                plugin.getEaConfig().str("shop.disabled_display", "dye"));

        for (int slot = 0; slot < clickable.getSize(); slot++) {
            GUIItem guiItem = clickable.getItem(slot);
            if (guiItem == null) continue;
            if (!(guiItem.getAttachement() instanceof ShopItem shopItem)) continue;

            SessionSettings.ShopOverride override = overrides.get(shopItem.getId());
            if (override == null || !override.isDisabled()) continue;
            if (guiItem.getItem() != null && guiItem.getItem().getType() == Material.RED_DYE) {
                continue; // already re-skinned by onShopOpen on this same screen
            }

            try {
                if (removeDisabled) {
                    clickable.setItem((GUIItem) null, slot);
                    continue;
                }
                String plainName = ChatColor.stripColor(ItemUtil.color(shopItem.getDisplayName()));
                ItemStack icon = ItemUtil.button(Material.RED_DYE,
                        Lang.raw("shop.disabled-name", "%item%", plainName),
                        Lang.raw("shop.disabled-lore"));
                clickable.setItem(new GUIItem(icon, guiItem.getListener(), guiItem.getAttachement()), slot);
            } catch (Throwable t) {
                plugin.debug("Shop Quick Buy override failed for '" + shopItem.getId() + "': " + t.getMessage());
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onBuy(PlayerBuyInShopEvent event) {
        PrivateSession session = sessions.getByArena(event.getArena());
        if (session == null) return;

        ShopItem original = event.getItem();
        if (original == null) return;
        SessionSettings.ShopOverride override =
                session.getSettings().getShopOverride(original.getId());
        if (override == null) return;

        if (override.isDisabled()) {
            event.addProblem(disabledProblem);
            return;
        }

        if (override.hasPriceOverride()) {
            DropType currency = BedwarsAPI.getGameAPI().getDropTypeById(override.getCurrency());
            if (currency == null) {
                plugin.debug("Shop override for '" + original.getId() + "': drop type '"
                        + override.getCurrency() + "' is not registered — default price kept.");
                return;
            }
            ShopItem clone = event.getClonedItem();
            for (ShopPrice price : new ArrayList<>(clone.getPrices())) {
                clone.removePrice(price);
            }
            clone.addPriceSpawner(currency, Math.max(1, override.getPrice()));
        }
    }
}
