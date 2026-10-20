package com.slg.exclusivearenas;

import de.marcely.bedwars.api.arena.Arena;
import de.marcely.bedwars.api.game.lobby.LobbyItem;
import de.marcely.bedwars.api.game.lobby.LobbyItemHandler;
import de.marcely.bedwars.api.game.spectator.Spectator;
import de.marcely.bedwars.api.game.spectator.SpectatorItem;
import de.marcely.bedwars.api.game.spectator.SpectatorItemHandler;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * A SPECTATOR hotbar item — add an entry to MBedwars' spectator-hotbar.yml with
 * {@code handler: 'exclusivearenas:rejoin_as_player'} — visible to players currently
 * spectating one of our private matches while it is still in its lobby, letting them rejoin as
 * a player without leaving and re-entering the arena. Hidden once the round is running or the
 * arena has no free player slot.
 *
 * <p>It has to be a spectator item: the moment someone starts spectating, MBedwars swaps their
 * hotbar for the spectator one, so a lobby-hotbar entry would never be in their hands. The
 * {@link LobbyVariant} is kept registered under the same id so an older lobby-hotbar.yml entry
 * keeps working too for the brief moment MBedwars still shows the lobby hotbar.
 *
 * <p>Converting a spectator straight to a player via the {@code Arena} API
 * ({@code getSpectateData(...).kick(...)} then {@code addPlayer(...)}) throws deep inside
 * MBedwars' own (obfuscated) implementation — {@code addPlayer} is built for a fresh join, not
 * for un-spectating someone. MBedwars' own {@code /bw join <arena>} command already handles this
 * exact transition correctly (its own {@code mbedwars.cmd.join} permission is default-true), so
 * this dispatches that instead of re-implementing the transition ourselves.
 */
public final class SpectatorRejoinHandler extends SpectatorItemHandler {

    public static final String ID = "exclusivearenas:rejoin_as_player";

    private final ExclusiveArenasPlugin plugin;
    private final PrivateSessionService sessions;

    public SpectatorRejoinHandler(ExclusiveArenasPlugin plugin, PrivateSessionService sessions) {
        super(ID, plugin);
        this.plugin = plugin;
        this.sessions = sessions;
    }

    @Override
    public boolean isVisible(Spectator spectator, SpectatorItem item) {
        if (!canRejoin(spectator.getPlayer(), spectator.getArena(), sessions)) return false;
        item.setItem(buildIcon());
        return true;
    }

    @Override
    public void handleUse(Spectator spectator, SpectatorItem item) {
        rejoin(plugin, sessions, spectator.getPlayer(), spectator.getArena());
    }

    /** Same item as a lobby-hotbar handler (see the class comment). */
    public static final class LobbyVariant extends LobbyItemHandler {
        private final ExclusiveArenasPlugin plugin;
        private final PrivateSessionService sessions;

        public LobbyVariant(ExclusiveArenasPlugin plugin, PrivateSessionService sessions) {
            super(ID, plugin);
            this.plugin = plugin;
            this.sessions = sessions;
        }

        @Override
        public boolean isVisible(Player player, Arena arena, LobbyItem item) {
            if (arena == null || !arena.isSpectating(player)) return false;
            if (!canRejoin(player, arena, sessions)) return false;
            item.setItem(buildIcon());
            return true;
        }

        @Override
        public void handleUse(Player player, Arena arena, LobbyItem item) {
            rejoin(plugin, sessions, player, arena);
        }
    }

    private static boolean canRejoin(Player player, Arena arena, PrivateSessionService sessions) {
        if (player == null || arena == null || !arena.exists()) return false;
        // Strictly lobby-only: not while RUNNING (a player who died mid-round is a spectator
        // too, but has a respawn/elimination flow of their own), and not during END_LOBBY /
        // RESETTING either — "/bw join" can't seat anyone there and the round is over anyway.
        if (!arena.getStatus().isLobby()) return false;
        if (arena.getPlayers().size() >= arena.getMaxPlayers()) return false;
        return sessions.getByArena(arena) != null;
    }

    private static void rejoin(ExclusiveArenasPlugin plugin, PrivateSessionService sessions,
                               Player player, Arena arena) {
        if (player == null || arena == null) return;
        // The join goes back through our own gate, and a guest's original ticket was spent on
        // their first entry — without a fresh one a Join-Code match would turn its own
        // spectator away here. They're already inside this very session, so re-authorising
        // them is safe.
        PrivateSession session = sessions.getByArena(arena);
        if (session != null) {
            plugin.getTicketService().grant(player.getUniqueId(), session.getSessionId(), arena.getName());
        }
        // MBedwars' own command reports success/failure (full, no longer in lobby, …) to the
        // player itself, so there's nothing left for us to check or message here.
        player.performCommand("bw join " + arena.getName());
    }

    private static ItemStack buildIcon() {
        return ItemUtil.button(Material.LIME_DYE,
                Lang.raw("spectator-rejoin.item-name"),
                Lang.raw("spectator-rejoin.item-desc"),
                Lang.raw("spectator-rejoin.item-hint"));
    }
}
