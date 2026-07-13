package com.slg.exclusivearenas;

import de.marcely.bedwars.api.arena.Arena;
import de.marcely.bedwars.api.arena.ArenaStatus;
import de.marcely.bedwars.api.arena.Team;
import de.marcely.bedwars.api.event.arena.ArenaStatusChangeEvent;
import de.marcely.bedwars.api.event.arena.RoundEndEvent;
import de.marcely.bedwars.api.event.arena.RoundStartEvent;
import de.marcely.bedwars.api.game.spawner.Spawner;
import de.marcely.bedwars.api.game.spawner.SpawnerDurationModifier;
import de.marcely.bedwars.api.game.upgrade.UpgradeState;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runs each private match's event timeline while its round is live: at round start it
 * pins MBedwars' in-game time limit to the timeline's Match End, then fires the other
 * events as their times come up — speeding up generators via spawner duration modifiers,
 * or destroying every bed for sudden death.
 *
 * Runs on whichever server actually hosts the arena; the timeline itself is mirrored
 * with the session through the shared database, so it doesn't matter where it was edited.
 */
public final class EventTimelineEngine implements Listener {

    private static final String MODIFIER_PREFIX = "exclusivearenas-";

    private final ExclusiveArenasPlugin plugin;
    private final PrivateSessionService sessions;
    private final TimelineService timelines;

    /** Live per-arena run state, keyed by lower-cased arena name. */
    private final Map<String, RunState> running = new ConcurrentHashMap<>();

    private static final class RunState {
        final Arena arena;
        final List<SessionSettings.TimelineEntry> pending; // sorted, Match End excluded
        BukkitTask ticker;

        RunState(Arena arena, List<SessionSettings.TimelineEntry> pending) {
            this.arena = arena;
            this.pending = pending;
        }
    }

    public EventTimelineEngine(ExclusiveArenasPlugin plugin, PrivateSessionService sessions,
                               TimelineService timelines) {
        this.plugin = plugin;
        this.sessions = sessions;
        this.timelines = timelines;
    }

    // ── Round lifecycle ──────────────────────────────────────────────────────────

    @EventHandler
    public void onRoundStart(RoundStartEvent event) {
        if (!timelines.isEnabled()) return;

        Arena arena = event.getArena();
        PrivateSession session = sessions.getByArena(arena);
        if (session == null) return;

        // With MBedwarsTweaks installed, its gen tiers run the schedule (rewritten per match by
        // TweaksTimelineBridge, so the scoreboard shows the custom timings). Only verify that
        // Tweaks actually engaged — if its gen-tiers feature is disabled in ITS config, nothing
        // will ever schedule, so at least pin the custom match-end time and say why.
        if (timelines.isTweaksBackend()) {
            new BukkitRunnable() {
                @Override
                public void run() {
                    if (!arena.exists() || arena.getStatus() != ArenaStatus.RUNNING) return;
                    TweaksTimelineBridge bridge = plugin.getTweaksBridge();
                    if (bridge != null && bridge.isHandling(arena)) return;
                    pinMatchEnd(arena, timelines.effectiveTimeline(session.getSettings()));
                    plugin.getLogger().warning("MBedwarsTweaks is installed but its gen tiers never "
                            + "scheduled for '" + arena.getName() + "' — is gen-tiers enabled in the "
                            + "Tweaks config? Only this match's end time could be applied.");
                }
            }.runTaskLater(plugin, 60L);
            return;
        }

        List<SessionSettings.TimelineEntry> timeline = timelines.effectiveTimeline(session.getSettings());

        List<SessionSettings.TimelineEntry> pending = new ArrayList<>();
        for (SessionSettings.TimelineEntry entry : timeline) {
            TimelineService.Definition def = timelines.definitionFor(entry);
            if (def == null) continue;

            if (def.type() == TimelineService.Type.MATCH_END) {
                pinMatchEnd(arena, List.of(entry));
            } else {
                pending.add(entry);
            }
        }
        if (pending.isEmpty()) return;

        RunState state = new RunState(arena, pending);
        state.ticker = new BukkitRunnable() {
            @Override
            public void run() {
                tick(state);
            }
        }.runTaskTimer(plugin, 20L, 20L);
        running.put(key(arena), state);
    }

    @EventHandler
    public void onRoundEnd(RoundEndEvent event) {
        stop(event.getArena());
    }

    @EventHandler
    public void onStatusChange(ArenaStatusChangeEvent event) {
        if (event.getNewStatus() != ArenaStatus.RUNNING) stop(event.getArena());
    }

    /** Tears down every live run — used on plugin reload/disable. */
    public void shutdown() {
        for (RunState state : running.values()) {
            if (state.ticker != null) state.ticker.cancel();
            removeOurModifiers(state.arena);
        }
        running.clear();
    }

