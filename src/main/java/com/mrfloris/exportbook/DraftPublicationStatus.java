package com.mrfloris.exportbook;

import java.util.Locale;
import java.util.Optional;

/** Durable publication outcome for a managed draft. */
enum DraftPublicationStatus {
    STAGED("staged"),
    PUBLISHED_ARCHIVE_PENDING("published-archive-pending"),
    PUBLISHED("published");

    private final String key;

    DraftPublicationStatus(String key) {
        this.key = key;
    }

    String key() {
        return key;
    }

    static Optional<DraftPublicationStatus> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (DraftPublicationStatus status : values()) {
            if (status.key.equals(normalized)) {
                return Optional.of(status);
            }
        }
        return Optional.empty();
    }
}
