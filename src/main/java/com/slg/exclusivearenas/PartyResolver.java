package com.slg.exclusivearenas;

import de.marcely.bedwars.api.hook.HookAPI;
import de.marcely.bedwars.api.hook.PartiesHook;
import org.bukkit.entity.Player;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

public final class PartyResolver {

    private PartyResolver() {}

    /**
     * Resolves the PartiesHook.Member for the given player via MBedwars' hook system.
     * Calls the callback with an empty Optional if no party hook is registered or the
     * player is not in a party.
     */
    public static void getPartyMember(Player player, Consumer<Optional<PartiesHook.Member>> callback) {
        getPartyMember(player.getUniqueId(), callback);
    }

    /**
     * Same as {@link #getPartyMember(Player, Consumer)}, but by raw UUID — the hook API
     * resolves by UUID directly, so this works even for a player who isn't currently an online
     * {@link Player} object. Lets a caller resolve one known UUID's party once (e.g. a match
     * host) instead of asking every online player individually whether they're related to it.
     */
    public static void getPartyMember(UUID playerId, Consumer<Optional<PartiesHook.Member>> callback) {
        PartiesHook[] hooks = HookAPI.get().getPartiesHooks();
        if (hooks == null || hooks.length == 0) {
            callback.accept(Optional.empty());
            return;
        }
        resolveFromHooks(hooks, 0, playerId, callback);
    }

    /**
     * Returns true if the given player is in a party led by ownerUUID.
     * The result is delivered asynchronously via the callback.
     */
    public static void isInLeadersParty(Player player, UUID ownerUUID, Consumer<Boolean> callback) {
        getPartyMember(player, opt -> {
            if (opt.isEmpty()) { callback.accept(false); return; }
            PartiesHook.Party party = opt.get().getParty();
            for (PartiesHook.Member leader : party.getLeaders()) {
                if (ownerUUID.equals(leader.getUniqueId())) {
                    callback.accept(true);
                    return;
                }
            }
            callback.accept(false);
        });
    }

    private static void resolveFromHooks(PartiesHook[] hooks, int index,
                                         UUID playerId,
                                         Consumer<Optional<PartiesHook.Member>> callback) {
        if (index >= hooks.length) {
            callback.accept(Optional.empty());
            return;
        }
        hooks[index].getMember(playerId, opt -> {
            if (opt != null && opt.isPresent()) {
                callback.accept(opt);
            } else {
                resolveFromHooks(hooks, index + 1, playerId, callback);
            }
        });
    }
}
