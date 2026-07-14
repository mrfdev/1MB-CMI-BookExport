package com.mrfloris.exportbook;

import java.text.Normalizer;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/** Produces a bounded, path-safe, Unicode-aware filename component. */
final class FilenameSanitizer {
    // Reserve four bytes for the .txt extension under common 255-byte component limits.
    private static final int MAXIMUM_BASE_UTF8_BYTES = 251;
    private static final Set<String> WINDOWS_RESERVED = Set.of(
            "con", "prn", "aux", "nul",
            "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
            "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9"
    );

    private FilenameSanitizer() {
    }

    static String sanitize(String input, int maximumLength, boolean lowercase) {
        String normalized = Normalizer.normalize(input == null ? "" : input, Normalizer.Form.NFKC);
        StringBuilder safe = new StringBuilder(normalized.length());
        boolean previousSeparator = false;

        for (int offset = 0; offset < normalized.length();) {
            int codePoint = normalized.codePointAt(offset);
            offset += Character.charCount(codePoint);

            if (Character.isLetterOrDigit(codePoint) || codePoint == '-' || codePoint == '.') {
                safe.appendCodePoint(codePoint);
                previousSeparator = false;
            } else if (Character.isWhitespace(codePoint) || codePoint == '_') {
                if (!previousSeparator && !safe.isEmpty()) {
                    safe.append('_');
                    previousSeparator = true;
                }
            }
            // Path separators, control characters, and filesystem punctuation are omitted.
        }

        String result = trimUnsafeEdges(safe.toString());
        if (lowercase) {
            result = result.toLowerCase(Locale.ROOT);
        }
        result = truncateCodePoints(result, maximumLength);
        result = truncateUtf8Bytes(result, MAXIMUM_BASE_UTF8_BYTES);
        result = trimUnsafeEdges(result);

        String base = result.contains(".") ? result.substring(0, result.indexOf('.')) : result;
        if (WINDOWS_RESERVED.contains(base.toLowerCase(Locale.ROOT))) {
            result = '_' + result;
            result = truncateCodePoints(result, maximumLength);
            result = truncateUtf8Bytes(result, MAXIMUM_BASE_UTF8_BYTES);
        }
        return result;
    }

    static String appendCollisionSuffix(String baseName, String suffix, int maximumLength) {
        int suffixCodePoints = suffix.codePointCount(0, suffix.length());
        int suffixBytes = suffix.getBytes(StandardCharsets.UTF_8).length;
        int availableCodePoints = maximumLength - suffixCodePoints;
        int availableBytes = MAXIMUM_BASE_UTF8_BYTES - suffixBytes;
        if (availableCodePoints < 1 || availableBytes < 1) {
            throw new IllegalArgumentException("Filename limit is too small for collision suffix: " + suffix);
        }

        String boundedBase = truncateCodePoints(baseName, availableCodePoints);
        boundedBase = truncateUtf8Bytes(boundedBase, availableBytes);
        boundedBase = trimUnsafeEnd(boundedBase);
        if (boundedBase.isEmpty()) {
            throw new IllegalArgumentException("Filename became empty after reserving the collision suffix.");
        }
        return boundedBase + suffix;
    }

    private static String truncateCodePoints(String input, int maximumLength) {
        int count = input.codePointCount(0, input.length());
        if (count <= maximumLength) {
            return input;
        }
        int end = input.offsetByCodePoints(0, maximumLength);
        return input.substring(0, end);
    }

    private static String truncateUtf8Bytes(String input, int maximumBytes) {
        if (input.getBytes(StandardCharsets.UTF_8).length <= maximumBytes) {
            return input;
        }

        int bytes = 0;
        int end = 0;
        while (end < input.length()) {
            int codePoint = input.codePointAt(end);
            int codePointBytes = new String(Character.toChars(codePoint))
                    .getBytes(StandardCharsets.UTF_8).length;
            if (bytes + codePointBytes > maximumBytes) {
                break;
            }
            bytes += codePointBytes;
            end += Character.charCount(codePoint);
        }
        return input.substring(0, end);
    }

    private static String trimUnsafeEdges(String input) {
        int start = 0;
        int end = input.length();
        while (start < end && (input.charAt(start) == '.' || input.charAt(start) == '_')) {
            start++;
        }
        while (end > start && (input.charAt(end - 1) == '.' || input.charAt(end - 1) == '_')) {
            end--;
        }
        return input.substring(start, end);
    }

    private static String trimUnsafeEnd(String input) {
        int end = input.length();
        while (end > 0 && (input.charAt(end - 1) == '.' || input.charAt(end - 1) == '_')) {
            end--;
        }
        return input.substring(0, end);
    }
}
