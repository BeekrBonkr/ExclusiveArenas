package com.slg.exclusivearenas;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Per-match customizations a host makes through Arena Settings: the event timeline and
 * shop item overrides. Lives on the {@link PrivateSession} and is persisted as one JSON
 * blob in the sessions table, so the server that actually hosts the arena enforces
 * settings even when they were edited from a hub.
 *
 * A {@code null} timeline means "use the configured defaults" — it only becomes a concrete
 * list once the host edits something, so config changes keep applying to untouched matches.
 */
public final class SessionSettings {

    /**
     * One scheduled event: which definition it is and when (seconds after round start).
     * {@code customType}/{@code customValue} are null for a catalog-referenced entry (the
     * usual case); non-null for a host-authored custom event — its own
     * {@link TimelineService.Type} name and a single type-specific value string, entirely
     * self-contained (see {@link TimelineService#definitionFor(TimelineEntry)}) rather than
     * pointing at a config-defined id.
     */
    public record TimelineEntry(String id, int seconds, String customType, String customValue) {
        public TimelineEntry(String id, int seconds) {
            this(id, seconds, null, null);
        }

        public boolean isCustom() {
            return customType != null;
        }
    }

    /** A host's override for one shop item. */
    public static final class ShopOverride {
        private boolean disabled;
        private Integer price;      // null = default price
        private String currency;    // MBedwars drop type id; null = default currency

        public boolean isDisabled() { return disabled; }
        public void setDisabled(boolean disabled) { this.disabled = disabled; }

        public Integer getPrice() { return price; }
        public String getCurrency() { return currency; }

        public void setPrice(Integer price, String currency) {
            this.price = price;
            this.currency = currency;
        }

        public boolean hasPriceOverride() { return price != null && currency != null; }

        /** True when this override no longer changes anything and can be dropped. */
        public boolean isNoop() { return !disabled && !hasPriceOverride(); }
    }

    private List<TimelineEntry> timeline; // null = defaults from config
    private final Map<String, ShopOverride> shop = new LinkedHashMap<>();
    private Integer playersPerTeam; // null = the arena's own default
    // ArenaWeatherType/ArenaTimeType enum names; null = UNTOUCHED (arena's own default climate)
    private String weatherType;
    private String timeType;

    // ── Team size ────────────────────────────────────────────────────────────────

    /** The host's players-per-team override, or null when the arena's own default applies. */
    public Integer getPlayersPerTeam() {
        return playersPerTeam;
    }

    public void setPlayersPerTeam(Integer playersPerTeam) {
        this.playersPerTeam = playersPerTeam;
    }

    // ── Environment (time / weather) ────────────────────────────────────────────

    /** {@code de.marcely.bedwars.api.arena.ArenaWeatherType} name, or null for UNTOUCHED. */
    public String getWeatherType() {
        return weatherType;
    }

    public void setWeatherType(String weatherType) {
        this.weatherType = weatherType;
    }

    /** {@code de.marcely.bedwars.api.arena.ArenaTimeType} name, or null for UNTOUCHED. */
    public String getTimeType() {
        return timeType;
    }

    public void setTimeType(String timeType) {
        this.timeType = timeType;
    }

    // ── Timeline ─────────────────────────────────────────────────────────────────

    /** The customized timeline, or null when the match still follows the config defaults. */
    public List<TimelineEntry> getTimeline() {
        return timeline;
    }

    public void setTimeline(List<TimelineEntry> timeline) {
        this.timeline = timeline == null ? null : new ArrayList<>(timeline);
    }

    // ── Shop ─────────────────────────────────────────────────────────────────────

    public ShopOverride getShopOverride(String itemId) {
        return shop.get(itemId);
    }

    public ShopOverride getOrCreateShopOverride(String itemId) {
        return shop.computeIfAbsent(itemId, k -> new ShopOverride());
    }

    /** Drops the override when it no longer changes anything. */
    public void pruneShopOverride(String itemId) {
        ShopOverride o = shop.get(itemId);
        if (o != null && o.isNoop()) shop.remove(itemId);
    }

    public Map<String, ShopOverride> getShopOverrides() {
        return shop;
    }

    public void clearShopOverrides() {
        shop.clear();
    }

