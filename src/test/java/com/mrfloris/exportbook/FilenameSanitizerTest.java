package com.mrfloris.exportbook;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FilenameSanitizerTest {
    @Test
    void keepsUnicodeButRemovesPathAndFilesystemPunctuation() {
        assertEquals(
                "café_你好_test",
                FilenameSanitizer.sanitize("Café / 你好:* Test", 96, true)
        );
    }

    @Test
    void preventsTraversalAndReservedDeviceNames() {
        assertEquals("secret", FilenameSanitizer.sanitize("../../secret", 96, false));
        assertEquals("_con", FilenameSanitizer.sanitize("CON", 96, true));
    }

    @Test
    void boundsByUnicodeCodePoints() {
        String result = FilenameSanitizer.sanitize("abcdefghij", 6, false);
        assertEquals("abcdef", result);
        assertTrue(result.codePointCount(0, result.length()) <= 6);
    }

    @Test
    void reservesConfiguredLengthForCollisionSuffix() {
        String result = FilenameSanitizer.appendCollisionSuffix("abcdefghij", "_12", 8);

        assertEquals("abcde_12", result);
        assertTrue(result.codePointCount(0, result.length()) <= 8);
    }

    @Test
    void collisionBoundingKeepsReservedNamePrefix() {
        String base = FilenameSanitizer.sanitize("CON", 96, true);

        assertEquals("_con", FilenameSanitizer.appendCollisionSuffix(base, "", 96));
        assertEquals("_con_1", FilenameSanitizer.appendCollisionSuffix(base, "_1", 96));
    }

    @Test
    void keepsUtf8FilenameUnderCommonFilesystemLimit() {
        String result = FilenameSanitizer.sanitize("界".repeat(160), 160, false);

        assertTrue((result + ".txt").getBytes(StandardCharsets.UTF_8).length <= 255);
    }
}
