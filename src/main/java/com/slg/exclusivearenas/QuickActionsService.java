package com.slg.exclusivearenas;

import de.marcely.bedwars.api.arena.Arena;
import de.marcely.bedwars.api.arena.ArenaStatus;
import de.marcely.bedwars.api.arena.KickReason;
import de.marcely.bedwars.api.arena.Team;
import de.marcely.bedwars.api.event.arena.ArenaStatusChangeEvent;
import de.marcely.bedwars.api.game.spawner.Spawner;
import de.marcely.bedwars.api.game.spectator.KickSpectatorReason;
import de.marcely.bedwars.api.game.spectator.SpectateReason;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One-click host shortcuts for a live private match. These are the powers MBedwars keeps
 * behind its (explicitly not-for-public-use) debug command, recreated through the stable
 * API so they can't silently break with an MBedwars update.
 *
 * Every action runs on the server hosting the arena; {@link RemoteCommandService} relays
 * a request here when the host clicks the button on another server. {@code actor} is null
 * on that relayed path — feedback then goes to the arena.
 */
public final class QuickActionsService implements Listener {

    private final ExclusiveArenasPlugin plugin;

    /**
     * Roster snapshots of regenerations in flight, keyed by lower-cased arena name — taken
     * before the round is wound down so everyone can be put back afterwards.
     */
    private final Map<String, RegenSnapshot> pendingRegens = new ConcurrentHashMap<>();

    private static final class RegenSnapshot {
        final PrivateSession session;
        final Map<UUID, String> playerTeams = new HashMap<>(); // team name, null = unassigned
        final Set<UUID> spectators = new HashSet<>();          // were already spectating; stay so
        final long startedAt = System.currentTimeMillis();
        Player actor; // may log off mid-regen; re-checked before messaging

        RegenSnapshot(PrivateSession session, Player actor) {
            this.session = session;
            this.actor = actor;
        }
    }

