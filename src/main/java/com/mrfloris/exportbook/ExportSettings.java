package com.mrfloris.exportbook;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

/** Immutable, validated runtime configuration. */
record ExportSettings(
        Path exportDirectory,
        String filenameFormat,
        boolean lowercaseFilenames,
        int maximumFilenameLength,
        boolean pagination,
        String paginationMarkup,
        boolean paginationOnFirstPage,
        String cmiDocumentHeader,
        boolean includeBookMetadata,
        ColorMode colorMode,
        int listPageSize,
        boolean debugLogging
) {
    private static final int MINIMUM_FILENAME_LENGTH = 16;
    private static final int MAXIMUM_FILENAME_LENGTH = 160;
    private static final int MAXIMUM_LIST_PAGE_SIZE = 50;

    static ExportSettings load(JavaPlugin plugin, FileConfiguration config) throws IOException {
        Logger logger = plugin.getLogger();

        Path directory = resolveDirectory(plugin, config.getString("exported-books-directory", "books"));
        Files.createDirectories(directory);
        if (!Files.isDirectory(directory)) {
            throw new IOException("Export path is not a directory: " + directory);
        }
        if (!Files.isWritable(directory)) {
            throw new IOException("Export directory is not writable: " + directory);
        }

        String filenameFormat = valueOrDefault(config.getString("filename-format"), "%title%");
        int maximumFilenameLength = clamp(
                config.getInt("maximum-filename-length", 96),
                MINIMUM_FILENAME_LENGTH,
                MAXIMUM_FILENAME_LENGTH
        );
        if (maximumFilenameLength != config.getInt("maximum-filename-length", 96)) {
            logger.warning("maximum-filename-length must be between 16 and 160; using " + maximumFilenameLength + '.');
        }

        String paginationMarkup = valueOrDefault(
                config.getString("pagination-markup"),
                "<NextPage>"
        );
        ColorMode colorMode = ColorMode.parse(config.getString("color-code-handling"))
                .orElseGet(() -> {
                    logger.warning("Unknown color-code-handling value; using cmi.");
                    return ColorMode.CMI;
                });

        String cmiHeader = config.getString("cmi-document-header", "<AutoPage>");
        if (colorMode == ColorMode.CMI && (cmiHeader == null || cmiHeader.isBlank())) {
            logger.warning("cmi-document-header cannot be blank in cmi mode; using <AutoPage>.");
            cmiHeader = "<AutoPage>";
        }

        int configuredListPageSize = config.getInt("list-page-size", 10);
        int listPageSize = clamp(configuredListPageSize, 1, MAXIMUM_LIST_PAGE_SIZE);
        if (listPageSize != configuredListPageSize) {
            logger.warning("list-page-size must be between 1 and 50; using " + listPageSize + '.');
        }

        return new ExportSettings(
                directory,
                filenameFormat,
                config.getBoolean("lowercase-filenames", true),
                maximumFilenameLength,
                config.getBoolean("pagination", true),
                paginationMarkup,
                config.getBoolean("pagination-on-first-page", false),
                cmiHeader == null ? "" : cmiHeader.trim(),
                config.getBoolean("book-meta", false),
                colorMode,
                listPageSize,
                config.getBoolean("debug-logging", false)
        );
    }

    private static Path resolveDirectory(JavaPlugin plugin, String configuredValue) {
        Path dataDirectory = plugin.getDataFolder().toPath().toAbsolutePath().normalize();
        String value = valueOrDefault(configuredValue, "books");

        if (value.startsWith("~/")) {
            Path pluginsDirectory = dataDirectory.getParent();
            Path serverRoot = pluginsDirectory == null ? null : pluginsDirectory.getParent();
            if (serverRoot == null) {
                throw new IllegalArgumentException("Unable to resolve the server root for: " + value);
            }
            return serverRoot.resolve(value.substring(2)).normalize();
        }

        Path configuredPath = Path.of(value);
        if (configuredPath.isAbsolute()) {
            return configuredPath.normalize();
        }

        Path resolved = dataDirectory.resolve(configuredPath).normalize();
        if (!resolved.startsWith(dataDirectory)) {
            throw new IllegalArgumentException("Relative export path leaves the BookExport data directory: " + value);
        }
        return resolved;
    }

    private static String valueOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
