package com.mrfloris.exportbook;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublicationPlanFileStoreTest {
    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-07-17T08:09:10.321Z"),
            ZoneOffset.UTC
    );
    private static final UUID TRANSACTION_ID = UUID.fromString(
            "12345678-1234-4abc-8def-1234567890ab"
    );
    private static final int MAXIMUM_FILENAME_LENGTH = 96;

    @TempDir
    Path temporaryDirectory;

    @ParameterizedTest
    @EnumSource(value = PublishCollisionMode.class, names = {"FAIL", "UNIQUE"})
    void nonReplacementPlanPrecomputesExactTargetsWithoutArtifactMutation(PublishCollisionMode mode)
            throws Exception {
        WorkflowDirectories directories = workflowDirectories();
        Path staged = writeUtf8(directories.staging().resolve("Guide.txt"), "approved bytes");
        if (mode == PublishCollisionMode.UNIQUE) {
            writeUtf8(directories.published().resolve("guide.TXT"), "existing");
        }
        ContentFingerprint expected = ContentFingerprint.from(staged);

        PublicationPlan plan = plan(directories, "guide.txt", mode, expected);

        assertEquals(TRANSACTION_ID, plan.transactionId());
        assertEquals(staged, plan.stagedPath());
        assertEquals(expected, plan.expectedFingerprint());
        assertEquals(mode, plan.collisionMode());
        assertNull(plan.backupPath());
        assertNull(plan.originalPublishedFingerprint());
        assertEquals(mode == PublishCollisionMode.UNIQUE ? "Guide_1.txt" : "Guide.txt",
                plan.publishedPath().getFileName().toString());
        assertTrue(plan.archivePath().getFileName().toString().endsWith(
                "_" + TRANSACTION_ID + ".txt"
        ));
        assertFalse(plan.toString().contains(temporaryDirectory.toString()));
        assertFalse(plan.toString().contains("approved bytes"));
        assertFalse(Files.exists(plan.publishedPath()));
        assertFalse(Files.exists(plan.archivePath()));
        assertTrue(listRegularFiles(directories.archive()).isEmpty());
        assertTrue(listRegularFiles(directories.backups()).isEmpty());
    }

    @Test
    void phaseMethodsExposeOneDurableBoundaryAtATime() throws Exception {
        WorkflowDirectories directories = workflowDirectories();
        Path staged = writeUtf8(directories.staging().resolve("guide.txt"), "approved bytes");
        PublicationPlan plan = plan(
                directories,
                "guide.txt",
                PublishCollisionMode.FAIL,
                ContentFingerprint.from(staged)
        );

        PublishResult live = BookFileStore.commitLive(plan);

        assertEquals(plan.publishedPath(), live.publishedPath());
        assertUtf8(plan.publishedPath(), "approved bytes");
        assertUtf8(staged, "approved bytes");
        assertFalse(Files.exists(plan.archivePath()));

        Path archive = BookFileStore.createArchive(plan);

        assertEquals(plan.archivePath(), archive);
        assertUtf8(archive, "approved bytes");
        assertTrue(Files.exists(staged));

        BookFileStore.removeStagedAfterArchive(plan);

        assertFalse(Files.exists(staged));
        assertUtf8(plan.publishedPath(), "approved bytes");
        assertUtf8(plan.archivePath(), "approved bytes");
        assertNoInternalTemporaryFiles(directories.all());
    }

    @Test
    void replacementBackupIsExactAndDoesNotMutateLiveTarget() throws Exception {
        WorkflowDirectories directories = workflowDirectories();
        Path staged = writeUtf8(directories.staging().resolve("guide.txt"), "new bytes");
        Path published = writeUtf8(directories.published().resolve("GUIDE.TXT"), "old bytes");
        ContentFingerprint oldFingerprint = ContentFingerprint.from(published);
        PublicationPlan plan = plan(
                directories,
                "guide.txt",
                PublishCollisionMode.REPLACE_WITH_BACKUP,
                ContentFingerprint.from(staged)
        );

        assertEquals(published, plan.publishedPath());
        assertEquals(oldFingerprint, plan.originalPublishedFingerprint());
        assertNotNull(plan.backupPath());
        assertTrue(plan.backupPath().getFileName().toString().endsWith(
                "_" + TRANSACTION_ID + ".txt"
        ));

        BookFileStore.createReplacementBackup(plan);

        assertUtf8(plan.backupPath(), "old bytes");
        assertUtf8(published, "old bytes");
        assertUtf8(staged, "new bytes");

        PublishResult live = BookFileStore.commitLive(plan);

        assertEquals(plan.backupPath(), live.backupPath());
        assertEquals(oldFingerprint, live.backupFingerprint());
        assertUtf8(plan.backupPath(), "old bytes");
        assertUtf8(published, "new bytes");
        assertUtf8(staged, "new bytes");
        assertNoInternalTemporaryFiles(directories.all());
    }

    @Test
    void changedReplacementTargetAfterBackupFailsClosed() throws Exception {
        WorkflowDirectories directories = workflowDirectories();
        Path staged = writeUtf8(directories.staging().resolve("guide.txt"), "new bytes");
        Path published = writeUtf8(directories.published().resolve("guide.txt"), "old bytes");
        PublicationPlan plan = plan(
                directories,
                "guide.txt",
                PublishCollisionMode.REPLACE_WITH_BACKUP,
                ContentFingerprint.from(staged)
        );
        BookFileStore.createReplacementBackup(plan);
        writeUtf8(published, "concurrent edit");

        BookExportException exception = assertThrows(
                BookExportException.class,
                () -> BookFileStore.commitLive(plan)
        );

        assertTrue(exception.getMessage().contains("changed after its backup"));
        assertUtf8(published, "concurrent edit");
        assertUtf8(staged, "new bytes");
        assertUtf8(plan.backupPath(), "old bytes");
        assertFalse(Files.exists(plan.archivePath()));
        assertNoInternalTemporaryFiles(directories.all());
    }

    @Test
    void lostReplacementBackupStopsArchiveAndKeepsStaged() throws Exception {
        WorkflowDirectories directories = workflowDirectories();
        Path staged = writeUtf8(directories.staging().resolve("guide.txt"), "new bytes");
        writeUtf8(directories.published().resolve("guide.txt"), "old bytes");
        PublicationPlan plan = plan(
                directories,
                "guide.txt",
                PublishCollisionMode.REPLACE_WITH_BACKUP,
                ContentFingerprint.from(staged)
        );
        BookFileStore.createReplacementBackup(plan);
        BookFileStore.commitLive(plan);
        Files.delete(plan.backupPath());

        assertThrows(BookExportException.class, () -> BookFileStore.createArchive(plan));

        assertUtf8(staged, "new bytes");
        assertUtf8(plan.publishedPath(), "new bytes");
        assertFalse(Files.exists(plan.archivePath()));
    }

    @Test
    void claimedExactUniqueTargetFailsWithoutOverwriting() throws Exception {
        WorkflowDirectories directories = workflowDirectories();
        Path staged = writeUtf8(directories.staging().resolve("guide.txt"), "approved");
        writeUtf8(directories.published().resolve("GUIDE.TXT"), "first claimant");
        PublicationPlan plan = plan(
                directories,
                "guide.txt",
                PublishCollisionMode.UNIQUE,
                ContentFingerprint.from(staged)
        );
        Path claimant = writeUtf8(plan.publishedPath(), "late claimant");

        BookExportException exception = assertThrows(
                BookExportException.class,
                () -> BookFileStore.commitLive(plan)
        );

        assertTrue(exception.getMessage().contains("was claimed"));
        assertUtf8(claimant, "late claimant");
        assertUtf8(staged, "approved");
        assertNoInternalTemporaryFiles(directories.all());
    }

    @Test
    void claimedArchiveOrManifestCompanionFailsWithoutDeletingStaged() throws Exception {
        WorkflowDirectories directories = workflowDirectories();
        Path staged = writeUtf8(directories.staging().resolve("guide.txt"), "approved");
        PublicationPlan plan = plan(
                directories,
                "guide.txt",
                PublishCollisionMode.FAIL,
                ContentFingerprint.from(staged)
        );
        BookFileStore.commitLive(plan);
        Path companion = writeUtf8(
                Path.of(plan.archivePath() + DraftManifestStore.MANIFEST_SUFFIX),
                "claimed metadata"
        );

        BookExportException exception = assertThrows(
                BookExportException.class,
                () -> BookFileStore.createArchive(plan)
        );

        assertTrue(exception.getMessage().contains("metadata companion"));
        assertUtf8(companion, "claimed metadata");
        assertUtf8(staged, "approved");
        assertFalse(Files.exists(plan.archivePath()));
        assertNoInternalTemporaryFiles(directories.all());
    }

    @Test
    void archiveOrLiveMismatchPreventsStagedRemoval() throws Exception {
        WorkflowDirectories directories = workflowDirectories();
        Path staged = writeUtf8(directories.staging().resolve("guide.txt"), "approved");
        PublicationPlan plan = plan(
                directories,
                "guide.txt",
                PublishCollisionMode.FAIL,
                ContentFingerprint.from(staged)
        );
        BookFileStore.commitLive(plan);
        BookFileStore.createArchive(plan);
        writeUtf8(plan.archivePath(), "tampered archive");

        assertThrows(
                BookExportException.class,
                () -> BookFileStore.removeStagedAfterArchive(plan)
        );

        assertUtf8(staged, "approved");
        assertUtf8(plan.publishedPath(), "approved");
        assertUtf8(plan.archivePath(), "tampered archive");
    }

    @Test
    void longHistoryNameStillRetainsFullTransactionIdAndBound() throws Exception {
        WorkflowDirectories directories = workflowDirectories();
        String longBase = "very-long-guide-name-" + "a".repeat(200);
        Path staged = writeUtf8(directories.staging().resolve(longBase + ".txt"), "approved");

        PublicationPlan plan = plan(
                directories,
                staged.getFileName().toString(),
                PublishCollisionMode.FAIL,
                ContentFingerprint.from(staged)
        );

        String archiveName = plan.archivePath().getFileName().toString();
        assertTrue(archiveName.endsWith("_" + TRANSACTION_ID + ".txt"));
        assertTrue(archiveName.codePointCount(0, archiveName.length()) <= 164);
    }

    @Test
    void reviewedChecksumMismatchStopsDuringPlanning() throws Exception {
        WorkflowDirectories directories = workflowDirectories();
        Path staged = writeUtf8(directories.staging().resolve("guide.txt"), "reviewed");
        ContentFingerprint reviewed = ContentFingerprint.from(staged);
        writeUtf8(staged, "changed");

        assertThrows(
                BookExportException.class,
                () -> plan(directories, "guide.txt", PublishCollisionMode.FAIL, reviewed)
        );

        assertTrue(listRegularFiles(directories.published()).isEmpty());
        assertTrue(listRegularFiles(directories.archive()).isEmpty());
        assertTrue(listRegularFiles(directories.backups()).isEmpty());
        assertUtf8(staged, "changed");
    }

    private static PublicationPlan plan(
            WorkflowDirectories directories,
            String requestedFilename,
            PublishCollisionMode collisionMode,
            ContentFingerprint expectedFingerprint
    ) throws IOException, BookExportException {
        return BookFileStore.planPublication(
                directories.staging(),
                directories.published(),
                directories.archive(),
                directories.backups(),
                requestedFilename,
                collisionMode,
                MAXIMUM_FILENAME_LENGTH,
                FIXED_CLOCK,
                TRANSACTION_ID,
                expectedFingerprint
        );
    }

    private WorkflowDirectories workflowDirectories() throws IOException {
        return new WorkflowDirectories(
                Files.createDirectories(temporaryDirectory.resolve("staging")),
                Files.createDirectories(temporaryDirectory.resolve("published")),
                Files.createDirectories(temporaryDirectory.resolve("archive")),
                Files.createDirectories(temporaryDirectory.resolve("backups"))
        );
    }

    private static Path writeUtf8(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.write(path, content.getBytes(StandardCharsets.UTF_8));
        return path;
    }

    private static void assertUtf8(Path path, String expected) throws IOException {
        assertArrayEquals(expected.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(path));
    }

    private static List<Path> listRegularFiles(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(Files::isRegularFile).sorted().toList();
        }
    }

    private static void assertNoInternalTemporaryFiles(Path... directories) throws IOException {
        for (Path directory : directories) {
            try (Stream<Path> paths = Files.walk(directory)) {
                List<Path> leftovers = paths
                        .filter(Files::isRegularFile)
                        .filter(path -> {
                            String filename = path.getFileName().toString();
                            return filename.startsWith(".bookexport-") && filename.endsWith(".tmp");
                        })
                        .toList();
                assertTrue(leftovers.isEmpty(), () -> "Temporary files were not cleaned: " + leftovers);
            }
        }
    }

    private record WorkflowDirectories(
            Path staging,
            Path published,
            Path archive,
            Path backups
    ) {
        Path[] all() {
            return new Path[]{staging, published, archive, backups};
        }
    }
}
