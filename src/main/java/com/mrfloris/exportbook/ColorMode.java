package com.mrfloris.exportbook;

import java.util.Locale;
import java.util.Optional;

/** Output formats supported for Minecraft legacy color and decoration codes. */
enum ColorMode {
    VANILLA,
    LEGACY,
    STRIP,
    CMI,
    MINI;

    static Optional<ColorMode> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }

        try {
            return Optional.of(valueOf(value.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }
}
