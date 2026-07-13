package com.slg.exclusivearenas;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class ItemUtil {

    private ItemUtil() {}

    /** An offline player's last-known name, or {@code fallback} if it was never cached. */
    public static String offlineName(UUID playerId, String fallback) {
        OfflinePlayer off = Bukkit.getOfflinePlayer(playerId);
        return off.getName() != null ? off.getName() : fallback;
    }

    public static ItemStack button(Material mat, String name, String... lore) {
        List<String> lines = new ArrayList<>();
        if (lore != null) {
            for (String l : lore) {
                if (l != null && !l.isEmpty()) lines.add(color(l));
            }
        }
        return button(mat, name, lines);
    }

    public static ItemStack button(Material mat, String name, List<String> lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(color(name));
            if (lore != null && !lore.isEmpty()) {
                List<String> lines = new ArrayList<>(lore.size());
                for (String l : lore) if (l != null) lines.add(color(l));
                meta.setLore(lines);
            }
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS,
                    ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
            item.setItemMeta(meta);
        }
        return item;
    }

    /** A player-head button showing the given player's skin. */
    public static ItemStack head(OfflinePlayer owner, String name, List<String> lore) {
        return head(owner, name, lore, false);
    }

    /** A player-head button, optionally with an enchant glint — used to mark it as "selected". */
    public static ItemStack head(OfflinePlayer owner, String name, List<String> lore, boolean glowing) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta base = item.getItemMeta();
        if (base instanceof SkullMeta skull) {
            skull.setOwningPlayer(owner);
            skull.setDisplayName(color(name));
            if (lore != null && !lore.isEmpty()) {
                List<String> lines = new ArrayList<>(lore.size());
                for (String l : lore) if (l != null) lines.add(color(l));
                skull.setLore(lines);
            }
            skull.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP, ItemFlag.HIDE_ENCHANTS);
            if (glowing) {
                // Belt-and-suspenders: the modern glint override should be enough on its own,
                // but a real (hidden) enchant is the long-battle-tested way to force a glint on
                // a skull, in case the override alone doesn't render for every client/version.
                skull.addEnchant(Enchantment.UNBREAKING, 1, true);
                skull.setEnchantmentGlintOverride(true);
            }
            item.setItemMeta(skull);
        }
        return item;
    }

    /**
     * Clones an arena's own configured icon (falling back to a default material if it has
     * none) and applies our own display name/lore, so the arena selector shows the same icon
     * players see elsewhere in MBedwars for that map.
     */
    public static ItemStack icon(ItemStack base, Material fallback, String name, List<String> lore) {
        ItemStack item = (base != null && base.getType() != Material.AIR) ? base.clone() : new ItemStack(fallback);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(color(name));
            if (lore != null && !lore.isEmpty()) {
                List<String> lines = new ArrayList<>(lore.size());
                for (String l : lore) if (l != null) lines.add(color(l));
                meta.setLore(lines);
            }
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS,
                    ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
            item.setItemMeta(meta);
        }
        return item;
    }

    /** A blank decorative pane used to frame menus. */
    public static ItemStack pane(Material mat) {
        return button(mat, "&r");
    }

    /**
     * Forces an enchantment glint onto the item. The modern glint override should be enough
     * on its own, but a real (hidden) enchant is the long-battle-tested fallback in case the
     * override alone doesn't render for every client/version.
     */
    public static ItemStack glint(ItemStack item) {
        if (item == null) return null;
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.addEnchant(Enchantment.UNBREAKING, 1, true);
            meta.setEnchantmentGlintOverride(true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            item.setItemMeta(meta);
        }
        return item;
    }

    public static String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s == null ? "" : s);
    }
}
