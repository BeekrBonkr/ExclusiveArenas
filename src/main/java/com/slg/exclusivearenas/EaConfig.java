package com.slg.exclusivearenas;

import org.bukkit.ChatColor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Loads config.yml from the MBedwars-managed add-on data folder
 * (plugins/MBedwars/add-ons/ExclusiveArenas/config.yml).
 * Handles config versioning and migrates older configs forward automatically.
 */
public final class EaConfig {

    static final int CURRENT_VERSION = 1;

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
        this.config = YamlConfiguration.loadConfiguration(configFile);
        migrate();
    }

    public String msg(String path) {
        String raw = config != null ? config.getString(path) : null;
        if (raw == null) raw = "&cMissing message: " + path;
        return ChatColor.translateAlternateColorCodes('&', raw);
    }

    public String str(String path, String def) {
        return config != null ? config.getString(path, def) : def;
    }

    public double num(String path, double def) {
        return config != null ? config.getDouble(path, def) : def;
    }

    public boolean bool(String path, boolean def) {
        return config != null ? config.getBoolean(path, def) : def;
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

    private void migrate() {
        if (config == null) return;
        int version = config.getInt("config-version", 0);
        if (version >= CURRENT_VERSION) return;

        plugin.getLogger().info("Migrating config from version " + version + " → " + CURRENT_VERSION);

        // v0 → v1: add all new keys with defaults (nothing to rename, just stamp the version)
        if (version < 1) {
            setIfAbsent("private.host_abandon_timeout_minutes", 5);
            setIfAbsent("private.stale_session_hours", 12);
            setIfAbsent("messages.locked_private", "&cThat arena is currently private and is not accepting joins.");
            setIfAbsent("messages.session_abandoned", "&cPrivate match ended: the host did not return in time.");
        }

        config.set("config-version", CURRENT_VERSION);
        try {
            config.save(configFile);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to save migrated config: " + e.getMessage());
        }
    }

    private void setIfAbsent(String path, Object value) {
        if (!config.isSet(path)) config.set(path, value);
    }
}