    public long countDisabled(Iterable<String> itemIds) {
        long n = 0;
        for (String id : itemIds) {
            ShopOverride o = shop.get(id);
            if (o != null && o.isDisabled()) n++;
        }
        return n;
    }

    // ── JSON (de)serialization ───────────────────────────────────────────────────

    /** Serializes to the JSON blob stored in the sessions table; null when nothing is set. */
    public String toJson() {
        if (timeline == null && shop.isEmpty() && playersPerTeam == null
                && weatherType == null && timeType == null) {
            return null;
        }

        JsonObject root = new JsonObject();
        if (playersPerTeam != null) {
            root.addProperty("ppt", playersPerTeam);
        }
        if (weatherType != null) {
            root.addProperty("weather", weatherType);
        }
        if (timeType != null) {
            root.addProperty("time", timeType);
        }
        if (timeline != null) {
            JsonArray arr = new JsonArray();
            for (TimelineEntry e : timeline) {
                JsonObject o = new JsonObject();
                o.addProperty("id", e.id());
                o.addProperty("t", e.seconds());
                if (e.customType() != null) o.addProperty("ct", e.customType());
                if (e.customValue() != null) o.addProperty("cv", e.customValue());
                arr.add(o);
            }
            root.add("timeline", arr);
        }
        if (!shop.isEmpty()) {
            JsonObject shopObj = new JsonObject();
            for (Map.Entry<String, ShopOverride> e : shop.entrySet()) {
                ShopOverride o = e.getValue();
                JsonObject item = new JsonObject();
                if (o.isDisabled()) item.addProperty("off", true);
                if (o.hasPriceOverride()) {
                    item.addProperty("price", o.getPrice());
                    item.addProperty("cur", o.getCurrency());
                }
                shopObj.add(e.getKey(), item);
            }
            root.add("shop", shopObj);
        }
        return root.toString();
    }

    /** Parses the stored blob; a null/blank/broken blob yields untouched defaults. */
    public static SessionSettings fromJson(String json) {
        SessionSettings s = new SessionSettings();
        if (json == null || json.isBlank()) return s;
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();

            if (root.has("ppt")) {
                s.playersPerTeam = root.get("ppt").getAsInt();
            }
            if (root.has("weather")) {
                s.weatherType = root.get("weather").getAsString();
            }
            if (root.has("time")) {
                s.timeType = root.get("time").getAsString();
            }
            if (root.has("timeline")) {
                List<TimelineEntry> entries = new ArrayList<>();
                for (JsonElement el : root.getAsJsonArray("timeline")) {
                    JsonObject o = el.getAsJsonObject();
                    entries.add(new TimelineEntry(
                            o.get("id").getAsString(),
                            o.get("t").getAsInt(),
                            o.has("ct") ? o.get("ct").getAsString() : null,
                            o.has("cv") ? o.get("cv").getAsString() : null));
                }
                s.timeline = entries;
            }
            if (root.has("shop")) {
                JsonObject shopObj = root.getAsJsonObject("shop");
                for (String itemId : shopObj.keySet()) {
                    JsonObject item = shopObj.getAsJsonObject(itemId);
                    ShopOverride o = new ShopOverride();
                    o.setDisabled(item.has("off") && item.get("off").getAsBoolean());
                    if (item.has("price") && item.has("cur")) {
                        o.setPrice(item.get("price").getAsInt(), item.get("cur").getAsString());
                    }
                    if (!o.isNoop()) s.shop.put(itemId, o);
                }
            }
        } catch (Exception ignored) {
            // A malformed blob (old version, manual edit) falls back to defaults rather
            // than poisoning the session.
        } catch (StackOverflowError ignored) {
            // Gson's recursive-descent parser can blow the stack on deeply nested/malicious
            // JSON. Exception doesn't catch Error, so this needs its own clause — otherwise an
            // uncaught StackOverflowError here would abort the whole reconcile() batch it's
            // called from, repeating every poll cycle until the row is fixed.
            return new SessionSettings();
        }
        return s;
    }

    /** Value comparison via the canonical JSON form — used to detect real changes on sync. */
    public boolean sameAs(String otherJson) {
        return Objects.equals(toJson(), otherJson == null || otherJson.isBlank() ? null : otherJson);
    }
}
