package com.slg.exclusivearenas;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/**
 * The event-timeline rulebook: parses the event definitions from config.yml, resolves a
 * session's effective timeline (its customized entries, or the defaults), and implements
 * the editing operations the timeline GUI exposes — move, delete, and the proportional
 * rescale that keeps every event inside the match when the host shortens Match End.
 *
 * Times are whole seconds after round start. {@link EventTimelineEngine} consumes the
 * resolved timeline when a round actually begins.
 */
public final class TimelineService {

    public static final String MATCH_END_ID_FALLBACK = "match_end";
    /** Events may never be moved earlier than this — 0:00 events misfire on some setups. */
    private static final int MIN_EVENT_SECONDS = 5;
    /** Editing granularity; rescaled times snap to this. */
    private static final int SNAP_SECONDS = 5;

    public enum Type { SPAWNER_SPEED, DESTROY_BEDS, SUDDEN_DEATH, MATCH_END }

    /** A configured event definition (the "what"); sessions store only id + time. */
    public record Definition(String id, String name, Material icon, int defaultSeconds,
                             Type type, String dropTypeId, double multiplier, String description) {}

    private final Logger logger;
    private Map<String, Definition> definitions = new LinkedHashMap<>();
    private String matchEndId = MATCH_END_ID_FALLBACK;
    private boolean enabled = true;
    /** True when the definitions come from MBedwarsTweaks' gen tiers (see TweaksTimelineBridge). */
    private boolean tweaksBackend = false;
    /** Match End can never be edited past this many seconds — timeline.max_match_time. */
    private int maxMatchSeconds = 60 * 60;

    public TimelineService(Logger logger) {
        this.logger = logger;
    }

    // ── Config loading ───────────────────────────────────────────────────────────

