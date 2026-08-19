package com.slg.exclusivearenas;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Loads config.yml from the MBedwars-managed add-on data folder
 * (plugins/MBedwars/add-ons/ExclusiveArenas/config.yml).
 *
 * On every startup the loaded config is:
 *   1. migrated forward by version (for renames/removals between versions), then
 *   2. reconciled key-by-key against the bundled default so that any key shipped with the
 *      plugin but missing from the server's copy is added back with its default value.
 *
 * The bundled resource config.yml is therefore the single source of truth for both defaults
 * and the full set of expected keys.
 */
public final class EaConfig {

    static final int CURRENT_VERSION = 7;

    private final JavaPlugin plugin;
    private final File dataFolder;
    private final File configFile;

    private YamlConfiguration config;

    public EaConfig(JavaPlugin plugin, File dataFolder) {
        this.plugin = plugin;
        this.dataFolder = dataFolder;
        this.configFile = new File(dataFolder, "config.yml");
    }

    public void load() {
        ensureDefaultExists();

        // Load via the throwing API — YamlConfiguration.loadConfiguration() swallows parse
        // errors and returns an EMPTY config, which the migrate/ensureComplete/save below
        // would then persist, silently replacing the admin's entire file with defaults over
        // a single indent typo. On a parse failure we instead keep the broken file untouched
        // (backed up alongside) and run this session on in-memory defaults.
        YamlConfiguration loaded = new YamlConfiguration();
        boolean parseFailed = false;
        try {
            loaded.load(configFile);
        } catch (FileNotFoundException e) {
            // Missing file (e.g. the bundled default couldn't be written) — run on defaults.
        } catch (IOException | InvalidConfigurationException e) {
            parseFailed = true;
            plugin.getLogger().severe("config.yml has a syntax error and could not be loaded — "
                    + "running on default values until it is fixed. Your file was NOT modified "
                    + "(a copy was saved next to it). Parse error: " + e.getMessage());
            backupBrokenFile(configFile);
        }
        this.config = loaded;

        boolean migrated = migrate();
        boolean completed = ensureComplete();
        // Never save over the user's file when it failed to parse — that would destroy
        // everything they wrote for the sake of one typo.
        if (parseFailed) return;
        if (migrated || completed) save();
    }

    /** Copies a syntactically-broken file to a timestamped .broken backup next to it. */
    static void backupBrokenFile(File file) {
        File backup = new File(file.getParentFile(),
                file.getName() + ".broken-" + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date()));
        try {
            Files.copy(file.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
            // best effort — the original stays in place either way
        }
    }

    public String str(String path, String def) {
        return config != null ? config.getString(path, def) : def;
    }

    public double num(String path, double def) {
        return config != null ? config.getDouble(path, def) : def;
    }

    public int intNum(String path, int def) {
        return config != null ? config.getInt(path, def) : def;
    }

    public boolean bool(String path, boolean def) {
        return config != null ? config.getBoolean(path, def) : def;
    }

    public org.bukkit.configuration.ConfigurationSection section(String path) {
        return config != null ? config.getConfigurationSection(path) : null;
    }

    private void ensureDefaultExists() {
        if (configFile.exists()) return;

        if (!dataFolder.exists() && !dataFolder.mkdirs()) {
            plugin.getLogger().warning("Unable to create addon data folder: " + dataFolder.getAbsolutePath());
        }

        try (InputStream in = plugin.getResource("config.yml")) {
            if (in == null) {
                plugin.getLogger().warning("Missing bundled config.yml resource.");
                return;
            }
            Files.copy(in, configFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            plugin.getLogger().warning("Unable to write default config.yml: " + e.getMessage());
        }
    }

    /**
     * Version-specific transformations that can't be derived from the bundled default —
     * i.e. renames, removals, or value reshaping. Plain additions of new keys are handled
     * generically by {@link #ensureComplete()}, so they do not need to be listed here.
     *
     * @return true if the config was changed and needs saving.
     */
    private boolean migrate() {
        if (config == null) return false;
        int version = config.getInt("config-version", 0);
        if (version >= CURRENT_VERSION) return false;

        plugin.getLogger().info("Migrating config from version " + version + " → " + CURRENT_VERSION);

        // v0 → v1: no renames.
        // v1 → v2: no renames (added the database/network/ticket keys — added by ensureComplete()).
        // v4 → v5: chat messages moved to lang.yml; the old messages section is dropped.
        if (version < 5) {
            config.set("messages", null);
        }
        // v5 → v6: map regeneration no longer shells out to a console command — it cycles
        // players through spectator mode and lets MBedwars' own reset regenerate the map.
        if (version < 6) {
            config.set("quick_actions.regenerate_command", null);
        }
        // v6 → v7: added timeline.max_match_time and the private.inactivity_* cleanup
        // settings — pure additions, restored automatically by ensureComplete() below.

        config.set("config-version", CURRENT_VERSION);
        return true;
    }

    /**
     * Ensures the server's config contains every key present in the bundled default,
     * adding any that are missing with their default value. Existing values are never
     * overwritten. Runs on every startup so the config self-heals against missing keys.
     *
     * @return true if any key was added and the config needs saving.
     */
    private boolean ensureComplete() {
        if (config == null) return false;

        YamlConfiguration defaults = loadBundledDefaults();
        if (defaults == null) return false;

        boolean changed = false;
        for (String key : defaults.getKeys(true)) {
            // Sections are created implicitly when their leaf children are set; only copy leaves.
            if (defaults.isConfigurationSection(key)) continue;
            if (!config.isSet(key)) {
                config.set(key, defaults.get(key));
                plugin.getLogger().info("Restored missing config key '" + key + "' with its default value.");
                changed = true;
            }
        }
        return changed;
    }

    private YamlConfiguration loadBundledDefaults() {
        try (InputStream in = plugin.getResource("config.yml")) {
            if (in == null) {
                plugin.getLogger().warning("Missing bundled config.yml resource; cannot verify config keys.");
                return null;
            }
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            plugin.getLogger().warning("Unable to read bundled config.yml for key verification: " + e.getMessage());
            return null;
        }
    }

    private void save() {
        if (config == null) return;
        try {
            config.save(configFile);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to save config: " + e.getMessage());
        }
    }
}
