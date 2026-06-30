package com.slg.exclusivearenas;

import de.marcely.bedwars.api.arena.AddPlayerIssue;
import de.marcely.bedwars.api.arena.Arena;
import de.marcely.bedwars.api.event.player.PlayerJoinArenaEvent;
import de.marcely.bedwars.api.event.remote.RemotePlayerPreJoinLocalArenaEvent;
import de.marcely.bedwars.api.hook.PartiesHook;
import de.marcely.bedwars.api.remote.RemotePlayer;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.UUID;

public final class JoinListener implements Listener {

    private static final String BYPASS_PERM = "exclusivearenas.bypass";

    private final ExclusiveArenasPlugin plugin;
    private final PrivateSessionService sessions;
    private final JoinTicketService tickets;

    public JoinListener(ExclusiveArenasPlugin plugin,
                        PrivateSessionService sessions,
                        JoinTicketService tickets) {
        this.plugin = plugin;
        this.sessions = sessions;
        this.tickets = tickets;
    }

    // ── Local arena join ──────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.NORMAL)
    public void onLocalJoin(PlayerJoinArenaEvent event) {
        Player player = event.getPlayer();
        Arena arena = event.getArena();
        UUID playerId = player.getUniqueId();

        PrivateSession session = sessions.getByArena(arena);
        if (session == null) return; // arena is not private, allow the join

        plugin.pauseLobbyCountdownIfNeeded(arena, session);

        if (player.hasPermission(BYPASS_PERM)) return;

        // Owner is always allowed; clear any abandon timer
        if (playerId.equals(session.getOwner())) {
            session.setHostLeftAt(null);
            return;
        }

        // Valid join ticket (granted by /ea join, party summon, or network message)
        if (tickets.consumeIfValid(playerId, session.getSessionId(), arena.getName())) return;

        // Party policy: allow if player is in the owner's party (async callback)
        if (session.getJoinPolicy() == JoinPolicy.PARTY) {
            final PrivateSession finalSession = session;
            PartyResolver.isInLeadersParty(player, session.getOwner(), allowed -> {
                if (allowed) return;
                event.addIssue(buildIssue(finalSession, arena, "party"));
            });
            return;
        }

        // Code policy: require ticket OR check public status
        if (session.getJoinPolicy() == JoinPolicy.CODE) {
            if (!session.isPublic()) {
                event.addIssue(buildLockedIssue(arena));
                return;
            }
            // Public code session — player didn't use /ea join so they don't have a ticket
            event.addIssue(buildIssue(session, arena, "code"));
        }
    }

    // ── Remote (network) arena join ───────────────────────────────────────────

    @EventHandler(priority = EventPriority.NORMAL)
    public void onRemotePreJoin(RemotePlayerPreJoinLocalArenaEvent event) {
        Arena arena = event.getArena();
        RemotePlayer remotePlayer = event.getPlayer();
        UUID playerId = remotePlayer.getUniqueId();

        PrivateSession session = sessions.getByArena(arena);
        if (session == null) return;

        if (playerId.equals(session.getOwner())) {
            session.setHostLeftAt(null);
            return;
        }

        if (tickets.consumeIfValid(playerId, session.getSessionId(), arena.getName())) return;

        // We cannot check party membership for a player on another server, so a ticket is required.
        if (!session.isPublic()) {
            event.setIssue(buildLockedIssue(arena));
            return;
        }
        event.setIssue(buildIssue(session, arena,
                session.getJoinPolicy() == JoinPolicy.PARTY ? "party" : "code"));
    }

    // ── Server join: auto-summon to active party session ──────────────────────

    @EventHandler(priority = EventPriority.MONITOR)
    public void onServerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (player.hasPermission(BYPASS_PERM)) return;

        // Delay 2 ticks so the player is fully initialised
        Bukkit.getScheduler().runTaskLater(plugin, () -> checkPartySession(player), 2L);
    }

    private void checkPartySession(Player player) {
        if (!player.isOnline()) return;

        PartyResolver.getPartyMember(player, opt -> {
            if (opt.isEmpty()) return;
            PartiesHook.Party party = opt.get().getParty();

            // Find if any leader in the party has an active private session with PARTY policy
            for (PartiesHook.Member leader : party.getLeaders()) {
                PrivateSession session = sessions.getByOwner(leader.getUniqueId());
                if (session == null || session.getJoinPolicy() != JoinPolicy.PARTY) continue;

                tickets.grant(player.getUniqueId(), session.getSessionId(), session.getArenaName());
                plugin.getNetworkBus().broadcastTicket(
                        player.getUniqueId(), session.getSessionId(), session.getArenaName());
                plugin.sendPlayerToArena(player, session.getArenaName());
                break; // only summon to one session
            }
        });
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private AddPlayerIssue buildIssue(PrivateSession session, Arena arena, String type) {
        String key = type.equals("party") ? "messages.locked_hint_party" : "messages.locked_hint_code";
        String def = type.equals("party")
                ? "&cThat arena is private. Join &f%owner%&c's party to enter."
                : "&cThat arena is private. Use &f/ea join <code>&c to enter.";

        String msg = plugin.getEaConfig().str(key, def)
                .replace("%arena%", arena.getName());

        if (type.equals("party")) {
            OfflinePlayer off = Bukkit.getOfflinePlayer(session.getOwner());
            msg = msg.replace("%owner%", off.getName() != null ? off.getName() : "?");
        } else {
            msg = msg.replace("%code%", session.getJoinCode() != null ? session.getJoinCode() : "");
        }

        return AddPlayerIssue.construct("exclusivearenas.private", ItemUtil.color(msg));
    }

    private AddPlayerIssue buildLockedIssue(Arena arena) {
        String msg = plugin.getEaConfig().str("messages.locked_private",
                "&cThat arena is currently private and is not accepting joins.")
                .replace("%arena%", arena.getName());
        return AddPlayerIssue.construct("exclusivearenas.private", ItemUtil.color(msg));
    }
}
