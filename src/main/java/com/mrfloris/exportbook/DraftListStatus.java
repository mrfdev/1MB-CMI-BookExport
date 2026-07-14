package com.mrfloris.exportbook;

import java.util.Objects;

/** Pure staged-row state derived from review metadata and current byte integrity. */
enum DraftListStatus {
    UNREVIEWED("Unreviewed", true, true, true),
    APPROVED("Approved", true, true, true),
    CHANGES_REQUESTED("Changes requested", false, true, false),
    CONTENT_CHANGED("Changed", false, true, true),
    LEGACY_UNTRACKED("Legacy", true, true, true),
    PUBLICATION_PENDING("Already published", false, false, false),
    ASSOCIATION_MISMATCH("Mismatch", false, false, false),
    DRAFT_MISSING("Missing", false, false, false),
    CORRUPT("Corrupt", false, false, false);

    private final String label;
    private final boolean publishSuggestionAllowed;
    private final boolean approveSuggestionAllowed;
    private final boolean changesSuggestionAllowed;

    DraftListStatus(
            String label,
            boolean publishSuggestionAllowed,
            boolean approveSuggestionAllowed,
            boolean changesSuggestionAllowed
    ) {
        this.label = label;
        this.publishSuggestionAllowed = publishSuggestionAllowed;
        this.approveSuggestionAllowed = approveSuggestionAllowed;
        this.changesSuggestionAllowed = changesSuggestionAllowed;
    }

    static DraftListStatus from(DraftReview review) {
        Objects.requireNonNull(review, "review");
        if (review.tracked()
                && review.manifest().publicationStatus() != DraftPublicationStatus.STAGED) {
            return PUBLICATION_PENDING;
        }
        return switch (review.integrity()) {
            case LEGACY_UNTRACKED -> LEGACY_UNTRACKED;
            case CONTENT_CHANGED -> CONTENT_CHANGED;
            case ASSOCIATION_MISMATCH -> ASSOCIATION_MISMATCH;
            case DRAFT_MISSING -> DRAFT_MISSING;
            case VERIFIED -> fromReviewStatus(review.manifest().reviewStatus());
        };
    }

    private static DraftListStatus fromReviewStatus(DraftReviewStatus status) {
        return switch (status) {
            case UNREVIEWED -> UNREVIEWED;
            case APPROVED -> APPROVED;
            case CHANGES_REQUESTED -> CHANGES_REQUESTED;
        };
    }

    String label() {
        return label;
    }

    boolean publishSuggestionAllowed() {
        return publishSuggestionAllowed;
    }

    boolean approveSuggestionAllowed() {
        return approveSuggestionAllowed;
    }

    boolean changesSuggestionAllowed() {
        return changesSuggestionAllowed;
    }
}
