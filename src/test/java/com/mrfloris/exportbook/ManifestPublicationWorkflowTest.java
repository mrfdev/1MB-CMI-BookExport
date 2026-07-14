package com.mrfloris.exportbook;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManifestPublicationWorkflowTest {
    private static final Instant NOW = Instant.parse("2026-07-14T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final DraftManifest.Actor ACTOR = new DraftManifest.Actor(
            "Reviewer",
            UUID.fromString("123e4567-e89b-12d3-a456-426614174000")
    );

    @TempDir
    Path temporaryDirectory;

    @Test
    void committedPublicationIsCheckpointedBeforeArchiveAndThenFinalized() throws Exception {
        Directories directories = directories();
        Path staged = write(directories.staging().resolve("guide.txt"), "reviewed bytes");
        DraftManifestStore manifests = new DraftManifestStore(CLOCK);
        manifests.createNative(staged, "guide.txt", ACTOR, "Book Author", 1, 14, NOW);
        DraftReview approved = manifests.verifyOrAdoptBeforePublish(staged, ACTOR);

        PublishResult live = BookFileStore.publishLive(
                directories.staging(),
                directories.published(),
                directories.archive(),
                directories.backups(),
                "guide.txt",
                PublishCollisionMode.FAIL,
                96,
                CLOCK,
                approved.manifest().effectiveFingerprint()
        );
        DraftManifest pending = manifests.checkpointCommittedPublication(
                live.stagedPath(),
                approved.manifest(),
                live.publishedPath(),
                live.backupPath(),
                live.backupFingerprint(),
                live.collisionMode(),
                ACTOR
        );

        assertEquals(DraftPublicationStatus.PUBLISHED_ARCHIVE_PENDING, pending.publicationStatus());
        assertTrue(Files.exists(staged));
        assertTrue(Files.exists(DraftManifestStore.sidecarPath(staged)));
        assertTrue(Files.exists(live.publishedPath()));
        assertTrue(BookFileStore.listTextFiles(directories.archive()).isEmpty());

        PublishResult archived = BookFileStore.archivePublished(
                live.withManifest(pending, null),
                directories.archive(),
                CLOCK,
                approved.manifest().effectiveFingerprint()
        );
        DraftManifest finalized = manifests.finalizePublication(
                archived.stagedPath(),
                archived.publishedPath(),
                archived.archivedPath(),
                archived.backupPath(),
                archived.backupFingerprint(),
                archived.collisionMode(),
                ACTOR
        );

        assertEquals(DraftPublicationStatus.PUBLISHED, finalized.publicationStatus());
        assertFalse(Files.exists(staged));
        assertFalse(Files.exists(DraftManifestStore.sidecarPath(staged)));
        assertTrue(Files.exists(DraftManifestStore.sidecarPath(archived.archivedPath())));
        assertEquals(
                finalized,
                manifests.findHistoryById(
                        directories.staging(),
                        directories.published(),
                        directories.archive(),
                        finalized.draftId()
                ).orElseThrow()
        );
    }

    @Test
    void stagedEditAfterLiveCommitCannotEraseOrRepeatCommittedOutcome() throws Exception {
        Directories directories = directories();
        Path staged = write(directories.staging().resolve("guide.txt"), "reviewed bytes");
        DraftManifestStore manifests = new DraftManifestStore(CLOCK);
        manifests.createNative(staged, "guide.txt", ACTOR, "Book Author", 1, 14, NOW);
        DraftReview approved = manifests.verifyOrAdoptBeforePublish(staged, ACTOR);

        PublishResult live = BookFileStore.publishLive(
                directories.staging(),
                directories.published(),
                directories.archive(),
                directories.backups(),
                "guide.txt",
                PublishCollisionMode.FAIL,
                96,
                CLOCK,
                approved.manifest().effectiveFingerprint()
        );
        write(staged, "edited after live commit");
        DraftManifest pending = manifests.checkpointCommittedPublication(
                staged,
                approved.manifest(),
                live.publishedPath(),
                live.backupPath(),
                live.backupFingerprint(),
                live.collisionMode(),
                ACTOR
        );
        PublishResult archiveAttempt = BookFileStore.archivePublished(
                live.withManifest(pending, null),
                directories.archive(),
                CLOCK,
                approved.manifest().effectiveFingerprint()
        );

        assertEquals(DraftPublicationStatus.PUBLISHED_ARCHIVE_PENDING, pending.publicationStatus());
        assertTrue(archiveAttempt.hasArchiveWarning());
        assertFalse(archiveAttempt.archived());
        assertEquals("reviewed bytes", Files.readString(live.publishedPath(), StandardCharsets.UTF_8));
        assertEquals("edited after live commit", Files.readString(staged, StandardCharsets.UTF_8));
        assertEquals(DraftIntegrity.CONTENT_CHANGED, manifests.inspect(staged).integrity());
        assertThrows(
                java.io.IOException.class,
                () -> manifests.verifyOrAdoptBeforePublish(staged, ACTOR)
        );
        assertEquals(
                pending,
                manifests.findHistoryById(
                        directories.staging(),
                        directories.published(),
                        directories.archive(),
                        pending.draftId()
                ).orElseThrow()
        );
    }

    private Directories directories() throws Exception {
        return new Directories(
                Files.createDirectories(temporaryDirectory.resolve("staging")),
                Files.createDirectories(temporaryDirectory.resolve("published")),
                Files.createDirectories(temporaryDirectory.resolve("archive")),
                Files.createDirectories(temporaryDirectory.resolve("backups"))
        );
    }

    private static Path write(Path path, String value) throws Exception {
        Files.writeString(path, value, StandardCharsets.UTF_8);
        return path;
    }

    private record Directories(Path staging, Path published, Path archive, Path backups) {
    }
}