    public QuickActionsService(ExclusiveArenasPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * True while this arena is being force-regenerated with a roster snapshot waiting to be
     * restored — lifecycle listeners must not treat the forced round end as the match ending.
     */
    public boolean isRegenPending(Arena arena) {
        return arena != null && pendingRegens.containsKey(key(arena.getName()));
    }

    // ── Kick all ─────────────────────────────────────────────────────────────────

    /**
     * Kicks every player and every spectator. With {@code keepHost} (shift-click) the host
     * stays in the match; a plain click clears the arena completely, host included.
     */
    public void kickAll(Player actor, PrivateSession session, Arena arena, boolean keepHost) {
        int kicked = 0;
        for (Player p : arena.getPlayers().toArray(new Player[0])) {
            if (keepHost && p.getUniqueId().equals(session.getOwner())) continue;
            if (arena.kickPlayer(p, KickReason.KICK)) kicked++;
        }
        kicked += arena.kickAllSpectators(KickSpectatorReason.PLUGIN_STOP);

        if (kicked > 0) {
            arena.broadcast(Lang.msg("kickall.broadcast"));
            tell(actor, arena, Lang.msg(keepHost ? "kickall.kicked-kept-host" : "kickall.kicked",
                    "%count%", String.valueOf(kicked)));
        } else {
            tell(actor, arena, Lang.msg("kickall.none"));
        }
    }

    // ── Regenerate map, keeping everyone ────────────────────────────────────────────

    /**
     * Regenerates the arena without anyone leaving it, in three acts:
     *   1. Everyone playing is switched to a spectator (teams snapshotted first). With no
     *      players left the round ends on its own and MBedwars runs its normal reset —
     *      which regenerates the map — instead of us force-stopping anything.
     *   2. The moment the arena is back in its lobby, everyone is re-added and put back on
     *      the team they had (spectators-by-choice stay spectators), and the arena is told
     *      the map was regenerated.
     *   3. If enough players are back (and quick_actions.restart_after_regen is on), the
     *      round is immediately force-started again via MBedwars' debug 13 — from the
     *      players' perspective the map simply reset around them.
     */
    public void regenerateKeepingPlayers(Player actor, PrivateSession session, Arena arena) {
        String k = key(arena.getName());
        if (pendingRegens.containsKey(k)) return; // one at a time
        if (arena.getStatus() != ArenaStatus.RUNNING) {
            tell(actor, arena, Lang.msg("quick.running-only"));
            return;
        }

        RegenSnapshot snapshot = new RegenSnapshot(session, actor);
        for (Player p : arena.getPlayers()) {
            Team team = arena.getPlayerTeam(p);
            snapshot.playerTeams.put(p.getUniqueId(), team != null ? team.name() : null);
        }
        for (Player p : arena.getSpectators()) {
            snapshot.spectators.add(p.getUniqueId());
        }
        // Registered before touching anyone, so the round end this causes is recognized
        // as ours (PrivacyLifecycleListener keeps the session alive while a regen pends).
        pendingRegens.put(k, snapshot);

        arena.broadcast(Lang.msg("quick.regen-started"));

        for (Player p : arena.getPlayers().toArray(new Player[0])) {
            try {
                if (arena.addSpectator(p, SpectateReason.PLUGIN) == null) {
                    arena.kickPlayer(p, KickReason.PLUGIN); // still re-added after the reset
                }
            } catch (Throwable t) {
                arena.kickPlayer(p, KickReason.PLUGIN);
            }
        }

        // Watchdog: if the arena never comes back to its lobby, drop the snapshot and say so.
        long timeoutTicks = 20L * Math.max(10,
                plugin.getEaConfig().intNum("quick_actions.regenerate_timeout_seconds", 60));
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            RegenSnapshot stale = pendingRegens.remove(k);
            if (stale != null && stale.actor != null && stale.actor.isOnline()) {
                stale.actor.sendMessage(Lang.msg("quick.regen-failed"));
            }
        }, timeoutTicks);
    }

    /**
     * Completion side of {@link #regenerateKeepingPlayers}: the moment the regenerated arena
     * is back in its lobby, everyone from the snapshot is re-added (with join tickets so our
     * own gate lets them through), put back on their teams, and the round is restarted.
     */
    @EventHandler
    public void onStatusChange(ArenaStatusChangeEvent event) {
        if (event.getNewStatus() != ArenaStatus.LOBBY) return;

        Arena arena = event.getArena();
        String k = key(arena.getName());
        if (!pendingRegens.containsKey(k)) return;

        // Give MBedwars a moment to finish settling the fresh lobby, then confirm the arena
        // is genuinely done regenerating (still LOBBY, not flickered back into a reset) before
        // reseating anyone — a handful of retries beats trusting a single fixed delay.
        confirmSettledThenReseat(arena, k, 10);
    }

    private void confirmSettledThenReseat(Arena arena, String k, int attemptsLeft) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            RegenSnapshot snapshot = pendingRegens.get(k);
            if (snapshot == null) return; // watchdog already gave up, or another pass handled it
            if (!arena.exists()) { pendingRegens.remove(k); return; }
            if (arena.getStatus() != ArenaStatus.LOBBY) {
                if (attemptsLeft > 0) confirmSettledThenReseat(arena, k, attemptsLeft - 1);
                return; // the timeout watchdog in regenerateKeepingPlayers still bounds this
            }
            if (pendingRegens.remove(k) != null) reseat(arena, snapshot);
        }, 20L);
    }

    private void reseat(Arena arena, RegenSnapshot snapshot) {
        PrivateSession session = snapshot.session;
        if (plugin.getSessionService().getById(session.getSessionId()) == null) return;
        if (!arena.exists() || arena.getStatus() != ArenaStatus.LOBBY) return;

        for (Map.Entry<UUID, String> entry : snapshot.playerTeams.entrySet()) {
            Player p = Bukkit.getPlayer(entry.getKey());
            if (p == null || !p.isOnline()) continue;

            plugin.getTicketService().grant(p.getUniqueId(), session.getSessionId(), arena.getName());
            Team team = teamByName(arena, entry.getValue());
            if (team != null) {
                if (arena.addPlayer(p, team) != null) arena.addPlayer(p); // team full/gone → any team
            } else {
                arena.addPlayer(p);
            }
        }
        for (UUID id : snapshot.spectators) {
            Player p = Bukkit.getPlayer(id);
            if (p == null || !p.isOnline()) continue;
            try {
                arena.addSpectator(p, SpectateReason.PLUGIN);
            } catch (Throwable ignored) {
                // an opt-in spectator we can't restore is not worth failing the regen over
            }
        }

        arena.broadcast(Lang.msg("quick.regen-broadcast"));
        if (snapshot.actor != null && snapshot.actor.isOnline()) {
            snapshot.actor.sendMessage(Lang.msg("quick.regen-done"));
        }

        if (!plugin.getEaConfig().bool("quick_actions.restart_after_regen", true)) return;

        // Restart the round once everyone is seated — needs another beat for MBedwars to
        // register the re-adds, and startMatchNow re-checks the player count itself.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!arena.exists() || arena.getStatus() != ArenaStatus.LOBBY) return;
            if (plugin.getSessionService().getById(session.getSessionId()) == null) return;
            if (arena.getPlayers().size() < 2) return; // stay in the lobby; host decides
            plugin.startMatchNow(null, arena, session);
        }, 40L);
    }

    private static Team teamByName(Arena arena, String teamName) {
        if (teamName == null) return null;
        for (Team team : arena.getEnabledTeams()) {
            if (team.name().equalsIgnoreCase(teamName)) return team;
        }
        return null;
    }

    // ── Simple in-match actions ───────────────────────────────────────────────────

    /** Restores health, hunger and extinguishes fire for everyone playing. */
    public void healAll(Player actor, PrivateSession session, Arena arena) {
        int count = 0;
        for (Player p : arena.getPlayers()) {
            var attr = p.getAttribute(Attribute.MAX_HEALTH);
            p.setHealth(attr != null ? attr.getValue() : 20.0);
            p.setFoodLevel(20);
            p.setSaturation(20f);
            p.setFireTicks(0);
            count++;
        }
        if (count > 0) arena.broadcast(Lang.msg("quick.heal-broadcast"));
        tell(actor, arena, Lang.msg("quick.healed", "%count%", String.valueOf(count)));
    }

    /** Makes every generator in the arena drop immediately. */
    public void dropAllSpawners(Player actor, PrivateSession session, Arena arena) {
        if (arena.getStatus() != ArenaStatus.RUNNING) {
            tell(actor, arena, Lang.msg("quick.running-only"));
            return;
        }
        int count = 0;
        for (Spawner spawner : arena.getSpawners()) {
            try {
                spawner.drop();
                count++;
            } catch (Throwable ignored) {
                // a single broken spawner shouldn't stop the rest
            }
        }
        if (count > 0) arena.broadcast(Lang.msg("quick.drop-broadcast"));
        tell(actor, arena, Lang.msg("quick.dropped", "%count%", String.valueOf(count)));
    }

    /** Sudden death: destroys every remaining bed. */
    public void destroyAllBeds(Player actor, PrivateSession session, Arena arena) {
        if (arena.getStatus() != ArenaStatus.RUNNING) {
            tell(actor, arena, Lang.msg("quick.running-only"));
            return;
        }
        int count = plugin.getTimelineEngine().destroyAllBeds(arena);
        if (count > 0) arena.broadcast(Lang.msg("quick.beds-broadcast"));
        tell(actor, arena, Lang.msg("quick.beds-destroyed", "%count%", String.valueOf(count)));
    }

    /** Removes every dropped item inside the arena bounds. */
    public void clearGroundItems(Player actor, PrivateSession session, Arena arena) {
        World world = arena.getGameWorld();
        int count = 0;
        if (world != null) {
            for (Item item : world.getEntitiesByClass(Item.class)) {
                if (!arena.isInside(item.getLocation())) continue;
                item.remove();
                count++;
            }
        }
        tell(actor, arena, Lang.msg("quick.items-cleared", "%count%", String.valueOf(count)));
    }

    /** Fires the next timeline event right now instead of waiting for it. */
    public void skipToNextEvent(Player actor, PrivateSession session, Arena arena) {
        if (arena.getStatus() != ArenaStatus.RUNNING) {
            tell(actor, arena, Lang.msg("quick.running-only"));
            return;
        }
        TimelineService.Definition def = plugin.getTimelineEngine().skipToNextEvent(arena);
        if (def == null) tell(actor, arena, Lang.msg("quick.nothing-to-skip"));
        // Success feedback is the event's own in-arena broadcast.
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    private void tell(Player actor, Arena arena, String message) {
        if (actor != null && actor.isOnline()) {
            actor.sendMessage(message);
        } else if (arena != null) {
            arena.broadcast(message);
        }
    }

    private static String key(String arenaName) {
        return arenaName.toLowerCase(Locale.ROOT);
    }
}
