package com.mrfloris.exportbook;

import java.util.Locale;
import java.util.Optional;

/** Human-review decision attached to the exact recorded draft bytes. */
enum DraftReviewStatus {
    UNREVIEWED("unreviewed"),
    APPROVED("approved"),
    CHANGES_REQUESTED("changes-requested");

    private final String key;

    DraftReviewStatus(String key) {
        this.key = key;
    }

    String key() {
        return key;
    }

    static Optional<DraftReviewStatus> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (DraftReviewStatus status : values()) {
            if (status.key.equals(normalized)) {
                return Optional.of(status);
            }
        }
        return Optional.empty();
    }
}
