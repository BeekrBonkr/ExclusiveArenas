package com.slg.exclusivearenas;

import de.marcely.bedwars.api.BedwarsAPI;
import de.marcely.bedwars.api.arena.Arena;
import de.marcely.bedwars.api.hook.PartiesHook;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Keeps a host's party continuously synced with their auto-summon match: any party member who
 * isn't currently in the arena is pulled in (and told why), and anyone standing in the arena who
 * is no longer part of the host's party is sent back out.
 *
 * Resolves each auto-summon session's party exactly once per tick (by the host's UUID, not by
 * asking every online player individually) — cheap even on a busy server with many players but
 * only a handful of private matches. MBedwars' party lookups only see players on the current
 * server, so this still runs on every server, each checking its own locally-hosted arenas.
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
        for (PrivateSession session : sessions.getAllSessions()) {
            if (session.isAutoSummon() && session.getJoinPolicy() == JoinPolicy.PARTY && session.getOwner() != null) {
                syncSession(session);
            }
        }
    }

    private void syncSession(PrivateSession session) {
        PartyResolver.getPartyMember(session.getOwner(), opt -> {
            if (opt.isEmpty()) return;
            PartiesHook.Party party = opt.get().getParty();

            boolean hostIsLeader = party.getLeaders().stream()
                    .anyMatch(leader -> leader.getUniqueId().equals(session.getOwner()));
            if (!hostIsLeader) return; // shouldn't normally happen — Party-policy creation requires it

            Set<UUID> partyIds = new HashSet<>();
            for (PartiesHook.Member m : party.getMembers(true)) partyIds.add(m.getUniqueId());

            Bukkit.getScheduler().runTask(plugin, () -> {
                if (sessions.getById(session.getSessionId()) == null) return; // ended meanwhile
                pullIn(session, partyIds);
                kickOut(session, partyIds);
            });
        });
    }

    /** Pulls any online party member not currently in the arena. */
    private void pullIn(PrivateSession session, Set<UUID> partyIds) {
        for (UUID memberId : partyIds) {
            if (memberId.equals(session.getOwner())) continue;

            Player member = Bukkit.getPlayer(memberId);
            if (member == null || !member.isOnline()) continue;

            Arena current = BedwarsAPI.getGameAPI().getArenaByPlayer(member);
            if (current != null && session.matchesArena(current)) continue; // already there

            // Guard against a session that ended moments ago but whose removal hasn't reached
            // this server's cache yet (e.g. a party member already kicked to a hub whose own
            // poll hasn't caught up): only summon into an arena that's genuinely still active.
            if (!ArenaNames.isActiveStatus(session.getArenaName())) continue;

            plugin.forceSummon(session, memberId, () -> member.sendMessage(Lang.msg(
                    "autosummon.pulled-in",
                    "%arena%", session.getArenaName(), "%owner%", ownerName(session))));
        }
    }

    /** Removes anyone from the arena who is no longer a member of the host's party. */
    private void kickOut(PrivateSession session, Set<UUID> partyIds) {
        Arena arena = BedwarsAPI.getGameAPI().getArenaByExactName(session.getArenaName());
        if (arena == null || !arena.exists()) return;

        Set<Player> present = new HashSet<>(arena.getPlayers());
        present.addAll(arena.getSpectators());

        for (Player member : present) {
            UUID id = member.getUniqueId();
            if (id.equals(session.getOwner())) continue;
            if (member.hasPermission(BYPASS_PERM)) continue;
            if (partyIds.contains(id)) continue;

            arena.kickPlayer(member);
            member.sendMessage(Lang.msg("autosummon.removed", "%owner%", ownerName(session)));
        }
    }

    private String ownerName(PrivateSession session) {
        return ItemUtil.offlineName(session.getOwner(), "the host");
    }
}
