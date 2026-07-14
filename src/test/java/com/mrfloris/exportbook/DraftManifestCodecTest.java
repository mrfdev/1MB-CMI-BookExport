package com.mrfloris.exportbook;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class DraftManifestCodecTest {
    private static final ContentFingerprint CONTENT = new ContentFingerprint(42L, "a".repeat(64));
    private static final ContentFingerprint BACKUP = new ContentFingerprint(17L, "b".repeat(64));
    private static final Instant CREATED_AT = Instant.parse("2026-07-14T10:02:03.456Z");
    private static final DraftManifestCodec CODEC = new DraftManifestCodec();

    @TempDir
    Path temporaryDirectory;

    @Test
    void deterministicUtf8CodecRoundTripsUnicodeEscapesAndCompleteMetadata() throws Exception {
        DraftManifest manifest = publishedManifest();

        byte[] first = CODEC.encode(manifest);
        byte[] second = CODEC.encode(manifest);
        DraftManifest decoded = CODEC.decode(first);
        String text = new String(first, StandardCharsets.UTF_8);

        assertArrayEquals(first, second);
        assertEquals(manifest, decoded);
        assertTrue(text.startsWith("schema-version=1\ndraft-id="));
        assertTrue(text.contains("created-by-name=Creator\\=Admin\\nLine Two\n"));
        assertTrue(text.contains("book-author=作者 📘\\=trusted\n"));
        assertTrue(text.endsWith("backup-sha256=" + "b".repeat(64) + "\n"));
        assertFalse(text.contains("#"));
    }

    @Test
    void readRejectsDirectoryAndSymbolicLink() throws Exception {
        assertThrows(IOException.class, () -> CODEC.read(temporaryDirectory));

        Path target = temporaryDirectory.resolve("manifest.meta");
        Files.write(target, CODEC.encode(nativeManifest()));
        Path link = temporaryDirectory.resolve("manifest-link.meta");
        try {
            Files.createSymbolicLink(link, target.toAbsolutePath());
        } catch (UnsupportedOperationException | IOException exception) {
            assumeTrue(false, "Symbolic links are unavailable: " + exception.getMessage());
        }
        assertThrows(IOException.class, () -> CODEC.read(link));
    }

    @Test
    void rejectsUnknownDuplicateMissingAndBlankMetadataLines() throws Exception {
        String valid = encodedText(nativeManifest());

        assertThrows(IOException.class, () -> decodeText(valid + "unknown-field=value\n"));
        assertThrows(IOException.class, () -> decodeText("schema-version=1\n" + valid));
        assertThrows(IOException.class, () -> decodeText(valid.replace("revision=1\n", "")));
        assertThrows(IOException.class, () -> decodeText(valid + "\n"));
        assertThrows(IOException.class, () -> decodeText(valid.replace("revision=1", "revision")));
    }

    @Test
    void rejectsFutureSchemaMalformedTypedFieldsAndUnsupportedEscapes() throws Exception {
        String valid = encodedText(nativeManifest());

        assertThrows(IOException.class, () -> decodeText(valid.replace("schema-version=1", "schema-version=2")));
        assertThrows(IOException.class, () -> decodeText(valid.replace("revision=1", "revision=not-a-number")));
        assertThrows(IOException.class, () -> decodeText(valid.replace("review-status=unreviewed",
                "review-status=future-status")));
        assertThrows(IOException.class, () -> decodeText(valid.replaceFirst(
                "created-at=[^\\n]*",
                "created-at=yesterday"
        )));
        assertThrows(IOException.class, () -> decodeText(valid.replace("book-author=作者 📘\\=trusted",
                "book-author=bad\\qescape")));
        assertThrows(IOException.class, () -> decodeText(valid.replace("book-author=作者 📘\\=trusted",
                "book-author=bad\\u12xz")));
    }

    @Test
    void rejectsNullOversizeAndMalformedUtf8() {
        assertThrows(IOException.class, () -> CODEC.decode(null));
        assertThrows(
                IOException.class,
                () -> CODEC.decode(new byte[DraftManifestCodec.MAXIMUM_ENCODED_BYTES + 1])
        );
        assertThrows(IOException.class, () -> CODEC.decode(new byte[]{(byte) 0xc3, (byte) 0x28}));
    }

    @Test
    void malformedValueIsNotEchoedIntoFailureMessages() throws Exception {
        String sentinel = "TOP_SECRET_BOOK_PAGE_7F91";
        String malformed = encodedText(nativeManifest()).replace("revision=1", "revision=" + sentinel);

        IOException failure = assertThrows(IOException.class, () -> decodeText(malformed));

        assertFalse(failure.toString().contains(sentinel));
        assertFalse(failure.getCause().toString().contains(sentinel));
    }

    @Test
    void unknownFieldAndMalformedNumericValuesNeverEchoUntrustedMetadata() throws Exception {
        String sentinel = "TOP_SECRET_BOOK_PAGE_7F91";
        String valid = encodedText(nativeManifest());
        List<String> malformed = List.of(
                valid + sentinel + "=value\n",
                valid.replace("schema-version=1", "schema-version=" + sentinel),
                valid.replace("source-pages=4", "source-pages=" + sentinel),
                valid.replace("source-utf16-units=98", "source-utf16-units=" + sentinel)
        );

        for (String value : malformed) {
            IOException failure = assertThrows(IOException.class, () -> decodeText(value));
            assertThrowableDoesNotContain(failure, sentinel);
        }
    }

    @Test
    void escapedUnpairedSurrogateMetadataIsRejected() throws Exception {
        String valid = encodedText(nativeManifest());
        String malformed = valid.replace(
                "book-author=作者 📘\\=trusted",
                "book-author=bad\\ud800"
        );

        assertThrows(IOException.class, () -> decodeText(malformed));
    }

    private static DraftManifest nativeManifest() {
        return DraftManifest.nativeDraft(
                UUID.fromString("11111111-2222-3333-4444-555555555555"),
                CREATED_AT,
                new DraftManifest.Actor(
                        "Creator=Admin\nLine Two",
                        UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
                ),
                "作者 📘=trusted",
                4,
                98,
                CONTENT,
                "rules.txt",
                "rules_1.txt"
        );
    }

    private static void assertThrowableDoesNotContain(Throwable failure, String sentinel) {
        Throwable current = failure;
        while (current != null) {
            assertFalse(current.toString().contains(sentinel));
            current = current.getCause();
        }
    }

    private static DraftManifest publishedManifest() {
        DraftManifest nativeManifest = nativeManifest();
        DraftManifest.Actor reviewer = new DraftManifest.Actor(
                "Reviewer",
                UUID.fromString("12345678-1234-5678-9abc-def012345678")
        );
        DraftManifest approved = nativeManifest.withReview(
                DraftReviewStatus.APPROVED,
                new DraftManifest.ReviewDecision(CREATED_AT.plusSeconds(60), reviewer, CONTENT, false)
        );
        return approved.withPublication(
                DraftPublicationStatus.PUBLISHED,
                new DraftManifest.Publication(
                        CREATED_AT.plusSeconds(120),
                        reviewer,
                        PublishCollisionMode.REPLACE_WITH_BACKUP,
                        "rules.txt",
                        "20260714_published_rules.txt",
                        "20260714_backup_rules.txt",
                        BACKUP
                )
        );
    }

    private static String encodedText(DraftManifest manifest) throws IOException {
        return new String(CODEC.encode(manifest), StandardCharsets.UTF_8);
    }

    private static DraftManifest decodeText(String text) throws IOException {
        return CODEC.decode(text.getBytes(StandardCharsets.UTF_8));
    }
}
