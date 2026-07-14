package com.mrfloris.exportbook;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** Build metadata generated from Gradle's single source of truth. */
record BuildInfo(
        String version,
        String buildNumber,
        String artifactFileName,
        String javaTarget,
        String paperTarget,
        String paperApiVersion,
        String docsUrl,
        String sourceUrl
) {
    private static final String CANONICAL_DOCS_URL =
            "https://docs.1moreblock.com/custom-server-plugins/bookexport/";

    static BuildInfo load(JavaPlugin plugin) {
        Properties properties = new Properties();
        try (InputStream input = plugin.getResource("build-info.properties")) {
            if (input != null) {
                properties.load(input);
            }
        } catch (IOException exception) {
            plugin.getLogger().warning("Unable to read build-info.properties: " + exception.getMessage());
        }

        return new BuildInfo(
                properties.getProperty("version", plugin.getPluginMeta().getVersion()),
                properties.getProperty("buildNumber", "unknown"),
                properties.getProperty("artifactFileName", "unknown"),
                properties.getProperty("javaTarget", "unknown"),
                properties.getProperty("paperTarget", "unknown"),
                properties.getProperty("paperApiVersion", "unknown"),
                properties.getProperty("docsUrl", CANONICAL_DOCS_URL),
                properties.getProperty("sourceUrl", plugin.getPluginMeta().getWebsite())
        );
    }
}
