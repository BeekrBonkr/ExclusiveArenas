package com.slg.exclusivearenas;

import de.marcely.bedwars.api.arena.Arena;
import de.marcely.bedwars.api.arena.ArenaStatus;
import de.marcely.bedwars.api.game.lobby.LobbyItem;
import de.marcely.bedwars.api.game.lobby.LobbyItemHandler;
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
 *
 * <p>Converting a spectator straight to a player via the {@code Arena} API
 * ({@code getSpectateData(...).kick(...)} then {@code addPlayer(...)}) throws deep inside
 * MBedwars' own (obfuscated) implementation — {@code addPlayer} is built for a fresh join, not
 * for un-spectating someone. MBedwars' own {@code /bw join <arena>} command already handles this
 * exact transition correctly (its own {@code mbedwars.cmd.join} permission is default-true), so
 * this dispatches that instead of re-implementing the transition ourselves.
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
        // Strictly lobby-only: not while RUNNING (a player who died mid-round is a spectator
        // too, but has a respawn/elimination flow of their own), and not during END_LOBBY /
        // RESETTING either — "/bw join" can't seat anyone there and the round is over anyway.
        if (!arena.getStatus().isLobby()) return false;
        if (arena.getPlayers().size() >= arena.getMaxPlayers()) return false;
        if (sessions.getByArena(arena) == null) return false;
        item.setItem(buildIcon());
        return true;
    }

    @Override
    public void handleUse(Player player, Arena arena, LobbyItem item) {
        // MBedwars' own command reports success/failure (full, no longer in lobby, …) to the
        // player itself, so there's nothing left for us to check or message here.
        player.performCommand("bw join " + arena.getName());
    }

    private ItemStack buildIcon() {
        return ItemUtil.button(Material.LIME_DYE,
                Lang.raw("spectator-rejoin.item-name"),
                Lang.raw("spectator-rejoin.item-desc"),
                Lang.raw("spectator-rejoin.item-hint"));
    }
}