    public void load(EaConfig config) {
        Map<String, Definition> defs = new LinkedHashMap<>();
        this.enabled = config.bool("timeline.enabled", true);
        this.maxMatchSeconds = Math.max(60, parseTime(config.str("timeline.max_match_time", "60:00")));

        ConfigurationSection events = config.section("timeline.events");
        if (events != null) {
            for (String id : events.getKeys(false)) {
                ConfigurationSection e = events.getConfigurationSection(id);
                if (e == null) continue;

                Type type;
                try {
                    type = Type.valueOf(e.getString("type", "spawner_speed").toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException ex) {
                    logger.warning("timeline.events." + id + ": unknown type '" + e.getString("type") + "' — skipped.");
                    continue;
                }
                Material icon = Material.matchMaterial(e.getString("icon", "CLOCK"));
                if (icon == null) icon = Material.CLOCK;

                defs.put(id, new Definition(
                        id,
                        e.getString("name", id),
                        icon,
                        parseTime(e.getString("time", "10:00")),
                        type,
                        e.getString("drop_type", null),
                        e.getDouble("multiplier", 1.0),
                        e.getString("description", "")));
            }
        }

        String endId = defs.values().stream()
                .filter(d -> d.type() == Type.MATCH_END)
                .map(Definition::id).findFirst().orElse(null);
        if (endId == null) {
            logger.warning("timeline.events has no match_end-type event; adding a 30:00 default.");
            endId = MATCH_END_ID_FALLBACK;
            defs.put(endId, new Definition(endId, "Match End", Material.CLOCK, 30 * 60,
                    Type.MATCH_END, null, 1.0, "The match ends."));
        }
        this.matchEndId = endId;
        this.definitions = defs;
        this.tweaksBackend = false; // re-applied by the bridge after each (re)load
    }

    /**
     * Replaces the default timeline with definitions built from MBedwarsTweaks' gen tiers —
     * the editor then edits the schedule the server actually runs, and the runtime side is
     * handled by {@link TweaksTimelineBridge} instead of {@link EventTimelineEngine}.
     */
    public void setTweaksDefaults(Map<String, Definition> defs, String endId) {
        if (defs == null || defs.isEmpty() || endId == null) return;
        this.definitions = defs;
        this.matchEndId = endId;
        this.tweaksBackend = true;
    }

    /** True when MBedwarsTweaks' gen tiers drive the timeline (scoreboard-visible). */
    public boolean isTweaksBackend() {
        return tweaksBackend;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Definition definition(String id) {
        return definitions.get(id);
    }

    /** All configured event ids — used by command tab-completion. */
    public java.util.Set<String> definitionIds() {
        return java.util.Collections.unmodifiableSet(definitions.keySet());
    }

    public String matchEndId() {
        return matchEndId;
    }

    // ── Effective timeline of a session ─────────────────────────────────────────────

    /**
     * The session's timeline sorted by time, Match End guaranteed present and last.
     * Custom entries whose definition no longer exists in config are dropped.
     */
    public List<SessionSettings.TimelineEntry> effectiveTimeline(SessionSettings settings) {
        List<SessionSettings.TimelineEntry> custom = settings.getTimeline();

        List<SessionSettings.TimelineEntry> out = new ArrayList<>();
        if (custom == null) {
            for (Definition d : definitions.values()) {
                out.add(new SessionSettings.TimelineEntry(d.id(), d.defaultSeconds()));
            }
        } else {
            boolean hasEnd = false;
            for (SessionSettings.TimelineEntry e : custom) {
                if (definitions.containsKey(e.id())) {
                    out.add(e);
                    hasEnd |= e.id().equals(matchEndId);
                }
            }
            if (!hasEnd) {
                out.add(new SessionSettings.TimelineEntry(matchEndId,
                        definitions.get(matchEndId).defaultSeconds()));
            }
        }
        sortWithEndLast(out);
        return out;
    }

    // ── Editing operations (used by the timeline GUI) ─────────────────────────────

    /**
     * Moves an event by {@code deltaSeconds}. Regular events are clamped between
     * {@value #MIN_EVENT_SECONDS}s and just before Match End. Moving Match End itself
     * rescales every other event proportionally (shrinking a 30:00 match to 15:00
     * halves each event time), which is also how events stay meaningful in short matches.
     *
     * @return the event's new time in seconds, or -1 if the event wasn't found.
     */
    public int moveEvent(SessionSettings settings, String eventId, int deltaSeconds) {
        List<SessionSettings.TimelineEntry> timeline = effectiveTimeline(settings);
        int idx = indexOf(timeline, eventId);
        if (idx < 0) return -1;

        int endTime = timeOf(timeline, matchEndId);
        int oldTime = timeline.get(idx).seconds();

        if (eventId.equals(matchEndId)) {
            int latestOther = timeline.stream()
                    .filter(e -> !e.id().equals(matchEndId))
                    .mapToInt(SessionSettings.TimelineEntry::seconds).max().orElse(0);
            // The match must stay at least a minute long, and never grows past the
            // configured cap (timeline.max_match_time — 1 hour by default).
            int newEnd = clamp(oldTime + deltaSeconds, Math.max(60, MIN_EVENT_SECONDS + SNAP_SECONDS), maxMatchSeconds);
            if (newEnd == oldTime) return oldTime;

            // oldTime is normally >= 60 (every prior edit clamps it there), but a hand-edited
            // config.yml could still declare match_end's default time as 0 — guard the ratio so
            // that reaches a sane clamp below instead of a NaN/Infinity from dividing by zero.
            double ratio = oldTime > 0 ? (double) newEnd / oldTime : 1.0;

            List<SessionSettings.TimelineEntry> rescaled = new ArrayList<>();
            for (SessionSettings.TimelineEntry e : timeline) {
                if (e.id().equals(matchEndId)) {
                    rescaled.add(new SessionSettings.TimelineEntry(e.id(), newEnd));
                } else if (newEnd < oldTime || latestOther >= newEnd) {
                    // Shrinking (or an event would fall outside): rescale proportionally.
                    int t = snap((int) Math.round(e.seconds() * ratio));
                    rescaled.add(new SessionSettings.TimelineEntry(e.id(),
                            clamp(t, MIN_EVENT_SECONDS, newEnd - SNAP_SECONDS)));
                } else {
                    rescaled.add(e); // growing with room to spare: leave others untouched
                }
            }
            sortWithEndLast(rescaled);
            settings.setTimeline(rescaled);
            return newEnd;
        }

        int newTime = clamp(oldTime + deltaSeconds, MIN_EVENT_SECONDS, endTime - SNAP_SECONDS);
        timeline.set(idx, new SessionSettings.TimelineEntry(eventId, newTime));
        sortWithEndLast(timeline);
        settings.setTimeline(timeline);
        return newTime;
    }

    /** Deletes an event from the timeline. Match End is refused. */
    public boolean deleteEvent(SessionSettings settings, String eventId) {
        if (eventId.equals(matchEndId)) return false;
        List<SessionSettings.TimelineEntry> timeline = effectiveTimeline(settings);
        if (!timeline.removeIf(e -> e.id().equals(eventId))) return false;
        settings.setTimeline(timeline);
        return true;
    }

    /** Reverts to the configured default timeline. */
    public void resetTimeline(SessionSettings settings) {
        settings.setTimeline(null);
    }

    // ── Formatting / parsing ───────────────────────────────────────────────────────

    /** "6:00"-style rendering of a second count. */
    public static String format(int seconds) {
        return (seconds / 60) + ":" + String.format("%02d", seconds % 60);
    }

    /** Accepts "m:ss" or plain seconds. */
    public static int parseTime(String raw) {
        if (raw == null) return 600;
        raw = raw.trim();
        try {
            int colon = raw.indexOf(':');
            if (colon < 0) return Math.max(0, Integer.parseInt(raw));
            int minutes = Integer.parseInt(raw.substring(0, colon));
            int seconds = Integer.parseInt(raw.substring(colon + 1));
            return Math.max(0, minutes * 60 + seconds);
        } catch (NumberFormatException e) {
            return 600;
        }
    }

    // ── Internals ────────────────────────────────────────────────────────────────

    /** Sorts by time; on a tie, Match End sorts last so it's always the final item. */
    private void sortWithEndLast(List<SessionSettings.TimelineEntry> list) {
        list.sort(Comparator
                .comparingInt(SessionSettings.TimelineEntry::seconds)
                .thenComparingInt(e -> e.id().equals(matchEndId) ? 1 : 0));
    }

    private static int indexOf(List<SessionSettings.TimelineEntry> list, String id) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id().equals(id)) return i;
        }
        return -1;
    }

    private static int timeOf(List<SessionSettings.TimelineEntry> list, String id) {
        int idx = indexOf(list, id);
        return idx < 0 ? Integer.MAX_VALUE : list.get(idx).seconds();
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static int snap(int seconds) {
        return Math.max(0, Math.round(seconds / (float) SNAP_SECONDS) * SNAP_SECONDS);
    }
}
