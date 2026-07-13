package com.slg.exclusivearenas;

import de.marcely.bedwars.api.BedwarsAPI;
import de.marcely.bedwars.api.arena.Arena;
import de.marcely.bedwars.api.arena.ArenaStatus;
import de.marcely.bedwars.api.event.arena.ArenaIssuesCheckEvent;
import de.marcely.bedwars.api.game.spawner.Spawner;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Periodic sweep (every {@code stability.health_check_seconds}) that checks this plugin's
 * session bookkeeping against MBedwars' actual arena state via the live API, rather than
 * trusting it stays in sync on its own, and self-heals the drift it finds:
 *
 * <ul>
 *   <li>A session whose arena has quietly gone {@code STOPPED} (crashed reset, manual admin
 *       action, anything not routed through this plugin) is ended instead of lingering.</li>
 *   <li>A match stuck {@code RUNNING} far longer than the timeline's own match-length cap is
 *       logged loudly, and — only if {@code stability.force_end_stuck_matches} is explicitly
 *       enabled — force-ended.</li>
 *   <li>A spawner whose {@code getRemainingNextDropTime()} stops decreasing is flagged as
 *       possibly desynced. Warn-only: auto-"fixing" a spawner is riskier than reporting it.</li>
 *   <li>MBedwars' own {@code getIssues()} (missing bed/spawn/lobby/etc.) is surfaced as a log
 *       warning so a misconfigured arena doesn't just silently misbehave.</li>
 * </ul>
 *
 * Every corrective action is logged clearly so admins can audit what the plugin corrected
 * on its own — this task never messages players, only the console.
 */
public final class ArenaHealthMonitorTask extends BukkitRunnable {

    /** Consecutive unchanged readings before a spawner is flagged — avoids one-tick false alarms. */
    private static final int SPAWNER_STALL_THRESHOLD = 3;

    private final ExclusiveArenasPlugin plugin;
    private final PrivateSessionService sessions;

    // Per-spawner-slot tracking (key: "<arena>#<index in getSpawners()>"), so a spawner that
    // stops ticking is caught without any state coming from MBedwars itself.
    private final Map<String, Integer> lastSpawnerRemaining = new HashMap<>();
    private final Map<String, Integer> spawnerStallCounts = new HashMap<>();
    private final Set<String> spawnerWarned = new HashSet<>();

    // Arenas already warned about their current set of MBedwars issues — cleared once the
    // arena reports no issues, so a recurring problem warns again rather than going silent.
    private final Set<String> issuesWarned = new HashSet<>();

    public ArenaHealthMonitorTask(ExclusiveArenasPlugin plugin, PrivateSessionService sessions) {
        this.plugin = plugin;
        this.sessions = sessions;
    }

    @Override
    public void run() {
        boolean forceEndStuck = plugin.getEaConfig().bool("stability.force_end_stuck_matches", false);
        long graceSeconds = plugin.getEaConfig().intNum("stability.stuck_match_grace_seconds", 300);
        long maxMatchSeconds = Math.max(60,
                TimelineService.parseTime(plugin.getEaConfig().str("timeline.max_match_time", "60:00")));

        for (PrivateSession session : sessions.getAllSessions()) {
            Arena arena = BedwarsAPI.getGameAPI().getArenaByExactName(session.getArenaName());
            if (arena == null || !arena.exists()) continue; // hosted on another server — not ours to check

            if (arena.getStatus() == ArenaStatus.STOPPED) {
                plugin.getLogger().warning("[Health] Session for '" + session.getArenaName()
                        + "' is still active but the arena is STOPPED — ending the session.");
                plugin.endMatch(session);
                continue;
            }

            if (arena.getStatus() == ArenaStatus.RUNNING) {
                checkStuckMatch(session, arena, maxMatchSeconds, graceSeconds, forceEndStuck);
                checkSpawnerDesync(arena);
            }

            checkArenaIssues(arena);
        }
    }

    private void checkStuckMatch(PrivateSession session, Arena arena, long maxMatchSeconds,
                                 long graceSeconds, boolean forceEnd) {
        long elapsed = arena.getRunningTime().getSeconds();
        if (elapsed <= maxMatchSeconds + graceSeconds) return;

        if (forceEnd) {
            plugin.getLogger().warning("[Health] '" + arena.getName() + "' has been RUNNING for "
                    + elapsed + "s (cap " + maxMatchSeconds + "s + " + graceSeconds
                    + "s grace) — force-ending it (stability.force_end_stuck_matches is enabled).");
            plugin.endMatch(session);
        } else {
            plugin.getLogger().warning("[Health] '" + arena.getName() + "' has been RUNNING for "
                    + elapsed + "s, well past its " + maxMatchSeconds + "s timeline cap — this match "
                    + "may be stuck. Set stability.force_end_stuck_matches: true to auto-end matches "
                    + "like this instead of just logging them.");
        }
    }

    private void checkSpawnerDesync(Arena arena) {
        String arenaKey = arena.getName().toLowerCase(Locale.ROOT);
        int i = 0;
        for (Spawner spawner : arena.getSpawners()) {
            String key = arenaKey + "#" + i++;
            int remaining;
            try {
                remaining = spawner.getRemainingNextDropTime();
            } catch (Throwable ignored) {
                continue; // best effort
            }

            Integer last = lastSpawnerRemaining.put(key, remaining);
            if (last != null && last == remaining) {
                int stalls = spawnerStallCounts.merge(key, 1, Integer::sum);
                if (stalls == SPAWNER_STALL_THRESHOLD && spawnerWarned.add(key)) {
                    plugin.getLogger().warning("[Health] A spawner in '" + arena.getName()
                            + "' hasn't ticked in a while (stuck at " + remaining
                            + "s remaining) — possible desync.");
                }
            } else {
                spawnerStallCounts.remove(key);
                spawnerWarned.remove(key);
            }
        }
    }

    private void checkArenaIssues(Arena arena) {
        String key = arena.getName().toLowerCase(Locale.ROOT);
        Set<ArenaIssuesCheckEvent.Issue> issues;
        try {
            issues = arena.getIssues();
        } catch (Throwable ignored) {
            return; // best effort
        }
        if (issues == null || issues.isEmpty()) {
            issuesWarned.remove(key);
            return;
        }
        if (!issuesWarned.add(key)) return; // already warned about this arena's current issues

        for (ArenaIssuesCheckEvent.Issue issue : issues) {
            plugin.getLogger().warning("[Health] '" + arena.getName() + "' has a configuration issue ("
                    + issue.getType() + "): " + issue.getMessage());
        }
    }
}
