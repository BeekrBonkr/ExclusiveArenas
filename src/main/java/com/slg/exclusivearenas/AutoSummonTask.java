package com.slg.exclusivearenas;

import de.marcely.bedwars.api.BedwarsAPI;
import de.marcely.bedwars.api.arena.Arena;
import de.marcely.bedwars.api.hook.PartiesHook;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Keeps a host's party continuously synced with their auto-summon match: any online party
 * member who isn't currently in the arena is pulled in (and told why), and anyone standing in
 * the arena who is no longer part of the host's party is sent back out.
 *
 * MBedwars has no party-membership-changed event and its party lookups only see players on the
 * current server, so this runs on EVERY server and checks that server's own online players and
 * locally-hosted arenas.
 */
public final class AutoSummonTask extends BukkitRunnable {

    private static final String BYPASS_PERM = "exclusivearenas.bypass";

    private final ExclusiveArenasPlugin plugin;
    private final PrivateSessionService sessions;

    public AutoSummonTask(ExclusiveArenasPlugin plugin, PrivateSessionService sessions) {
        this.plugin = plugin;
        this.sessions = sessions;
    }

    @Override
    public void run() {
        Map<UUID, PrivateSession> byHost = new HashMap<>();
        for (PrivateSession s : sessions.getAllSessions()) {
            if (s.isAutoSummon() && s.getJoinPolicy() == JoinPolicy.PARTY && s.getOwner() != null) {
                byHost.put(s.getOwner(), s);
            }
        }

        if (!byHost.isEmpty()) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                considerPullIn(player, byHost);
            }
        }

        for (PrivateSession session : sessions.getAllSessions()) {
            if (session.isAutoSummon() && session.getJoinPolicy() == JoinPolicy.PARTY) {
                considerKickOut(session);
            }
        }
    }

    /** Pulls an online player into their party leader's auto-summon match if they aren't in it. */
    private void considerPullIn(Player player, Map<UUID, PrivateSession> byHost) {
        UUID pid = player.getUniqueId();
        PartyResolver.getPartyMember(player, opt -> {
            if (opt.isEmpty()) return;
            PartiesHook.Party party = opt.get().getParty();

            for (PartiesHook.Member leader : party.getLeaders()) {
                PrivateSession session = byHost.get(leader.getUniqueId());
                if (session == null) continue;
                if (pid.equals(session.getOwner())) return; // the host doesn't summon themselves

                Arena current = BedwarsAPI.getGameAPI().getArenaByPlayer(player);
                if (current != null && session.matchesArena(current)) return; // already there

                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) return;
                    if (sessions.getById(session.getSessionId()) == null) return;
                    Arena now = BedwarsAPI.getGameAPI().getArenaByPlayer(player);
                    if (now != null && session.matchesArena(now)) return; // arrived meanwhile

                    // Message only once they've actually landed — not before, since a cross-server
                    // summon can take a moment to complete.
                    plugin.forceSummon(session, pid, () -> player.sendMessage(ItemUtil.color(
                            "&eYou were sent to &f" + session.getArenaName()
                                    + "&e because you're in &f" + ownerName(session) + "&e's party.")));
                });
                return;
            }
        });
    }

    /** Removes anyone from the arena who is no longer a member of the host's party. */
    private void considerKickOut(PrivateSession session) {
        Arena arena = BedwarsAPI.getGameAPI().getArenaByExactName(session.getArenaName());
        if (arena == null || !arena.exists()) return;

        Set<Player> present = new HashSet<>(arena.getPlayers());
        present.addAll(arena.getSpectators());

        for (Player member : present) {
            if (member.getUniqueId().equals(session.getOwner())) continue;
            if (member.hasPermission(BYPASS_PERM)) continue;

            PartyResolver.isInLeadersParty(member, session.getOwner(), allowed -> {
                if (allowed) return;

                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!member.isOnline()) return;
                    if (sessions.getById(session.getSessionId()) == null) return;
                    Arena now = BedwarsAPI.getGameAPI().getArenaByExactName(session.getArenaName());
                    if (now == null || !now.exists()) return;
                    if (!now.getPlayers().contains(member) && !now.isSpectating(member)) return;

                    now.kickPlayer(member);
                    member.sendMessage(ItemUtil.color("&cYou were removed from the match because you left &f"
                            + ownerName(session) + "&c's party."));
                });
            });
        }
    }

    private String ownerName(PrivateSession session) {
        OfflinePlayer off = Bukkit.getOfflinePlayer(session.getOwner());
        return off.getName() != null ? off.getName() : "the host";
    }
}
