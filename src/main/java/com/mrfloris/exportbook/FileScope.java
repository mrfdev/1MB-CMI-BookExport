package com.mrfloris.exportbook;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

/** Named workflow directories exposed by scoped list commands. */
enum FileScope {
    PUBLISHED("published"),
    STAGED("staged"),
    ARCHIVE("archive"),
    BACKUPS("backups");

    private final String key;

    FileScope(String key) {
        this.key = key;
    }

    String key() {
        return key;
    }

    String displayName() {
        return switch (this) {
            case PUBLISHED -> "published files";
            case STAGED -> "staged drafts";
            case ARCHIVE -> "archived drafts";
            case BACKUPS -> "replacement backups";
        };
    }

    Path directory(ExportSettings settings) {
        return switch (this) {
            case PUBLISHED -> settings.publishedDirectory();
            case STAGED -> settings.stagingDirectory();
            case ARCHIVE -> settings.archiveDirectory();
            case BACKUPS -> settings.backupDirectory();
        };
    }

    static Optional<FileScope> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (FileScope scope : values()) {
            if (scope.key.equals(normalized)) {
                return Optional.of(scope);
            }
        }
        return Optional.empty();
    }
}
