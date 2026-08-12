package com.slg.exclusivearenas;

import de.marcely.bedwars.api.BedwarsAPI;
import de.marcely.bedwars.api.arena.Arena;
import de.marcely.bedwars.api.arena.ArenaStatus;
import de.marcely.bedwars.api.arena.KickReason;
import de.marcely.bedwars.api.arena.Team;
import de.marcely.bedwars.api.event.arena.ArenaStatusChangeEvent;
import de.marcely.bedwars.api.game.spawner.Spawner;
import de.marcely.bedwars.api.game.spectator.KickSpectatorReason;
import de.marcely.bedwars.api.game.spectator.SpectateReason;
import de.marcely.bedwars.api.game.upgrade.QueuedTrap;
import de.marcely.bedwars.api.game.upgrade.UpgradeLevel;
import de.marcely.bedwars.api.game.upgrade.UpgradeState;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
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

    /** Arenas (lower-cased name) currently frozen by {@link #toggleFreeze} — see {@link #onMove}. */
    private final Set<String> frozenArenas = ConcurrentHashMap.newKeySet();

    /** Arenas (lower-cased name) with PvP damage blocked by {@link #togglePvp}. */
    private final Set<String> pvpDisabledArenas = ConcurrentHashMap.newKeySet();

    /** Arenas (lower-cased name) currently paused by {@link #togglePause} — also freezes movement. */
    private final Set<String> pausedArenas = ConcurrentHashMap.newKeySet();

    /** Last time each player actually moved (X/Z change), for {@link #kickAfkPlayers}. */
    private final Map<UUID, Long> lastActivity = new ConcurrentHashMap<>();

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
        if (keepHost) {
            // kickAllSpectators would sweep the host out too when they happen to be
            // spectating — kick spectators one by one and spare the host instead.
            for (Player spec : arena.getSpectators().toArray(new Player[0])) {
                if (spec.getUniqueId().equals(session.getOwner())) continue;
                try {
                    var data = arena.getSpectateData(spec);
                    if (data != null) {
                        data.kick(KickSpectatorReason.PLUGIN_STOP);
                        kicked++;
                    }
                } catch (Throwable ignored) {
                    // best effort per spectator
                }
            }
        } else {
            kicked += arena.kickAllSpectators(KickSpectatorReason.PLUGIN_STOP);
        }

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

        // Watchdog: a regen that's merely SLOW (big map, slow disk) must never have its snapshot
        // pulled out from under it — the players switched to spectator above would then be
        // stuck there permanently, since onStatusChange (the real completion path) has no fixed
        // deadline of its own and would find nothing left to reseat them from. So the configured
        // timeout only warns; giving up for real happens much later, and even then checks once
        // more whether the arena actually did settle before conceding.
        long softTimeoutTicks = 20L * Math.max(10,
                plugin.getEaConfig().intNum("quick_actions.regenerate_timeout_seconds", 60));
        long hardTimeoutTicks = softTimeoutTicks * 5;

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            RegenSnapshot stillPending = pendingRegens.get(k);
            if (stillPending == null) return; // already completed
            if (stillPending.actor != null && stillPending.actor.isOnline()) {
                stillPending.actor.sendMessage(Lang.msg("quick.regen-slow"));
            }
        }, softTimeoutTicks);

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            RegenSnapshot stale = pendingRegens.get(k);
            if (stale == null) return; // already completed
            // Last chance: reseat now if the arena actually did settle and the event was missed,
            // rather than stranding everyone as spectators.
            if (arena.exists() && arena.getStatus() == ArenaStatus.LOBBY && pendingRegens.remove(k) != null) {
                reseat(arena, stale);
                return;
            }
            pendingRegens.remove(k);
            if (stale.actor != null && stale.actor.isOnline()) {
                stale.actor.sendMessage(Lang.msg("quick.regen-failed"));
            }
        }, hardTimeoutTicks);
    }

    /**
     * Completion side of {@link #regenerateKeepingPlayers}: the moment the regenerated arena
     * is back in its lobby, everyone from the snapshot is re-added (with join tickets so our
     * own gate lets them through), put back on their teams, and the round is restarted.
     */
    @EventHandler
    public void onStatusChange(ArenaStatusChangeEvent event) {
        // Freezing/pausing/PvP-blocking only ever make sense mid-round — don't let them silently
        // carry over into this arena's next match once the round they were set during ends.
        if (event.getNewStatus() != ArenaStatus.RUNNING) {
            String k = key(event.getArena().getName());
            frozenArenas.remove(k);
            pvpDisabledArenas.remove(k);
            pausedArenas.remove(k);
        }

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

    // ── Team management ───────────────────────────────────────────────────────────

    /** Instantly ends the match, awarding {@code winner} the win. */
    public void forceWin(Player actor, PrivateSession session, Arena arena, Team winner) {
        if (winner == null) return;
        try {
            arena.endMatch(winner);
            tell(actor, arena, Lang.msg("quick.force-win", "%team%", winner.getDisplayName(null)));
        } catch (Throwable t) {
            plugin.getLogger().warning("Force-win failed on " + arena.getName() + ": " + t.getMessage());
        }
    }

    /** Swaps every player between two teams' rosters. */
    public void swapTeams(Player actor, PrivateSession session, Arena arena, Team a, Team b) {
        if (a == null || b == null || a == b) return;
        List<Player> aPlayers = playersOnTeam(arena, a);
        List<Player> bPlayers = playersOnTeam(arena, b);
        for (Player p : aPlayers) arena.setPlayerTeam(p, b);
        for (Player p : bPlayers) arena.setPlayerTeam(p, a);
        tell(actor, arena, Lang.msg("quick.teams-swapped",
                "%team-a%", a.getDisplayName(null), "%team-b%", b.getDisplayName(null)));
    }

    /** Re-shuffles every current player evenly across the enabled teams. */
    public void balanceTeams(Player actor, PrivateSession session, Arena arena) {
        List<Team> teams = new ArrayList<>(arena.getEnabledTeams());
        if (teams.isEmpty()) return;
        List<Player> players = new ArrayList<>(arena.getPlayers());
        java.util.Collections.shuffle(players);

        int i = 0;
        for (Player p : players) {
            arena.setPlayerTeam(p, teams.get(i % teams.size()));
            i++;
        }
        tell(actor, arena, Lang.msg("quick.teams-balanced", "%count%", String.valueOf(players.size())));
    }

    // ── Traps & upgrades ─────────────────────────────────────────────────────────

    /**
     * Force-triggers a random team's queued trap, if any team has one queued. The exact
     * semantics of {@code UpgradeState#triggerTrap}'s player/boolean arguments aren't
     * documented anywhere — a best-effort "chaos" action, wrapped so a wrong interpretation
     * degrades to a no-op rather than breaking anything.
     */
    public void triggerRandomTrap(Player actor, PrivateSession session, Arena arena) {
        List<Team> candidates = new ArrayList<>();
        for (Team team : arena.getAliveTeams()) {
            UpgradeState state = arena.getUpgradeState(team);
            if (state != null && !state.getQueuedTraps().isEmpty()) candidates.add(team);
        }
        if (candidates.isEmpty()) {
            tell(actor, arena, Lang.msg("quick.no-traps-queued"));
            return;
        }

        Random random = new Random();
        Team target = candidates.get(random.nextInt(candidates.size()));
        List<Player> players = new ArrayList<>(arena.getPlayers());
        Player triggerer = players.isEmpty() ? null : players.get(random.nextInt(players.size()));
        try {
            arena.getUpgradeState(target).triggerTrap(triggerer, target, true);
            tell(actor, arena, Lang.msg("quick.trap-triggered", "%team%", target.getDisplayName(null)));
        } catch (Throwable t) {
            plugin.getLogger().warning("Trap trigger failed on " + arena.getName() + ": " + t.getMessage());
        }
    }

    /** Clears every team's queued (not yet triggered) traps. */
    public void clearAllTrapQueues(Player actor, PrivateSession session, Arena arena) {
        int cleared = 0;
        for (Team team : arena.getEnabledTeams()) {
            UpgradeState state = arena.getUpgradeState(team);
            if (state == null || state.getQueuedTraps().isEmpty()) continue;
            cleared += state.getQueuedTraps().size();
            state.clearTrapQueue();
        }
        tell(actor, arena, Lang.msg("quick.traps-cleared", "%count%", String.valueOf(cleared)));
    }

    /** Resets every team's generator/shop upgrades back to nothing purchased. */
    public void resetAllTeamUpgrades(Player actor, PrivateSession session, Arena arena) {
        int cleared = 0;
        for (Team team : arena.getEnabledTeams()) {
            UpgradeState state = arena.getUpgradeState(team);
            if (state == null) continue;
            for (UpgradeLevel level : new ArrayList<>(state.getActiveUpgrades())) {
                try {
                    if (state.clearUpgrade(level.getUpgrade()) != null) cleared++;
                } catch (Throwable ignored) {
                    // best effort per upgrade
                }
            }
        }
        tell(actor, arena, Lang.msg("quick.upgrades-reset", "%count%", String.valueOf(cleared)));
    }

    // ── Buffs ────────────────────────────────────────────────────────────────────

    /** Grants everyone currently in the arena a timed potion effect. */
    public void grantEffect(Player actor, PrivateSession session, Arena arena,
                            org.bukkit.potion.PotionEffectType type, int amplifier, int seconds) {
        if (type == null) return;
        int count = 0;
        for (Player p : arena.getPlayers()) {
            p.addPotionEffect(new org.bukkit.potion.PotionEffect(type, Math.max(1, seconds) * 20, amplifier, false, true));
            count++;
        }
        if (count > 0) arena.broadcast(Lang.msg("quick.buff-broadcast"));
        tell(actor, arena, Lang.msg("quick.buffed", "%count%", String.valueOf(count)));
    }

    // ── Freeze ───────────────────────────────────────────────────────────────────

    /** Toggles whether everyone in the arena is locked in place — a "hold on a second" button. */
    public void toggleFreeze(Player actor, PrivateSession session, Arena arena) {
        String k = key(arena.getName());
        if (!frozenArenas.add(k)) {
            frozenArenas.remove(k);
            arena.broadcast(Lang.msg("quick.unfrozen"));
        } else {
            arena.broadcast(Lang.msg("quick.frozen"));
        }
    }

    /** Keeps {@link #lastActivity} from accumulating entries for players long gone. */
    @EventHandler
    public void onPlayerQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        lastActivity.remove(event.getPlayer().getUniqueId());
    }

    /** Snaps movement back for anyone in a frozen (or paused — see {@link #togglePause}) arena. */
    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) return;
        boolean realMove = from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ();
        if (realMove) lastActivity.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());

        if (!realMove || (frozenArenas.isEmpty() && pausedArenas.isEmpty())) return;

        Player player = event.getPlayer();
        Arena arena = BedwarsAPI.getGameAPI().getArenaByPlayer(player);
        if (arena == null) return;
        String k = key(arena.getName());
        if (!frozenArenas.contains(k) && !pausedArenas.contains(k)) return;
        event.setTo(from);
        // Without this, a player already falling/knocked back when frozen keeps their old
        // velocity and immediately drifts again next tick despite the position snap-back.
        player.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
        player.setFallDistance(0f);
    }

    // ── Recovery ─────────────────────────────────────────────────────────────────

    /** Sweeps disconnected players who are back online (on this server) and rejoins them. */
    public void forceRejoinDisconnected(Player actor, PrivateSession session, Arena arena) {
        int rejoined = 0;
        for (de.marcely.bedwars.api.arena.QuitPlayerMemory memory :
                new ArrayList<>(arena.getQuitPlayerMemories())) {
            Player online = Bukkit.getPlayer(memory.getUniqueId());
            if (online == null || !online.isOnline()) continue;
            try {
                if (arena.rejoinPlayer(online) == null) rejoined++;
            } catch (Throwable ignored) {
                // best effort per player
            }
        }
        tell(actor, arena, Lang.msg("quick.rejoined", "%count%", String.valueOf(rejoined)));
    }

    // ── Debug ────────────────────────────────────────────────────────────────────

    /**
     * Visualizes the arena's match-area border to {@code actor} only — a local-only visual
     * aid, never relayed cross-server (the border particles wouldn't render for a player on a
     * different server than the arena anyway).
     */
    public void revealBorder(Player actor, Arena arena) {
        if (actor == null) return;
        try {
            BedwarsAPI.getGameAPI().drawBorder(arena.getMinRegionCorner(), arena.getMaxRegionCorner(), actor);
            actor.sendMessage(Lang.msg("quick.border-shown"));
        } catch (Throwable t) {
            plugin.getLogger().warning("Draw border failed on " + arena.getName() + ": " + t.getMessage());
        }
    }

    // ── Match timer ─────────────────────────────────────────────────────────────

    /** Nudges the match-end countdown by {@code deltaSeconds} (negative to shorten). */
    public void adjustMatchTimer(Player actor, PrivateSession session, Arena arena, int deltaSeconds) {
        if (arena.getStatus() != ArenaStatus.RUNNING) {
            tell(actor, arena, Lang.msg("quick.running-only"));
            return;
        }
        int next = Math.max(30, arena.getIngameTimeRemaining() + deltaSeconds);
        arena.setIngameTimeRemaining(next);
        arena.broadcast(Lang.msg(deltaSeconds >= 0 ? "quick.timer-extended" : "quick.timer-shortened",
                "%time%", TimelineService.format(next)));
        tell(actor, arena, Lang.msg("quick.timer-adjusted", "%time%", TimelineService.format(next)));
    }

    // ── PvP toggle ───────────────────────────────────────────────────────────────

    /** Toggles whether players can damage each other at all in this arena. */
    public void togglePvp(Player actor, PrivateSession session, Arena arena) {
        String k = key(arena.getName());
        if (!pvpDisabledArenas.add(k)) {
            pvpDisabledArenas.remove(k);
            arena.broadcast(Lang.msg("quick.pvp-enabled"));
        } else {
            arena.broadcast(Lang.msg("quick.pvp-disabled"));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPvpDamage(EntityDamageByEntityEvent event) {
        if (pvpDisabledArenas.isEmpty()) return;
        if (!(event.getEntity() instanceof Player victim)) return;
        Player attacker = resolveAttacker(event.getDamager());
        if (attacker == null) return;

        Arena arena = BedwarsAPI.getGameAPI().getArenaByPlayer(victim);
        if (arena == null || !pvpDisabledArenas.contains(key(arena.getName()))) return;
        event.setCancelled(true);
    }

    private static Player resolveAttacker(Entity damager) {
        if (damager instanceof Player p) return p;
        if (damager instanceof Projectile proj && proj.getShooter() instanceof Player p) return p;
        return null;
    }

    // ── Emergency pause ─────────────────────────────────────────────────────────

    /** Freezes movement AND holds off every pending timeline event until resumed. */
    public void togglePause(Player actor, PrivateSession session, Arena arena) {
        String k = key(arena.getName());
        boolean nowPaused = pausedArenas.add(k);
        if (!nowPaused) pausedArenas.remove(k);
        plugin.getTimelineEngine().setPaused(arena, nowPaused);
        arena.broadcast(Lang.msg(nowPaused ? "quick.paused" : "quick.resumed"));
    }

    // ── Inventory / roster chaos ────────────────────────────────────────────────

    /** Clears every player's inventory and armor — a hard reset, not a heal. */
    public void stripInventories(Player actor, PrivateSession session, Arena arena) {
        int count = 0;
        for (Player p : arena.getPlayers()) {
            p.getInventory().clear();
            p.getInventory().setArmorContents(null);
            p.getInventory().setItemInOffHand(null);
            count++;
        }
        if (count > 0) arena.broadcast(Lang.msg("quick.stripped-broadcast"));
        tell(actor, arena, Lang.msg("quick.stripped", "%count%", String.valueOf(count)));
    }

    /** Grants Strength II + Resistance II for 60s to whichever team currently has the fewest players. */
    public void comebackBuff(Player actor, PrivateSession session, Arena arena) {
        Map<Team, Integer> counts = new HashMap<>();
        for (Player p : arena.getPlayers()) {
            Team t = arena.getPlayerTeam(p);
            if (t != null) counts.merge(t, 1, Integer::sum);
        }
        if (counts.isEmpty()) {
            tell(actor, arena, Lang.msg("quick.no-teams-eligible"));
            return;
        }
        Team lowest = null;
        int lowestCount = Integer.MAX_VALUE;
        for (Map.Entry<Team, Integer> e : counts.entrySet()) {
            if (e.getValue() < lowestCount) { lowestCount = e.getValue(); lowest = e.getKey(); }
        }
        for (Player p : playersOnTeam(arena, lowest)) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, 60 * 20, 0, false, true));
            p.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 60 * 20, 0, false, true));
        }
        arena.broadcast(Lang.msg("quick.comeback-broadcast", "%team%", lowest.getDisplayName(null)));
        tell(actor, arena, Lang.msg("quick.comeback-applied", "%team%", lowest.getDisplayName(null)));
    }

    /**
     * Teleports every player to a random X/Z within the arena's configured region (same
     * height they're already at, to avoid dropping anyone into terrain or off the map).
     */
    public void randomScatter(Player actor, PrivateSession session, Arena arena) {
        if (arena.getStatus() != ArenaStatus.RUNNING) {
            tell(actor, arena, Lang.msg("quick.running-only"));
            return;
        }
        World world = arena.getGameWorld();
        var min = arena.getMinRegionCorner();
        var max = arena.getMaxRegionCorner();
        if (world == null || min == null || max == null) {
            tell(actor, arena, Lang.msg("quick.no-region"));
            return;
        }
        double minX = Math.min(min.getX(), max.getX()), maxX = Math.max(min.getX(), max.getX());
        double minZ = Math.min(min.getZ(), max.getZ()), maxZ = Math.max(min.getZ(), max.getZ());
        Random random = new Random();
        int count = 0;
        for (Player p : arena.getPlayers()) {
            Location loc = p.getLocation().clone();
            loc.setWorld(world);
            loc.setX(minX + random.nextDouble() * (maxX - minX));
            loc.setZ(minZ + random.nextDouble() * (maxZ - minZ));
            p.teleport(loc);
            count++;
        }
        if (count > 0) arena.broadcast(Lang.msg("quick.scatter-broadcast"));
        tell(actor, arena, Lang.msg("quick.scattered", "%count%", String.valueOf(count)));
    }

    /** Kicks anyone who hasn't actually moved in {@code quick_actions.afk_kick_threshold_seconds}. */
    public void kickAfkPlayers(Player actor, PrivateSession session, Arena arena) {
        if (arena.getStatus() != ArenaStatus.RUNNING) {
            tell(actor, arena, Lang.msg("quick.running-only"));
            return;
        }
        long thresholdMs = plugin.getEaConfig().intNum("quick_actions.afk_kick_threshold_seconds", 120) * 1000L;
        long now = System.currentTimeMillis();
        int kicked = 0;
        for (Player p : new ArrayList<>(arena.getPlayers())) {
            Long last = lastActivity.get(p.getUniqueId());
            if (last == null || now - last < thresholdMs) continue;
            try {
                arena.kickPlayer(p, KickReason.KICK);
                kicked++;
            } catch (Throwable ignored) {
                // best effort per player
            }
        }
        if (kicked > 0) arena.broadcast(Lang.msg("quick.afk-broadcast", "%count%", String.valueOf(kicked)));
        tell(actor, arena, Lang.msg("quick.afk-kicked", "%count%", String.valueOf(kicked)));
    }

    // ── Shop / info ──────────────────────────────────────────────────────────────

    /** Clears every host-configured shop price/disable override for this session. */
    public void resetShopPrices(Player actor, PrivateSession session, Arena arena) {
        int count = session.getSettings().getShopOverrides().size();
        session.getSettings().clearShopOverrides();
        plugin.getSessionService().saveSettings(session);
        tell(actor, arena, Lang.msg("quick.shop-reset", "%count%", String.valueOf(count)));
    }

    /** Gives every player a compass pointed at their nearest enemy (a one-time snapshot, not a live track). */
    public void giveTrackingCompass(Player actor, PrivateSession session, Arena arena) {
        int given = 0;
        for (Player p : arena.getPlayers()) {
            Team myTeam = arena.getPlayerTeam(p);
            Player nearest = null;
            double nearestDist = Double.MAX_VALUE;
            for (Player other : arena.getPlayers()) {
                if (other.equals(p) || !other.getWorld().equals(p.getWorld())) continue;
                if (myTeam != null && myTeam.equals(arena.getPlayerTeam(other))) continue;
                double d = other.getLocation().distanceSquared(p.getLocation());
                if (d < nearestDist) { nearestDist = d; nearest = other; }
            }
            ItemStack compass = new ItemStack(Material.COMPASS);
            ItemMeta meta = compass.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(ItemUtil.color(nearest != null
                        ? "&e&lTracker &7(" + nearest.getName() + ")" : "&e&lTracker"));
                compass.setItemMeta(meta);
            }
            p.getInventory().addItem(compass);
            if (nearest != null) p.setCompassTarget(nearest.getLocation());
            given++;
        }
        if (given > 0) arena.broadcast(Lang.msg("quick.compass-broadcast"));
        tell(actor, arena, Lang.msg("quick.compass-given", "%count%", String.valueOf(given)));
    }

    /** Broadcasts each enabled team's alive/eliminated status and total kills. */
    public void announceStats(Player actor, PrivateSession session, Arena arena) {
        arena.broadcast(Lang.msg("quick.stats-header", "%arena%", arena.getName()));
        for (Team team : arena.getEnabledTeams()) {
            boolean alive = arena.getAliveTeams().contains(team);
            int kills = 0;
            for (Player p : playersOnTeam(arena, team)) {
                kills += arena.getPlayerKillHistory(p).size();
            }
            arena.broadcast(Lang.msg(alive ? "quick.stats-line-alive" : "quick.stats-line-dead",
                    "%team%", team.getDisplayName(null), "%kills%", String.valueOf(kills)));
        }
        tell(actor, arena, Lang.msg("quick.stats-sent"));
    }

    private static List<Player> playersOnTeam(Arena arena, Team team) {
        List<Player> out = new ArrayList<>();
        for (Player p : arena.getPlayers()) {
            if (team.equals(arena.getPlayerTeam(p))) out.add(p);
        }
        return out;
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
