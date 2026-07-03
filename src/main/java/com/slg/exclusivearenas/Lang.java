package com.slg.exclusivearenas;

/**
 * All user-facing text, loaded from lang.yml (versioned + self-healing like every other
 * config — see {@link VersionedYaml}). Accessed statically because messages are needed
 * from every corner of the plugin and threading is trivially safe (the config reference
 * is replaced atomically on reload; reads never mutate).
 *
 * Placeholders are passed as pairs: {@code Lang.msg("join.usage", "%code%", code)}.
 */
public final class Lang {

    private static volatile VersionedYaml yaml;

    private Lang() {}

    public static void init(VersionedYaml y) {
        yaml = y;
    }

    /** The colored message at {@code key} with {@code %placeholder%} pairs applied. */
    public static String msg(String key, String... placeholderPairs) {
        return ItemUtil.color(raw(key, placeholderPairs));
    }

    /** Same as {@link #msg}, but without color translation (for contexts that color later). */
    public static String raw(String key, String... placeholderPairs) {
        VersionedYaml y = yaml;
        String value = y != null ? y.config().getString(key) : null;
        if (value == null) value = "&cMissing lang key: " + key;
        if (placeholderPairs != null) {
            for (int i = 0; i + 1 < placeholderPairs.length; i += 2) {
                String ph = placeholderPairs[i];
                String v = placeholderPairs[i + 1];
                value = value.replace(ph, v == null ? "" : v);
            }
        }
        return value;
    }
}
