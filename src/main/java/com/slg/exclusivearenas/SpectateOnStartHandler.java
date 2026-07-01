package com.slg.exclusivearenas;

import de.marcely.bedwars.api.arena.Arena;
import de.marcely.bedwars.api.event.arena.RoundStartEvent;
import de.marcely.bedwars.api.game.lobby.LobbyItem;
import de.marcely.bedwars.api.game.lobby.LobbyItemHandler;
import de.marcely.bedwars.api.game.spectator.SpectateReason;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A lobby hotbar item — add an entry to MBedwars' lobby-hotbar.yml with
 * {@code handler: 'exclusivearenas:toggle_spectate'} — visible only inside an ExclusiveArenas
 * private match, that lets a player opt out of playing: using it leaves their team, and marks
 * them to be converted into a spectator the moment the round actually starts instead of being
 * forced to play. Using it again cancels the opt-out.
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
        return arena.getStatus().isLobby() && sessions.getByArena(arena) != null;
    }

    @Override
    public void handleUse(Player player, Arena arena, LobbyItem item) {
        UUID id = player.getUniqueId();
        if (opted.remove(id)) {
            player.sendMessage(ItemUtil.color("&aYou'll play in this match — pick a team to join in."));
            return;
        }
        opted.add(id);
        arena.leaveTeamDuringLobby(player);
        player.sendMessage(ItemUtil.color("&eYou'll spectate this match once it starts."));
    }

    @EventHandler
    public void onRoundStart(RoundStartEvent event) {
        if (opted.isEmpty()) return;
        Arena arena = event.getArena();
        for (Player player : new ArrayList<>(arena.getPlayers())) {
            if (!opted.remove(player.getUniqueId())) continue;
            arena.addSpectator(player, SpectateReason.PLUGIN);
        }
    }
}
