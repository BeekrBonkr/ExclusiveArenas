package com.slg.exclusivearenas;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Saved arena configurations ("presets"): a named snapshot of a match's {@link SessionSettings}
 * (event timeline + shop overrides) a host can apply to any later match.
 *
 * Stored in the shared database when one is configured — so a preset saved on the hub loads on
 * any backend — otherwise in presets.yml in the add-on's data folder. All access is
 * callback-based: reads run off-thread and call back on the main thread, writes are
 * fire-and-forget, so a menu open never blocks the server on I/O.
 */
public final class PresetService {

    /** Presets per player are capped to keep menus one page and the table small. */
    public static final int MAX_PRESETS = 20;
    public static final int MAX_NAME_LENGTH = 24;

    private final ExclusiveArenasPlugin plugin;
    private final File file;
    private YamlConfiguration fileStore; // lazily loaded; only used without a database

    public PresetService(ExclusiveArenasPlugin plugin, File dataFolder) {
        this.plugin = plugin;
        this.file = new File(dataFolder, "presets.yml");
    }

    /** True when {@code name} is usable as a preset name (also the tab-completion charset). */
    public static boolean isValidName(String name) {
        return name != null && !name.isBlank() && name.length() <= MAX_NAME_LENGTH
                && name.matches("[A-Za-z0-9_-]+");
    }

    /** Loads the player's presets (name → settings JSON) and hands them back on the main thread. */
    public void list(UUID owner, Consumer<LinkedHashMap<String, String>> callback) {
        Database db = plugin.getDatabase();
        if (db == null) {
            LinkedHashMap<String, String> fromFile = loadFromFile(owner);
            plugin.debug("presets: list(" + owner + ") [file] -> " + fromFile.size()
                    + " preset(s): " + fromFile.keySet());
            callback.accept(fromFile);
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            LinkedHashMap<String, String> presets;
            try {
                presets = db.loadPresets(owner);
            } catch (Exception e) {
                plugin.getLogger().warning("Could not load presets for " + owner + ": " + e.getMessage());
                presets = new LinkedHashMap<>();
            }
            plugin.debug("presets: list(" + owner + ") [database] -> " + presets.size()
                    + " preset(s): " + presets.keySet());
            LinkedHashMap<String, String> result = presets;
            Bukkit.getScheduler().runTask(plugin, () -> callback.accept(result));
        });
    }

    /** Saves (or overwrites) a preset. Fire-and-forget; validation is the caller's job. */
    public void save(UUID owner, String name, String settingsJson) {
        Database db = plugin.getDatabase();
        if (db != null) {
            db.upsertPreset(owner, name, settingsJson);
            return;
        }
        YamlConfiguration store = fileStore();
        store.set(ownerPath(owner) + "." + name, settingsJson == null ? "" : settingsJson);
        saveFile(store);
    }

    /**
     * Saves (or overwrites) a preset and reports back on the main thread whether it actually
     * persisted — so a caller can message the player honestly instead of optimistically
     * claiming success before the write (async in database mode) has even happened.
     */
    public void save(UUID owner, String name, String settingsJson, Consumer<Boolean> callback) {
        Database db = plugin.getDatabase();
        if (db != null) {
            db.upsertPreset(owner, name, settingsJson, ok -> Bukkit.getScheduler().runTask(plugin, () -> {
                plugin.debug("presets: save(" + owner + ", " + name + ") [database] -> " + ok);
                callback.accept(ok);
            }));
            return;
        }
        YamlConfiguration store = fileStore();
        store.set(ownerPath(owner) + "." + name, settingsJson == null ? "" : settingsJson);
        boolean ok = saveFile(store); // file mode is synchronous — already on the caller's thread
        plugin.debug("presets: save(" + owner + ", " + name + ") [file] -> " + ok
                + " (path: " + file.getAbsolutePath() + ")");
        callback.accept(ok);
    }

    public void delete(UUID owner, String name) {
        Database db = plugin.getDatabase();
        if (db != null) {
            db.deletePreset(owner, name);
            return;
        }
        YamlConfiguration store = fileStore();
        store.set(ownerPath(owner) + "." + name, null);
        saveFile(store);
    }

    /** The first free "Preset-N" name, or null when the player is at the cap. */
    public static String nextFreeName(LinkedHashMap<String, String> existing) {
        if (existing.size() >= MAX_PRESETS) return null;
        for (int i = 1; i <= MAX_PRESETS; i++) {
            String candidate = "Preset-" + i;
            boolean taken = existing.keySet().stream().anyMatch(n -> n.equalsIgnoreCase(candidate));
            if (!taken) return candidate;
        }
        return null;
    }

    /** The stored preset name matching {@code raw} case-insensitively, or null. */
    public static String existingName(LinkedHashMap<String, String> existing, String raw) {
        for (String name : existing.keySet()) {
            if (name.equalsIgnoreCase(raw)) return name;
        }
        return null;
    }

    // ── File fallback (single-server mode) ───────────────────────────────────────

    private LinkedHashMap<String, String> loadFromFile(UUID owner) {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        ConfigurationSection section = fileStore().getConfigurationSection(ownerPath(owner));
        if (section != null) {
            section.getKeys(false).stream()
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .forEach(name -> out.put(name, section.getString(name, "")));
        }
        return out;
    }

    private synchronized YamlConfiguration fileStore() {
        if (fileStore == null) {
            fileStore = YamlConfiguration.loadConfiguration(file);
        }
        return fileStore;
    }

    private boolean saveFile(YamlConfiguration store) {
        try {
            store.save(file);
            return true;
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save presets.yml: " + e.getMessage());
            return false;
        }
    }

    private static String ownerPath(UUID owner) {
        return "presets." + owner.toString().toLowerCase(Locale.ROOT);
    }
}
