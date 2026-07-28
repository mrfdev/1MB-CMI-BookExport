package com.mrfloris.exportbook;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PublicationTransactionCodecTest {
    private static final PublicationTransactionCodec CODEC = new PublicationTransactionCodec();
    private static final Instant NOW = Instant.parse("2026-07-17T10:02:03.456Z");
    private static final UUID TRANSACTION_ID = UUID.fromString(
            "11111111-2222-3333-4444-555555555555"
    );
    private static final UUID DRAFT_ID = UUID.fromString(
            "66666666-7777-8888-9999-aaaaaaaaaaaa"
    );
    private static final ContentFingerprint APPROVED = new ContentFingerprint(42L, "a".repeat(64));
    private static final ContentFingerprint REPLACED = new ContentFingerprint(17L, "b".repeat(64));

    @TempDir
    Path temporaryDirectory;

    @Test
    void deterministicStrictCodecRoundTripsCompleteContentFreeMetadata() throws Exception {
        PublicationTransaction transaction = replacementTransaction();

        byte[] first = CODEC.encode(transaction);
        byte[] second = CODEC.encode(transaction);
        String text = new String(first, StandardCharsets.UTF_8);

        assertArrayEquals(first, second);
        assertEquals(transaction, CODEC.decode(first));
        assertTrue(text.startsWith("schema-version=1\ntransaction-id="));
        assertTrue(text.contains("published-by-name=Admin\\=One\\nConsole\n"));
        assertTrue(text.contains("state=prepared\n"));
        assertTrue(text.contains("backup-filename=backup_rules.txt\n"));
        assertTrue(text.endsWith("workflow-roots-sha256=" + "c".repeat(64) + "\n"));
        assertFalse(text.contains("TOP_SECRET_BOOK_PAGE_7F91"));
        assertFalse(text.contains(temporaryDirectory.toString()));
    }

    @Test
    void rejectsUnknownDuplicateMissingFutureAndNonCanonicalFields() throws Exception {
        String valid = encodedText(failTransaction());

        assertThrows(IOException.class, () -> decodeText(valid + "future=value\n"));
        assertThrows(IOException.class, () -> decodeText("schema-version=1\n" + valid));
        assertThrows(IOException.class, () -> decodeText(valid.replace("revision=1\n", "")));
        assertThrows(IOException.class, () -> decodeText(
                valid.replace("schema-version=1", "schema-version=2")
        ));
        assertThrows(IOException.class, () -> decodeText(
                valid.replace("revision=1", "revision=01")
        ));
        assertThrows(IOException.class, () -> decodeText(
                valid.replace("state=prepared", "state=PREPARED")
        ));
        assertThrows(IOException.class, () -> decodeText(
                valid.replace("collision-mode=fail", "collision-mode=replace")
        ));
        assertThrows(IOException.class, () -> decodeText(
                valid.replace("transaction-id=" + TRANSACTION_ID,
                        "transaction-id=1-2-3-4-5")
        ));
    }

    @Test
    void rejectsMalformedUtf8OversizeDirectoryAndSymbolicLink() throws Exception {
        assertThrows(IOException.class, () -> CODEC.decode(null));
        assertThrows(IOException.class, () -> CODEC.decode(
                new byte[PublicationTransactionCodec.MAXIMUM_ENCODED_BYTES + 1]
        ));
        assertThrows(IOException.class, () -> CODEC.decode(new byte[]{(byte) 0xc3, (byte) 0x28}));
        assertThrows(IOException.class, () -> CODEC.read(temporaryDirectory));

        Path target = temporaryDirectory.resolve("transaction.properties");
        Files.write(target, CODEC.encode(failTransaction()));
        Path link = temporaryDirectory.resolve("transaction-link.properties");
        try {
            Files.createSymbolicLink(link, target.toAbsolutePath());
        } catch (UnsupportedOperationException | IOException exception) {
            assumeTrue(false, "Symbolic links are unavailable: " + exception.getMessage());
        }
        assertThrows(IOException.class, () -> CODEC.read(link));
    }

    @Test
    void malformedUntrustedValuesNeverAppearInFailureChain() throws Exception {
        String sentinel = "TOP_SECRET_BOOK_PAGE_7F91";
        String valid = encodedText(failTransaction());
        IOException numeric = assertThrows(IOException.class, () -> decodeText(
                valid.replace("revision=1", "revision=" + sentinel)
        ));
        IOException filename = assertThrows(IOException.class, () -> decodeText(
                valid.replace("intended-filename=rules.txt", "intended-filename=" + sentinel)
        ));
        IOException unknown = assertThrows(IOException.class, () -> decodeText(
                valid + sentinel + "=value\n"
        ));

        assertThrowableDoesNotContain(numeric, sentinel);
        assertThrowableDoesNotContain(filename, sentinel);
        assertThrowableDoesNotContain(unknown, sentinel);
    }

    @Test
    void basenameAndReplacementInvariantsFailClosed() {
        PublicationTransaction valid = replacementTransaction();

        assertThrows(IllegalArgumentException.class, () -> copy(
                valid,
                PublishCollisionMode.REPLACE_WITH_BACKUP,
                "../rules.txt",
                "backup_rules.txt",
                REPLACED
        ));
        assertThrows(IllegalArgumentException.class, () -> copy(
                valid,
                PublishCollisionMode.REPLACE_WITH_BACKUP,
                "rules.txt",
                null,
                null
        ));
        assertThrows(IllegalArgumentException.class, () -> copy(
                valid,
                PublishCollisionMode.FAIL,
                "rules.txt",
                "backup_rules.txt",
                REPLACED
        ));
    }

    @Test
    void stateTransitionsAreExactAndModeAware() {
        PublicationTransaction fail = failTransaction();
        PublicationTransaction live = fail.withState(
                PublicationTransactionState.LIVE_COMMITTED,
                NOW.plusSeconds(1)
        );
        assertEquals(2L, live.revision());
        assertEquals(PublicationTransactionState.LIVE_COMMITTED, live.state());
        assertThrows(IllegalStateException.class, () -> fail.withState(
                PublicationTransactionState.BACKUP_CREATED,
                NOW.plusSeconds(1)
        ));

        PublicationTransaction replacement = replacementTransaction();
        PublicationTransaction backedUp = replacement.withState(
                PublicationTransactionState.BACKUP_CREATED,
                NOW.plusSeconds(1)
        );
        assertEquals(PublicationTransactionState.BACKUP_CREATED, backedUp.state());
        assertThrows(IllegalStateException.class, () -> replacement.withState(
                PublicationTransactionState.LIVE_COMMITTED,
                NOW.plusSeconds(1)
        ));
        assertThrows(IllegalArgumentException.class, () -> backedUp.withState(
                PublicationTransactionState.LIVE_COMMITTED,
                NOW.minusSeconds(1)
        ));
    }

    @Test
    void workflowRootHashIsDeterministicOrderedAndContentFree() {
        Path first = temporaryDirectory.resolve("staging");
        Path second = temporaryDirectory.resolve("published");

        String sameA = PublicationTransaction.workflowRootsSha256(first, second);
        String sameB = PublicationTransaction.workflowRootsSha256(first, second);
        String reversed = PublicationTransaction.workflowRootsSha256(second, first);

        assertEquals(sameA, sameB);
        assertNotEquals(sameA, reversed);
        assertTrue(sameA.matches("[0-9a-f]{64}"));
        assertFalse(sameA.contains(first.toString()));
        assertThrows(
                IllegalArgumentException.class,
                () -> PublicationTransaction.workflowRootsSha256(new Path[0])
        );
    }

    private static PublicationTransaction failTransaction() {
        return PublicationTransaction.prepared(
                TRANSACTION_ID,
                NOW,
                DRAFT_ID,
                3L,
                new DraftManifest.Actor(
                        "Admin=One\nConsole",
                        UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
                ),
                PublishCollisionMode.FAIL,
                "rules.txt",
                "rules_1.txt",
                "rules.txt",
                "published_rules.txt",
                null,
                APPROVED,
                null,
                "c".repeat(64)
        );
    }

    private static PublicationTransaction replacementTransaction() {
        PublicationTransaction fail = failTransaction();
        return copy(
                fail,
                PublishCollisionMode.REPLACE_WITH_BACKUP,
                fail.intendedFilename(),
                "backup_rules.txt",
                REPLACED
        );
    }

    private static PublicationTransaction copy(
            PublicationTransaction source,
            PublishCollisionMode mode,
            String intendedFilename,
            String backupFilename,
            ContentFingerprint replaced
    ) {
        return PublicationTransaction.prepared(
                source.transactionId(),
                source.createdAt(),
                source.draftId(),
                source.approvedManifestRevision(),
                source.publisher(),
                mode,
                intendedFilename,
                source.stagedFilename(),
                source.publishedFilename(),
                source.archiveFilename(),
                backupFilename,
                source.approvedFingerprint(),
                replaced,
                source.workflowRootsSha256()
        );
    }

    private static String encodedText(PublicationTransaction transaction) throws IOException {
        return new String(CODEC.encode(transaction), StandardCharsets.UTF_8);
    }

    private static PublicationTransaction decodeText(String text) throws IOException {
        return CODEC.decode(text.getBytes(StandardCharsets.UTF_8));
    }

    private static void assertThrowableDoesNotContain(Throwable failure, String sentinel) {
        Throwable current = failure;
        while (current != null) {
            assertFalse(current.toString().contains(sentinel));
            current = current.getCause();
        }
    }
}
