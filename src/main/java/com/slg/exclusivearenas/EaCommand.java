package com.slg.exclusivearenas;

import de.marcely.bedwars.api.BedwarsAPI;
import de.marcely.bedwars.api.arena.Arena;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class EaCommand implements CommandExecutor, TabCompleter {

    private static final String PERM_USE    = "exclusivearenas.command";
    private static final String PERM_BYPASS = "exclusivearenas.bypass";

    private final ExclusiveArenasPlugin plugin;
    private final DraftService drafts;
    private final PrivateSessionService sessions;
    private final JoinTicketService tickets;
    private final GuiManager gui;

    public EaCommand(ExclusiveArenasPlugin plugin,
                     DraftService drafts,
                     PrivateSessionService sessions,
                     JoinTicketService tickets,
                     GuiManager gui) {
        this.plugin = plugin;
        this.drafts = drafts;
        this.sessions = sessions;
        this.tickets = tickets;
        this.gui = gui;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(color("&cPlayers only."));
            return true;
        }
        if (!p.hasPermission(PERM_USE)) {
            p.sendMessage(color("&cYou don't have permission to use ExclusiveArenas."));
            return true;
        }

        String sub = args.length == 0 ? "menu" : args[0].toLowerCase(Locale.ROOT);

        switch (sub) {
            case "menu" -> gui.openMainMenu(p);

            case "arena", "create", "builder" -> gui.openBuilder(p);

            case "help" -> gui.openHelp(p);

            case "lobby", "controls" -> {
                Arena arena = BedwarsAPI.getGameAPI().getArenaByPlayer(p);
                if (arena == null) { p.sendMessage(color("&cYou are not in an arena.")); return true; }
                PrivateSession session = sessions.getByArena(arena);
                if (session == null) { p.sendMessage(color("&cThis is not a private match.")); return true; }
                gui.openLobbyControls(p, session, arena);
            }

            case "join" -> {
                if (args.length < 2) { p.sendMessage(color("&cUsage: /ea join <code>")); return true; }
                handleJoin(p, args[1].trim());
            }

            case "start" -> {
                PrivateSession session = requireHostedSession(p);
                if (session == null) return true;
                Arena arena = BedwarsAPI.getGameAPI().getArenaByPlayer(p);
                plugin.startLobbyCountdown(arena, session);
            }

            case "end" -> {
                PrivateSession session = requireHostedSession(p);
                if (session == null) return true;
                Arena arena = BedwarsAPI.getGameAPI().getArenaByPlayer(p);
                arena.broadcast(color("&cThe host ended the private match."));
                sessions.endSession(session);
                plugin.getNetworkBus().broadcastEnd(session.getSessionId());
                arena.kickAllPlayers();
            }

            case "summon" -> {
                PrivateSession session = requireHostedSession(p);
                if (session == null) return true;
                if (session.getJoinPolicy() != JoinPolicy.PARTY) {
                    p.sendMessage(color("&c/ea summon only works for Party-policy matches."));
                    return true;
                }
                Arena arena = BedwarsAPI.getGameAPI().getArenaByPlayer(p);
                plugin.summonPartyToArena(p, arena, session);
            }

            default -> {
                p.sendMessage(color("&cUnknown subcommand. Use &f/ea help &cfor available commands."));
            }
        }
        return true;
    }

    private void handleJoin(Player p, String code) {
        // Session state is replicated to every server, so the code resolves locally even when
        // the arena lives on another server.
        PrivateSession session = sessions.getByJoinCode(code);
        if (session == null) {
            p.sendMessage(color("&cInvalid or expired join code."));
            return;
        }
        if (session.getJoinPolicy() == JoinPolicy.CODE && !session.isPublic()) {
            p.sendMessage(color("&cThat arena is currently private and not accepting joins."));
            return;
        }

        // Authorise this player on the arena server (local grant + network broadcast), then route them.
        tickets.grant(p.getUniqueId(), session.getSessionId(), session.getArenaName());
        plugin.getNetworkBus().broadcastTicket(p.getUniqueId(), session.getSessionId(), session.getArenaName());
        plugin.sendPlayerToArena(p, session.getArenaName());
    }

    /** Returns the session the player is hosting in their current arena, or null with an error. */
    private PrivateSession requireHostedSession(Player p) {
        Arena arena = BedwarsAPI.getGameAPI().getArenaByPlayer(p);
        if (arena == null) {
            p.sendMessage(color("&cYou are not in an arena."));
            return null;
        }
        PrivateSession session = sessions.getByArena(arena);
        if (session == null) {
            p.sendMessage(color("&cThis is not a private match."));
            return null;
        }
        if (!p.getUniqueId().equals(session.getOwner()) && !p.hasPermission(PERM_BYPASS)) {
            p.sendMessage(color("&cOnly the host can do that."));
            return null;
        }
        return session;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return partial(args[0], Arrays.asList(
                    "menu", "arena", "help", "lobby", "join",
                    "start", "end", "summon"));
        }
        return Collections.emptyList();
    }

    private static List<String> partial(String token, List<String> options) {
        String t = token == null ? "" : token.toLowerCase(Locale.ROOT);
        return options.stream().filter(o -> o.startsWith(t)).toList();
    }

    private static String color(String s) {
        return org.bukkit.ChatColor.translateAlternateColorCodes('&', s == null ? "" : s);
    }
}
