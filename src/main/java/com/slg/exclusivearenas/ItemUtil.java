package com.slg.exclusivearenas;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;

public final class ItemUtil {

    private ItemUtil() {}

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
            skull.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
            if (glowing) skull.setEnchantmentGlintOverride(true);
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

    /** Stub item for features planned but not yet implemented. */
    public static ItemStack stub(String name, String description) {
        return button(Material.CLOCK, "&8✦ &7" + name + " &8(Soon)",
                "&8" + strip(description),
                "&8Not yet available.");
    }

    public static String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s == null ? "" : s);
    }

    private static String strip(String s) {
        return s == null ? "" : s;
    }
}
