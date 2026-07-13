package com.slg.exclusivearenas;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * A YAML file managed against a bundled default resource, shared by every config the plugin
 * ships (config.yml, lang.yml, guis.yml). On every load the file is:
 *
 * <ol>
 *   <li>created from the bundled resource if missing (keeping the resource's comments),</li>
 *   <li>migrated forward when {@code config-version} is older than the current version
 *       (the caller supplies any rename/removal transformations),</li>
 *   <li>reconciled key-by-key against the bundled default, restoring any missing key with
 *       its default value, and</li>
 *   <li>re-annotated with the bundled default's comments (header and per-key), so servers
 *       always carry up-to-date documentation even across upgrades.</li>
 * </ol>
 *
 * User VALUES are never overwritten — only missing keys are added and comments refreshed.
 */
public final class VersionedYaml {

    /** Version-specific transformations (renames/removals) applied before reconciliation. */
    @FunctionalInterface
    public interface Migrator {
        /** @return true if the config was changed. */
        boolean migrate(YamlConfiguration config, int fromVersion);
    }

    private static final String VERSION_KEY = "config-version";

    private final JavaPlugin plugin;
    private final File file;
    private final String resourceName;
    private final int currentVersion;
    private final Migrator migrator;

    private YamlConfiguration config = new YamlConfiguration();

    public VersionedYaml(JavaPlugin plugin, File dataFolder, String resourceName,
                         int currentVersion, Migrator migrator) {
        this.plugin = plugin;
        this.file = new File(dataFolder, resourceName);
        this.resourceName = resourceName;
        this.currentVersion = currentVersion;
        this.migrator = migrator;
    }

    public YamlConfiguration config() {
        return config;
    }

    public File file() {
        return file;
    }

    public void load() {
        ensureDefaultExists();
        this.config = YamlConfiguration.loadConfiguration(file);

        boolean changed = migrate();
        YamlConfiguration defaults = loadBundledDefaults();
        if (defaults != null) {
            changed |= ensureComplete(defaults);
            changed |= syncComments(defaults);
        }
        if (changed) save();
    }

    public void save() {
        try {
            config.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to save " + resourceName + ": " + e.getMessage());
        }
    }

    private void ensureDefaultExists() {
        if (file.exists()) return;

        File dir = file.getParentFile();
        if (dir != null && !dir.exists() && !dir.mkdirs()) {
            plugin.getLogger().warning("Unable to create data folder: " + dir.getAbsolutePath());
        }
        try (InputStream in = plugin.getResource(resourceName)) {
            if (in == null) {
                plugin.getLogger().warning("Missing bundled resource: " + resourceName);
                return;
            }
            Files.copy(in, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            plugin.getLogger().warning("Unable to write default " + resourceName + ": " + e.getMessage());
        }
    }

    private boolean migrate() {
        int version = config.getInt(VERSION_KEY, 0);
        if (version >= currentVersion) return false;

        plugin.getLogger().info("Migrating " + resourceName + " from version "
                + version + " → " + currentVersion);
        if (migrator != null && !migrator.migrate(config, version)) {
            plugin.getLogger().info(resourceName + ": migrator made no key changes for this step "
                    + "(only the version stamp advanced).");
        }
        config.set(VERSION_KEY, currentVersion);
        return true; // the version-stamp write above always needs saving, regardless of the migrator
    }

    /** Restores any key present in the bundled default but missing from the server's copy. */
    private boolean ensureComplete(YamlConfiguration defaults) {
        boolean changed = false;
        for (String key : defaults.getKeys(true)) {
            // Sections are created implicitly when their leaf children are set; only copy leaves.
            if (defaults.isConfigurationSection(key)) continue;
            if (!config.isSet(key)) {
                config.set(key, defaults.get(key));
                plugin.getLogger().info(resourceName + ": restored missing key '" + key
                        + "' with its default value.");
                changed = true;
            }
        }
        return changed;
    }

    /**
     * Copies the bundled default's header and per-key comments onto the loaded config so a
     * programmatic save (from migration or key restoration) doesn't strip the documentation
     * users rely on while editing — and upgrades refresh it.
     */
    private boolean syncComments(YamlConfiguration defaults) {
        boolean changed = false;

        List<String> header = defaults.options().getHeader();
        if (header != null && !header.isEmpty() && !header.equals(config.options().getHeader())) {
            config.options().setHeader(header);
            changed = true;
        }
        for (String key : defaults.getKeys(true)) {
            List<String> comments = defaults.getComments(key);
            if (!comments.isEmpty() && config.isSet(key) && !comments.equals(config.getComments(key))) {
                config.setComments(key, comments);
                changed = true;
            }
        }
        return changed;
    }

    private YamlConfiguration loadBundledDefaults() {
        try (InputStream in = plugin.getResource(resourceName)) {
            if (in == null) {
                plugin.getLogger().warning("Missing bundled " + resourceName + "; cannot verify keys.");
                return null;
            }
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            plugin.getLogger().warning("Unable to read bundled " + resourceName + ": " + e.getMessage());
            return null;
        }
    }
}
