package com.mrfloris.exportbook;

import java.nio.file.Path;
import java.util.Objects;

/** Content-free review result for a staged path and its optional sidecar. */
record DraftReview(
        Path draftPath,
        Path manifestPath,
        DraftManifest manifest,
        ContentFingerprint actualFingerprint,
        DraftIntegrity integrity
) {
    DraftReview {
        Objects.requireNonNull(draftPath, "draftPath");
        Objects.requireNonNull(manifestPath, "manifestPath");
        Objects.requireNonNull(integrity, "integrity");
        if (integrity == DraftIntegrity.LEGACY_UNTRACKED && manifest != null) {
            throw new IllegalArgumentException("An untracked legacy draft may not have a manifest.");
        }
        if (integrity != DraftIntegrity.LEGACY_UNTRACKED
                && integrity != DraftIntegrity.DRAFT_MISSING
                && manifest == null) {
            throw new IllegalArgumentException("Tracked integrity states require a manifest.");
        }
        if (integrity == DraftIntegrity.DRAFT_MISSING && actualFingerprint != null) {
            throw new IllegalArgumentException("A missing draft may not have an actual fingerprint.");
        }
        if (integrity != DraftIntegrity.DRAFT_MISSING && actualFingerprint == null) {
            throw new IllegalArgumentException("An existing draft requires an actual fingerprint.");
        }
    }

    boolean tracked() {
        return manifest != null;
    }

    boolean publishable() {
        return integrity == DraftIntegrity.VERIFIED
                && manifest != null
                && manifest.reviewStatus() == DraftReviewStatus.APPROVED
                && manifest.publicationStatus() == DraftPublicationStatus.STAGED;
    }

    @Override
    public String toString() {
        return "DraftReview[draft=" + draftPath.getFileName()
                + ", tracked=" + tracked()
                + ", integrity=" + integrity
                + ", reviewStatus=" + (manifest == null ? "none" : manifest.reviewStatus())
                + ", publicationStatus=" + (manifest == null ? "none" : manifest.publicationStatus()) + ']';
    }
}
