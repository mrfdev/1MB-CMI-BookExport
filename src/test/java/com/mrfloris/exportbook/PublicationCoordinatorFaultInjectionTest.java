package com.mrfloris.exportbook;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublicationCoordinatorFaultInjectionTest {
    private static final String SECRET_SENTINEL = "TOP_SECRET_BOOK_PAGE_7F91";
    private static final String REVIEWED_BYTES = "<AutoPage>\nRendered " + SECRET_SENTINEL + '\n';
    private static final String OLD_LIVE_BYTES = "previous live publication\n";
    private static final Instant NOW = Instant.parse("2026-07-17T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final DraftManifest.Actor ACTOR = new DraftManifest.Actor(
            "Publisher",
            UUID.fromString("123e4567-e89b-42d3-a456-426614174000")
    );
    private static final UUID UNRELATED_DRAFT = UUID.fromString(
            "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"
    );

    @TempDir
    Path temporaryDirectory;

    static Stream<Arguments> faultScenarios() {
        return Stream.of(PublishCollisionMode.values()).flatMap(mode ->
                Stream.of(PublicationBoundary.values())
                        .filter(boundary -> mode == PublishCollisionMode.REPLACE_WITH_BACKUP
                                || (boundary != PublicationBoundary.BACKUP_FILE_COMMITTED
                                && boundary != PublicationBoundary.BACKUP_CREATED_DURABLE))
                        .map(boundary -> Arguments.of(mode, boundary, expectedAssessment(boundary)))
        );
    }

    @ParameterizedTest(name = "{0} crash after {1} -> {2}")
    @MethodSource("faultScenarios")
    void everyDurableBoundaryProducesAnAuditableRestartState(
            PublishCollisionMode collisionMode,
            PublicationBoundary injectedBoundary,
            PublicationRecoveryAssessment expected
    ) throws Exception {
        Context context = context(collisionMode);
        AtomicBoolean injected = new AtomicBoolean();
        PublicationFaultInjector injector = boundary -> {
            if (boundary == injectedBoundary) {
                injected.set(true);
                throw new IOException("Injected publication boundary failure: " + boundary);
            }
        };
        PublicationCoordinator coordinator = new PublicationCoordinator(
                CLOCK,
                context.manifests(),
                context.transactions(),
                injector
        );

        PublishResult result = null;
        BookExportException failure = null;
        try {
            result = coordinator.publish(context.settings(), "guide.txt", collisionMode, ACTOR);
        } catch (BookExportException exception) {
            failure = exception;
        }

        assertTrue(injected.get(), "The requested boundary must be reachable in this scenario.");
        assertPrivacy(context, result, failure);

        PublicationTransactionStore restartedStore = new PublicationTransactionStore(
                context.settings().transactionDirectory(),
                CLOCK
        );
        PublicationRecoveryScanner restartedScanner = new PublicationRecoveryScanner(
                context.settings(),
                restartedStore,
                CLOCK
        );
        Map<String, FileSnapshot> beforeScans = snapshotTree(temporaryDirectory);
        PublicationRecoveryReport first = restartedScanner.scan();
        PublicationRecoveryReport second = restartedScanner.scan();

        assertEquals(first, second);
        assertEquals(beforeScans, snapshotTree(temporaryDirectory));
        assertFalse(first.toString().contains(SECRET_SENTINEL));
        assertFalse(first.toString().contains(temporaryDirectory.toString()));

        if (expected == null) {
            assertTrue(first.entries().isEmpty());
            assertFalse(first.hasGlobalBlocker());
        } else {
            assertEquals(1, first.entries().size());
            PublicationRecoveryEntry entry = first.entries().getFirst();
            assertEquals(expected, entry.assessment());
            assertFalse(first.hasGlobalBlocker());
            boolean completed = expected
                    == PublicationRecoveryAssessment.COMPLETED_JOURNAL_REMAINS;
            assertEquals(!completed, first.blocksPublication(
                    entry.transaction().draftId(),
                    entry.transaction().stagedFilename(),
                    entry.transaction().publishedFilename()
            ));
            assertFalse(first.blocksPublication(
                    UNRELATED_DRAFT,
                    "unrelated-stage.txt",
                    "unrelated-live.txt"
            ));
        }

        assertReplacementBackup(
                context,
                collisionMode == PublishCollisionMode.REPLACE_WITH_BACKUP
                        && injectedBoundary.ordinal()
                        >= PublicationBoundary.BACKUP_FILE_COMMITTED.ordinal()
        );
    }

    @ParameterizedTest(name = "successful {0} publication removes its journal")
    @EnumSource(PublishCollisionMode.class)
    void successfulPublicationFinalizesArtifactsAndDeletesJournal(
            PublishCollisionMode collisionMode
    ) throws Exception {
        Context context = context(collisionMode);
        PublicationCoordinator coordinator = new PublicationCoordinator(
                CLOCK,
                context.manifests(),
                context.transactions()
        );

        PublishResult result = coordinator.publish(
                context.settings(),
                "guide.txt",
                collisionMode,
                ACTOR
        );

        assertTrue(result.archived());
        assertFalse(result.hasArchiveWarning());
        assertFalse(result.hasManifestWarning());
        assertNotNull(result.manifest());
        assertEquals(DraftPublicationStatus.PUBLISHED, result.manifest().publicationStatus());
        assertFalse(Files.exists(context.staged()));
        assertEquals(REVIEWED_BYTES, Files.readString(result.publishedPath()));
        assertEquals(REVIEWED_BYTES, Files.readString(result.archivedPath()));
        assertTrue(context.transactions().scan().isEmpty());
        assertTrue(new PublicationRecoveryScanner(
                context.settings(),
                new PublicationTransactionStore(context.settings().transactionDirectory(), CLOCK),
                CLOCK
        ).scan().entries().isEmpty());
        assertReplacementBackup(
                context,
                collisionMode == PublishCollisionMode.REPLACE_WITH_BACKUP
        );
        if (collisionMode == PublishCollisionMode.UNIQUE) {
            assertEquals(
                    OLD_LIVE_BYTES,
                    Files.readString(context.settings().publishedDirectory().resolve("guide.txt"))
            );
        }
        assertPrivacy(context, result, null);
    }

    @Test
    void malformedResidualJournalBlocksGloballyAndScanningNeverChangesIt() throws Exception {
        Context context = context(PublishCollisionMode.FAIL);
        PublicationCoordinator coordinator = new PublicationCoordinator(
                CLOCK,
                context.manifests(),
                context.transactions(),
                boundary -> {
                    if (boundary == PublicationBoundary.PREPARED_DURABLE) {
                        throw new IOException("Injected prepared boundary failure");
                    }
                }
        );
        try {
            coordinator.publish(context.settings(), "guide.txt", PublishCollisionMode.FAIL, ACTOR);
        } catch (BookExportException expected) {
            // The durable prepared journal intentionally remains.
        }
        PublicationRecoveryReport readable = new PublicationRecoveryScanner(
                context.settings(), context.transactions(), CLOCK
        ).scan();
        UUID transactionId = readable.entries().getFirst().transactionId();
        Files.writeString(
                context.transactions().pathFor(transactionId),
                "not-a-valid-transaction=true\n",
                StandardCharsets.UTF_8
        );
        PublicationRecoveryScanner restarted = new PublicationRecoveryScanner(
                context.settings(),
                new PublicationTransactionStore(context.settings().transactionDirectory(), CLOCK),
                CLOCK
        );
        Map<String, FileSnapshot> before = snapshotTree(temporaryDirectory);

        PublicationRecoveryReport first = restarted.scan();
        PublicationRecoveryReport second = restarted.scan();

        assertEquals(before, snapshotTree(temporaryDirectory));
        assertEquals(first, second);
        assertTrue(first.hasGlobalBlocker());
        assertTrue(first.blocksPublication(UNRELATED_DRAFT, "anything.txt", "anything-else.txt"));
        assertEquals(
                PublicationRecoveryAssessment.UNREADABLE_REQUIRES_MANUAL_REVIEW,
                first.entries().getFirst().assessment()
        );
        assertFalse(first.toString().contains(SECRET_SENTINEL));
        assertFalse(first.toString().contains(temporaryDirectory.toString()));
    }

    @Test
    void directFileWriteDoesNotCreatePublicationJournal() throws Exception {
        Context context = context(PublishCollisionMode.FAIL);

        Path direct = BookFileStore.writeUnique(
                context.settings().publishedDirectory(),
                "direct-book",
                REVIEWED_BYTES,
                context.settings().maximumFilenameLength()
        );

        assertEquals(REVIEWED_BYTES, Files.readString(direct));
        assertTrue(context.transactions().scan().isEmpty());
        assertTrue(new PublicationRecoveryScanner(
                context.settings(), context.transactions(), CLOCK
        ).scan().entries().isEmpty());
    }

    private Context context(PublishCollisionMode collisionMode) throws Exception {
        Path staging = Files.createDirectories(temporaryDirectory.resolve("staging"));
        Path published = Files.createDirectories(temporaryDirectory.resolve("published"));
        Path archive = Files.createDirectories(temporaryDirectory.resolve("archive"));
        Path backups = Files.createDirectories(temporaryDirectory.resolve("backups"));
        Path transactionsDirectory = Files.createDirectories(temporaryDirectory.resolve("transactions"));
        ExportSettings settings = new ExportSettings(
                3,
                false,
                WorkflowMode.STAGED,
                staging,
                published,
                archive,
                backups,
                transactionsDirectory,
                collisionMode,
                "%title%",
                true,
                96,
                true,
                "<NextPage>",
                false,
                "<AutoPage>",
                false,
                ColorMode.CMI,
                10,
                false
        );
        Path staged = staging.resolve("guide.txt");
        Files.writeString(staged, REVIEWED_BYTES, StandardCharsets.UTF_8);
        DraftManifestStore manifests = new DraftManifestStore(CLOCK);
        manifests.createNative(
                staged,
                "guide.txt",
                ACTOR,
                "Book Author",
                1,
                SECRET_SENTINEL.length(),
                NOW
        );
        manifests.approve(staged, ACTOR);

        ContentFingerprint oldLiveFingerprint = null;
        if (collisionMode != PublishCollisionMode.FAIL) {
            Path oldLive = published.resolve("guide.txt");
            Files.writeString(
                    oldLive,
                    OLD_LIVE_BYTES,
                    StandardCharsets.UTF_8
            );
            oldLiveFingerprint = ContentFingerprint.from(oldLive);
        }
        PublicationTransactionStore transactions = new PublicationTransactionStore(
                transactionsDirectory,
                CLOCK
        );
        transactions.initialize();
        return new Context(settings, staged, manifests, transactions, oldLiveFingerprint);
    }

    private static PublicationRecoveryAssessment expectedAssessment(
            PublicationBoundary boundary
    ) {
        return switch (boundary) {
            case PREPARED_DURABLE ->
                    PublicationRecoveryAssessment.ABANDONED_BEFORE_LIVE_COMMIT;
            case BACKUP_FILE_COMMITTED, BACKUP_CREATED_DURABLE ->
                    PublicationRecoveryAssessment.BACKUP_CREATED_NO_LIVE_COMMIT;
            case LIVE_FILE_COMMITTED, LIVE_COMMITTED_DURABLE ->
                    PublicationRecoveryAssessment.LIVE_COMMIT_NEEDS_CHECKPOINT;
            case MANIFEST_CHECKPOINT_COMMITTED, MANIFEST_CHECKPOINTED_DURABLE ->
                    PublicationRecoveryAssessment.CHECKPOINT_NEEDS_ARCHIVE;
            case ARCHIVE_FILE_COMMITTED, ARCHIVE_CREATED_DURABLE ->
                    PublicationRecoveryAssessment.ARCHIVE_NEEDS_SOURCE_CLEANUP;
            case STAGED_FILE_REMOVED, STAGED_REMOVED_DURABLE ->
                    PublicationRecoveryAssessment.ARCHIVE_NEEDS_MANIFEST_FINALIZE;
            case HISTORY_MANIFEST_CREATED, ACTIVE_MANIFEST_REMOVED,
                    MANIFEST_FINALIZED, FINALIZED_DURABLE ->
                    PublicationRecoveryAssessment.COMPLETED_JOURNAL_REMAINS;
            case JOURNAL_DELETED -> null;
        };
    }

    private static void assertReplacementBackup(Context context, boolean expected)
            throws Exception {
        List<String> backups = BookFileStore.listTextFiles(context.settings().backupDirectory());
        if (!expected) {
            assertTrue(backups.isEmpty());
            return;
        }
        assertEquals(1, backups.size());
        Path backup = context.settings().backupDirectory().resolve(backups.getFirst());
        assertEquals(OLD_LIVE_BYTES, Files.readString(backup));
        assertEquals(
                context.oldLiveFingerprint(),
                ContentFingerprint.from(backup)
        );
    }

    private static void assertPrivacy(
            Context context,
            PublishResult result,
            BookExportException failure
    ) throws Exception {
        if (result != null) {
            assertFalse(result.toString().contains(SECRET_SENTINEL));
        }
        if (failure != null) {
            StringWriter trace = new StringWriter();
            failure.printStackTrace(new PrintWriter(trace));
            assertFalse(failure.getMessage().contains(SECRET_SENTINEL));
            assertFalse(trace.toString().contains(SECRET_SENTINEL));
        }
        try (Stream<Path> paths = Files.list(context.settings().transactionDirectory())) {
            for (Path journal : paths.filter(Files::isRegularFile).toList()) {
                assertFalse(Files.readString(journal).contains(SECRET_SENTINEL));
            }
        }
    }

    private static Map<String, FileSnapshot> snapshotTree(Path root) throws Exception {
        Map<String, FileSnapshot> snapshots = new LinkedHashMap<>();
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted().toList()) {
                snapshots.put(
                        root.relativize(path).toString(),
                        new FileSnapshot(
                                Files.isDirectory(path),
                                Files.getLastModifiedTime(path),
                                Files.isRegularFile(path) ? ContentFingerprint.from(path) : null
                        )
                );
            }
        }
        return snapshots;
    }

    private record Context(
            ExportSettings settings,
            Path staged,
            DraftManifestStore manifests,
            PublicationTransactionStore transactions,
            ContentFingerprint oldLiveFingerprint
    ) {
    }

    private record FileSnapshot(
            boolean directory,
            FileTime modified,
            ContentFingerprint fingerprint
    ) {
    }
}
