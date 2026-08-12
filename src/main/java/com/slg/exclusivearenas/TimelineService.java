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

    public enum Type {
        SPAWNER_SPEED, DESTROY_BEDS, SUDDEN_DEATH, MATCH_END,
        /** One-time immediate extra drop from every spawner of a drop type — value = drop type id. */
        RESOURCE_BURST,
        /** Timed potion effect for every player currently in the arena — value = "TYPE:AMPLIFIER:SECONDS". */
        TEAM_BUFF,
        /** Force-triggers a random team's queued trap, if any are queued. No value. */
        TRAP_CHAOS,
        /** Scripted weather change — value = an {@code ArenaWeatherType} name. */
        WEATHER_CHANGE,
        /** Scripted time-of-day change — value = an {@code ArenaTimeType} name. */
        TIME_CHANGE,
        /** Pure broadcast, no gameplay effect — value = the message. */
        ANNOUNCEMENT,
        /** Cosmetic firework show over the arena. No value. */
        FIREWORKS,
        /** Fully heals/feeds every player currently in the arena. No value. */
        HEAL_ALL,
        /** Removes every dropped item lying in the arena. No value. */
        CLEAR_ITEMS,
        /** Re-shuffles every current player evenly across the enabled teams. No value. */
        BALANCE_TEAMS,
        /** Clears every team's queued (not yet triggered) traps. No value. */
        CLEAR_TRAPS,
        /** Resets every team's generator/shop upgrades to nothing purchased. No value. */
        RESET_UPGRADES
    }

    /** Types that don't need a {@code value} — {@code /ea timeline custom <type> <time>} skips it, and the
     *  GUI's "Add Event" list is the primary way hosts reach these (as pre-configured catalog entries). */
    public static boolean requiresValue(Type type) {
        return switch (type) {
            case RESOURCE_BURST, TEAM_BUFF, WEATHER_CHANGE, TIME_CHANGE, ANNOUNCEMENT -> true;
            case SPAWNER_SPEED, DESTROY_BEDS, SUDDEN_DEATH, MATCH_END, TRAP_CHAOS, FIREWORKS,
                 HEAL_ALL, CLEAR_ITEMS, BALANCE_TEAMS, CLEAR_TRAPS, RESET_UPGRADES -> false;
        };
    }

    /**
     * A configured event definition (the "what"); sessions store only id + time (or, for a
     * host-authored custom event, id + time + type + value — see
     * {@link SessionSettings.TimelineEntry}). {@code dropTypeId} is reused as a generic
     * single-value slot for the newer types above (weather/time name, potion spec, message
     * text, …) — named for its original SPAWNER_SPEED-only purpose, but not renamed everywhere
     * to keep this change minimal. {@code includeByDefault} (config key {@code default},
     * defaults to true) lets an admin define a catalog entry that ISN'T part of every match's
     * starting schedule — an optional extra hosts can add via the timeline editor instead.
     */
    public record Definition(String id, String name, Material icon, int defaultSeconds,
                             Type type, String dropTypeId, double multiplier, String description,
                             boolean includeByDefault) {}

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

                // default: false marks an event as an optional catalog extra — not part of a
                // fresh match's starting schedule, but available for a host to add later via
                // the timeline editor's "Add Event" flow.
                boolean includeByDefault = e.getBoolean("default", true) || type == Type.MATCH_END;

                defs.put(id, new Definition(
                        id,
                        e.getString("name", id),
                        icon,
                        parseTime(e.getString("time", "10:00")),
                        type,
                        e.getString("drop_type", null),
                        e.getDouble("multiplier", 1.0),
                        e.getString("description", ""),
                        includeByDefault));
            }
        }

        String endId = defs.values().stream()
                .filter(d -> d.type() == Type.MATCH_END)
                .map(Definition::id).findFirst().orElse(null);
        if (endId == null) {
            logger.warning("timeline.events has no match_end-type event; adding a 30:00 default.");
            endId = MATCH_END_ID_FALLBACK;
            defs.put(endId, new Definition(endId, "Match End", Material.CLOCK, 30 * 60,
                    Type.MATCH_END, null, 1.0, "The match ends.", true));
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

    /** Catalog-only lookup — null for a host-authored custom entry's id, use {@link #definitionFor} instead. */
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

    /**
     * Resolves a timeline entry's definition regardless of whether it references the catalog
     * or is a host-authored custom event (synthesized on the fly from its own type + value —
     * nothing to look up, it's entirely self-contained).
     */
    public Definition definitionFor(SessionSettings.TimelineEntry entry) {
        if (entry.isCustom()) return syntheticDefinition(entry);
        return definitions.get(entry.id());
    }

    /** Same as {@link #definitionFor(SessionSettings.TimelineEntry)}, resolved by id against a session's current timeline. */
    public Definition definitionFor(SessionSettings settings, String id) {
        for (SessionSettings.TimelineEntry e : effectiveTimeline(settings)) {
            if (e.id().equals(id)) return definitionFor(e);
        }
        return definitions.get(id);
    }

    private Definition syntheticDefinition(SessionSettings.TimelineEntry entry) {
        Type type;
        try {
            type = Type.valueOf(entry.customType());
        } catch (IllegalArgumentException | NullPointerException e) {
            type = Type.ANNOUNCEMENT;
        }
        String value = entry.customValue() == null ? "" : entry.customValue();

        return switch (type) {
            case RESOURCE_BURST -> new Definition(entry.id(), "Resource Burst: " + value, Material.CHEST,
                    entry.seconds(), type, value, 1.0,
                    "One-time bonus drop from every " + value + " generator.", true);
            case TEAM_BUFF -> new Definition(entry.id(), "Buff: " + value, Material.POTION,
                    entry.seconds(), type, value, 1.0,
                    "Grants everyone in the arena a temporary " + value + " effect.", true);
            case TRAP_CHAOS -> new Definition(entry.id(), "Trap Chaos", Material.TRIPWIRE_HOOK,
                    entry.seconds(), type, value, 1.0,
                    "Force-triggers a random team's queued trap.", true);
            case WEATHER_CHANGE -> new Definition(entry.id(), "Weather: " + value, Material.PAINTING,
                    entry.seconds(), type, value, 1.0,
                    "Changes the arena's weather to " + value + ".", true);
            case TIME_CHANGE -> new Definition(entry.id(), "Time: " + value, Material.CLOCK,
                    entry.seconds(), type, value, 1.0,
                    "Changes the arena's time of day to " + value + ".", true);
            case FIREWORKS -> new Definition(entry.id(), "Fireworks", Material.FIREWORK_ROCKET,
                    entry.seconds(), type, value, 1.0,
                    "A celebratory firework show over the arena.", true);
            case HEAL_ALL -> new Definition(entry.id(), "Heal Pulse", Material.GOLDEN_APPLE,
                    entry.seconds(), type, value, 1.0,
                    "Fully heals and feeds every player in the arena.", true);
            case CLEAR_ITEMS -> new Definition(entry.id(), "Item Cleanup", Material.HOPPER,
                    entry.seconds(), type, value, 1.0,
                    "Removes every dropped item lying in the arena.", true);
            case BALANCE_TEAMS -> new Definition(entry.id(), "Team Reshuffle", Material.COMPASS,
                    entry.seconds(), type, value, 1.0,
                    "Re-shuffles every current player evenly across the enabled teams.", true);
            case CLEAR_TRAPS -> new Definition(entry.id(), "Trap Purge", Material.STRING,
                    entry.seconds(), type, value, 1.0,
                    "Clears every team's queued (not yet triggered) traps.", true);
            case RESET_UPGRADES -> new Definition(entry.id(), "Upgrade Reset", Material.ANVIL,
                    entry.seconds(), type, value, 1.0,
                    "Resets every team's generator and shop upgrades to nothing purchased.", true);
            default -> new Definition(entry.id(), "Announcement", Material.PAPER,
                    entry.seconds(), Type.ANNOUNCEMENT, value, 1.0, value, true);
        };
    }

    /** Presets per player are capped to keep the timeline from becoming unmanageable. */
    public static final int MAX_CUSTOM_EVENTS = 15;

    /**
     * Adds a catalog definition to the session's timeline at the given time, if it isn't
     * already present. Returns false when the id is unknown or already scheduled.
     */
    public boolean addEvent(SessionSettings settings, String id, int seconds) {
        Definition def = definitions.get(id);
        if (def == null) return false;

        List<SessionSettings.TimelineEntry> timeline = effectiveTimeline(settings);
        if (indexOf(timeline, id) >= 0) return false;

        timeline.add(new SessionSettings.TimelineEntry(id,
                clamp(seconds, MIN_EVENT_SECONDS, timeOf(timeline, matchEndId) - SNAP_SECONDS)));
        sortWithEndLast(timeline);
        settings.setTimeline(timeline);
        return true;
    }

    /**
     * Adds a host-authored custom event — its own type + value, not a catalog reference — at
     * the given time. Returns the generated entry, or null when the session is already at
     * {@link #MAX_CUSTOM_EVENTS} or {@code type}/{@code value} don't make sense together.
     */
    public SessionSettings.TimelineEntry addCustomEvent(SessionSettings settings, Type type, String value, int seconds) {
        if (type == null || !isCustomCreatable(type)) return null;

        List<SessionSettings.TimelineEntry> timeline = effectiveTimeline(settings);
        long customCount = timeline.stream().filter(SessionSettings.TimelineEntry::isCustom).count();
        if (customCount >= MAX_CUSTOM_EVENTS) return null;

        String id = "custom:" + java.util.UUID.randomUUID();
        int clampedTime = clamp(seconds, MIN_EVENT_SECONDS, timeOf(timeline, matchEndId) - SNAP_SECONDS);
        SessionSettings.TimelineEntry entry = new SessionSettings.TimelineEntry(id, clampedTime, type.name(), value);

        timeline.add(entry);
        sortWithEndLast(timeline);
        settings.setTimeline(timeline);
        return entry;
    }

    /**
     * Only the newer, self-contained types can be host-authored from scratch. The original four
     * (SPAWNER_SPEED, DESTROY_BEDS, SUDDEN_DEATH, MATCH_END) need admin-configured semantics a
     * bare id+time+value can't express (a validated drop_type + multiplier for SPAWNER_SPEED,
     * MATCH_END's special non-deletable/rescale-anchor status, …) — they stay catalog-only.
     */
    public static boolean isCustomCreatable(Type type) {
        return switch (type) {
            case RESOURCE_BURST, TEAM_BUFF, TRAP_CHAOS, WEATHER_CHANGE, TIME_CHANGE, ANNOUNCEMENT, FIREWORKS,
                 HEAL_ALL, CLEAR_ITEMS, BALANCE_TEAMS, CLEAR_TRAPS, RESET_UPGRADES -> true;
            case SPAWNER_SPEED, DESTROY_BEDS, SUDDEN_DEATH, MATCH_END -> false;
        };
    }

    // ── Effective timeline of a session ─────────────────────────────────────────────

    /**
     * The session's timeline sorted by time, Match End guaranteed present and last. An entry
     * referencing a catalog id that no longer exists in config is dropped; a host-authored
     * entry (its own type + value, not a catalog reference — see
     * {@link SessionSettings.TimelineEntry#isCustom()}) is always kept, since it's entirely
     * self-contained and doesn't depend on the catalog at all.
     */
    public List<SessionSettings.TimelineEntry> effectiveTimeline(SessionSettings settings) {
        List<SessionSettings.TimelineEntry> custom = settings.getTimeline();

        List<SessionSettings.TimelineEntry> out = new ArrayList<>();
        if (custom == null) {
            for (Definition d : definitions.values()) {
                if (!d.includeByDefault()) continue; // optional catalog extra — not scheduled unless added
                out.add(new SessionSettings.TimelineEntry(d.id(), d.defaultSeconds()));
            }
        } else {
            boolean hasEnd = false;
            for (SessionSettings.TimelineEntry e : custom) {
                if (e.isCustom() || definitions.containsKey(e.id())) {
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

    /**
     * Accepts "m:ss" or plain seconds. Same grammar as {@code EaCommand.parseDuration}, kept as
     * a separate method because that one returns null on bad input (a command can just reject
     * it) while config parsing always needs a usable fallback value instead.
     */
    public static int parseTime(String raw) {
        if (raw == null) return 600;
        raw = raw.trim();
        try {
            int colon = raw.indexOf(':');
            if (colon < 0) return Math.max(0, Integer.parseInt(raw));
            int minutes = Integer.parseInt(raw.substring(0, colon));
            int seconds = Integer.parseInt(raw.substring(colon + 1));
            if (minutes < 0 || seconds < 0 || seconds > 59) return 600;
            long total = (long) minutes * 60 + seconds; // long math so a huge minutes can't wrap the int
            return total > Integer.MAX_VALUE ? 600 : (int) total;
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
