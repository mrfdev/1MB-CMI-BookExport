package com.mrfloris.exportbook;

import java.util.Locale;
import java.util.Optional;

/** How a managed draft entered the manifest workflow. */
enum DraftOrigin {
    NATIVE("native"),
    LEGACY_ADOPTED("legacy-adopted");

    private final String key;

    DraftOrigin(String key) {
        this.key = key;
    }

    String key() {
        return key;
    }

    static Optional<DraftOrigin> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (DraftOrigin origin : values()) {
            if (origin.key.equals(normalized)) {
                return Optional.of(origin);
            }
        }
        return Optional.empty();
    }
}
