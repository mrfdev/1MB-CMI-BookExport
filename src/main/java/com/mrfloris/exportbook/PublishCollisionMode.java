package com.mrfloris.exportbook;

import java.util.Locale;
import java.util.Optional;

/** Safe choices for an existing case-insensitive published filename. */
enum PublishCollisionMode {
    FAIL("fail"),
    UNIQUE("unique"),
    REPLACE_WITH_BACKUP("replace-with-backup");

    private final String key;

    PublishCollisionMode(String key) {
        this.key = key;
    }

    String key() {
        return key;
    }

    static Optional<PublishCollisionMode> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.equals("replace")) {
            return Optional.of(REPLACE_WITH_BACKUP);
        }
        for (PublishCollisionMode mode : values()) {
            if (mode.key.equals(normalized)) {
                return Optional.of(mode);
            }
        }
        return Optional.empty();
    }
}
