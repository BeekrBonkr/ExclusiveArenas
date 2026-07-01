package com.slg.exclusivearenas;

import de.marcely.bedwars.api.arena.Arena;
import de.marcely.bedwars.api.arena.picker.condition.variable.ArenaConditionVariable;
import de.marcely.bedwars.api.arena.picker.condition.variable.ArenaConditionVariableValueNumber;
import de.marcely.bedwars.api.remote.RemoteArena;
import org.bukkit.plugin.Plugin;

/**
 * Exposes whether an arena currently hosts an ExclusiveArenas private match as an MBedwars
 * arena-picker condition variable ({@value #NAME}, 1 or 0). Server admins can reference it in
 * their ArenasGUI layout's "condition" field to keep reserved private lobbies out of the public
 * arena picker menu, e.g.:
 *
 * <pre>
 *   - type: arenas-collection
 *     condition: "[exclusivearenas_private=0]"
 *     ...
 * </pre>
 */
public final class PrivacyConditionVariable extends ArenaConditionVariable<ArenaConditionVariableValueNumber> {

    public static final String NAME = "exclusivearenas_private";

    private final PrivateSessionService sessions;

    public PrivacyConditionVariable(Plugin plugin, PrivateSessionService sessions) {
        super(plugin, NAME, ArenaConditionVariableValueNumber.class);
        this.sessions = sessions;
    }

    @Override
    public ArenaConditionVariableValueNumber getValue(Arena arena) {
        return new ArenaConditionVariableValueNumber(
                arena != null && sessions.getByArena(arena) != null ? 1f : 0f);
    }

    @Override
    public ArenaConditionVariableValueNumber getValue(RemoteArena arena) {
        return new ArenaConditionVariableValueNumber(
                arena != null && sessions.getByArenaName(arena.getName()) != null ? 1f : 0f);
    }
}
