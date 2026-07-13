package com.slg.exclusivearenas;

import de.marcely.bedwars.api.arena.Arena;
import de.marcely.bedwars.api.arena.ArenaStatus;
import de.marcely.bedwars.api.event.arena.ArenaDeleteEvent;
import de.marcely.bedwars.api.event.arena.ArenaStatusChangeEvent;
import de.marcely.bedwars.api.event.arena.RoundEndEvent;
import me.metallicgoat.tweaksaddon.api.GenTiersAPI;
import me.metallicgoat.tweaksaddon.api.events.gentiers.GenTiersScheduleEvent;
import me.metallicgoat.tweaksaddon.api.gentiers.GenTierActionType;
import me.metallicgoat.tweaksaddon.api.gentiers.GenTierLevel;
import me.metallicgoat.tweaksaddon.api.gentiers.GenTierState;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Integration with MBedwarsTweaks' gen-tiers. When Tweaks is installed, IT runs the in-match
 * event schedule (Diamond II, bed destruction, sudden death, game over) and its scoreboard
 * placeholders show the next event — so instead of running our own parallel schedule (whose
 * changes the scoreboard can't see), this bridge:
 *
 *   1. Builds the plugin's DEFAULT timeline from the Tweaks gen-tier config, so the editor
 *      shows exactly the events the server really runs, and
 *   2. Intercepts {@link GenTiersScheduleEvent} for private matches and rewrites which tier
 *      comes next and when, following the host's customized timeline. Tweaks then fires the
 *      tier with its own handlers — and because its own state carries the adjusted time,
 *      the scoreboard's next-event timer displays the custom timing correctly.
 *
 * This class is only ever class-loaded when the MBedwarsTweaks plugin is present (see
 * {@link ExclusiveArenasPlugin}); nothing else in the plugin imports the Tweaks API.
 */
public final class TweaksTimelineBridge implements Listener {

    /** Timeline definition ids for Tweaks tiers are "tier-&lt;n&gt;". */
    private static final String TIER_ID_PREFIX = "tier-";

    private final ExclusiveArenasPlugin plugin;
    private final PrivateSessionService sessions;
    private final TimelineService timelines;

    /**
     * Per-arena remaining custom timeline and Tweaks-transition tracking, keyed by lower-cased
     * arena name. Built on the round's first schedule event, cleared when the round ends.
     */
    private final Map<String, ArenaQueueState> queues = new ConcurrentHashMap<>();

    /**
     * {@code GenTiersScheduleEvent} is assumed to fire once per real tier transition, but Tweaks
     * gives no hard guarantee it won't also fire again for the SAME upcoming tier (e.g. it
     * re-evaluates scheduling for some unrelated reason). Blindly popping the queue on every call
     * would then silently skip an entry and desync our schedule from what Tweaks actually runs —
     * which would explain custom timings drifting out of sync with the scoreboard over a match.
     * Instead we only advance the queue when {@link GenTierState#getCurrentTier()} has actually
     * changed since the last call, and otherwise re-apply the same entry with a freshly
     * recomputed delay — which also self-corrects any drift between our elapsed-time schedule
     * and Tweaks' wall-clock {@code nextTierTime} (e.g. after the arena's timer was paused).
     */
    private static final class ArenaQueueState {
        final ArrayDeque<SessionSettings.TimelineEntry> queue;
        GenTierLevel lastCurrentTier;
        SessionSettings.TimelineEntry lastScheduled;

        ArenaQueueState(ArrayDeque<SessionSettings.TimelineEntry> queue) {
            this.queue = queue;
        }
    }

    private TweaksTimelineBridge(ExclusiveArenasPlugin plugin, PrivateSessionService sessions,
                                 TimelineService timelines) {
        this.plugin = plugin;
        this.sessions = sessions;
        this.timelines = timelines;
    }

    /**
     * Creates the bridge and seeds the timeline defaults from the Tweaks gen-tier config.
     * Returns null when the Tweaks API is unusable or has no tiers configured — the caller
     * then keeps the internal timeline engine exactly as if Tweaks weren't installed.
     */
    static TweaksTimelineBridge tryCreate(ExclusiveArenasPlugin plugin, PrivateSessionService sessions,
                                          TimelineService timelines) {
        try {
            TweaksTimelineBridge bridge = new TweaksTimelineBridge(plugin, sessions, timelines);
            if (!bridge.rebuildDefaults()) return null;
            return bridge;
        } catch (Throwable t) {
            plugin.getLogger().warning("MBedwarsTweaks is present but its gen-tiers API could not be "
                    + "used (" + t.getMessage() + ") — falling back to the internal timeline engine.");
            return null;
        }
    }

    /**
     * (Re)builds the default timeline definitions from the Tweaks gen-tier chain. Tier times in
     * Tweaks are relative to the previous tier; the editor works in absolute seconds, so they
     * are accumulated here. Returns false when no tiers are configured.
     */
    boolean rebuildDefaults() {
        GenTierLevel level = GenTiersAPI.getFirstTier();
        if (level == null) return false;

        Map<String, TimelineService.Definition> defs = new LinkedHashMap<>();
        Map<String, Integer> upgradesSeen = new HashMap<>();
        String endId = null;
        long cumulative = 0;

        for (int guard = 0; level != null && guard < 100; level = level.getNextLevel(), guard++) {
            Duration time = level.getTime();
            cumulative += time == null ? 0 : time.getSeconds();

            String id = TIER_ID_PREFIX + level.getTier();
            GenTierActionType action = level.getHandler() != null
                    ? level.getHandler().getActionType() : GenTierActionType.PLUGIN;

            TimelineService.Type type;
            Material icon;
            String description;
            String dropTypeId = null;
            switch (action) {
                case GEN_UPGRADE -> {
                    type = TimelineService.Type.SPAWNER_SPEED;
                    dropTypeId = level.getTypeId();
                    icon = upgradeIcon(dropTypeId, upgradesSeen);
                    description = Lang.raw("timeline.tweaks-gen-upgrade",
                            "%type%", dropTypeId == null ? "?" : dropTypeId);
                }
                case BED_DESTROY -> {
                    type = TimelineService.Type.DESTROY_BEDS;
                    icon = Material.RED_BED;
                    description = Lang.raw("timeline.tweaks-bed-destroy");
                }
                case SUDDEN_DEATH -> {
                    // Distinct from DESTROY_BEDS: Tweaks' dragons threaten/kill players but never
                    // touch MBedwars' own bed-tracking, so this type gets a forced bed-break of
                    // its own in onGenTierSchedule below when a host's custom timeline needs it.
                    type = TimelineService.Type.SUDDEN_DEATH;
                    icon = Material.DRAGON_HEAD;
                    description = Lang.raw("timeline.tweaks-sudden-death");
                }
                case GAME_OVER -> {
                    type = TimelineService.Type.MATCH_END;
                    icon = Material.CLOCK;
                    description = Lang.raw("timeline.tweaks-game-over");
                    endId = id;
                }
                default -> {
                    type = TimelineService.Type.SPAWNER_SPEED; // effect belongs to Tweaks; type is cosmetic here
                    icon = Material.COMMAND_BLOCK;
                    description = Lang.raw("timeline.tweaks-plugin");
                }
            }

            defs.put(id, new TimelineService.Definition(id, level.getTierName(), icon,
                    (int) Math.min(cumulative, Integer.MAX_VALUE), type, dropTypeId, 1.0, description, true));
        }

        // A Tweaks config without a game-over tier still needs a movable, non-deletable Match End.
        if (endId == null) {
            endId = TimelineService.MATCH_END_ID_FALLBACK;
            defs.put(endId, new TimelineService.Definition(endId, "Match End", Material.CLOCK,
                    (int) Math.max(30 * 60, cumulative + 300), TimelineService.Type.MATCH_END,
                    null, 1.0, Lang.raw("timeline.tweaks-game-over"), true));
        }

        timelines.setTweaksDefaults(defs, endId);
        return true;
    }

    /** True once this round's Tweaks scheduling has passed through the bridge for the arena. */
    public boolean isHandling(Arena arena) {
        return arena != null && queues.containsKey(key(arena));
    }

    /**
     * Fires the next scheduled tier right away (the Quick Actions skip). Returns the fired
     * event's definition, or null when there is nothing skippable — no next tier, or only
     * Game Over remains (skipping the whole match away is the End button's job).
     */
    public TimelineService.Definition skipToNextEvent(Arena arena) {
        GenTierState state = GenTiersAPI.getState(arena);
        if (state == null || !state.isValid()) return null;

        GenTierLevel next = state.getNextTier();
        if (next == null) return null;
        if (next.getHandler() != null && next.getHandler().getActionType() == GenTierActionType.GAME_OVER) {
            return null;
        }

        state.setRemainingNextTier(Duration.ZERO); // Tweaks' own ticker fires it within ~2s

        TimelineService.Definition def = timelines.definition(TIER_ID_PREFIX + next.getTier());
        if (def != null) return def;
        return new TimelineService.Definition(TIER_ID_PREFIX + next.getTier(), next.getTierName(),
                Material.CLOCK, 0, TimelineService.Type.SPAWNER_SPEED, null, 1.0, "", true);
    }

    // ── The actual interception ───────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onGenTierSchedule(GenTiersScheduleEvent event) {
        Arena arena = event.getArena();
        if (!timelines.isEnabled()) return;
        PrivateSession session = sessions.getByArena(arena);
        if (session == null) return; // public match — Tweaks runs untouched

        ArenaQueueState state = queues.computeIfAbsent(key(arena),
                k -> new ArenaQueueState(new ArrayDeque<>(timelines.effectiveTimeline(session.getSettings()))));

        // Only consume the next queued entry once Tweaks' own state shows a tier actually
        // completed since we last looked — a repeat call for the same upcoming tier (whatever
        // the reason) just re-applies what we already scheduled instead of skipping an entry.
        GenTierLevel nativeCurrent = event.getArenaState().getCurrentTier();
        boolean advanced = state.lastScheduled == null || !Objects.equals(nativeCurrent, state.lastCurrentTier);
        state.lastCurrentTier = nativeCurrent;

        if (advanced) {
            SessionSettings.TimelineEntry next = null;
            while (!state.queue.isEmpty()) {
                SessionSettings.TimelineEntry candidate = state.queue.pollFirst();
                TimelineService.Definition candidateDef = timelines.definitionFor(candidate);
                if (candidateDef == null) continue; // stale id (config changed since the edit)
                if (!isTweaksRepresentable(candidateDef.type())) {
                    // Weather/buff/announcement/etc. events have no equivalent Tweaks gen-tier to
                    // substitute in — Tweaks' scheduling model only understands "which tier comes
                    // next", so there's nothing to hand back for these. Skip rather than crash or
                    // cancel the whole schedule; timeline.backend: internal supports every type.
                    plugin.debug("Timeline event '" + candidate.id() + "' (" + candidateDef.type()
                            + ") has no MBedwarsTweaks equivalent — skipped on this backend.");
                    continue;
                }
                next = candidate;
                break;
            }
            state.lastScheduled = next;
        }

        SessionSettings.TimelineEntry entry = state.lastScheduled;
        if (entry == null) {
            // Nothing left in the custom timeline: stop Tweaks' chain cleanly so the
            // scoreboard doesn't keep advertising a tier that will never come.
            event.setCancelled(true);
            try {
                event.getArenaState().cancelTiers();
            } catch (Throwable ignored) {
                // display cleanup only
            }
            return;
        }

        TimelineService.Definition def = timelines.definitionFor(entry);
        if (def == null) {
            // The definition vanished (e.g. a config reload) after this entry was queued —
            // nothing valid left to hand back for it.
            event.setCancelled(true);
            try {
                event.getArenaState().cancelTiers();
            } catch (Throwable ignored) {
                // display cleanup only
            }
            return;
        }
        long elapsed = Math.max(0, arena.getRunningTime().getSeconds());
        long delay = Math.max(0, entry.seconds() - elapsed);

        if (def.type() == TimelineService.Type.MATCH_END) {
            GenTierLevel over = gameOverLevel();
            if (over != null) {
                event.setNextTier(over);
                event.setDelay(Duration.ofSeconds(Math.max(1, delay)));
            } else {
                // No game-over tier in Tweaks: pin MBedwars' own time limit instead.
                event.setCancelled(true);
                try {
                    arena.setIngameTimeRemaining((int) Math.max(1, delay));
                    event.getArenaState().cancelTiers();
                } catch (Throwable t) {
                    plugin.getLogger().warning("Could not set the match time limit on "
                            + arena.getName() + ": " + t.getMessage());
                }
            }
            return;
        }

        GenTierLevel level = levelFor(entry.id());
        if (level == null) {
            // Tier vanished from the Tweaks config mid-round — nothing valid left to hand back.
            event.setCancelled(true);
            try {
                event.getArenaState().cancelTiers();
            } catch (Throwable ignored) {
                // display cleanup only
            }
            return;
        }

        if (def.type() == TimelineService.Type.SUDDEN_DEATH && session.getSettings().getTimeline() != null) {
            // Tweaks' Sudden Death dragons threaten/kill players but never register a bed as
            // destroyed with MBedwars — normally harmless because the real Bed Destruction tier
            // already ran first, but a host-customized timeline can reorder or drop that tier.
            // Force it here, timed to land the moment Sudden Death itself actually fires.
            Bukkit.getScheduler().runTaskLater(plugin,
                    () -> { if (arena.exists()) plugin.getTimelineEngine().destroyAllBeds(arena); },
                    Math.max(1, delay) * 20L);
        }

        event.setNextTier(level);
        event.setDelay(Duration.ofSeconds(delay));
    }

    // ── Round lifecycle cleanup ───────────────────────────────────────────────────

    @EventHandler
    public void onRoundEnd(RoundEndEvent event) {
        queues.remove(key(event.getArena()));
    }

    @EventHandler
    public void onStatusChange(ArenaStatusChangeEvent event) {
        if (event.getNewStatus() != ArenaStatus.RUNNING) queues.remove(key(event.getArena()));
    }

    @EventHandler
    public void onArenaDelete(ArenaDeleteEvent event) {
        queues.remove(key(event.getArena()));
    }

    /** Clears all per-round state — used on plugin reload/disable. */
    public void shutdown() {
        queues.clear();
    }

    // ── Internals ────────────────────────────────────────────────────────────────

    /** The only event types Tweaks' tier-substitution scheduling model can represent. */
    private static boolean isTweaksRepresentable(TimelineService.Type type) {
        return switch (type) {
            case SPAWNER_SPEED, DESTROY_BEDS, SUDDEN_DEATH, MATCH_END -> true;
            default -> false;
        };
    }

    private static GenTierLevel levelFor(String defId) {
        if (defId == null || !defId.startsWith(TIER_ID_PREFIX)) return null;
        try {
            return GenTiersAPI.getTier(Integer.parseInt(defId.substring(TIER_ID_PREFIX.length())));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static GenTierLevel gameOverLevel() {
        for (GenTierLevel level : GenTiersAPI.getTiers()) {
            if (level.getHandler() != null
                    && level.getHandler().getActionType() == GenTierActionType.GAME_OVER) {
                return level;
            }
        }
        return null;
    }

    /** First upgrade of a resource gets the ingot/gem icon, later ones the block form. */
    private static Material upgradeIcon(String dropTypeId, Map<String, Integer> seen) {
        String type = dropTypeId == null ? "" : dropTypeId.toLowerCase(Locale.ROOT);
        int nth = seen.merge(type, 1, Integer::sum);
        if (type.contains("diamond")) return nth == 1 ? Material.DIAMOND : Material.DIAMOND_BLOCK;
        if (type.contains("emerald")) return nth == 1 ? Material.EMERALD : Material.EMERALD_BLOCK;
        if (type.contains("gold")) return nth == 1 ? Material.GOLD_INGOT : Material.GOLD_BLOCK;
        if (type.contains("iron")) return nth == 1 ? Material.IRON_INGOT : Material.IRON_BLOCK;
        return Material.BEACON;
    }

    private static String key(Arena arena) {
        return arena.getName().toLowerCase(Locale.ROOT);
    }
}
