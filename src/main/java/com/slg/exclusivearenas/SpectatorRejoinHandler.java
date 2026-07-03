package com.slg.exclusivearenas;

import de.marcely.bedwars.api.arena.Arena;
import de.marcely.bedwars.api.arena.ArenaStatus;
import de.marcely.bedwars.api.game.lobby.LobbyItem;
import de.marcely.bedwars.api.game.lobby.LobbyItemHandler;
import de.marcely.bedwars.api.game.spectator.KickSpectatorReason;
import de.marcely.bedwars.api.game.spectator.Spectator;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/**
 * A lobby hotbar item — add an entry to MBedwars' lobby-hotbar.yml with
 * {@code handler: 'exclusivearenas:rejoin_as_player'} — visible only to players currently
 * spectating one of our private matches, letting them rejoin as a player without leaving and
 * re-entering the arena. Hidden once the round is running or the arena has no free player slot;
 * actually rejoining still requires the arena to be in its lobby.
 */
public final class SpectatorRejoinHandler extends LobbyItemHandler {

    public static final String ID = "exclusivearenas:rejoin_as_player";

    private final PrivateSessionService sessions;

    public SpectatorRejoinHandler(Plugin plugin, PrivateSessionService sessions) {
        super(ID, plugin);
        this.sessions = sessions;
    }

    @Override
    public boolean isVisible(Player player, Arena arena, LobbyItem item) {
        if (!arena.isSpectating(player)) return false;
        if (arena.getStatus() == ArenaStatus.RUNNING) return false;
        if (arena.getPlayers().size() >= arena.getMaxPlayers()) return false;
        if (sessions.getByArena(arena) == null) return false;
        item.setItem(buildIcon());
        return true;
    }

    @Override
    public void handleUse(Player player, Arena arena, LobbyItem item) {
        if (!arena.getStatus().isLobby()) {
            player.sendMessage(Lang.msg("spectator-rejoin.lobby-only"));
            return;
        }

        Spectator spectator = arena.getSpectateData(player);
        if (spectator != null && spectator.isPresent()) spectator.kick(KickSpectatorReason.JOIN_ARENA);

        if (!arena.getPlayers().contains(player) && arena.addPlayer(player) != null) {
            // MBedwars rejected the re-add (e.g. the arena filled up while spectating).
            player.sendMessage(Lang.msg("spectator-rejoin.rejoin-failed"));
            return;
        }
        player.sendMessage(Lang.msg("spectator-rejoin.now-playing"));
    }

    private ItemStack buildIcon() {
        return ItemUtil.button(Material.LIME_DYE,
                Lang.raw("spectator-rejoin.item-name"),
                Lang.raw("spectator-rejoin.item-desc"),
                Lang.raw("spectator-rejoin.item-hint"));
    }
}
