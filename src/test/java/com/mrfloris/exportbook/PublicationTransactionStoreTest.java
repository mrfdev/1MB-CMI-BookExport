package com.mrfloris.exportbook;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PublicationTransactionStoreTest {
    private static final Instant CREATED = Instant.parse("2026-07-17T10:02:03.456Z");
    private static final Instant UPDATED = Instant.parse("2026-07-17T10:03:04.567Z");
    private static final UUID TRANSACTION_ID = UUID.fromString(
            "11111111-2222-3333-4444-555555555555"
    );

    @TempDir
    Path temporaryDirectory;

    @Test
    void durableLifecycleUsesCanonicalFilenameAndExactRevisions() throws Exception {
        Path directory = temporaryDirectory.resolve("transactions");
        PublicationTransactionStore store = store(directory);
        PublicationTransaction prepared = transaction(TRANSACTION_ID);

        store.initialize();
        PublicationTransaction created = store.create(prepared);
        assertEquals(prepared, created);
        assertEquals(
                TRANSACTION_ID + PublicationTransactionStore.JOURNAL_SUFFIX,
                store.pathFor(TRANSACTION_ID).getFileName().toString()
        );
        assertEquals(prepared, store.find(TRANSACTION_ID).orElseThrow());
        assertEquals(List.of(prepared), store.list());

        PublicationTransaction live = store.update(
                prepared,
                PublicationTransactionState.LIVE_COMMITTED
        );
        assertEquals(2L, live.revision());
        assertEquals(UPDATED, live.updatedAt());
        assertEquals(live, store.find(TRANSACTION_ID).orElseThrow());

        PublicationTransaction checkpointed = store.update(
                live,
                PublicationTransactionState.MANIFEST_CHECKPOINTED
        );
        assertEquals(3L, checkpointed.revision());
        store.delete(checkpointed);

        assertTrue(store.find(TRANSACTION_ID).isEmpty());
        assertTrue(store.list().isEmpty());
        assertNoTemporaryFiles(directory);
    }

    @Test
    void createNeverOverwritesExactOrCaseVariantJournal() throws Exception {
        Path directory = temporaryDirectory.resolve("create-cas");
        PublicationTransactionStore store = store(directory);
        PublicationTransaction prepared = transaction(TRANSACTION_ID);
        store.create(prepared);
        byte[] first = Files.readAllBytes(store.pathFor(TRANSACTION_ID));

        assertThrows(IOException.class, () -> store.create(prepared));
        assertEquals(List.of(prepared), store.list());
        assertTrue(java.util.Arrays.equals(first, Files.readAllBytes(store.pathFor(TRANSACTION_ID))));

        Path otherDirectory = temporaryDirectory.resolve("case-variant");
        PublicationTransactionStore other = store(otherDirectory);
        other.initialize();
        Path variant = other.pathFor(TRANSACTION_ID).resolveSibling(
                other.pathFor(TRANSACTION_ID).getFileName().toString().toUpperCase(
                        java.util.Locale.ROOT
                )
        );
        Files.writeString(variant, "reserved", StandardCharsets.US_ASCII);
        assertThrows(IOException.class, () -> other.create(prepared));
    }

    @Test
    void updateAndDeleteCompareExactBytesBeforeMutation() throws Exception {
        Path directory = temporaryDirectory.resolve("tamper-cas");
        PublicationTransactionStore store = store(directory);
        PublicationTransaction prepared = store.create(transaction(TRANSACTION_ID));
        Path journal = store.pathFor(TRANSACTION_ID);
        byte[] tampered = "untrusted mutation".getBytes(StandardCharsets.UTF_8);
        Files.write(journal, tampered);

        assertThrows(IOException.class, () -> store.update(
                prepared,
                PublicationTransactionState.LIVE_COMMITTED
        ));
        assertTrue(java.util.Arrays.equals(tampered, Files.readAllBytes(journal)));
        assertThrows(IOException.class, () -> store.delete(prepared));
        assertTrue(java.util.Arrays.equals(tampered, Files.readAllBytes(journal)));
    }

    @Test
    void concurrentStoresHaveOneCompareAndSwapWinner() throws Exception {
        Path directory = temporaryDirectory.resolve("concurrent-cas");
        PublicationTransactionStore first = store(directory);
        PublicationTransactionStore second = store(directory);
        PublicationTransaction prepared = first.create(transaction(TRANSACTION_ID));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> firstResult = executor.submit(() -> update(first, prepared, ready, start));
            Future<Boolean> secondResult = executor.submit(() -> update(second, prepared, ready, start));
            ready.await();
            start.countDown();

            long winners = Stream.of(firstResult.get(), secondResult.get())
                    .filter(Boolean::booleanValue)
                    .count();
            assertEquals(1L, winners);
            PublicationTransaction stored = first.find(TRANSACTION_ID).orElseThrow();
            assertEquals(PublicationTransactionState.LIVE_COMMITTED, stored.state());
            assertEquals(2L, stored.revision());
        } finally {
            executor.shutdownNow();
        }
        assertNoTemporaryFiles(directory);
    }

    @Test
    void scanPreservesStableFailuresWithoutParserMessages() throws Exception {
        Path directory = temporaryDirectory.resolve("scan-failures");
        PublicationTransactionStore store = store(directory);
        store.initialize();
        Path malformedName = directory.resolve(
                "NOT-A-UUID" + PublicationTransactionStore.JOURNAL_SUFFIX
        );
        Files.writeString(malformedName, "TOP_SECRET_BOOK_PAGE_7F91", StandardCharsets.UTF_8);

        Path corrupt = store.pathFor(TRANSACTION_ID);
        Files.writeString(corrupt, "TOP_SECRET_BOOK_PAGE_7F91", StandardCharsets.UTF_8);

        List<PublicationTransactionStore.JournalEntry> entries = store.scan();
        assertEquals(2, entries.size());
        assertEquals(
                List.of(
                        PublicationTransactionStore.JournalReadFailure.MALFORMED_FILENAME,
                        PublicationTransactionStore.JournalReadFailure.UNREADABLE_OR_MALFORMED
                ),
                entries.stream()
                        .map(PublicationTransactionStore.JournalEntry::failure)
                        .sorted()
                        .toList()
        );
        for (PublicationTransactionStore.JournalEntry entry : entries) {
            assertFalse(entry.readable());
            assertFalse(entry.toString().contains("TOP_SECRET_BOOK_PAGE_7F91"));
        }
        IOException failure = assertThrows(IOException.class, store::list);
        assertFalse(failure.toString().contains("TOP_SECRET_BOOK_PAGE_7F91"));
    }

    @Test
    void scanRejectsSymlinksAndIdMismatchWithoutFollowingOrLeaking() throws Exception {
        Path directory = temporaryDirectory.resolve("scan-association");
        PublicationTransactionStore store = store(directory);
        store.initialize();
        PublicationTransaction transaction = transaction(TRANSACTION_ID);

        UUID otherId = UUID.fromString("aaaaaaaa-2222-3333-4444-555555555555");
        Files.write(store.pathFor(otherId), new PublicationTransactionCodec().encode(transaction));
        assertEquals(
                PublicationTransactionStore.JournalReadFailure.TRANSACTION_ID_MISMATCH,
                store.scan().getFirst().failure()
        );

        Files.delete(store.pathFor(otherId));
        Path target = temporaryDirectory.resolve("outside.properties");
        Files.write(target, new PublicationTransactionCodec().encode(transaction));
        try {
            Files.createSymbolicLink(store.pathFor(TRANSACTION_ID), target.toAbsolutePath());
        } catch (UnsupportedOperationException | IOException exception) {
            assumeTrue(false, "Symbolic links are unavailable: " + exception.getMessage());
        }
        assertEquals(
                PublicationTransactionStore.JournalReadFailure.NOT_REGULAR_FILE,
                store.scan().getFirst().failure()
        );
    }

    @Test
    void missingDirectoryScanIsReadOnlyAndContentNeverEntersJournal() throws Exception {
        Path directory = temporaryDirectory.resolve("missing");
        PublicationTransactionStore store = store(directory);

        assertTrue(store.scan().isEmpty());
        assertFalse(Files.exists(directory));

        PublicationTransaction created = store.create(transaction(TRANSACTION_ID));
        String stored = Files.readString(store.pathFor(created.transactionId()));
        assertFalse(stored.contains("TOP_SECRET_BOOK_PAGE_7F91"));
        assertFalse(stored.contains(temporaryDirectory.toString()));
        assertNoTemporaryFiles(directory);
    }

    private static PublicationTransactionStore store(Path directory) {
        return new PublicationTransactionStore(
                directory,
                Clock.fixed(UPDATED, ZoneOffset.UTC)
        );
    }

    private static PublicationTransaction transaction(UUID id) {
        return PublicationTransaction.prepared(
                id,
                CREATED,
                UUID.fromString("66666666-7777-8888-9999-aaaaaaaaaaaa"),
                3L,
                new DraftManifest.Actor("Admin", null),
                PublishCollisionMode.FAIL,
                "rules.txt",
                "rules_1.txt",
                "rules.txt",
                "published_rules.txt",
                null,
                new ContentFingerprint(42L, "a".repeat(64)),
                null,
                "c".repeat(64)
        );
    }

    private static boolean update(
            PublicationTransactionStore store,
            PublicationTransaction expected,
            CountDownLatch ready,
            CountDownLatch start
    ) throws InterruptedException {
        ready.countDown();
        start.await();
        try {
            store.update(expected, PublicationTransactionState.LIVE_COMMITTED);
            return true;
        } catch (IOException exception) {
            return false;
        }
    }

    private static void assertNoTemporaryFiles(Path directory) throws IOException {
        try (Stream<Path> paths = Files.list(directory)) {
            assertTrue(paths.noneMatch(path -> path.getFileName().toString().endsWith(".tmp")));
        }
    }
}
