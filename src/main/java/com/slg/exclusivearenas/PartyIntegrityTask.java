package com.slg.exclusivearenas;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.UUID;

/**
 * Periodic check (every {@code private.party_check_seconds}) that the host of every
 * PARTY-gated session still leads a party. When they don't any more — they left the party,
 * were removed from it, or handed leadership to someone else — the session is cleanly
 * converted to a CODE-gated match ({@link PrivateSessionService#convertToCodePolicy})
 * instead of lingering behind a party that no longer authorises anyone to join.
 *
 * Only the server the host is currently online on performs the check (party hooks resolve
 * reliably for local players); the converted policy reaches every other backend through the
 * shared database. The party lookup itself may resolve asynchronously, so the actual
 * conversion is bounced back onto the main thread and re-validated there.
 */
public final class PartyIntegrityTask extends BukkitRunnable {

    private final ExclusiveArenasPlugin plugin;
    private final PrivateSessionService sessions;

    public PartyIntegrityTask(ExclusiveArenasPlugin plugin, PrivateSessionService sessions) {
        this.plugin = plugin;
        this.sessions = sessions;
    }

    @Override
    public void run() {
        // With no parties plugin hooked in, every lookup comes back empty — indistinguishable
        // from "host left their party" — so never convert anything while that's the case.
        // (PARTY sessions can only be created while a hook is present, but the parties plugin
        // may have been unloaded/reloaded since.)
        if (!PartyResolver.hasPartiesHook()) return;

        for (PrivateSession session : sessions.getAllSessions()) {
            if (session.getJoinPolicy() != JoinPolicy.PARTY) continue;
            UUID owner = session.getOwner();
            if (owner == null) continue;

            Player host = Bukkit.getPlayer(owner);
            if (host == null || !host.isOnline()) continue; // another server's host — not ours to judge

            UUID sessionId = session.getSessionId();
            PartyResolver.getPartyMember(owner, opt -> {
                boolean stillLeader = opt.isPresent() && opt.get().getParty().getLeaders().stream()
                        .anyMatch(leader -> owner.equals(leader.getUniqueId()));
                if (stillLeader) return;
                Bukkit.getScheduler().runTask(plugin, () -> plugin.convertSessionToCode(sessionId));
            });
        }
    }
}
