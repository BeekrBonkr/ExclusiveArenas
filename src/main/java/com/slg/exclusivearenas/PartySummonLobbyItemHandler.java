package com.slg.exclusivearenas;

import de.marcely.bedwars.api.arena.Arena;
import de.marcely.bedwars.api.game.lobby.LobbyItem;
import de.marcely.bedwars.api.game.lobby.LobbyItemHandler;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/**
 * A lobby hotbar item — add an entry to MBedwars' lobby-hotbar.yml with
 * {@code handler: 'exclusivearenas:summon_party'} — visible only to the match's host, and only
 * when the session is party-gated, that pulls their online party members straight into the
 * lobby (the same action as the Match Controls "Summon Party" button).
 */
public final class PartySummonLobbyItemHandler extends LobbyItemHandler {

    public static final String ID = "exclusivearenas:summon_party";

    private final ExclusiveArenasPlugin plugin;
    private final PrivateSessionService sessions;

    public PartySummonLobbyItemHandler(ExclusiveArenasPlugin plugin, PrivateSessionService sessions) {
        super(ID, plugin);
        this.plugin = plugin;
        this.sessions = sessions;
    }

    @Override
    public boolean isVisible(Player player, Arena arena, LobbyItem item) {
        PrivateSession session = sessions.getByArena(arena);
        boolean visible = session != null
                && session.getJoinPolicy() == JoinPolicy.PARTY
                && player.getUniqueId().equals(session.getOwner());
        if (visible) item.setItem(buildIcon());
        return visible;
    }

    @Override
    public void handleUse(Player player, Arena arena, LobbyItem item) {
        PrivateSession session = sessions.getByArena(arena);
        if (session == null) return;
        plugin.summonPartyToArena(player, session);
    }

    private ItemStack buildIcon() {
        return ItemUtil.button(Material.ENDER_PEARL,
                Lang.raw("party-summon.item-name"),
                Lang.raw("party-summon.item-desc"),
                Lang.raw("party-summon.item-desc-2"),
                Lang.raw("party-summon.item-hint"));
    }
}