    // ── Manual skip (Quick Actions) ───────────────────────────────────────────────

    /**
     * Fires the next pending event immediately, as if its time had come. Returns its
     * definition, or null when nothing is left to skip to (Match End doesn't count —
     * skipping the whole match away would be an End button with extra steps).
     */
    public TimelineService.Definition skipToNextEvent(Arena arena) {
        if (timelines.isTweaksBackend()) {
            TweaksTimelineBridge bridge = plugin.getTweaksBridge();
            return bridge != null ? bridge.skipToNextEvent(arena) : null;
        }

        RunState state = running.get(key(arena));
        if (state == null || state.pending.isEmpty()) return null;

        SessionSettings.TimelineEntry entry = state.pending.remove(0);
        TimelineService.Definition def = timelines.definitionFor(entry);
        if (def != null) fire(state.arena, def);
        if (state.pending.isEmpty()) stop(arena);
        return def;
    }

    // ── Internals ────────────────────────────────────────────────────────────────

    /** Applies the timeline's Match End as MBedwars' in-game time limit. */
    private void pinMatchEnd(Arena arena, List<SessionSettings.TimelineEntry> timeline) {
        for (SessionSettings.TimelineEntry entry : timeline) {
            TimelineService.Definition def = timelines.definitionFor(entry);
            if (def == null || def.type() != TimelineService.Type.MATCH_END) continue;
            try {
                arena.setIngameTimeRemaining(entry.seconds());
                arena.broadcast(Lang.msg("timeline.end-applied",
                        "%time%", TimelineService.format(entry.seconds())));
            } catch (Throwable t) {
                plugin.getLogger().warning("Could not set the match time limit on "
                        + arena.getName() + ": " + t.getMessage());
            }
            return;
        }
    }

    private void tick(RunState state) {
        Arena arena = state.arena;
        if (!arena.exists() || arena.getStatus() != ArenaStatus.RUNNING) {
            stop(arena);
            return;
        }

        long elapsed = arena.getRunningTime().getSeconds();
        while (!state.pending.isEmpty() && state.pending.get(0).seconds() <= elapsed) {
            SessionSettings.TimelineEntry entry = state.pending.remove(0);
            TimelineService.Definition def = timelines.definitionFor(entry);
            if (def != null) fire(arena, def);
        }
        if (state.pending.isEmpty()) stop(arena);
    }

    private void fire(Arena arena, TimelineService.Definition def) {
        try {
            switch (def.type()) {
                case SPAWNER_SPEED -> applySpawnerSpeed(arena, def);
                case DESTROY_BEDS, SUDDEN_DEATH -> destroyAllBeds(arena);
                case MATCH_END -> { /* handled at round start via the ingame timer */ }
                case RESOURCE_BURST -> applyResourceBurst(arena, def);
                case TEAM_BUFF -> applyTeamBuff(arena, def);
                case TRAP_CHAOS -> applyTrapChaos(arena);
                case WEATHER_CHANGE -> arena.setWeatherType(ExclusiveArenasPlugin.parseWeatherType(def.dropTypeId()));
                case TIME_CHANGE -> arena.setTimeType(ExclusiveArenasPlugin.parseTimeType(def.dropTypeId()));
                case FIREWORKS -> applyFireworks(arena);
                case ANNOUNCEMENT -> {
                    // Its own message IS the broadcast — skip the generic "event fired" line below,
                    // which would otherwise show right next to (and be redundant with) it.
                    String message = def.dropTypeId() == null || def.dropTypeId().isBlank()
                            ? def.description() : def.dropTypeId();
                    if (message != null && !message.isBlank()) arena.broadcast(ItemUtil.color(message));
                    return;
                }
            }
            arena.broadcast(Lang.msg("timeline.event-fired", "%event%", def.name()));
        } catch (Throwable t) {
            plugin.getLogger().warning("Timeline event '" + def.id() + "' failed on "
                    + arena.getName() + ": " + t.getMessage());
        }
    }

    private void applyResourceBurst(Arena arena, TimelineService.Definition def) {
        if (def.dropTypeId() == null) return;
        for (Spawner spawner : arena.getSpawners()) {
            if (!spawner.getDropType().getId().equalsIgnoreCase(def.dropTypeId())) continue;
            try {
                spawner.drop();
            } catch (Throwable ignored) {
                // best effort per spawner
            }
        }
    }

