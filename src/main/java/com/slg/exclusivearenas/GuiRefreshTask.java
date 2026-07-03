package com.slg.exclusivearenas;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * Re-renders open menus whose lore shows live data (match state, timers, player counts)
 * once a second, in place — items are swapped inside the same inventory, so there is no
 * reopen flicker and the viewer's cursor stays put.
 *
 * Cost when nothing relevant is open is one holder-instanceof check per online player.
 */
public final class GuiRefreshTask extends BukkitRunnable {

    private final ExclusiveArenasPlugin plugin;
    private final PrivateSessionService sessions;
    private final GuiManager gui;

    public GuiRefreshTask(ExclusiveArenasPlugin plugin, PrivateSessionService sessions, GuiManager gui) {
        this.plugin = plugin;
        this.sessions = sessions;
        this.gui = gui;
    }

    @Override
    public void run() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            Inventory top = p.getOpenInventory().getTopInventory();
            if (top.getType() == InventoryType.CRAFTING) continue; // nothing open
            if (!(top.getHolder() instanceof GuiHolder holder)) continue;
            if (!isLive(holder.type())) continue;

            try {
                if (!gui.refreshInPlace(p, holder)) {
                    // The menu's subject vanished (match ended) — close rather than show a ghost.
                    p.closeInventory();
                    p.sendMessage(Lang.msg("general.match-gone"));
                }
            } catch (Throwable t) {
                plugin.debug("GUI refresh failed for " + p.getName() + ": " + t.getMessage());
            }
        }
    }

    private static boolean isLive(GuiHolder.Type type) {
        return type == GuiHolder.Type.CONTROLS
                || type == GuiHolder.Type.ARENA_LIST
                || type == GuiHolder.Type.ADMIN_LIST;
    }
}
