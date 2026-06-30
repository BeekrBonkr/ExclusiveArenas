package com.slg.exclusivearenas;

import de.marcely.bedwars.api.BedwarsAddon;

/**
 * Registers ExclusiveArenas as a first-class MBedwars add-on.
 *
 * Being a real add-on (rather than a plain plugin that merely depends on MBedwars)
 * is what lets us resolve {@link #getDataFolder()} to MBedwars' managed
 * {@code plugins/MBedwars/add-ons/ExclusiveArenas/} directory, independent of
 * where the plugin jar physically lives.
 */
public final class ExclusiveArenasAddon extends BedwarsAddon {

    public ExclusiveArenasAddon(ExclusiveArenasPlugin plugin) {
        super(plugin);
    }

    @Override
    public String getName() {
        return "ExclusiveArenas";
    }
}