    /** Value format: "POTION_TYPE:amplifier:seconds" (amplifier/seconds default to 0/30). */
    private void applyTeamBuff(Arena arena, TimelineService.Definition def) {
        if (def.dropTypeId() == null) return;
        String[] parts = def.dropTypeId().split(":");
        PotionEffectType type = PotionEffectType.getByName(parts[0].toUpperCase(Locale.ROOT));
        if (type == null) return;
        int amplifier = parts.length > 1 ? parseIntOr(parts[1], 0) : 0;
        int seconds = parts.length > 2 ? parseIntOr(parts[2], 30) : 30;
        for (Player p : arena.getPlayers()) {
            p.addPotionEffect(new PotionEffect(type, Math.max(1, seconds) * 20, amplifier, false, true));
        }
    }

    private static int parseIntOr(String raw, int fallback) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * Force-triggers a random team's queued trap, if any team has one queued. The exact
     * semantics of {@code UpgradeState#triggerTrap}'s player/boolean arguments aren't
     * documented — this is a best-effort "chaos" event, wrapped in the caller's try/catch, so
     * an imperfect interpretation degrades to a no-op rather than breaking anything.
     */
    private void applyTrapChaos(Arena arena) {
        List<Team> candidates = new ArrayList<>();
        for (Team team : arena.getAliveTeams()) {
            UpgradeState state = arena.getUpgradeState(team);
            if (state != null && !state.getQueuedTraps().isEmpty()) candidates.add(team);
        }
        if (candidates.isEmpty()) return;

        Random random = new Random();
        Team target = candidates.get(random.nextInt(candidates.size()));
        List<Player> players = new ArrayList<>(arena.getPlayers());
        Player triggerer = players.isEmpty() ? null : players.get(random.nextInt(players.size()));
        arena.getUpgradeState(target).triggerTrap(triggerer, target, true);
    }

    private void applyFireworks(Arena arena) {
        World world = arena.getGameWorld();
        if (world == null) return;
        for (Player p : arena.getPlayers()) {
            Location loc = p.getLocation().add(0, 2, 0);
            Firework firework = world.spawn(loc, Firework.class);
            FireworkMeta meta = firework.getFireworkMeta();
            meta.addEffect(FireworkEffect.builder()
                    .withColor(org.bukkit.Color.YELLOW, org.bukkit.Color.AQUA, org.bukkit.Color.WHITE)
                    .with(FireworkEffect.Type.BALL_LARGE)
                    .trail(true).flicker(true).build());
            meta.setPower(1);
            firework.setFireworkMeta(meta);
        }
    }

    private void applySpawnerSpeed(Arena arena, TimelineService.Definition def) {
        if (def.dropTypeId() == null) return;
        boolean any = false;
        for (Spawner spawner : arena.getSpawners()) {
            if (!spawner.getDropType().getId().equalsIgnoreCase(def.dropTypeId())) continue;
            any = true;

            String id = MODIFIER_PREFIX + def.id();
            SpawnerDurationModifier existing = spawner.getDropDurationModifier(id);
            if (existing != null) {
                existing.setValue(def.multiplier());
            } else {
                spawner.addDropDurationModifier(id, plugin,
                        SpawnerDurationModifier.Operation.MULTIPLY, def.multiplier());
            }
        }
        if (!any) {
            plugin.debug("Timeline event '" + def.id() + "': no spawners of drop type '"
                    + def.dropTypeId() + "' in " + arena.getName() + " — nothing to speed up.");
        }
    }

    /** Destroys every remaining bed in the arena (also used by the Sudden Death quick action). */
    public int destroyAllBeds(Arena arena) {
        int destroyed = 0;
        for (Team team : new ArrayList<>(arena.getAliveTeams())) {
            try {
                if (arena.getBedDestructionTime(team) != null) continue; // already gone
                arena.destroyBedNaturally(team, null);
                destroyed++;
            } catch (Throwable t) {
                plugin.getLogger().warning("Could not destroy " + team.name() + "'s bed in "
                        + arena.getName() + ": " + t.getMessage());
            }
        }
        return destroyed;
    }

    private void stop(Arena arena) {
        RunState state = running.remove(key(arena));
        if (state == null) return;
        if (state.ticker != null) state.ticker.cancel();
        // Spawner objects outlive the round — without this, a speed-up applied for the
        // private match would leak into the arena's NEXT (possibly public) round.
        removeOurModifiers(state.arena);
    }

    private void removeOurModifiers(Arena arena) {
        try {
            if (!arena.exists()) return;
            for (Spawner spawner : arena.getSpawners()) {
                for (SpawnerDurationModifier mod : new ArrayList<>(spawner.getDropDurationModifiers())) {
                    if (mod.getId().startsWith(MODIFIER_PREFIX)) {
                        spawner.removeDropDurationModifier(mod);
                    }
                }
            }
        } catch (Throwable ignored) {
            // best effort — arena may be mid-teardown
        }
    }

    private static String key(Arena arena) {
        return arena.getName().toLowerCase(Locale.ROOT);
    }
}
