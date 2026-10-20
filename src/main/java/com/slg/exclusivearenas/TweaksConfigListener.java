package com.slg.exclusivearenas;

import me.metallicgoat.tweaksaddon.api.events.PostConfigLoadEvent;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

/**
 * Re-runs the MBedwarsTweaks timeline-bridge setup whenever Tweaks (re)loads its configs.
 *
 * Since MBedwarsTweaks 5.1.1, Tweaks reads its gen-tier config inside its own
 * {@code BedwarsAPI.onReady} callback rather than directly in {@code onEnable}. Which plugin's
 * ready-callback runs first depends on enable order, so on servers where ExclusiveArenas is
 * enabled before Tweaks the bridge's initial attempt finds zero tiers and silently falls back
 * to the internal timeline engine. Tweaks fires {@link PostConfigLoadEvent} (on the main
 * thread) the moment its configs are in, so listening for it makes the bridge engage
 * regardless of ordering, and also picks up a {@code /tweaks reload} that changed the tiers.
 *
 * Like {@link TweaksTimelineBridge}, this class is only ever class-loaded when the
 * MBedwarsTweaks plugin is present, because it imports the Tweaks API.
 */
final class TweaksConfigListener implements Listener {

    private final ExclusiveArenasPlugin plugin;

    private TweaksConfigListener(ExclusiveArenasPlugin plugin) {
        this.plugin = plugin;
    }

    /** Registers the listener; call only when the MBedwarsTweaks plugin is installed. */
    static void register(ExclusiveArenasPlugin plugin) {
        Bukkit.getPluginManager().registerEvents(new TweaksConfigListener(plugin), plugin);
    }

    @EventHandler
    public void onTweaksConfigLoaded(PostConfigLoadEvent event) {
        plugin.onTweaksConfigLoaded(event.isFirstLoad());
    }
}
