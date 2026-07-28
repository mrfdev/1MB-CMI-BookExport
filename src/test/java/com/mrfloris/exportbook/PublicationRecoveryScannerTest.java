package com.mrfloris.exportbook;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublicationRecoveryScannerTest {
    private static final String SECRET_SENTINEL = "TOP_SECRET_BOOK_PAGE_7F91";
    private static final Instant NOW = Instant.parse("2026-07-17T08:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final UUID TRANSACTION_ID = UUID.fromString(
            "11111111-2222-4333-8444-555555555555"
    );
    private static final DraftManifest.Actor ACTOR = new DraftManifest.Actor(
            "Publisher",
            UUID.fromString("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee")
    );

    @TempDir
    Path temporaryDirectory;

    @Test
    void repeatedPreparedScanIsReadOnlyScopedAndContentFree() throws Exception {
        TestContext context = context();
        PublicationTransaction transaction = context.preparedTransaction();
        context.transactions().create(transaction);
        Map<String, FileSnapshot> before = snapshotTree(temporaryDirectory);

        PublicationRecoveryReport first = context.scanner().scan();
        PublicationRecoveryReport second = context.scanner().scan();
        Map<String, FileSnapshot> after = snapshotTree(temporaryDirectory);

        assertEquals(before, after);
        assertEquals(first, second);
        assertEquals(1, first.entries().size());
        PublicationRecoveryEntry entry = first.entries().getFirst();
        assertEquals(
                PublicationRecoveryAssessment.ABANDONED_BEFORE_LIVE_COMMIT,
                entry.assessment()
        );
        assertEquals(ArtifactObservation.MATCHES_EXPECTED, entry.stagedArtifact());
        assertEquals(ArtifactObservation.MISSING, entry.publishedArtifact());
        assertEquals(
                PublicationRecoveryEntry.ManifestObservation.APPROVED_STAGED,
                entry.manifestObservation()
        );
        assertTrue(first.blocksPublication(
                transaction.draftId(),
                transaction.stagedFilename(),
                transaction.publishedFilename()
        ));
        assertFalse(first.blocksPublication(
                UUID.fromString("99999999-8888-4777-8666-555555555555"),
                "other-staged.txt",
                "other-live.txt"
        ));
        assertFalse(first.toString().contains(SECRET_SENTINEL));
        assertFalse(entry.toString().contains(temporaryDirectory.toString()));
        assertFalse(Files.readString(
                context.transactions().pathFor(TRANSACTION_ID),
                StandardCharsets.UTF_8
        ).contains(SECRET_SENTINEL));
    }

    @Test
    void unreadableJournalBlocksGloballyWithoutEchoingItsBytes() throws Exception {
        TestContext context = context();
        Path malformed = context.transactions().pathFor(TRANSACTION_ID);
        Files.writeString(
                malformed,
                "malformed-field=" + SECRET_SENTINEL + '\n',
                StandardCharsets.UTF_8
        );
        Map<String, FileSnapshot> before = snapshotTree(temporaryDirectory);

        PublicationRecoveryReport report = context.scanner().scan();

        assertEquals(before, snapshotTree(temporaryDirectory));
        assertEquals(1, report.entries().size());
        assertEquals(1, report.criticalCount());
        assertTrue(report.hasGlobalBlocker());
        assertTrue(report.blocksPublication(
                UUID.fromString("99999999-8888-4777-8666-555555555555"),
                "unrelated-staged.txt",
                "unrelated-live.txt"
        ));
        assertEquals(
                PublicationRecoveryAssessment.UNREADABLE_REQUIRES_MANUAL_REVIEW,
                report.entries().getFirst().assessment()
        );
        assertFalse(report.toString().contains(SECRET_SENTINEL));
        assertFalse(report.toString().contains(temporaryDirectory.toString()));
    }

    @Test
    void scannerRecognizesEveryNonReplacementCrashBoundaryWithoutRepairingIt() throws Exception {
        TestContext context = context();
        PublicationPlan plan = context.plan(PublishCollisionMode.FAIL);
        PublicationTransaction transaction = context.preparedTransaction(plan);
        context.transactions().create(transaction);
        assertAssessment(context, PublicationRecoveryAssessment.ABANDONED_BEFORE_LIVE_COMMIT);

        BookFileStore.commitLive(plan);
        assertAssessment(context, PublicationRecoveryAssessment.LIVE_COMMIT_NEEDS_CHECKPOINT);
        transaction = context.transactions().update(
                transaction,
                PublicationTransactionState.LIVE_COMMITTED
        );
        assertAssessment(context, PublicationRecoveryAssessment.LIVE_COMMIT_NEEDS_CHECKPOINT);

        DraftManifestStore manifests = new DraftManifestStore(CLOCK);
        manifests.checkpointCommittedPublication(
                plan.stagedPath(),
                context.approved(),
                plan.publishedPath(),
                null,
                null,
                plan.collisionMode(),
                ACTOR
        );
        assertAssessment(context, PublicationRecoveryAssessment.CHECKPOINT_NEEDS_ARCHIVE);
        transaction = context.transactions().update(
                transaction,
                PublicationTransactionState.MANIFEST_CHECKPOINTED
        );

        BookFileStore.createArchive(plan);
        assertAssessment(context, PublicationRecoveryAssessment.ARCHIVE_NEEDS_SOURCE_CLEANUP);
        transaction = context.transactions().update(
                transaction,
                PublicationTransactionState.ARCHIVE_CREATED
        );

        BookFileStore.removeStagedAfterArchive(plan);
        assertAssessment(context, PublicationRecoveryAssessment.ARCHIVE_NEEDS_MANIFEST_FINALIZE);
        transaction = context.transactions().update(
                transaction,
                PublicationTransactionState.STAGED_REMOVED
        );

        manifests.finalizePublication(
                plan.stagedPath(),
                plan.publishedPath(),
                plan.archivePath(),
                null,
                null,
                plan.collisionMode(),
                ACTOR
        );
        assertAssessment(context, PublicationRecoveryAssessment.COMPLETED_JOURNAL_REMAINS);
        context.transactions().update(transaction, PublicationTransactionState.FINALIZED);
        assertAssessment(context, PublicationRecoveryAssessment.COMPLETED_JOURNAL_REMAINS);
    }

    @Test
    void replacementBackupAheadOfPreparedJournalIsSafelyIdentified() throws Exception {
        TestContext context = context();
        Files.writeString(
                context.settings().publishedDirectory().resolve("guide.txt"),
                "previous live bytes",
                StandardCharsets.UTF_8
        );
        PublicationPlan plan = context.plan(PublishCollisionMode.REPLACE_WITH_BACKUP);
        PublicationTransaction transaction = context.preparedTransaction(plan);
        context.transactions().create(transaction);

        BookFileStore.createReplacementBackup(plan);

        assertAssessment(context, PublicationRecoveryAssessment.BACKUP_CREATED_NO_LIVE_COMMIT);
        context.transactions().update(transaction, PublicationTransactionState.BACKUP_CREATED);
        assertAssessment(context, PublicationRecoveryAssessment.BACKUP_CREATED_NO_LIVE_COMMIT);
    }

    private TestContext context() throws Exception {
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
                PublishCollisionMode.FAIL,
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
        Files.writeString(
                staged,
                "Rendered page: " + SECRET_SENTINEL,
                StandardCharsets.UTF_8
        );
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
        DraftManifest approved = manifests.approve(staged, ACTOR).manifest();
        PublicationTransactionStore transactions = new PublicationTransactionStore(
                transactionsDirectory,
                CLOCK
        );
        transactions.initialize();
        return new TestContext(
                settings,
                staged,
                approved,
                transactions,
                new PublicationRecoveryScanner(settings, transactions, CLOCK)
        );
    }

    private static Map<String, FileSnapshot> snapshotTree(Path root) throws Exception {
        Map<String, FileSnapshot> snapshots = new LinkedHashMap<>();
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted().toList()) {
                String relative = root.relativize(path).toString();
                FileTime modified = Files.getLastModifiedTime(path);
                ContentFingerprint fingerprint = Files.isRegularFile(path)
                        ? ContentFingerprint.from(path) : null;
                snapshots.put(relative, new FileSnapshot(Files.isDirectory(path), modified, fingerprint));
            }
        }
        return snapshots;
    }

    private static void assertAssessment(
            TestContext context,
            PublicationRecoveryAssessment expected
    ) {
        PublicationRecoveryReport report = context.scanner().scan();
        assertEquals(1, report.entries().size());
        assertEquals(expected, report.entries().getFirst().assessment());
    }

    private record FileSnapshot(
            boolean directory,
            FileTime modified,
            ContentFingerprint fingerprint
    ) {
    }

    private record TestContext(
            ExportSettings settings,
            Path staged,
            DraftManifest approved,
            PublicationTransactionStore transactions,
            PublicationRecoveryScanner scanner
    ) {
        PublicationPlan plan(PublishCollisionMode collisionMode) throws Exception {
            return BookFileStore.planPublication(
                    settings.stagingDirectory(),
                    settings.publishedDirectory(),
                    settings.archiveDirectory(),
                    settings.backupDirectory(),
                    staged.getFileName().toString(),
                    collisionMode,
                    settings.maximumFilenameLength(),
                    CLOCK,
                    TRANSACTION_ID,
                    approved.effectiveFingerprint()
            );
        }

        PublicationTransaction preparedTransaction() throws Exception {
            return preparedTransaction(plan(PublishCollisionMode.FAIL));
        }

        PublicationTransaction preparedTransaction(PublicationPlan plan) {
            return PublicationTransaction.prepared(
                    TRANSACTION_ID,
                    NOW,
                    approved,
                    ACTOR,
                    plan.collisionMode(),
                    plan.publishedPath().getFileName().toString(),
                    plan.archivePath().getFileName().toString(),
                    plan.backupPath() == null ? null : plan.backupPath().getFileName().toString(),
                    plan.originalPublishedFingerprint(),
                    PublicationTransaction.workflowRootsSha256(
                            settings.stagingDirectory(),
                            settings.publishedDirectory(),
                            settings.archiveDirectory(),
                            settings.backupDirectory(),
                            settings.transactionDirectory()
                    )
            );
        }
    }
}
