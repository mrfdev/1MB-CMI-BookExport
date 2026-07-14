package com.mrfloris.exportbook;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;

/** Immutable, validated runtime configuration. */
record ExportSettings(
        int configVersion,
        boolean directCompatibilityMode,
        WorkflowMode workflowMode,
        Path stagingDirectory,
        Path publishedDirectory,
        Path archiveDirectory,
        Path backupDirectory,
        PublishCollisionMode publishCollisionMode,
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
    static final int SUPPORTED_CONFIG_VERSION = 3;
    private static final int MINIMUM_FILENAME_LENGTH = 16;
    private static final int MAXIMUM_FILENAME_LENGTH = 160;
    private static final int MAXIMUM_LIST_PAGE_SIZE = 50;

    static ExportSettings load(
            JavaPlugin plugin,
            FileConfiguration config,
            int rawConfigVersion
    ) throws IOException {
        Logger logger = plugin.getLogger();
        if (rawConfigVersion > SUPPORTED_CONFIG_VERSION) {
            throw new IllegalArgumentException("config.yml uses unsupported future config-version "
                    + rawConfigVersion + "; this build supports up to " + SUPPORTED_CONFIG_VERSION + '.');
        }
        if (rawConfigVersion < 1) {
            throw new IllegalArgumentException("config-version must be a positive number.");
        }

        boolean compatibilityMode = rawConfigVersion < SUPPORTED_CONFIG_VERSION;
        WorkflowMode workflowMode;
        if (compatibilityMode) {
            workflowMode = WorkflowMode.DIRECT;
            logger.warning("Config version " + rawConfigVersion + " is loaded in direct compatibility mode. "
                    + "BookExport will not rewrite the file; migrate to config version 3 to stage by default.");
        } else {
            workflowMode = WorkflowMode.parse(config.getString("workflow-mode"))
                    .orElseThrow(() -> new IllegalArgumentException(
                            "workflow-mode must be staged or direct."
                    ));
        }

        PublishCollisionMode publishCollisionMode = PublishCollisionMode.parse(
                config.getString("publish-collision-mode")
        ).orElseThrow(() -> new IllegalArgumentException(
                "publish-collision-mode must be fail, unique, or replace-with-backup."
        ));

        Path stagingDirectory = validateDirectory(
                resolveDirectory(plugin, config.getString("staging-directory"), "staging"),
                "Staging"
        );
        Path publishedDirectory = validateDirectory(
                resolveDirectory(
                        plugin,
                        config.getString("exported-books-directory"),
                        "~/plugins/CMI/CustomText/"
                ),
                "Published"
        );
        Path archiveDirectory = validateDirectory(
                resolveDirectory(plugin, config.getString("archive-directory"), "archive"),
                "Archive"
        );
        Path backupDirectory = validateDirectory(
                resolveDirectory(plugin, config.getString("backup-directory"), "backups"),
                "Backup"
        );
        validateDistinctWorkflowDirectories(List.of(
                stagingDirectory,
                publishedDirectory,
                archiveDirectory,
                backupDirectory
        ));

        String filenameFormat = valueOrDefault(config.getString("filename-format"), "%title%");
        int maximumFilenameLength = clamp(
                config.getInt("maximum-filename-length", 96),
                MINIMUM_FILENAME_LENGTH,
                MAXIMUM_FILENAME_LENGTH
        );
        if (maximumFilenameLength != config.getInt("maximum-filename-length", 96)) {
            logger.warning("maximum-filename-length must be between 16 and 160; using "
                    + maximumFilenameLength + '.');
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
                rawConfigVersion,
                compatibilityMode,
                workflowMode,
                stagingDirectory,
                publishedDirectory,
                archiveDirectory,
                backupDirectory,
                publishCollisionMode,
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

    Path exportDestination(boolean forceStage) {
        return forceStage || workflowMode == WorkflowMode.STAGED ? stagingDirectory : publishedDirectory;
    }

    FileScope exportScope(boolean forceStage) {
        return forceStage || workflowMode == WorkflowMode.STAGED ? FileScope.STAGED : FileScope.PUBLISHED;
    }

    private static DirectoryResolution resolveDirectory(
            JavaPlugin plugin,
            String configuredValue,
            String fallback
    ) {
        Path dataDirectory = plugin.getDataFolder().toPath().toAbsolutePath().normalize();
        String value = valueOrDefault(configuredValue, fallback);

        if (value.startsWith("~/")) {
            Path pluginsDirectory = dataDirectory.getParent();
            Path serverRoot = pluginsDirectory == null ? null : pluginsDirectory.getParent();
            if (serverRoot == null) {
                throw new IllegalArgumentException("Unable to resolve the server root for: " + value);
            }
            Path resolved = serverRoot.resolve(value.substring(2)).normalize();
            if (!resolved.startsWith(serverRoot)) {
                throw new IllegalArgumentException("Server-relative workflow path leaves the server root: "
                        + value);
            }
            return new DirectoryResolution(resolved, serverRoot);
        }

        Path configuredPath = Path.of(value);
        if (configuredPath.isAbsolute()) {
            return new DirectoryResolution(configuredPath.normalize(), null);
        }

        Path resolved = dataDirectory.resolve(configuredPath).normalize();
        if (!resolved.startsWith(dataDirectory)) {
            throw new IllegalArgumentException("Relative workflow path leaves the BookExport data directory: "
                    + value);
        }
        return new DirectoryResolution(resolved, dataDirectory);
    }

    private static Path validateDirectory(DirectoryResolution resolution, String label) throws IOException {
        Path directory = resolution.path();
        if (resolution.containmentRoot() != null) {
            rejectSymbolicLinkComponents(resolution.containmentRoot(), directory, label);
        }
        Files.createDirectories(directory);
        if (Files.isSymbolicLink(directory)) {
            throw new IOException(label + " directory may not be a symbolic link: " + directory);
        }
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(label + " path is not a directory: " + directory);
        }

        Path probe = null;
        try {
            probe = Files.createTempFile(directory, ".bookexport-probe-", ".tmp");
        } finally {
            if (probe != null) {
                Files.deleteIfExists(probe);
            }
        }
        // Resolve any symbolic links in ancestor components before comparing the
        // workflow roots. The final component itself was rejected above when it
        // was a link, while canonical ancestors prevent alias paths from evading
        // the distinct/non-overlapping-directory check.
        Path realDirectory = directory.toRealPath().normalize();
        if (resolution.containmentRoot() != null) {
            Path realRoot = resolution.containmentRoot().toRealPath().normalize();
            if (!realDirectory.startsWith(realRoot)) {
                throw new IllegalArgumentException(label
                        + " relative path resolves outside the BookExport data directory: " + directory);
            }
        }
        return realDirectory;
    }

    private static void rejectSymbolicLinkComponents(Path root, Path directory, String label) throws IOException {
        Path current = root;
        for (Path component : root.relativize(directory)) {
            current = current.resolve(component);
            if (Files.isSymbolicLink(current)) {
                throw new IOException(label + " relative path may not pass through a symbolic link: " + current);
            }
        }
    }

    private static void validateDistinctWorkflowDirectories(List<Path> directories) {
        for (int leftIndex = 0; leftIndex < directories.size(); leftIndex++) {
            Path left = directories.get(leftIndex);
            for (int rightIndex = leftIndex + 1; rightIndex < directories.size(); rightIndex++) {
                Path right = directories.get(rightIndex);
                if (left.startsWith(right) || right.startsWith(left)) {
                    throw new IllegalArgumentException(
                            "Workflow directories must be distinct and may not contain each other: "
                                    + left + " and " + right
                    );
                }
            }
        }
    }

    private static String valueOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private record DirectoryResolution(Path path, Path containmentRoot) {
    }
}
