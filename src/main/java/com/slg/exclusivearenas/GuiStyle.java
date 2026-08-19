package com.slg.exclusivearenas;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Every menu's look — titles, sizes, and each button's slot, material, name, lore, and
 * glint — is read from guis.yml (versioned + self-healing like every other config; see
 * {@link VersionedYaml}). The bundled resource is the single source of truth for defaults.
 *
 * Conventions:
 * <ul>
 *   <li>Buttons live at {@code <menu>.buttons.<button>} with fields
 *       {@code slot / material / name / lore / glint}.</li>
 *   <li>Setting {@code slot: -1} hides a button entirely (its click stops matching too).</li>
 *   <li>Templates for dynamic items (arena icons, player heads, …) live at
 *       {@code <menu>.items.<item>} and only use {@code name / lore / glint} — the material
 *       comes from the live object at runtime.</li>
 *   <li>Placeholder values may contain {@code \n}; the lore line splits into several lines.
 *       A lore line that becomes empty after placeholder replacement is dropped, which is
 *       how optional lines are expressed.</li>
 * </ul>
 */
public final class GuiStyle {

    private static volatile VersionedYaml yaml;

    private GuiStyle() {}

    public static void init(VersionedYaml y) {
        yaml = y;
    }

    /**
     * A handful of buttons are two different "looks" of the same click handler and are only
     * ever wired up correctly if they share a slot (see the header comment in guis.yml) —
     * nothing at load time enforces that, so an admin edit that moves one half without the
     * other silently breaks the pairing. Called once after {@link #init} to catch that early
     * with a log warning instead of a confusing "wrong button did something else" report.
     */
    public static void warnIfPairedSlotsMismatched(java.util.logging.Logger logger) {
        checkPair(logger, "controls.buttons.public-on", "controls.buttons.public-off");
        checkPair(logger, "controls.buttons.public-on", "controls.buttons.summon-party");
        checkPair(logger, "builder.buttons.public-on", "builder.buttons.public-off");
        // The main menu's context-sensitive button: three looks of one control.
        checkPair(logger, "main.buttons.arena-management", "main.buttons.create-arena");
        checkPair(logger, "main.buttons.arena-management", "main.buttons.match-controls");
        // Manage Teams' team lock, and the timeline editor's selection card.
        checkPair(logger, "team-select.buttons.lock-teams", "team-select.buttons.unlock-teams");
        checkPair(logger, "timeline.buttons.selected", "timeline.buttons.no-selection");
    }

    private static void checkPair(java.util.logging.Logger logger, String a, String b) {
        int slotA = slot(a);
        int slotB = slot(b);
        // A button hidden with slot: -1 is deliberately opted out — only flag a real mismatch.
        if (slotA < 0 || slotB < 0 || slotA == slotB) return;
        logger.warning("guis.yml: '" + a + "' (slot " + slotA + ") and '" + b + "' (slot " + slotB
                + ") are paired state buttons and should share a slot — only one will ever be "
                + "shown/clickable as configured.");
    }

    // ── Menu-level ───────────────────────────────────────────────────────────────

    public static String title(String menu, String... ph) {
        String raw = str(menu + ".title", "&1&l" + menu);
        return ItemUtil.color(apply(raw, ph));
    }

    /** Menu size in slots, clamped to a sane 9..54 multiple of 9. */
    public static int size(String menu, int def) {
        int v = yaml == null ? def : yaml.config().getInt(menu + ".size", def);
        v = Math.max(9, Math.min(54, v));
        return (v / 9) * 9;
    }

    public static Material material(String key, Material def) {
        String name = str(key, null);
        if (name == null) return def;
        Material m = Material.matchMaterial(name.trim().toUpperCase(Locale.ROOT));
        return m != null ? m : def;
    }

    // ── Buttons ──────────────────────────────────────────────────────────────────

    /** The configured slot of {@code <menu>.buttons.<button>}; -1 hides the button. */
    public static int slot(String buttonKey) {
        return yaml == null ? -1 : yaml.config().getInt(buttonKey + ".slot", -1);
    }

    /** Builds the configured button item. */
    public static ItemStack item(String buttonKey, String... ph) {
        Material mat = material(buttonKey + ".material", Material.BARRIER);
        ItemStack item = ItemUtil.button(mat, name(buttonKey, ph), lore(buttonKey, ph));
        if (glint(buttonKey)) ItemUtil.glint(item);
        return item;
    }

    /**
     * Places the configured button into the inventory (skipping hidden or out-of-range
     * slots) and returns the slot used, or -1 when hidden.
     */
    public static int place(Inventory inv, String buttonKey, String... ph) {
        int slot = slot(buttonKey);
        if (slot < 0 || slot >= inv.getSize()) return -1;
        inv.setItem(slot, item(buttonKey, ph));
        return slot;
    }

    // ── Templates for dynamic items ───────────────────────────────────────────────

    public static String name(String key, String... ph) {
        return apply(str(key + ".name", "&cMissing: " + key), ph);
    }

    public static List<String> lore(String key, String... ph) {
        List<String> raw = yaml == null ? List.of() : yaml.config().getStringList(key + ".lore");
        List<String> out = new ArrayList<>(raw.size());
        for (String line : raw) {
            String applied = apply(line, ph);
            if (applied.isEmpty()) continue; // optional line whose placeholder was blank
            for (String part : applied.split("\n")) out.add(part);
        }
        return out;
    }

    public static boolean glint(String key) {
        return yaml != null && yaml.config().getBoolean(key + ".glint", false);
    }

    /** True when the key exists — used for optional template sections. */
    public static boolean has(String key) {
        if (yaml == null) return false;
        ConfigurationSection section = yaml.config().getConfigurationSection(key);
        return section != null || yaml.config().isSet(key);
    }

    /** A colored plain string straight from guis.yml (for keys that aren't buttons/templates). */
    public static String rawString(String key, String def, String... ph) {
        return ItemUtil.color(apply(str(key, def), ph));
    }

    // ── Internals ────────────────────────────────────────────────────────────────

    private static String str(String key, String def) {
        return yaml == null ? def : yaml.config().getString(key, def);
    }

    /**
     * Substitutes every placeholder in a single pass over the original text. Doing it
     * sequentially (repeated {@code String.replace}) risks a substituted value that happens to
     * contain a later placeholder's token text being corrupted by that later substitution —
     * e.g. an arena name containing the literal substring "%owner%".
     */
    private static String apply(String value, String... ph) {
        if (value == null) return "";
        if (ph == null || ph.length < 2) return value;

        StringBuilder pattern = new StringBuilder();
        Map<String, String> replacements = new HashMap<>();
        for (int i = 0; i + 1 < ph.length; i += 2) {
            String token = ph[i];
            if (token == null || token.isEmpty()) continue;
            if (pattern.length() > 0) pattern.append('|');
            pattern.append(Pattern.quote(token));
            replacements.put(token, ph[i + 1] == null ? "" : ph[i + 1]);
        }
        if (pattern.length() == 0) return value;

        Matcher m = Pattern.compile(pattern.toString()).matcher(value);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement(replacements.get(m.group())));
        }
        m.appendTail(out);
        return out.toString();
    }
}
