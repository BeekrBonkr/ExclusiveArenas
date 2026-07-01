package com.slg.exclusivearenas;

import de.marcely.bedwars.api.arena.Arena;
import de.marcely.bedwars.api.game.lobby.LobbyItem;
import de.marcely.bedwars.api.game.lobby.LobbyItemHandler;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * A lobby hotbar item, visible only to the match's host, that opens Match Controls without
 * needing to go through {@code /ea}. Add an entry to MBedwars' lobby-hotbar.yml with
 * {@code handler: 'exclusivearenas:open_controls'} to expose it.
 */
public final class MatchControlsLobbyItemHandler extends LobbyItemHandler {

    public static final String ID = "exclusivearenas:open_controls";

    private final PrivateSessionService sessions;
    private final GuiManager gui;

    public MatchControlsLobbyItemHandler(Plugin plugin, PrivateSessionService sessions, GuiManager gui) {
        super(ID, plugin);
        this.sessions = sessions;
        this.gui = gui;
    }

    @Override
    public boolean isVisible(Player player, Arena arena, LobbyItem item) {
        PrivateSession session = sessions.getByArena(arena);
        return session != null && player.getUniqueId().equals(session.getOwner());
    }

    @Override
    public void handleUse(Player player, Arena arena, LobbyItem item) {
        PrivateSession session = sessions.getByArena(arena);
        if (session == null) return;
        gui.openControls(player, session, player.hasPermission(GuiManager.ADMIN_PERM));
    }
}
