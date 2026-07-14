package com.mrfloris.exportbook;

import java.util.Locale;
import java.util.Optional;

/** Determines whether normal exports become drafts or publish directly. */
enum WorkflowMode {
    STAGED,
    DIRECT;

    static Optional<WorkflowMode> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(value.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    String key() {
        return name().toLowerCase(Locale.ROOT);
    }
}
