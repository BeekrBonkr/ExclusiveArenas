package com.slg.exclusivearenas;

import de.marcely.bedwars.api.BedwarsAPI;
import de.marcely.bedwars.api.arena.Arena;
import de.marcely.bedwars.api.event.arena.RoundStartEvent;
import de.marcely.bedwars.api.event.player.PlayerTeamChangeEvent;
import de.marcely.bedwars.api.game.lobby.LobbyItem;
import de.marcely.bedwars.api.game.lobby.LobbyItemHandler;
import de.marcely.bedwars.api.game.spectator.KickSpectatorReason;
import de.marcely.bedwars.api.game.spectator.SpectateReason;
import de.marcely.bedwars.api.game.spectator.Spectator;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A lobby hotbar item — add an entry to MBedwars' lobby-hotbar.yml with
 * {@code handler: 'exclusivearenas:toggle_spectate'} — visible only inside an ExclusiveArenas
 * private match, that lets a player opt out of playing. Using it immediately converts them to a
 * spectator (leaving their team and freeing up their arena player slot right away, rather than
 * waiting until the round starts), and turns gray; using it again returns them to being a
 * player and turns green. Being moved onto a real team by any means also clears the opt-out.
 */
public final class SpectateOnStartHandler extends LobbyItemHandler implements Listener {

    public static final String ID = "exclusivearenas:toggle_spectate";

    private final PrivateSessionService sessions;
    private final Set<UUID> opted = ConcurrentHashMap.newKeySet();

    public SpectateOnStartHandler(Plugin plugin, PrivateSessionService sessions) {
        super(ID, plugin);
        this.sessions = sessions;
    }

    @Override
    public boolean isVisible(Player player, Arena arena, LobbyItem item) {
        if (!arena.getStatus().isLobby() || sessions.getByArena(arena) == null) return false;
        item.setItem(buildIcon(opted.contains(player.getUniqueId())));
        return true;
    }

    @Override
    public void handleUse(Player player, Arena arena, LobbyItem item) {
        if (!arena.getStatus().isLobby()) {
            player.sendMessage(Lang.msg("spectate.lobby-only"));
            return;
        }

        UUID id = player.getUniqueId();
        if (opted.contains(id)) {
            switchToPlaying(player, arena, id);
        } else {
            switchToSpectating(player, arena, id);
        }

        item.setItem(buildIcon(opted.contains(id)));
        try {
            BedwarsAPI.getGameAPI().forceLobbyHotbarRefresh(player);
        } catch (Throwable ignored) {
            // best effort — the item will still update next time it's rendered
        }
    }

    private void switchToSpectating(Player player, Arena arena, UUID id) {
        if (arena.isSpectating(player)) {
            opted.add(id); // already spectating somehow — just reflect it, nothing to do
            return;
        }
        Spectator spectator = arena.addSpectator(player, SpectateReason.PLUGIN);
        if (spectator == null) {
            player.sendMessage(Lang.msg("spectate.switch-failed"));
            return;
        }
        opted.add(id);
        player.sendMessage(Lang.msg("spectate.now-spectating"));
    }

    private void switchToPlaying(Player player, Arena arena, UUID id) {
        Spectator spectator = arena.getSpectateData(player);
        if (spectator != null && spectator.isPresent()) spectator.kick(KickSpectatorReason.JOIN_ARENA);

        opted.remove(id);
        if (!arena.getPlayers().contains(player)) {
            if (arena.addPlayer(player) != null) {
                // MBedwars rejected the re-add (e.g. the arena filled up while spectating) —
                // leave them spectating rather than stranding them in limbo.
                opted.add(id);
                player.sendMessage(Lang.msg("spectate.rejoin-failed"));
                return;
            }
        }
        player.sendMessage(Lang.msg("spectate.now-playing"));
    }

    /**
     * Defensive: if a player somehow ends up on a real team while still marked opted-out
     * (shouldn't normally happen once they're actually spectating, but covers odd edge cases —
     * an admin command, a future code path, etc.), clear the flag so their hotbar reflects it.
     */
    @EventHandler
    public void onTeamChange(PlayerTeamChangeEvent event) {
        if (event.getNewTeam() == null) return;
        if (opted.remove(event.getPlayer().getUniqueId())) {
            try {
                BedwarsAPI.getGameAPI().forceLobbyHotbarRefresh(event.getPlayer());
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * Defensive: anyone still marked opted-out when the round starts is force-spectated, in
     * case they never actually ended up spectating (e.g. addSpectator silently failed earlier).
     */
    @EventHandler
    public void onRoundStart(RoundStartEvent event) {
        if (opted.isEmpty()) return;
        Arena arena = event.getArena();
        for (Player player : new ArrayList<>(arena.getPlayers())) {
            if (!opted.remove(player.getUniqueId())) continue;
            arena.addSpectator(player, SpectateReason.PLUGIN);
        }
    }

    private ItemStack buildIcon(boolean spectating) {
        return ItemUtil.button(spectating ? Material.GREEN_DYE : Material.GRAY_DYE,
                Lang.raw(spectating ? "spectate.item-name-on" : "spectate.item-name-off"),
                Lang.raw(spectating ? "spectate.item-desc-on" : "spectate.item-desc-off"),
                Lang.raw("spectate.item-hint"));
    }
}
