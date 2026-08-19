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
     *
     * The {@link #MAX_PRESETS} cap is enforced here against a freshly-loaded count, not just by
     * the caller checking a menu snapshot taken whenever it was opened — otherwise two saves
     * issued back to back while already at the cap could both pass a stale client-side check
     * and land one over, since database-mode writes are async round-trips spanning several
     * ticks. Overwriting an existing name is never blocked by the cap.
     */
    public void save(UUID owner, String name, String settingsJson, Consumer<Boolean> callback) {
        Database db = plugin.getDatabase();
        if (db != null) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                LinkedHashMap<String, String> existing;
                try {
                    existing = db.loadPresets(owner);
                } catch (Exception e) {
                    plugin.getLogger().warning("Could not check preset count for " + owner + ": " + e.getMessage());
                    Bukkit.getScheduler().runTask(plugin, () -> callback.accept(false));
                    return;
                }
                if (existingName(existing, name) == null && existing.size() >= MAX_PRESETS) {
                    plugin.debug("presets: save(" + owner + ", " + name + ") [database] -> rejected, at cap");
                    Bukkit.getScheduler().runTask(plugin, () -> callback.accept(false));
                    return;
                }
                db.upsertPreset(owner, name, settingsJson, ok -> Bukkit.getScheduler().runTask(plugin, () -> {
                    plugin.debug("presets: save(" + owner + ", " + name + ") [database] -> " + ok);
                    callback.accept(ok);
                }));
            });
            return;
        }
        // File mode runs entirely on the main thread (Bukkit is single-threaded), so a fresh
        // count check right before writing is inherently race-free here, unlike database mode.
        LinkedHashMap<String, String> currentFilePresets = loadFromFile(owner);
        if (existingName(currentFilePresets, name) == null && currentFilePresets.size() >= MAX_PRESETS) {
            plugin.debug("presets: save(" + owner + ", " + name + ") [file] -> rejected, at cap");
            callback.accept(false);
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

    /**
     * Deletes a preset and reports back on the main thread whether the delete actually
     * persisted — mirrors the callback {@code save} overload, so a menu can message the
     * player honestly instead of claiming success for a write that failed.
     */
    public void delete(UUID owner, String name, Consumer<Boolean> callback) {
        Database db = plugin.getDatabase();
        if (db != null) {
            db.deletePreset(owner, name, ok -> Bukkit.getScheduler().runTask(plugin, () -> {
                plugin.debug("presets: delete(" + owner + ", " + name + ") [database] -> " + ok);
                callback.accept(ok);
            }));
            return;
        }
        YamlConfiguration store = fileStore();
        store.set(ownerPath(owner) + "." + name, null);
        boolean ok = saveFile(store); // file mode is synchronous — already on the caller's thread
        plugin.debug("presets: delete(" + owner + ", " + name + ") [file] -> " + ok);
        callback.accept(ok);
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
