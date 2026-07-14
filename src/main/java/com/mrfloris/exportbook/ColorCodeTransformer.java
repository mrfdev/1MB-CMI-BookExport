package com.mrfloris.exportbook;

import java.util.Locale;

/** Converts validated Minecraft section-sign formatting into export formats. */
final class ColorCodeTransformer {
    private static final char SECTION = '\u00A7';
    private static final String HEX_DIGITS = "0123456789abcdef";

    private ColorCodeTransformer() {
    }

    static String transform(String input, ColorMode mode) {
        if (input == null || input.isEmpty() || mode == ColorMode.VANILLA || input.indexOf(SECTION) < 0) {
            return input;
        }

        StringBuilder output = new StringBuilder(input.length() + 16);
        for (int index = 0; index < input.length(); index++) {
            char current = input.charAt(index);
            if (current != SECTION || index + 1 >= input.length()) {
                output.append(current);
                continue;
            }

            char code = Character.toLowerCase(input.charAt(index + 1));
            String unusualHex = readUnusualHex(input, index);
            if (unusualHex != null) {
                appendHex(output, unusualHex, mode);
                index += 13;
                continue;
            }

            String compactHex = readCompactHex(input, index);
            if (compactHex != null) {
                appendHex(output, compactHex, mode);
                index += 7;
                continue;
            }

            if (code == 'x') {
                int malformedEnd = malformedUnusualHexEnd(input, index);
                output.append(input, index, malformedEnd);
                index = malformedEnd - 1;
                continue;
            }

            if (isLegacyColor(code)) {
                if (mode == ColorMode.LEGACY) {
                    output.append('&').append(code);
                } else {
                    appendHex(output, legacyColorToHex(code), mode);
                }
                index++;
                continue;
            }

            if (isDecoration(code)) {
                appendDecoration(output, code, mode);
                index++;
                continue;
            }

            // A literal or malformed section-sign sequence is data, not a color code.
            output.append(current);
        }
        return output.toString();
    }

    private static int malformedUnusualHexEnd(String input, int start) {
        int end = Math.min(input.length(), start + 2);
        for (int pair = 0; pair < 6 && end + 1 < input.length(); pair++) {
            if (input.charAt(end) != SECTION) {
                break;
            }
            end += 2;
        }
        return end;
    }

    private static String readUnusualHex(String input, int start) {
        if (start + 13 >= input.length() || Character.toLowerCase(input.charAt(start + 1)) != 'x') {
            return null;
        }

        StringBuilder hex = new StringBuilder(6);
        for (int offset = 2; offset <= 12; offset += 2) {
            if (input.charAt(start + offset) != SECTION) {
                return null;
            }
            char digit = Character.toLowerCase(input.charAt(start + offset + 1));
            if (HEX_DIGITS.indexOf(digit) < 0) {
                return null;
            }
            hex.append(digit);
        }
        return hex.toString().toUpperCase(Locale.ROOT);
    }

    private static String readCompactHex(String input, int start) {
        if (start + 7 >= input.length() || input.charAt(start + 1) != '#') {
            return null;
        }

        String hex = input.substring(start + 2, start + 8);
        for (int index = 0; index < hex.length(); index++) {
            if (HEX_DIGITS.indexOf(Character.toLowerCase(hex.charAt(index))) < 0) {
                return null;
            }
        }
        return hex.toUpperCase(Locale.ROOT);
    }

    private static void appendHex(StringBuilder output, String hex, ColorMode mode) {
        switch (mode) {
            case STRIP -> {
                // Intentionally omit color codes.
            }
            case LEGACY -> appendLegacyHex(output, hex);
            case CMI -> output.append("{#").append(hex).append('}');
            case MINI -> output.append("<#").append(hex).append('>');
            case VANILLA -> throw new IllegalStateException("VANILLA is returned before conversion");
        }
    }

    private static void appendLegacyHex(StringBuilder output, String hex) {
        output.append("&x");
        for (int index = 0; index < hex.length(); index++) {
            output.append('&').append(hex.charAt(index));
        }
    }

    private static void appendDecoration(StringBuilder output, char code, ColorMode mode) {
        switch (mode) {
            case STRIP -> {
                // Intentionally omit decorations and reset.
            }
            case LEGACY, CMI -> output.append('&').append(code);
            case MINI -> output.append(miniTag(code));
            case VANILLA -> throw new IllegalStateException("VANILLA is returned before conversion");
        }
    }

    private static String miniTag(char code) {
        return switch (code) {
            case 'k' -> "<obfuscated>";
            case 'l' -> "<bold>";
            case 'm' -> "<strikethrough>";
            case 'n' -> "<underlined>";
            case 'o' -> "<italic>";
            case 'r' -> "<reset>";
            default -> throw new IllegalArgumentException("Unknown decoration code: " + code);
        };
    }

    private static boolean isLegacyColor(char code) {
        return (code >= '0' && code <= '9') || (code >= 'a' && code <= 'f');
    }

    private static boolean isDecoration(char code) {
        return code == 'k' || code == 'l' || code == 'm' || code == 'n' || code == 'o' || code == 'r';
    }

    private static String legacyColorToHex(char code) {
        return switch (code) {
            case '0' -> "000000";
            case '1' -> "0000AA";
            case '2' -> "00AA00";
            case '3' -> "00AAAA";
            case '4' -> "AA0000";
            case '5' -> "AA00AA";
            case '6' -> "FFAA00";
            case '7' -> "AAAAAA";
            case '8' -> "555555";
            case '9' -> "5555FF";
            case 'a' -> "55FF55";
            case 'b' -> "55FFFF";
            case 'c' -> "FF5555";
            case 'd' -> "FF55FF";
            case 'e' -> "FFFF55";
            case 'f' -> "FFFFFF";
            default -> throw new IllegalArgumentException("Unknown legacy color code: " + code);
        };
    }
}
