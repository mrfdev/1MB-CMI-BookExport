package com.mrfloris.exportbook;

import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;

/** Paper lifecycle entry point for BookExport. */
public final class ExportBookPlugin extends JavaPlugin {
    private BuildInfo buildInfo;
    private ExportSettings settings;
    private BookExporter exporter;
    private volatile String lastFailure = "none";

    /** Constructor used by Paper's plugin loader. */
    public ExportBookPlugin() {
        super();
    }

    @Override
    public void onEnable() {
        buildInfo = BuildInfo.load(this);
        saveDefaultConfig();

        try {
            settings = loadValidatedSettings();
        } catch (IOException | IllegalArgumentException exception) {
            getLogger().log(Level.SEVERE, "BookExport configuration is invalid; disabling the plugin.", exception);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        exporter = new BookExporter(this);
        BookExportCommand commandHandler = new BookExportCommand(this, exporter);
        PluginCommand command = Objects.requireNonNull(
                getCommand("bookexport"),
                "bookexport command is missing from plugin.yml"
        );
        command.setExecutor(commandHandler);
        command.setTabCompleter(commandHandler);

        getLogger().info(() -> "Enabled BookExport " + buildInfo.version()
                + " (Java " + buildInfo.javaTarget()
                + ", Paper " + buildInfo.paperTarget()
                + ", output=" + settings.exportDirectory() + ")");
    }

    @Override
    public void onDisable() {
        getLogger().info("BookExport disabled.");
    }

    void reloadRuntimeSettings() throws IOException {
        try {
            ExportSettings candidate = loadValidatedSettings();
            settings = candidate;
            clearLastFailure();
        } catch (IOException | IllegalArgumentException exception) {
            recordFailure("Configuration reload failed: " + exception.getMessage());
            if (exception instanceof IOException ioException) {
                throw ioException;
            }
            throw new IOException(exception.getMessage(), exception);
        }
    }

    ExportSettings settings() {
        return settings;
    }

    BuildInfo buildInfo() {
        return buildInfo;
    }

    Optional<String> pluginVersion(String pluginName) {
        Plugin runtimePlugin = getServer().getPluginManager().getPlugin(pluginName);
        if (runtimePlugin == null) {
            return Optional.empty();
        }
        return Optional.of(runtimePlugin.getPluginMeta().getVersion());
    }

    String lastFailure() {
        return lastFailure;
    }

    void recordFailure(String failure) {
        lastFailure = failure == null || failure.isBlank() ? "unknown failure" : failure;
    }

    void clearLastFailure() {
        lastFailure = "none";
    }

    private ExportSettings loadValidatedSettings() throws IOException {
        File configFile = new File(getDataFolder(), "config.yml");
        YamlConfiguration candidate = new YamlConfiguration();
        try {
            candidate.load(configFile);
        } catch (InvalidConfigurationException exception) {
            throw new IOException("config.yml contains invalid YAML.", exception);
        }

        try (InputStream input = getResource("config.yml")) {
            if (input == null) {
                throw new IOException("The packaged config.yml resource is missing.");
            }
            try (Reader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
                candidate.setDefaults(YamlConfiguration.loadConfiguration(reader));
            }
        }

        return ExportSettings.load(this, candidate);
    }
}
