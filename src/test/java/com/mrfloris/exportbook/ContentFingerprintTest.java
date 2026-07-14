package com.mrfloris.exportbook;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ContentFingerprintTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void fingerprintsKnownSha256VectorAndExactBytes() throws Exception {
        Path file = Files.writeString(
                temporaryDirectory.resolve("abc.txt"),
                "abc",
                StandardCharsets.UTF_8
        );

        ContentFingerprint fingerprint = ContentFingerprint.from(file);

        assertEquals(3L, fingerprint.utf8Bytes());
        assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                fingerprint.sha256()
        );
        assertTrue(fingerprint.matches(file));
    }

    @Test
    void countsUtf8BytesAndDetectsSameLengthMutation() throws Exception {
        Path file = temporaryDirectory.resolve("unicode.txt");
        String original = "Café 你好 📘";
        Files.writeString(file, original, StandardCharsets.UTF_8);
        ContentFingerprint fingerprint = ContentFingerprint.from(file);

        assertEquals(original.getBytes(StandardCharsets.UTF_8).length, fingerprint.utf8Bytes());

        byte[] changed = Files.readAllBytes(file);
        changed[0] ^= 1;
        Files.write(file, changed);

        assertEquals(fingerprint.utf8Bytes(), Files.size(file));
        assertFalse(fingerprint.matches(file));
    }

    @Test
    void rejectsInvalidStoredFingerprints() {
        assertThrows(IllegalArgumentException.class, () -> new ContentFingerprint(-1L, "0".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> new ContentFingerprint(0L, "0".repeat(63)));
        assertThrows(IllegalArgumentException.class, () -> new ContentFingerprint(0L, "A".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> new ContentFingerprint(0L, null));
    }

    @Test
    void rejectsDirectoryAndSymbolicLinkWithoutFollowingIt() throws Exception {
        assertThrows(IOException.class, () -> ContentFingerprint.from(temporaryDirectory));

        Path target = Files.writeString(
                temporaryDirectory.resolve("target.txt"),
                "external secret",
                StandardCharsets.UTF_8
        );
        Path link = temporaryDirectory.resolve("link.txt");
        try {
            Files.createSymbolicLink(link, target.toAbsolutePath());
        } catch (UnsupportedOperationException | IOException exception) {
            assumeTrue(false, "Symbolic links are unavailable: " + exception.getMessage());
        }

        IOException failure = assertThrows(IOException.class, () -> ContentFingerprint.from(link));
        assertTrue(failure.getMessage().contains("link.txt"));
        assertFalse(failure.getMessage().contains("external secret"));
    }
}
