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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class DraftManifestStoreTest {
    private static final Instant CREATED_AT = Instant.parse("2026-07-14T10:02:03.456Z");
    private static final Instant ACTION_AT = Instant.parse("2026-07-14T12:22:23.456Z");
    private static final UUID DRAFT_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID SECOND_DRAFT_ID = UUID.fromString("22222222-3333-4444-5555-666666666666");
    private static final DraftManifest.Actor CREATOR = new DraftManifest.Actor(
            "AuthorPlayer",
            UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
    );
    private static final DraftManifest.Actor REVIEWER = new DraftManifest.Actor(
            "ReviewAdmin",
            UUID.fromString("12345678-1234-5678-9abc-def012345678")
    );
    private static final String CONTENT = "<AutoPage>\nCafé 你好 📘\n<NextPage>\nRules";

    @TempDir
    Path temporaryDirectory;

    @Test
    void createNativeRecordsExactMetadataAndCallerSuppliedInstant() throws Exception {
        Workflow workflow = workflow("native");
        DraftManifestStore store = store(ACTION_AT, DRAFT_ID);
        Path draft = writeUtf8(workflow.staging().resolve("rules_1.txt"), CONTENT);

        DraftReview review = store.createNative(
                draft,
                "rules.txt",
                CREATOR,
                "Signed Author 你好",
                3,
                81,
                CREATED_AT
        );

        DraftManifest manifest = review.manifest();
        assertEquals(DraftIntegrity.VERIFIED, review.integrity());
        assertEquals(draft.toAbsolutePath(), review.draftPath());
        assertEquals(DraftManifestStore.sidecarPath(draft.toAbsolutePath()), review.manifestPath());
        assertEquals(DRAFT_ID, manifest.draftId());
        assertEquals(CREATED_AT, manifest.createdAt());
        assertNotEquals(ACTION_AT, manifest.createdAt());
        assertEquals(CREATOR, manifest.createdBy());
        assertEquals("Signed Author 你好", manifest.bookAuthor());
        assertEquals(3, manifest.sourcePages());
        assertEquals(81, manifest.sourceUtf16Units());
        assertEquals("rules.txt", manifest.intendedFilename());
        assertEquals("rules_1.txt", manifest.stagedFilename());
        assertEquals(ContentFingerprint.from(draft), manifest.contentFingerprint());
        assertEquals(manifest, new DraftManifestCodec().read(review.manifestPath()));
        assertNoTemporaryManifests(workflow.all());
    }

    @Test
    void companionPathIsDeterministicAndExistingSidecarFailsWithoutOverwrite() throws Exception {
        Workflow workflow = workflow("companion");
        DraftManifestStore store = store(ACTION_AT, DRAFT_ID);
        Path draft = writeUtf8(workflow.staging().resolve("Guide.TXT"), CONTENT);
        Path sidecar = DraftManifestStore.sidecarPath(draft);

        assertEquals(
                "Guide.TXT" + DraftManifestStore.MANIFEST_SUFFIX,
                sidecar.getFileName().toString()
        );
        assertThrows(IllegalArgumentException.class, () -> DraftManifestStore.sidecarPath(
                workflow.staging().resolve("guide.md")
        ));

        store.createNative(draft, "guide.txt", CREATOR, "Author", 1, 10, CREATED_AT);
        byte[] originalSidecar = Files.readAllBytes(sidecar);

        assertThrows(
                IOException.class,
                () -> store.createNative(draft, "guide.txt", CREATOR, "Other", 2, 20, CREATED_AT)
        );
        assertArrayEquals(originalSidecar, Files.readAllBytes(sidecar));
        assertNoTemporaryManifests(workflow.all());
    }

    @Test
    void caseVariantSidecarIsTrackedAndUpdatedInsteadOfAdoptedAsLegacy() throws Exception {
        Workflow workflow = workflow("case-variant");
        DraftManifestStore store = store(ACTION_AT, DRAFT_ID);
        Path draft = writeUtf8(workflow.staging().resolve("Guide.txt"), CONTENT);
        DraftReview created = store.createNative(
                draft, "Guide.txt", CREATOR, "Author", 1, 10, CREATED_AT
        );
        Path lowerCaseSidecar = workflow.staging().resolve(
                "guide.txt" + DraftManifestStore.MANIFEST_SUFFIX.toUpperCase(java.util.Locale.ROOT)
        );
        Files.move(created.manifestPath(), lowerCaseSidecar);

        DraftReview inspected = store.inspect(draft);
        assertTrue(inspected.tracked());
        assertEquals(DraftIntegrity.VERIFIED, inspected.integrity());
        assertTrue(inspected.manifestPath().toString().equalsIgnoreCase(
                lowerCaseSidecar.toAbsolutePath().toString()
        ));
        assertEquals(DraftOrigin.NATIVE, inspected.manifest().origin());

        DraftReview approved = store.approve(draft, REVIEWER);
        assertEquals(DraftReviewStatus.APPROVED, approved.manifest().reviewStatus());
        assertTrue(approved.manifestPath().toString().equalsIgnoreCase(
                lowerCaseSidecar.toAbsolutePath().toString()
        ));
        assertThrows(IOException.class, () -> store.createNative(
                draft, "Guide.txt", CREATOR, "Other", 2, 20, CREATED_AT
        ));

        Path published = writeUtf8(workflow.published().resolve("GUIDE.TXT"), CONTENT);
        Path archive = writeUtf8(workflow.archive().resolve("Published_Guide.TXT"), CONTENT);
        Files.delete(draft);
        DraftManifest finalized = store.finalizePublication(
                draft, published, archive, null, PublishCollisionMode.FAIL, REVIEWER
        );
        Path history = DraftManifestStore.sidecarPath(archive);
        Path upperHistory = history.resolveSibling(history.getFileName().toString().toUpperCase(
                java.util.Locale.ROOT
        ));
        Files.move(history, upperHistory);
        assertEquals(List.of(finalized), store.listArchiveHistory(workflow.archive()));
    }

    @Test
    void multipleCaseVariantSidecarsFailAsAmbiguous() throws Exception {
        Workflow workflow = workflow("case-ambiguity");
        DraftManifestStore store = store(ACTION_AT, DRAFT_ID);
        DraftReview created = createNative(store, workflow, "Guide.txt", CONTENT);
        Path variant = workflow.staging().resolve(
                "guide.txt" + DraftManifestStore.MANIFEST_SUFFIX.toUpperCase(java.util.Locale.ROOT)
        );
        try {
            Files.copy(created.manifestPath(), variant);
        } catch (IOException exception) {
            assumeTrue(false, "Filesystem does not permit distinct case variants: " + exception.getMessage());
        }
        long matchingSidecars;
        try (Stream<Path> paths = Files.list(workflow.staging())) {
            matchingSidecars = paths
                    .filter(path -> path.getFileName().toString().equalsIgnoreCase(
                            created.manifestPath().getFileName().toString()
                    ))
                    .count();
        }
        assumeTrue(matchingSidecars > 1, "Filesystem aliases case-variant filenames.");

        assertThrows(IOException.class, () -> store.inspect(created.draftPath()));
        assertThrows(IOException.class, () -> store.approve(created.draftPath(), REVIEWER));
    }

    @Test
    void creationMarkerPreventsLegacyAdoptionAndIsRemovedOnlyAfterNativeManifestSucceeds()
            throws Exception {
        Workflow workflow = workflow("creation-marker");
        DraftManifestStore store = store(ACTION_AT, DRAFT_ID);
        Path draft = writeUtf8(workflow.staging().resolve("Guide.txt"), CONTENT);
        Path marker = workflow.staging().resolve(
                "guide.txt" + DraftManifestStore.CREATION_MARKER_SUFFIX.toUpperCase(java.util.Locale.ROOT)
        );
        writeUtf8(marker, "claimed");

        assertThrows(IOException.class, () -> store.inspect(draft));
        assertThrows(IOException.class, () -> store.approve(draft, REVIEWER));
        assertThrows(IOException.class, () -> store.verifyOrAdoptBeforePublish(draft, REVIEWER));
        assertFalse(Files.exists(DraftManifestStore.sidecarPath(draft)));

        DraftReview created = store.createNative(
                draft, "Guide.txt", CREATOR, "Author", 1, 10, CREATED_AT
        );
        assertEquals(DraftIntegrity.VERIFIED, created.integrity());
        assertFalse(Files.exists(marker));
        assertTrue(Files.isRegularFile(created.manifestPath()));
    }

    @Test
    void concurrentNativeCreateHasOneWinnerAndNeverReplacesItsSidecar() throws Exception {
        Workflow workflow = workflow("create-race");
        Path draft = writeUtf8(workflow.staging().resolve("rules.txt"), CONTENT);
        DraftManifestStore first = store(ACTION_AT, DRAFT_ID);
        DraftManifestStore second = store(ACTION_AT, SECOND_DRAFT_ID);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Optional<UUID>> firstAttempt = executor.submit(
                    () -> createConcurrently(first, draft, ready, start)
            );
            Future<Optional<UUID>> secondAttempt = executor.submit(
                    () -> createConcurrently(second, draft, ready, start)
            );
            ready.await();
            start.countDown();

            List<UUID> winners = Stream.of(firstAttempt.get(), secondAttempt.get())
                    .flatMap(Optional::stream)
                    .toList();
            assertEquals(1, winners.size());
            DraftManifest stored = new DraftManifestCodec().read(DraftManifestStore.sidecarPath(draft));
            assertEquals(winners.getFirst(), stored.draftId());
            assertTrue(stored.draftId().equals(DRAFT_ID) || stored.draftId().equals(SECOND_DRAFT_ID));
        } finally {
            executor.shutdownNow();
        }
        assertNoTemporaryManifests(workflow.all());
    }

    @Test
    void inspectDistinguishesUnchangedChangedAndMissingDrafts() throws Exception {
        Workflow workflow = workflow("integrity");
        DraftManifestStore store = store(ACTION_AT, DRAFT_ID);
        Path draft = createNative(store, workflow, "rules.txt", CONTENT).draftPath();

        assertEquals(DraftIntegrity.VERIFIED, store.inspect(draft).integrity());

        Files.writeString(draft, CONTENT + " changed", StandardCharsets.UTF_8);
        assertEquals(DraftIntegrity.CONTENT_CHANGED, store.inspect(draft).integrity());

        Files.delete(draft);
        DraftReview missing = store.inspect(draft);
        assertEquals(DraftIntegrity.DRAFT_MISSING, missing.integrity());
        assertTrue(missing.tracked());
        assertNull(missing.actualFingerprint());
    }

    @Test
    void associationMismatchAndCorruptManifestFailClosed() throws Exception {
        Workflow mismatchWorkflow = workflow("association");
        DraftManifestStore mismatchStore = store(ACTION_AT, DRAFT_ID);
        DraftReview created = createNative(mismatchStore, mismatchWorkflow, "rules.txt", CONTENT);
        DraftManifest mismatched = copyWithStagedFilename(created.manifest(), "other.txt");
        Files.write(created.manifestPath(), new DraftManifestCodec().encode(mismatched));

        DraftReview mismatch = mismatchStore.inspect(created.draftPath());
        assertEquals(DraftIntegrity.ASSOCIATION_MISMATCH, mismatch.integrity());
        assertThrows(IOException.class, () -> mismatchStore.approve(created.draftPath(), REVIEWER));
        assertThrows(
                IOException.class,
                () -> mismatchStore.verifyOrAdoptBeforePublish(created.draftPath(), REVIEWER)
        );

        Workflow corruptWorkflow = workflow("corrupt");
        DraftManifestStore corruptStore = store(ACTION_AT, SECOND_DRAFT_ID);
        DraftReview corrupt = createNative(corruptStore, corruptWorkflow, "corrupt.txt", CONTENT);
        Files.writeString(corrupt.manifestPath(), "not-a-valid-manifest", StandardCharsets.UTF_8);

        assertThrows(IOException.class, () -> corruptStore.inspect(corrupt.draftPath()));
        assertThrows(IOException.class, () -> corruptStore.approve(corrupt.draftPath(), REVIEWER));
        assertTrue(Files.exists(corrupt.manifestPath()));
    }

    @Test
    void approveCanBindEditedBytesAndReapproveWithoutDiscardingInitialFingerprint() throws Exception {
        Workflow workflow = workflow("approve");
        DraftManifestStore store = store(ACTION_AT, DRAFT_ID);
        DraftReview created = createNative(store, workflow, "rules.txt", CONTENT);
        ContentFingerprint initial = created.manifest().contentFingerprint();

        Files.writeString(created.draftPath(), CONTENT + "\nStaff edit one", StandardCharsets.UTF_8);
        DraftReview changed = store.inspect(created.draftPath());
        assertEquals(DraftIntegrity.CONTENT_CHANGED, changed.integrity());

        DraftReview approved = store.approve(created.draftPath(), REVIEWER);
        assertEquals(DraftIntegrity.VERIFIED, approved.integrity());
        assertEquals(DraftReviewStatus.APPROVED, approved.manifest().reviewStatus());
        assertEquals(2L, approved.manifest().revision());
        assertEquals(ACTION_AT, approved.manifest().reviewDecision().at());
        assertFalse(approved.manifest().reviewDecision().implicit());
        assertEquals(initial, approved.manifest().contentFingerprint());
        assertEquals(approved.actualFingerprint(), approved.manifest().effectiveFingerprint());

        Files.writeString(created.draftPath(), CONTENT + "\nStaff edit two", StandardCharsets.UTF_8);
        DraftReview reapproved = store.approve(created.draftPath(), REVIEWER);
        assertEquals(3L, reapproved.manifest().revision());
        assertEquals(initial, reapproved.manifest().contentFingerprint());
        assertEquals(reapproved.actualFingerprint(), reapproved.manifest().effectiveFingerprint());
        assertNoTemporaryManifests(workflow.all());
    }

    @Test
    void changesRequestedBlocksPublicationUntilExplicitReapproval() throws Exception {
        Workflow workflow = workflow("changes");
        DraftManifestStore store = store(ACTION_AT, DRAFT_ID);
        DraftReview created = createNative(store, workflow, "rules.txt", CONTENT);

        DraftReview changes = store.requestChanges(created.draftPath(), REVIEWER);
        assertEquals(DraftReviewStatus.CHANGES_REQUESTED, changes.manifest().reviewStatus());
        assertEquals(2L, changes.manifest().revision());
        assertFalse(changes.manifest().reviewDecision().implicit());
        assertThrows(
                IOException.class,
                () -> store.verifyOrAdoptBeforePublish(created.draftPath(), REVIEWER)
        );

        DraftReview reapproved = store.approve(created.draftPath(), REVIEWER);
        assertEquals(DraftReviewStatus.APPROVED, reapproved.manifest().reviewStatus());
        assertTrue(reapproved.publishable());
        assertEquals(3L, reapproved.manifest().revision());
    }

    @Test
    void legacyDraftCanBeInspectedAndExplicitlyAdopted() throws Exception {
        Workflow workflow = workflow("legacy-approve");
        DraftManifestStore store = store(ACTION_AT, DRAFT_ID);
        Path draft = writeUtf8(workflow.staging().resolve("legacy draft.txt"), CONTENT);

        DraftReview legacy = store.inspect(draft);
        assertEquals(DraftIntegrity.LEGACY_UNTRACKED, legacy.integrity());
        assertFalse(legacy.tracked());
        assertFalse(Files.exists(legacy.manifestPath()));

        DraftReview approved = store.approve(draft, REVIEWER);
        assertEquals(DraftOrigin.LEGACY_ADOPTED, approved.manifest().origin());
        assertEquals(ACTION_AT, approved.manifest().createdAt());
        assertEquals(REVIEWER, approved.manifest().createdBy());
        assertEquals(-1, approved.manifest().sourcePages());
        assertEquals(-1, approved.manifest().sourceUtf16Units());
        assertEquals(DraftReviewStatus.APPROVED, approved.manifest().reviewStatus());
        assertFalse(approved.manifest().reviewDecision().implicit());
        assertEquals(2L, approved.manifest().revision());
        assertTrue(approved.publishable());
    }

    @Test
    void prePublicationImplicitlyAdoptsAndApprovesLegacyOrNativeDrafts() throws Exception {
        Workflow legacyWorkflow = workflow("legacy-publish");
        DraftManifestStore legacyStore = store(ACTION_AT, DRAFT_ID);
        Path legacyDraft = writeUtf8(legacyWorkflow.staging().resolve("legacy.txt"), CONTENT);

        DraftReview legacy = legacyStore.verifyOrAdoptBeforePublish(legacyDraft, REVIEWER);
        assertEquals(DraftOrigin.LEGACY_ADOPTED, legacy.manifest().origin());
        assertEquals(DraftReviewStatus.APPROVED, legacy.manifest().reviewStatus());
        assertTrue(legacy.manifest().reviewDecision().implicit());
        assertEquals(REVIEWER, legacy.manifest().reviewDecision().actor());
        assertTrue(legacy.publishable());

        Workflow nativeWorkflow = workflow("native-publish");
        DraftManifestStore nativeStore = store(ACTION_AT, SECOND_DRAFT_ID);
        DraftReview nativeDraft = createNative(nativeStore, nativeWorkflow, "native.txt", CONTENT);
        DraftReview nativeApproved = nativeStore.verifyOrAdoptBeforePublish(nativeDraft.draftPath(), REVIEWER);

        assertEquals(DraftOrigin.NATIVE, nativeApproved.manifest().origin());
        assertTrue(nativeApproved.manifest().reviewDecision().implicit());
        assertEquals(2L, nativeApproved.manifest().revision());
        assertTrue(nativeApproved.publishable());
    }

    @Test
    void prePublicationRejectsChangedTrackedBytesUntilApproved() throws Exception {
        Workflow workflow = workflow("changed-prepublish");
        DraftManifestStore store = store(ACTION_AT, DRAFT_ID);
        DraftReview created = createNative(store, workflow, "rules.txt", CONTENT);
        Files.writeString(created.draftPath(), CONTENT + " changed", StandardCharsets.UTF_8);

        assertThrows(
                IOException.class,
                () -> store.verifyOrAdoptBeforePublish(created.draftPath(), REVIEWER)
        );
        DraftManifest unchangedManifest = new DraftManifestCodec().read(created.manifestPath());
        assertEquals(DraftReviewStatus.UNREVIEWED, unchangedManifest.reviewStatus());
        assertEquals(1L, unchangedManifest.revision());

        DraftReview approved = store.approve(created.draftPath(), REVIEWER);
        assertEquals(DraftIntegrity.VERIFIED, approved.integrity());
        assertTrue(store.verifyOrAdoptBeforePublish(created.draftPath(), REVIEWER).publishable());
    }

    @Test
    void pendingPublicationIsIdempotentAndRejectsChangedDraftOrLiveBytes() throws Exception {
        Workflow workflow = workflow("pending");
        DraftManifestStore store = store(ACTION_AT, DRAFT_ID);
        DraftReview approved = approvedNative(store, workflow, "rules.txt", CONTENT);
        Path published = writeUtf8(workflow.published().resolve("rules.txt"), CONTENT);

        DraftManifest pending = store.markPublicationPending(
                approved.draftPath(),
                published,
                null,
                PublishCollisionMode.FAIL,
                REVIEWER
        );
        DraftManifest repeated = store.markPublicationPending(
                approved.draftPath(),
                published,
                null,
                PublishCollisionMode.FAIL,
                REVIEWER
        );

        assertEquals(DraftPublicationStatus.PUBLISHED_ARCHIVE_PENDING, pending.publicationStatus());
        assertEquals(3L, pending.revision());
        assertEquals(pending, repeated);
        assertEquals(ACTION_AT, pending.publication().at());
        assertNull(pending.publication().archiveFilename());

        Files.writeString(approved.draftPath(), CONTENT + " edited after approval", StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> store.markPublicationPending(
                approved.draftPath(), published, null, PublishCollisionMode.FAIL, REVIEWER
        ));

        Files.writeString(approved.draftPath(), CONTENT, StandardCharsets.UTF_8);
        Files.writeString(published, CONTENT + " wrong live bytes", StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> store.markPublicationPending(
                approved.draftPath(), published, null, PublishCollisionMode.FAIL, REVIEWER
        ));
    }

    @Test
    void checkpointRecordsCommittedStateBeforeLiveRevalidationAndFinalizeStillFailsClosed()
            throws Exception {
        Workflow workflow = workflow("checkpoint");
        DraftManifestStore store = store(ACTION_AT, DRAFT_ID);
        DraftReview approved = approvedNative(store, workflow, "rules.txt", CONTENT);
        Path published = writeUtf8(workflow.published().resolve("rules.txt"), "wrong live bytes");

        DraftManifest pending = store.checkpointCommittedPublication(
                approved.draftPath(), published, null, PublishCollisionMode.FAIL, REVIEWER
        );
        assertEquals(DraftPublicationStatus.PUBLISHED_ARCHIVE_PENDING, pending.publicationStatus());

        Path archive = writeUtf8(workflow.archive().resolve("published_rules.txt"), CONTENT);
        assertThrows(IOException.class, () -> store.finalizePublication(
                approved.draftPath(), published, archive, null, PublishCollisionMode.FAIL, REVIEWER
        ));
        assertEquals(pending, new DraftManifestCodec().read(approved.manifestPath()));
        assertFalse(Files.exists(DraftManifestStore.sidecarPath(archive)));
    }

    @Test
    void trustedCheckpointUsesApprovedTokenAndCapturedBackupWithoutRereadingMutableSources()
            throws Exception {
        Workflow workflow = workflow("trusted-checkpoint");
        DraftManifestStore store = store(ACTION_AT, DRAFT_ID);
        DraftReview approved = approvedNative(store, workflow, "rules.txt", CONTENT);
        Path published = writeUtf8(workflow.published().resolve("rules.txt"), CONTENT);
        Path backup = writeUtf8(workflow.backups().resolve("old_rules.txt"), "old live bytes");
        ContentFingerprint capturedBackup = ContentFingerprint.from(backup);
        Files.delete(approved.draftPath());
        Files.delete(backup);

        DraftManifest pending = store.checkpointCommittedPublication(
                approved.draftPath(),
                approved.manifest(),
                published,
                backup,
                capturedBackup,
                PublishCollisionMode.REPLACE_WITH_BACKUP,
                REVIEWER
        );

        assertEquals(DraftPublicationStatus.PUBLISHED_ARCHIVE_PENDING, pending.publicationStatus());
        assertEquals(capturedBackup, pending.publication().backupFingerprint());
        writeUtf8(approved.draftPath(), "retained staged copy changed after live commit");
        assertEquals(Optional.of(pending), store.findHistoryById(
                workflow.staging(),
                workflow.published(),
                workflow.archive(),
                DRAFT_ID
        ));
        assertThrows(IOException.class, () -> store.findHistoryById(
                workflow.staging(), workflow.archive(), DRAFT_ID
        ));
    }

    @Test
    void capturedBackupFingerprintRejectsBackupChangedBeforeCheckpoint() throws Exception {
        Workflow workflow = workflow("captured-backup");
        DraftManifestStore store = store(ACTION_AT, DRAFT_ID);
        DraftReview approved = approvedNative(store, workflow, "rules.txt", CONTENT);
        Path published = writeUtf8(workflow.published().resolve("rules.txt"), CONTENT);
        Path backup = writeUtf8(workflow.backups().resolve("old_rules.txt"), "old live bytes");
        ContentFingerprint captured = ContentFingerprint.from(backup);
        Files.writeString(backup, "changed backup", StandardCharsets.UTF_8);

        assertThrows(IOException.class, () -> store.markPublicationPending(
                approved.draftPath(),
                published,
                backup,
                captured,
                PublishCollisionMode.REPLACE_WITH_BACKUP,
                REVIEWER
        ));
        assertEquals(
                DraftPublicationStatus.STAGED,
                new DraftManifestCodec().read(approved.manifestPath()).publicationStatus()
        );
    }

    @Test
    void finalizeMovesSidecarToArchiveAndRecordsBackupThenSupportsReplay() throws Exception {
        Workflow workflow = workflow("finalize");
        DraftManifestStore store = store(ACTION_AT, DRAFT_ID);
        DraftReview approved = approvedNative(store, workflow, "rules.txt", CONTENT);
        Path published = writeUtf8(workflow.published().resolve("rules.txt"), CONTENT);
        Path backup = writeUtf8(workflow.backups().resolve("old_rules.txt"), "old live content");
        Path archive = writeUtf8(workflow.archive().resolve("20260714_published_rules.txt"), CONTENT);
        ContentFingerprint backupFingerprint = ContentFingerprint.from(backup);

        store.markPublicationPending(
                approved.draftPath(),
                published,
                backup,
                PublishCollisionMode.REPLACE_WITH_BACKUP,
                REVIEWER
        );
        Files.delete(approved.draftPath());
        DraftManifest finalized = store.finalizePublication(
                approved.draftPath(),
                published,
                archive,
                backup,
                PublishCollisionMode.REPLACE_WITH_BACKUP,
                REVIEWER
        );

        Path activeSidecar = DraftManifestStore.sidecarPath(approved.draftPath());
        Path historySidecar = DraftManifestStore.sidecarPath(archive);
        assertEquals(DraftPublicationStatus.PUBLISHED, finalized.publicationStatus());
        assertEquals(4L, finalized.revision());
        assertEquals("rules.txt", finalized.publication().publishedFilename());
        assertEquals("20260714_published_rules.txt", finalized.publication().archiveFilename());
        assertEquals("old_rules.txt", finalized.publication().backupFilename());
        assertEquals(backupFingerprint, finalized.publication().backupFingerprint());
        assertFalse(Files.exists(activeSidecar));
        assertTrue(Files.isRegularFile(historySidecar));
        assertEquals(finalized, new DraftManifestCodec().read(historySidecar));

        DraftManifest replay = store.finalizePublication(
                approved.draftPath(),
                published,
                archive,
                backup,
                PublishCollisionMode.REPLACE_WITH_BACKUP,
                REVIEWER
        );
        assertEquals(finalized, replay);
        assertNoTemporaryManifests(workflow.all());
    }

    @Test
    void finalizeRejectsWrongArchiveAndKeepsActiveSidecar() throws Exception {
        Workflow workflow = workflow("finalize-fail");
        DraftManifestStore store = store(ACTION_AT, DRAFT_ID);
        DraftReview approved = approvedNative(store, workflow, "rules.txt", CONTENT);
        Path published = writeUtf8(workflow.published().resolve("rules.txt"), CONTENT);
        Path archive = writeUtf8(workflow.archive().resolve("published_rules.txt"), "different bytes");
        Path activeSidecar = DraftManifestStore.sidecarPath(approved.draftPath());

        assertThrows(IOException.class, () -> store.finalizePublication(
                approved.draftPath(),
                published,
                archive,
                null,
                PublishCollisionMode.FAIL,
                REVIEWER
        ));

        assertTrue(Files.isRegularFile(activeSidecar));
        assertFalse(Files.exists(DraftManifestStore.sidecarPath(archive)));
        assertEquals(DraftPublicationStatus.STAGED, new DraftManifestCodec().read(activeSidecar).publicationStatus());
        assertNoTemporaryManifests(workflow.all());
    }

    @Test
    void historyCombinesPendingAndArchivedRecordsNewestFirstAndFindsById() throws Exception {
        Workflow workflow = workflow("history");
        Instant older = Instant.parse("2026-07-14T09:00:00Z");
        Instant newer = Instant.parse("2026-07-14T13:00:00Z");
        DraftManifestStore olderStore = store(older, DRAFT_ID);
        DraftReview olderDraft = approvedNative(olderStore, workflow, "older.txt", "older content");
        Path olderPublished = writeUtf8(workflow.published().resolve("older.txt"), "older content");
        Path olderArchive = writeUtf8(workflow.archive().resolve("older_published.txt"), "older content");
        Files.delete(olderDraft.draftPath());
        DraftManifest olderFinalized = olderStore.finalizePublication(
                olderDraft.draftPath(),
                olderPublished,
                olderArchive,
                null,
                PublishCollisionMode.FAIL,
                REVIEWER
        );

        DraftManifestStore newerStore = store(newer, SECOND_DRAFT_ID);
        DraftReview newerDraft = approvedNative(newerStore, workflow, "newer.txt", "newer content");
        Path newerPublished = writeUtf8(workflow.published().resolve("newer.txt"), "newer content");
        DraftManifest newerPending = newerStore.markPublicationPending(
                newerDraft.draftPath(),
                newerPublished,
                null,
                PublishCollisionMode.FAIL,
                REVIEWER
        );

        List<DraftManifest> history = newerStore.listHistory(workflow.staging(), workflow.archive());

        assertEquals(List.of(newerPending, olderFinalized), history);
        assertEquals(Optional.of(newerPending), newerStore.findHistoryById(
                workflow.staging(), workflow.archive(), SECOND_DRAFT_ID
        ));
        assertEquals(Optional.of(olderFinalized), newerStore.findHistoryById(
                workflow.staging(), workflow.archive(), DRAFT_ID
        ));
        assertEquals(Optional.empty(), newerStore.findHistoryById(
                workflow.staging(), workflow.archive(), UUID.randomUUID()
        ));
        assertEquals(List.of(olderFinalized), newerStore.listArchiveHistory(workflow.archive()));
        assertEquals(Optional.of(olderFinalized), newerStore.findArchiveById(workflow.archive(), DRAFT_ID));
    }

    @Test
    void historyRejectsDuplicateDraftIdsAndArchiveAssociationMismatch() throws Exception {
        Workflow duplicates = workflow("duplicate-history");
        DraftManifestStore firstStore = store(Instant.parse("2026-07-14T09:00:00Z"), DRAFT_ID);
        DraftManifestStore secondStore = store(Instant.parse("2026-07-14T10:00:00Z"), DRAFT_ID);
        finalizeDirect(firstStore, duplicates, "first.txt", "first content");
        finalizeDirect(secondStore, duplicates, "second.txt", "second content");

        assertThrows(
                IOException.class,
                () -> firstStore.listHistory(duplicates.staging(), duplicates.archive())
        );

        Workflow mismatch = workflow("archive-association");
        DraftManifestStore store = store(ACTION_AT, SECOND_DRAFT_ID);
        DraftManifest finalized = finalizeDirect(store, mismatch, "rules.txt", CONTENT);
        Path correctSidecar = mismatch.archive().resolve(
                finalized.publication().archiveFilename() + DraftManifestStore.MANIFEST_SUFFIX
        );
        Path wrongSidecar = mismatch.archive().resolve("wrong.txt" + DraftManifestStore.MANIFEST_SUFFIX);
        Files.move(correctSidecar, wrongSidecar);

        assertThrows(IOException.class, () -> store.listArchiveHistory(mismatch.archive()));
    }

    @Test
    void historyReconcilesExactStalePendingSidecarAndMetadataListingSkipsContentHash()
            throws Exception {
        Workflow workflow = workflow("history-recovery");
        DraftManifestStore store = store(ACTION_AT, DRAFT_ID);
        DraftReview approved = approvedNative(store, workflow, "rules.txt", CONTENT);
        Path published = writeUtf8(workflow.published().resolve("rules.txt"), CONTENT);
        DraftManifest pending = store.markPublicationPending(
                approved.draftPath(), published, null, PublishCollisionMode.FAIL, REVIEWER
        );
        byte[] pendingSidecar = new DraftManifestCodec().encode(pending);
        Path activeSidecar = approved.manifestPath();
        Path archive = writeUtf8(workflow.archive().resolve("published_rules.txt"), CONTENT);
        Files.delete(approved.draftPath());
        DraftManifest finalized = store.finalizePublication(
                approved.draftPath(), published, archive, null, PublishCollisionMode.FAIL, REVIEWER
        );
        Files.write(activeSidecar, pendingSidecar);

        assertEquals(
                List.of(finalized),
                store.listHistoryMetadata(workflow.staging(), workflow.archive())
        );
        assertEquals(List.of(finalized), store.listHistory(workflow.staging(), workflow.archive()));

        DraftManifestStore recoveryStore = store(ACTION_AT.plusSeconds(3_600), SECOND_DRAFT_ID);
        DraftManifest recovered = recoveryStore.finalizePublication(
                approved.draftPath(), published, archive, null, PublishCollisionMode.FAIL, REVIEWER
        );
        assertEquals(finalized, recovered);
        assertFalse(Files.exists(activeSidecar));

        Files.writeString(archive, "changed archive bytes", StandardCharsets.UTF_8);
        assertEquals(
                List.of(finalized),
                store.listHistoryMetadata(workflow.staging(), workflow.archive())
        );
        assertThrows(IOException.class, () -> store.listHistory(workflow.staging(), workflow.archive()));
    }

    @Test
    void corruptUnrelatedActiveManifestDoesNotHideValidArchiveOrPendingHistory()
            throws Exception {
        Workflow workflow = workflow("history-corrupt-active-isolation");
        DraftManifestStore archiveStore = store(
                Instant.parse("2026-07-14T09:00:00Z"),
                DRAFT_ID
        );
        DraftManifest finalized = finalizeDirect(
                archiveStore,
                workflow,
                "archived.txt",
                "archived content"
        );

        DraftManifestStore pendingStore = store(
                Instant.parse("2026-07-14T13:00:00Z"),
                SECOND_DRAFT_ID
        );
        DraftReview pendingDraft = approvedNative(
                pendingStore,
                workflow,
                "pending.txt",
                "pending content"
        );
        Path pendingPublished = writeUtf8(
                workflow.published().resolve("pending.txt"),
                "pending content"
        );
        DraftManifest pending = pendingStore.markPublicationPending(
                pendingDraft.draftPath(),
                pendingPublished,
                null,
                PublishCollisionMode.FAIL,
                REVIEWER
        );

        Path corruptDraft = writeUtf8(
                workflow.staging().resolve("corrupt-unrelated.txt"),
                "corrupt draft remains fail-closed"
        );
        Files.writeString(
                DraftManifestStore.sidecarPath(corruptDraft),
                "not-a-valid-manifest",
                StandardCharsets.UTF_8
        );

        assertThrows(IOException.class, () -> pendingStore.inspect(corruptDraft));
        assertThrows(IOException.class, () -> pendingStore.approve(corruptDraft, REVIEWER));
        assertThrows(
                IOException.class,
                () -> pendingStore.verifyOrAdoptBeforePublish(corruptDraft, REVIEWER)
        );
        assertEquals(
                List.of(pending, finalized),
                pendingStore.listHistoryMetadata(workflow.staging(), workflow.archive())
        );
        assertEquals(
                Optional.of(finalized),
                pendingStore.findHistoryById(
                        workflow.staging(),
                        workflow.published(),
                        workflow.archive(),
                        DRAFT_ID
                )
        );
        assertEquals(
                Optional.of(pending),
                pendingStore.findHistoryById(
                        workflow.staging(),
                        workflow.published(),
                        workflow.archive(),
                        SECOND_DRAFT_ID
                )
        );
    }

    @Test
    void ambiguousUnrelatedActiveSidecarsDoNotHideValidArchiveHistory() throws Exception {
        Workflow workflow = workflow("history-ambiguous-active-isolation");
        DraftManifestStore store = store(ACTION_AT, DRAFT_ID, SECOND_DRAFT_ID);
        DraftManifest finalized = finalizeDirect(store, workflow, "archived.txt", "archive content");
        DraftReview ambiguous = createNative(store, workflow, "Guide.txt", CONTENT);
        Path variant = workflow.staging().resolve(
                "guide.txt" + DraftManifestStore.MANIFEST_SUFFIX.toUpperCase(java.util.Locale.ROOT)
        );
        try {
            Files.copy(ambiguous.manifestPath(), variant);
        } catch (IOException exception) {
            assumeTrue(false, "Filesystem does not permit distinct case variants: " + exception.getMessage());
        }
        long matchingSidecars;
        try (Stream<Path> paths = Files.list(workflow.staging())) {
            matchingSidecars = paths
                    .filter(path -> path.getFileName().toString().equalsIgnoreCase(
                            ambiguous.manifestPath().getFileName().toString()
                    ))
                    .count();
        }
        assumeTrue(matchingSidecars > 1, "Filesystem aliases case-variant filenames.");

        assertThrows(IOException.class, () -> store.inspect(ambiguous.draftPath()));
        assertEquals(
                List.of(finalized),
                store.listHistoryMetadata(workflow.staging(), workflow.archive())
        );
        assertEquals(
                Optional.of(finalized),
                store.findHistoryById(
                        workflow.staging(),
                        workflow.published(),
                        workflow.archive(),
                        DRAFT_ID
                )
        );
    }

    @Test
    void missingDraftAndManifestIsReportedWithoutCreatingAnything() throws Exception {
        Workflow workflow = workflow("missing");
        DraftManifestStore store = store(ACTION_AT, DRAFT_ID);
        Path missing = workflow.staging().resolve("missing.txt");

        DraftReview review = store.inspect(missing);

        assertEquals(DraftIntegrity.DRAFT_MISSING, review.integrity());
        assertFalse(review.tracked());
        assertNull(review.actualFingerprint());
        assertFalse(Files.exists(review.manifestPath()));
        assertNoTemporaryManifests(workflow.all());
    }

    private DraftManifest finalizeDirect(
            DraftManifestStore store,
            Workflow workflow,
            String filename,
            String content
    ) throws Exception {
        DraftReview approved = approvedNative(store, workflow, filename, content);
        Path published = writeUtf8(workflow.published().resolve(filename), content);
        Path archive = writeUtf8(workflow.archive().resolve("published_" + filename), content);
        Files.delete(approved.draftPath());
        return store.finalizePublication(
                approved.draftPath(),
                published,
                archive,
                null,
                PublishCollisionMode.FAIL,
                REVIEWER
        );
    }

    private DraftReview approvedNative(
            DraftManifestStore store,
            Workflow workflow,
            String filename,
            String content
    ) throws Exception {
        DraftReview created = createNative(store, workflow, filename, content);
        return store.approve(created.draftPath(), REVIEWER);
    }

    private DraftReview createNative(
            DraftManifestStore store,
            Workflow workflow,
            String filename,
            String content
    ) throws Exception {
        Path draft = writeUtf8(workflow.staging().resolve(filename), content);
        return store.createNative(
                draft,
                filename,
                CREATOR,
                "Book Author",
                2,
                content.length(),
                CREATED_AT
        );
    }

    private static DraftManifest copyWithStagedFilename(DraftManifest source, String stagedFilename) {
        return new DraftManifest(
                source.schemaVersion(),
                source.draftId(),
                source.revision(),
                source.origin(),
                source.reviewStatus(),
                source.publicationStatus(),
                source.createdAt(),
                source.createdBy(),
                source.bookAuthor(),
                source.sourcePages(),
                source.sourceUtf16Units(),
                source.contentFingerprint(),
                source.intendedFilename(),
                stagedFilename,
                source.reviewDecision(),
                source.publication()
        );
    }

    private Workflow workflow(String name) throws IOException {
        Path root = Files.createDirectories(temporaryDirectory.resolve(name));
        return new Workflow(
                Files.createDirectories(root.resolve("staging")),
                Files.createDirectories(root.resolve("published")),
                Files.createDirectories(root.resolve("archive")),
                Files.createDirectories(root.resolve("backups"))
        );
    }

    private static DraftManifestStore store(Instant clockInstant, UUID... ids) {
        int[] index = {0};
        return new DraftManifestStore(
                Clock.fixed(clockInstant, ZoneOffset.UTC),
                () -> {
                    if (index[0] >= ids.length) {
                        throw new IllegalStateException("Test draft ID supplier exhausted.");
                    }
                    return ids[index[0]++];
                },
                new DraftManifestCodec()
        );
    }

    private static Path writeUtf8(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
        return path;
    }

    private static Optional<UUID> createConcurrently(
            DraftManifestStore store,
            Path draft,
            CountDownLatch ready,
            CountDownLatch start
    ) throws Exception {
        ready.countDown();
        start.await();
        try {
            return Optional.of(store.createNative(
                    draft,
                    "rules.txt",
                    CREATOR,
                    "Book Author",
                    2,
                    CONTENT.length(),
                    CREATED_AT
            ).manifest().draftId());
        } catch (IOException exception) {
            return Optional.empty();
        }
    }

    private static void assertNoTemporaryManifests(Path... directories) throws IOException {
        for (Path directory : directories) {
            if (!Files.exists(directory)) {
                continue;
            }
            try (Stream<Path> paths = Files.walk(directory)) {
                List<Path> leftovers = paths
                        .filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().startsWith(".bookexport-manifest-"))
                        .filter(path -> path.getFileName().toString().endsWith(".tmp"))
                        .toList();
                assertTrue(leftovers.isEmpty(), () -> "Temporary manifest files remain: " + leftovers);
            }
        }
    }

    private record Workflow(Path staging, Path published, Path archive, Path backups) {
        Path[] all() {
            return new Path[]{staging, published, archive, backups};
        }
    }
}
