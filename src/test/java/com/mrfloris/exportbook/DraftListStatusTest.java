package com.mrfloris.exportbook;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DraftListStatusTest {
    private static final ContentFingerprint FINGERPRINT = new ContentFingerprint(
            12L,
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    );

    @Test
    void derivesEveryVerifiedReviewState() {
        assertEquals(DraftListStatus.UNREVIEWED, DraftListStatus.from(review(manifest(), DraftIntegrity.VERIFIED)));

        DraftManifest approved = manifest().withReview(
                DraftReviewStatus.APPROVED,
                decision(false)
        );
        assertEquals(DraftListStatus.APPROVED, DraftListStatus.from(review(approved, DraftIntegrity.VERIFIED)));

        DraftManifest changes = manifest().withReview(
                DraftReviewStatus.CHANGES_REQUESTED,
                decision(false)
        );
        assertEquals(
                DraftListStatus.CHANGES_REQUESTED,
                DraftListStatus.from(review(changes, DraftIntegrity.VERIFIED))
        );
    }

    @Test
    void integrityFailuresOverrideRecordedReviewState() {
        DraftManifest approved = manifest().withReview(DraftReviewStatus.APPROVED, decision(false));

        assertEquals(DraftListStatus.CONTENT_CHANGED,
                DraftListStatus.from(review(approved, DraftIntegrity.CONTENT_CHANGED)));
        assertEquals(DraftListStatus.ASSOCIATION_MISMATCH,
                DraftListStatus.from(review(approved, DraftIntegrity.ASSOCIATION_MISMATCH)));
        assertEquals(DraftListStatus.DRAFT_MISSING,
                DraftListStatus.from(new DraftReview(
                        Path.of("rules.txt"),
                        Path.of("rules.txt.bookexport-manifest.properties"),
                        approved,
                        null,
                        DraftIntegrity.DRAFT_MISSING
                )));
    }

    @Test
    void legacyAndUnreviewedRemainCompatiblePublishSuggestions() {
        DraftReview legacy = new DraftReview(
                Path.of("legacy.txt"),
                Path.of("legacy.txt.bookexport-manifest.properties"),
                null,
                FINGERPRINT,
                DraftIntegrity.LEGACY_UNTRACKED
        );

        assertEquals(DraftListStatus.LEGACY_UNTRACKED, DraftListStatus.from(legacy));
        assertTrue(DraftListStatus.LEGACY_UNTRACKED.publishSuggestionAllowed());
        assertTrue(DraftListStatus.UNREVIEWED.publishSuggestionAllowed());
        assertTrue(DraftListStatus.APPROVED.publishSuggestionAllowed());
    }

    @Test
    void unsafeStatesDoNotOfferPublication() {
        for (DraftListStatus status : new DraftListStatus[]{
                DraftListStatus.CHANGES_REQUESTED,
                DraftListStatus.CONTENT_CHANGED,
                DraftListStatus.PUBLICATION_PENDING,
                DraftListStatus.ASSOCIATION_MISMATCH,
                DraftListStatus.DRAFT_MISSING,
                DraftListStatus.CORRUPT
        }) {
            assertFalse(status.publishSuggestionAllowed(), status.name());
        }
    }

    @Test
    void publicationOutcomeOverridesReviewAndIntegritySuggestions() {
        DraftManifest approved = manifest().withApproval(decision(false));
        DraftManifest published = approved.withPublication(
                DraftPublicationStatus.PUBLISHED_ARCHIVE_PENDING,
                new DraftManifest.Publication(
                        Instant.parse("2026-07-14T12:06:00Z"),
                        new DraftManifest.Actor("Publisher", null),
                        PublishCollisionMode.FAIL,
                        "rules.txt",
                        null,
                        null,
                        null
                )
        );

        DraftListStatus status = DraftListStatus.from(review(published, DraftIntegrity.VERIFIED));

        assertEquals(DraftListStatus.PUBLICATION_PENDING, status);
        assertFalse(status.approveSuggestionAllowed());
        assertFalse(status.changesSuggestionAllowed());
        assertFalse(status.publishSuggestionAllowed());
    }

    private static DraftManifest manifest() {
        return DraftManifest.nativeDraft(
                UUID.fromString("123e4567-e89b-12d3-a456-426614174000"),
                Instant.parse("2026-07-14T12:00:00Z"),
                new DraftManifest.Actor("Author", UUID.fromString("223e4567-e89b-12d3-a456-426614174000")),
                "Book Author",
                2,
                24,
                FINGERPRINT,
                "rules.txt",
                "rules.txt"
        );
    }

    private static DraftManifest.ReviewDecision decision(boolean implicit) {
        return new DraftManifest.ReviewDecision(
                Instant.parse("2026-07-14T12:05:00Z"),
                new DraftManifest.Actor("Reviewer", null),
                FINGERPRINT,
                implicit
        );
    }

    private static DraftReview review(DraftManifest manifest, DraftIntegrity integrity) {
        return new DraftReview(
                Path.of("rules.txt"),
                Path.of("rules.txt.bookexport-manifest.properties"),
                manifest,
                FINGERPRINT,
                integrity
        );
    }
}
