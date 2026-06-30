package com.slg.exclusivearenas;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

public final class ItemUtil {

    private ItemUtil() {}

    public static ItemStack button(Material mat, String name, String... lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(color(name));
            if (lore != null && lore.length > 0) {
                List<String> lines = new ArrayList<>();
                for (String l : lore) {
                    if (l != null && !l.isEmpty()) lines.add(color(l));
                }
                meta.setLore(lines);
            }
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            item.setItemMeta(meta);
        }
        return item;
    }

    /** Stub item for features planned but not yet implemented. */
    public static ItemStack stub(String name, String description) {
        return button(Material.CLOCK, "&7" + name,
                "&8[Coming Soon]",
                "&7" + description);
    }

    public static String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s == null ? "" : s);
    }
}
