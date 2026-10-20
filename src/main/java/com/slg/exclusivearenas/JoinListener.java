package com.slg.exclusivearenas;

import de.marcely.bedwars.api.arena.AddPlayerIssue;
import de.marcely.bedwars.api.arena.Arena;
import de.marcely.bedwars.api.event.player.PlayerJoinArenaEvent;
import de.marcely.bedwars.api.event.player.SpectatorJoinArenaEvent;
import de.marcely.bedwars.api.event.remote.RemotePlayerPreJoinLocalArenaEvent;
import de.marcely.bedwars.api.game.spectator.SpectateReason;
import de.marcely.bedwars.api.hook.PartiesHook;
import de.marcely.bedwars.api.BedwarsAPI;
import de.marcely.bedwars.api.remote.RemoteAPI;
import de.marcely.bedwars.api.remote.RemotePlayer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

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

        plugin.prepareLobby(arena, session);

        // Owner is always allowed; clear any abandon timer. This must run BEFORE the bypass
        // allow — a bypass-holding host returning through it would otherwise never clear
        // hostLeftAt, and the abandon timeout would kill their own session under them.
        if (playerId.equals(session.getOwner())) {
            session.setHostLeftAt(null);
            session.markRecentJoin(playerId);
            plugin.syncPlayerClimate(arena, player);
            return;
        }

        if (player.hasPermission(BYPASS_PERM)) return;

        // Valid join ticket (granted by /ea join, party summon, or network message)
        if (tickets.consumeIfValid(playerId, session.getSessionId(), arena.getName())) {
            session.markRecentJoin(playerId);
            plugin.syncPlayerClimate(arena, player);
            return;
        }

        // Party policy: the membership check is async but this event is not — MBedwars reads
        // getIssues() the instant this method returns, so we must deny synchronously first and
        // only correct the decision (by re-summoning the player) once the async check actually
        // confirms they're allowed. This also means a hook that throws or never calls back simply
        // leaves the join denied, instead of silently admitting the player.
        if (session.getJoinPolicy() == JoinPolicy.PARTY) {
            // Most party hooks answer synchronously, though — in that case the answer is already
            // in hand before this method returns, so a member can simply be admitted instead of
            // being shown a denial and then summoned a tick later. The state word settles who
            // got there first: 0 = undecided, 1 = the sync window closed (the hook will answer
            // later, via the summon path), 2 = the hook allowed the player inside the window.
            final PrivateSession finalSession = session;
            AtomicInteger state = new AtomicInteger(0);
            PartyResolver.isInLeadersParty(player, session.getOwner(), allowed -> {
                if (!allowed) return; // denied (issue added below, or never admitted)
                if (state.compareAndSet(0, 2)) return; // answered in time — admitted directly below
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) return;
                    finalSession.markRecentJoin(playerId);
                    plugin.forceSummon(finalSession, playerId);
                    plugin.syncPlayerClimate(arena, player);
                });
            });
            if (!state.compareAndSet(0, 1)) {
                // The hook already confirmed them (state 2): admit now, no denial round-trip.
                session.markRecentJoin(playerId);
                plugin.syncPlayerClimate(arena, player);
                return;
            }
            event.addIssue(buildIssue(session, arena, IssueKind.PARTY));
            return;
        }

        // Code policy: require ticket OR check public status
        if (session.getJoinPolicy() == JoinPolicy.CODE) {
            if (!session.isPublic()) {
                event.addIssue(buildLockedIssue(arena));
                return;
            }
            // Public code session — player didn't use /ea join so they don't have a ticket
            event.addIssue(buildIssue(session, arena, IssueKind.CODE));
            return;
        }

        // Unrecognized/future join policy: deny by default instead of silently falling through.
        event.addIssue(buildLockedIssue(arena));
    }

    // ── Spectator join (same gating as a regular join) ─────────────────────────

    @EventHandler(priority = EventPriority.NORMAL)
    public void onSpectatorJoin(SpectatorJoinArenaEvent event) {
        // Only gate a deliberate "join as spectator" attempt — internal transitions like
        // dying, losing, or following a party elsewhere are never blocked.
        if (event.getReason() != SpectateReason.ENTER) return;

        Player player = event.getPlayer();
        Arena arena = event.getArena();
        UUID playerId = player.getUniqueId();

        PrivateSession session = sessions.getByArena(arena);
        if (session == null) return;

        if (player.hasPermission(BYPASS_PERM)) return;
        if (playerId.equals(session.getOwner())) return;
        if (tickets.consumeIfValid(playerId, session.getSessionId(), arena.getName())) return;

        if (session.getJoinPolicy() == JoinPolicy.PARTY) {
            // Same deny-first-then-correct shape as onLocalJoin, for the same reason: the async
            // party check must never be the thing standing between "denied" and "admitted".
            event.setCancelled(true);
            player.sendMessage(Lang.msg("locked.hint-party", "%owner%", ownerName(session)));
            final PrivateSession finalSession = session;
            PartyResolver.isInLeadersParty(player, session.getOwner(), allowed -> {
                if (!allowed) return;
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) return;
                    finalSession.markRecentJoin(playerId);
                    plugin.forceSummon(finalSession, playerId);
                });
            });
            return;
        }

        // Code policy: a ticket (checked above) is required — a bare spectate attempt has none.
        // The join code is deliberately NOT substituted into a denial: handing the code to the
        // very player being turned away would make the deny message a code oracle.
        event.setCancelled(true);
        if (!session.isPublic()) {
            player.sendMessage(Lang.msg("locked.private"));
        } else {
            player.sendMessage(Lang.msg("locked.hint-code"));
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
            session.markRecentJoin(playerId);
            return;
        }

        // Peek, don't consume: this is only MBedwars asking whether the transfer may go ahead.
        // The player's actual arrival fires the regular join gate (onLocalJoin), which is where
        // the ticket gets spent — consuming it here would leave that gate with nothing to honour
        // and deny the very player this call just approved.
        if (tickets.hasValidTicket(playerId, session.getSessionId(), arena.getName())) {
            session.markRecentJoin(playerId);
            return;
        }

        // We cannot check party membership for a player on another server, so a ticket is required.
        if (!session.isPublic()) {
            event.setIssue(buildLockedIssue(arena));
            return;
        }
        event.setIssue(buildIssue(session, arena,
                session.getJoinPolicy() == JoinPolicy.PARTY ? IssueKind.PARTY : IssueKind.CODE));
    }

    // ── Server join: auto-summon to active party session ──────────────────────

    /**
     * On a proxy network a player "joins" a backend on every server switch, not just when they
     * log in. A login that happened longer ago than this is a hop (e.g. the member deliberately
     * left the match for the hub) and must not pull them straight back in.
     */
    private static final Duration LOGIN_ROUTING_WINDOW = Duration.ofSeconds(20);

    @EventHandler(priority = EventPriority.MONITOR)
    public void onServerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (player.hasPermission(BYPASS_PERM)) return;

        // Delay 2 ticks so the player is fully initialised
        Bukkit.getScheduler().runTaskLater(plugin, () -> checkPartySession(player), 2L);
    }

    private void checkPartySession(Player player) {
        if (!player.isOnline()) return;
        UUID playerId = player.getUniqueId();
        if (isServerHop(playerId)) return;

        PartyResolver.getPartyMember(player, opt -> {
            if (opt.isEmpty()) return;
            PartiesHook.Party party = opt.get().getParty();

            // Find if any leader in the party has an active private session with PARTY policy.
            // The leader's own matches are never a target for the leader: a host who leaves
            // their lobby (to the hub, or just to log out) decides for themselves when to go
            // back — "Go to Arena" is one click away, and the abandon timer covers the rest.
            PrivateSession target = null;
            outer:
            for (PartiesHook.Member leader : party.getLeaders()) {
                if (leader.getUniqueId().equals(playerId)) continue;
                for (PrivateSession session : sessions.getSessionsByOwner(leader.getUniqueId())) {
                    if (session.getJoinPolicy() != JoinPolicy.PARTY) continue;
                    target = session;
                    break outer; // only summon to one session
                }
            }
            if (target == null) return;

            // The lookup above may have resolved off the main thread (or after this player
            // disconnected); re-check both before touching Bukkit API or granting a ticket.
            final PrivateSession finalTarget = target;
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player online = Bukkit.getPlayer(playerId);
                if (online == null || !online.isOnline()) return;
                // Already there (MBedwars seated them on arrival) — nothing to route.
                Arena current = BedwarsAPI.getGameAPI().getArenaByPlayer(online);
                if (current != null && finalTarget.matchesArena(current)) return;
                tickets.grant(playerId, finalTarget.getSessionId(), finalTarget.getArenaName());
                plugin.sendPlayerToArena(online, finalTarget.getArenaName());
            });
        });
    }

    /**
     * True when this join is a server switch rather than a fresh login — only knowable on a
     * network, where MBedwars' RemoteAPI tracks when the player logged into the proxy. On a
     * standalone server every join is a login.
     */
    private boolean isServerHop(UUID playerId) {
        RemoteAPI api = ArenaNames.remoteApi();
        if (api == null) return false;
        try {
            RemotePlayer rp = api.getOnlinePlayer(playerId);
            Instant login = rp == null ? null : rp.getLoginTime();
            return login != null && Duration.between(login, Instant.now()).compareTo(LOGIN_ROUTING_WINDOW) > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    // ── Server quit: drop the player's in-progress builder draft ──────────────

    /**
     * {@link DraftPrivateMatch} is documented to live only "until the session is created or
     * the player logs out" — this is the logout half. Their soft arena reservation is released
     * too, so the map frees up immediately instead of waiting out the reservation TTL.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onServerQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        DraftPrivateMatch draft = plugin.getDraftService().get(playerId);
        if (draft == null) return;
        if (draft.getArenaName() != null) {
            sessions.releaseDraftArena(draft.getArenaName(), playerId);
        }
        plugin.getDraftService().clear(playerId);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private enum IssueKind { PARTY, CODE }

    private AddPlayerIssue buildIssue(PrivateSession session, Arena arena, IssueKind kind) {
        String key = kind == IssueKind.PARTY ? "locked.hint-party" : "locked.hint-code";
        String msg = Lang.raw(key, "%arena%", arena.getName());

        // Only %owner% is ever substituted here: this message is a DENIAL, so filling in
        // %code% would hand the join code to exactly the player being turned away if an admin
        // ever added the placeholder to the lang string.
        if (kind == IssueKind.PARTY) {
            msg = msg.replace("%owner%", ownerName(session));
        }

        return AddPlayerIssue.construct("exclusivearenas.private", ItemUtil.color(msg));
    }

    private String ownerName(PrivateSession session) {
        return ItemUtil.offlineName(session.getOwner(), "?");
    }

    private AddPlayerIssue buildLockedIssue(Arena arena) {
        String msg = Lang.raw("locked.private", "%arena%", arena.getName());
        return AddPlayerIssue.construct("exclusivearenas.private", ItemUtil.color(msg));
    }
}
