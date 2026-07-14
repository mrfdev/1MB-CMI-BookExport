package com.mrfloris.exportbook;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DraftManifestTest {
    private static final UUID DRAFT_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID CREATOR_ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final UUID REVIEWER_ID = UUID.fromString("12345678-1234-5678-9abc-def012345678");
    private static final Instant CREATED_AT = Instant.parse("2026-07-14T10:02:03.456Z");
    private static final Instant REVIEWED_AT = Instant.parse("2026-07-14T11:12:13.456Z");
    private static final Instant PUBLISHED_AT = Instant.parse("2026-07-14T12:22:23.456Z");
    private static final ContentFingerprint CONTENT = fingerprint(24L, 'a');
    private static final ContentFingerprint OTHER_CONTENT = fingerprint(24L, 'b');
    private static final DraftManifest.Actor CREATOR = new DraftManifest.Actor("AuthorPlayer", CREATOR_ID);
    private static final DraftManifest.Actor REVIEWER = new DraftManifest.Actor("ReviewAdmin", REVIEWER_ID);

    @Test
    void nativeFactoryRecordsExactMetadata() {
        DraftManifest manifest = nativeDraft("Written by 你好");

        assertEquals(DraftManifest.CURRENT_SCHEMA_VERSION, manifest.schemaVersion());
        assertEquals(DRAFT_ID, manifest.draftId());
        assertEquals(1L, manifest.revision());
        assertEquals(DraftOrigin.NATIVE, manifest.origin());
        assertEquals(DraftReviewStatus.UNREVIEWED, manifest.reviewStatus());
        assertEquals(DraftPublicationStatus.STAGED, manifest.publicationStatus());
        assertEquals(CREATED_AT, manifest.createdAt());
        assertEquals(CREATOR, manifest.createdBy());
        assertEquals("Written by 你好", manifest.bookAuthor());
        assertEquals(3, manifest.sourcePages());
        assertEquals(81, manifest.sourceUtf16Units());
        assertEquals(CONTENT, manifest.contentFingerprint());
        assertEquals("rules.txt", manifest.intendedFilename());
        assertEquals("rules_1.txt", manifest.stagedFilename());
        assertNull(manifest.reviewDecision());
        assertNull(manifest.publication());
    }

    @Test
    void legacyFactoryMarksUnknownSourceMetadataExplicitly() {
        DraftManifest manifest = DraftManifest.adoptedLegacy(
                DRAFT_ID,
                CREATED_AT,
                CREATOR,
                CONTENT,
                "legacy draft.txt"
        );

        assertEquals(DraftOrigin.LEGACY_ADOPTED, manifest.origin());
        assertEquals(-1, manifest.sourcePages());
        assertEquals(-1, manifest.sourceUtf16Units());
        assertEquals("", manifest.bookAuthor());
        assertEquals("legacy draft.txt", manifest.intendedFilename());
        assertEquals("legacy draft.txt", manifest.stagedFilename());
    }

    @Test
    void approveChangesRequestAndReapproveAdvanceRevisionWithoutLosingCreationData() {
        DraftManifest initial = nativeDraft("Book Author");
        DraftManifest.ReviewDecision requestedChanges = new DraftManifest.ReviewDecision(
                REVIEWED_AT,
                REVIEWER,
                OTHER_CONTENT,
                false
        );
        DraftManifest changes = initial.withReview(DraftReviewStatus.CHANGES_REQUESTED, requestedChanges);
        DraftManifest.ReviewDecision approval = new DraftManifest.ReviewDecision(
                REVIEWED_AT.plusSeconds(60),
                REVIEWER,
                CONTENT,
                false
        );
        DraftManifest approved = changes.withReview(DraftReviewStatus.APPROVED, approval);

        assertEquals(2L, changes.revision());
        assertEquals(DraftReviewStatus.CHANGES_REQUESTED, changes.reviewStatus());
        assertEquals(requestedChanges, changes.reviewDecision());
        assertEquals(OTHER_CONTENT, changes.effectiveFingerprint());
        assertEquals(3L, approved.revision());
        assertEquals(DraftReviewStatus.APPROVED, approved.reviewStatus());
        assertEquals(approval, approved.reviewDecision());
        assertEquals(initial.createdAt(), approved.createdAt());
        assertEquals(initial.createdBy(), approved.createdBy());
        assertEquals(initial.contentFingerprint(), approved.contentFingerprint());
    }

    @Test
    void publicationCanMoveFromPendingToArchivedAndRecordsBackupMetadata() {
        DraftManifest approved = approvedDraft();
        DraftManifest.Publication pendingDetails = new DraftManifest.Publication(
                PUBLISHED_AT,
                REVIEWER,
                PublishCollisionMode.REPLACE_WITH_BACKUP,
                "rules.txt",
                null,
                "20260714_backup_rules.txt",
                OTHER_CONTENT
        );

        DraftManifest pending = approved.withPublication(
                DraftPublicationStatus.PUBLISHED_ARCHIVE_PENDING,
                pendingDetails
        );
        DraftManifest.Publication finalDetails = pendingDetails.withArchiveFilename(
                "20260714_published_rules.txt"
        );
        DraftManifest published = pending.withPublication(DraftPublicationStatus.PUBLISHED, finalDetails);

        assertEquals(3L, pending.revision());
        assertEquals(DraftPublicationStatus.PUBLISHED_ARCHIVE_PENDING, pending.publicationStatus());
        assertNull(pending.publication().archiveFilename());
        assertEquals(4L, published.revision());
        assertEquals(DraftPublicationStatus.PUBLISHED, published.publicationStatus());
        assertEquals("20260714_published_rules.txt", published.publication().archiveFilename());
        assertEquals("20260714_backup_rules.txt", published.publication().backupFilename());
        assertEquals(OTHER_CONTENT, published.publication().backupFingerprint());
    }

    @Test
    void rejectsInvalidRecordCombinationsAndFilenames() {
        DraftManifest nativeManifest = nativeDraft("Book Author");
        DraftManifest.ReviewDecision approval = new DraftManifest.ReviewDecision(
                REVIEWED_AT,
                REVIEWER,
                CONTENT,
                false
        );

        assertThrows(IllegalArgumentException.class, () -> copy(nativeManifest, 2, 1L,
                DraftOrigin.NATIVE, DraftReviewStatus.UNREVIEWED, DraftPublicationStatus.STAGED,
                3, 81, CONTENT, "rules.txt", "rules.txt", null, null));
        assertThrows(IllegalArgumentException.class, () -> copy(nativeManifest, 1, 0L,
                DraftOrigin.NATIVE, DraftReviewStatus.UNREVIEWED, DraftPublicationStatus.STAGED,
                3, 81, CONTENT, "rules.txt", "rules.txt", null, null));
        assertThrows(IllegalArgumentException.class, () -> copy(nativeManifest, 1, 1L,
                DraftOrigin.NATIVE, DraftReviewStatus.UNREVIEWED, DraftPublicationStatus.STAGED,
                -1, 81, CONTENT, "rules.txt", "rules.txt", null, null));
        assertThrows(IllegalArgumentException.class, () -> copy(nativeManifest, 1, 1L,
                DraftOrigin.NATIVE, DraftReviewStatus.UNREVIEWED, DraftPublicationStatus.STAGED,
                3, 81, CONTENT, "../rules.txt", "rules.txt", null, null));
        assertThrows(IllegalArgumentException.class, () -> copy(nativeManifest, 1, 1L,
                DraftOrigin.NATIVE, DraftReviewStatus.UNREVIEWED, DraftPublicationStatus.STAGED,
                3, 81, CONTENT, "rules.txt", "rules.yml", null, null));
        assertThrows(IllegalArgumentException.class, () -> copy(nativeManifest, 1, 1L,
                DraftOrigin.NATIVE, DraftReviewStatus.UNREVIEWED, DraftPublicationStatus.STAGED,
                3, 81, CONTENT, "rules.txt", "rules.txt", approval, null));
        assertThrows(IllegalArgumentException.class, () -> copy(nativeManifest, 1, 1L,
                DraftOrigin.NATIVE, DraftReviewStatus.APPROVED, DraftPublicationStatus.STAGED,
                3, 81, CONTENT, "rules.txt", "rules.txt", null, null));
        DraftManifest reboundApproval = copy(nativeManifest, 1, 1L,
                DraftOrigin.NATIVE, DraftReviewStatus.APPROVED, DraftPublicationStatus.STAGED,
                3, 81, CONTENT, "rules.txt", "rules.txt",
                new DraftManifest.ReviewDecision(REVIEWED_AT, REVIEWER, OTHER_CONTENT, false), null);
        assertEquals(CONTENT, reboundApproval.contentFingerprint());
        assertEquals(OTHER_CONTENT, reboundApproval.effectiveFingerprint());
        assertThrows(IllegalArgumentException.class, () -> copy(nativeManifest, 1, 1L,
                DraftOrigin.NATIVE, DraftReviewStatus.UNREVIEWED, DraftPublicationStatus.PUBLISHED,
                3, 81, CONTENT, "rules.txt", "rules.txt", null, null));
    }

    @Test
    void rejectsPublicationWithoutApprovalAndPublishedRecordWithoutArchive() {
        DraftManifest manifest = nativeDraft("Book Author");
        DraftManifest.Publication pending = new DraftManifest.Publication(
                PUBLISHED_AT,
                REVIEWER,
                PublishCollisionMode.FAIL,
                "rules.txt",
                null,
                null,
                null
        );

        assertThrows(IllegalStateException.class, () -> manifest.withPublication(
                DraftPublicationStatus.PUBLISHED_ARCHIVE_PENDING,
                pending
        ));

        DraftManifest approved = approvedDraft();
        assertThrows(IllegalArgumentException.class, () -> approved.withPublication(
                DraftPublicationStatus.PUBLISHED,
                pending
        ));
        DraftManifest.Publication incorrectlyArchivedPending = new DraftManifest.Publication(
                PUBLISHED_AT,
                REVIEWER,
                PublishCollisionMode.FAIL,
                "rules.txt",
                "published_rules.txt",
                null,
                null
        );
        assertThrows(IllegalArgumentException.class, () -> approved.withPublication(
                DraftPublicationStatus.PUBLISHED_ARCHIVE_PENDING,
                incorrectlyArchivedPending
        ));
    }

    @Test
    void adoptedLegacyManifestRequiresExplicitlyUnknownSourceMetadata() {
        DraftManifest nativeManifest = nativeDraft("Book Author");

        assertThrows(IllegalArgumentException.class, () -> copy(
                nativeManifest,
                1,
                1L,
                DraftOrigin.LEGACY_ADOPTED,
                DraftReviewStatus.UNREVIEWED,
                DraftPublicationStatus.STAGED,
                -1,
                -1,
                CONTENT,
                "rules.txt",
                "rules.txt",
                null,
                null
        ));
    }

    @Test
    void malformedUtf16MetadataIsRejectedBeforeEncoding() {
        assertThrows(IllegalArgumentException.class, () -> nativeDraft("bad\ud800author"));
        assertThrows(IllegalArgumentException.class, () -> new DraftManifest.Actor("bad\udc00actor", null));
    }

    @Test
    void rejectsOrphanedBackupMetadata() {
        assertThrows(IllegalArgumentException.class, () -> new DraftManifest.Publication(
                PUBLISHED_AT,
                REVIEWER,
                PublishCollisionMode.FAIL,
                "rules.txt",
                null,
                null,
                OTHER_CONTENT
        ));
        assertThrows(NullPointerException.class, () -> new DraftManifest.Publication(
                PUBLISHED_AT,
                REVIEWER,
                PublishCollisionMode.REPLACE_WITH_BACKUP,
                "rules.txt",
                null,
                "backup.txt",
                null
        ));
    }

    @Test
    void contentFreeToStringDoesNotExposeBookAuthorSentinel() {
        String sentinel = "TOP_SECRET_BOOK_PAGE_7F91";
        DraftManifest manifest = nativeDraft(sentinel);

        String output = manifest.toString();

        assertFalse(output.contains(sentinel));
        assertFalse(output.contains("Book Author"));
        assertTrue(output.contains("reviewStatus=UNREVIEWED"));
        assertTrue(output.contains("publicationStatus=STAGED"));
    }

    private static DraftManifest approvedDraft() {
        DraftManifest initial = nativeDraft("Book Author");
        return initial.withReview(
                DraftReviewStatus.APPROVED,
                new DraftManifest.ReviewDecision(REVIEWED_AT, REVIEWER, CONTENT, false)
        );
    }

    private static DraftManifest nativeDraft(String bookAuthor) {
        return DraftManifest.nativeDraft(
                DRAFT_ID,
                CREATED_AT,
                CREATOR,
                bookAuthor,
                3,
                81,
                CONTENT,
                "rules.txt",
                "rules_1.txt"
        );
    }

    private static DraftManifest copy(
            DraftManifest source,
            int schemaVersion,
            long revision,
            DraftOrigin origin,
            DraftReviewStatus reviewStatus,
            DraftPublicationStatus publicationStatus,
            int pages,
            int utf16Units,
            ContentFingerprint fingerprint,
            String intendedFilename,
            String stagedFilename,
            DraftManifest.ReviewDecision decision,
            DraftManifest.Publication publication
    ) {
        return new DraftManifest(
                schemaVersion,
                source.draftId(),
                revision,
                origin,
                reviewStatus,
                publicationStatus,
                source.createdAt(),
                source.createdBy(),
                source.bookAuthor(),
                pages,
                utf16Units,
                fingerprint,
                intendedFilename,
                stagedFilename,
                decision,
                publication
        );
    }

    private static ContentFingerprint fingerprint(long bytes, char hexadecimal) {
        return new ContentFingerprint(bytes, Character.toString(hexadecimal).repeat(64));
    }
}
