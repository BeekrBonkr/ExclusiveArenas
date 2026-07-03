package com.slg.exclusivearenas;

import de.marcely.bedwars.api.arena.Arena;
import de.marcely.bedwars.api.game.lobby.LobbyItem;
import de.marcely.bedwars.api.game.lobby.LobbyItemHandler;
import de.marcely.bedwars.api.game.spectator.SpectateReason;
import de.marcely.bedwars.api.game.spectator.Spectator;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/**
 * A lobby hotbar item — add an entry to MBedwars' lobby-hotbar.yml with
 * {@code handler: 'exclusivearenas:toggle_spectate'} — visible only inside an ExclusiveArenas
 * private match, that lets a player opt out of playing. Using it immediately converts them to a
 * spectator (leaving their team and freeing up their arena player slot right away, rather than
 * waiting until the round starts). Once spectating, this item disappears — {@link
 * SpectatorRejoinHandler} takes over as the way back to playing.
 */
public final class SpectateOnStartHandler extends LobbyItemHandler {

    public static final String ID = "exclusivearenas:toggle_spectate";

    private final PrivateSessionService sessions;

    public SpectateOnStartHandler(Plugin plugin, PrivateSessionService sessions) {
        super(ID, plugin);
        this.sessions = sessions;
    }

    @Override
    public boolean isVisible(Player player, Arena arena, LobbyItem item) {
        if (!arena.getStatus().isLobby() || arena.isSpectating(player)) return false;
        if (sessions.getByArena(arena) == null) return false;
        item.setItem(buildIcon());
        return true;
    }

    @Override
    public void handleUse(Player player, Arena arena, LobbyItem item) {
        if (!arena.getStatus().isLobby() || arena.isSpectating(player)) {
            player.sendMessage(Lang.msg("spectate.lobby-only"));
            return;
        }

        Spectator spectator = arena.addSpectator(player, SpectateReason.PLUGIN);
        if (spectator == null) {
            player.sendMessage(Lang.msg("spectate.switch-failed"));
            return;
        }
        player.sendMessage(Lang.msg("spectate.now-spectating"));
    }

    private ItemStack buildIcon() {
        return ItemUtil.button(Material.GRAY_DYE,
                Lang.raw("spectate.item-name"),
                Lang.raw("spectate.item-desc"),
                Lang.raw("spectate.item-hint"));
    }
}
