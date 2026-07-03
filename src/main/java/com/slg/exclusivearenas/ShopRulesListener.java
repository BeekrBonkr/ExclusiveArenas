package com.slg.exclusivearenas;

import de.marcely.bedwars.api.BedwarsAPI;
import de.marcely.bedwars.api.event.player.PlayerBuyInShopEvent;
import de.marcely.bedwars.api.event.player.PlayerOpenShopEvent;
import de.marcely.bedwars.api.game.shop.ShopItem;
import de.marcely.bedwars.api.game.shop.price.ShopPrice;
import de.marcely.bedwars.api.game.spawner.DropType;
import org.bukkit.ChatColor;
import org.bukkit.Material;
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
 * Display: every shop open hands us a per-open CLONE of the page
 * ({@link PlayerOpenShopEvent#getClonedPage()}), so disabled items can be re-skinned as
 * red dye (or removed entirely — shop.disabled_display in config.yml) and price overrides
 * shown as the real price, without ever touching the global shop definitions.
 *
 * Enforcement: purchases are still independently checked at buy time on the buy event's own
 * per-transaction clone — the display pass is cosmetic, the buy pass is the guarantee.
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
