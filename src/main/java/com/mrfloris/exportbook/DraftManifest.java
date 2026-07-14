package com.mrfloris.exportbook;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Versioned, content-free metadata associated with one exact staged export. */
record DraftManifest(
        int schemaVersion,
        UUID draftId,
        long revision,
        DraftOrigin origin,
        DraftReviewStatus reviewStatus,
        DraftPublicationStatus publicationStatus,
        Instant createdAt,
        Actor createdBy,
        String bookAuthor,
        int sourcePages,
        int sourceUtf16Units,
        ContentFingerprint contentFingerprint,
        String intendedFilename,
        String stagedFilename,
        ReviewDecision reviewDecision,
        Publication publication
) {
    static final int CURRENT_SCHEMA_VERSION = 1;
    private static final int MAXIMUM_TEXT_FIELD_LENGTH = 4_096;

    DraftManifest {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported draft manifest schema version: " + schemaVersion);
        }
        Objects.requireNonNull(draftId, "draftId");
        if (revision < 1L) {
            throw new IllegalArgumentException("revision must be positive.");
        }
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(reviewStatus, "reviewStatus");
        Objects.requireNonNull(publicationStatus, "publicationStatus");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(createdBy, "createdBy");
        bookAuthor = boundedText(bookAuthor, "bookAuthor", true);
        if (sourcePages < -1) {
            throw new IllegalArgumentException("sourcePages must be -1 or greater.");
        }
        if (sourceUtf16Units < -1) {
            throw new IllegalArgumentException("sourceUtf16Units must be -1 or greater.");
        }
        if (origin == DraftOrigin.NATIVE && (sourcePages < 0 || sourceUtf16Units < 0)) {
            throw new IllegalArgumentException("Native manifests require known source counts.");
        }
        if (origin == DraftOrigin.LEGACY_ADOPTED
                && (!bookAuthor.isEmpty() || sourcePages != -1 || sourceUtf16Units != -1)) {
            throw new IllegalArgumentException("Adopted legacy manifests require explicitly unknown source metadata.");
        }
        Objects.requireNonNull(contentFingerprint, "contentFingerprint");
        intendedFilename = plainTextFilename(intendedFilename, "intendedFilename");
        stagedFilename = plainTextFilename(stagedFilename, "stagedFilename");

        if (reviewStatus == DraftReviewStatus.UNREVIEWED && reviewDecision != null) {
            throw new IllegalArgumentException("Unreviewed manifests may not have a review decision.");
        }
        if (reviewStatus != DraftReviewStatus.UNREVIEWED && reviewDecision == null) {
            throw new IllegalArgumentException("Reviewed manifests require a review decision.");
        }
        if (reviewStatus == DraftReviewStatus.CHANGES_REQUESTED && reviewDecision.implicit()) {
            throw new IllegalArgumentException("A changes-requested decision may not be implicit.");
        }
        if (publicationStatus == DraftPublicationStatus.STAGED && publication != null) {
            throw new IllegalArgumentException("Staged manifests may not have publication details.");
        }
        if (publicationStatus != DraftPublicationStatus.STAGED && publication == null) {
            throw new IllegalArgumentException("Published manifests require publication details.");
        }
        if (publicationStatus != DraftPublicationStatus.STAGED
                && reviewStatus != DraftReviewStatus.APPROVED) {
            throw new IllegalArgumentException("Published manifests require an approved review decision.");
        }
        if (publicationStatus == DraftPublicationStatus.PUBLISHED
                && (publication.archiveFilename() == null || publication.archiveFilename().isBlank())) {
            throw new IllegalArgumentException("Published manifests require an archive filename.");
        }
        if (publicationStatus == DraftPublicationStatus.PUBLISHED_ARCHIVE_PENDING
                && publication.archiveFilename() != null) {
            throw new IllegalArgumentException("Archive-pending manifests may not claim an archive filename.");
        }
    }

    static DraftManifest nativeDraft(
            UUID draftId,
            Instant createdAt,
            Actor creator,
            String bookAuthor,
            int sourcePages,
            int sourceUtf16Units,
            ContentFingerprint fingerprint,
            String intendedFilename,
            String stagedFilename
    ) {
        return new DraftManifest(
                CURRENT_SCHEMA_VERSION,
                draftId,
                1L,
                DraftOrigin.NATIVE,
                DraftReviewStatus.UNREVIEWED,
                DraftPublicationStatus.STAGED,
                createdAt,
                creator,
                bookAuthor,
                sourcePages,
                sourceUtf16Units,
                fingerprint,
                intendedFilename,
                stagedFilename,
                null,
                null
        );
    }

    static DraftManifest adoptedLegacy(
            UUID draftId,
            Instant adoptedAt,
            Actor adopter,
            ContentFingerprint fingerprint,
            String stagedFilename
    ) {
        return new DraftManifest(
                CURRENT_SCHEMA_VERSION,
                draftId,
                1L,
                DraftOrigin.LEGACY_ADOPTED,
                DraftReviewStatus.UNREVIEWED,
                DraftPublicationStatus.STAGED,
                adoptedAt,
                adopter,
                "",
                -1,
                -1,
                fingerprint,
                stagedFilename,
                stagedFilename,
                null,
                null
        );
    }

    DraftManifest withReview(DraftReviewStatus status, ReviewDecision decision) {
        if (publicationStatus != DraftPublicationStatus.STAGED) {
            throw new IllegalStateException("A published draft may not receive another review decision.");
        }
        return new DraftManifest(
                schemaVersion,
                draftId,
                nextRevision(),
                origin,
                status,
                publicationStatus,
                createdAt,
                createdBy,
                bookAuthor,
                sourcePages,
                sourceUtf16Units,
                contentFingerprint,
                intendedFilename,
                stagedFilename,
                decision,
                publication
        );
    }

    DraftManifest withApproval(ReviewDecision decision) {
        Objects.requireNonNull(decision, "decision");
        if (publicationStatus != DraftPublicationStatus.STAGED) {
            throw new IllegalStateException("A published draft may not receive another approval.");
        }
        return new DraftManifest(
                schemaVersion,
                draftId,
                nextRevision(),
                origin,
                DraftReviewStatus.APPROVED,
                publicationStatus,
                createdAt,
                createdBy,
                bookAuthor,
                sourcePages,
                sourceUtf16Units,
                contentFingerprint,
                intendedFilename,
                stagedFilename,
                decision,
                publication
        );
    }

    ContentFingerprint effectiveFingerprint() {
        if (reviewDecision != null) {
            return reviewDecision.fingerprint();
        }
        return contentFingerprint;
    }

    DraftManifest withPublication(DraftPublicationStatus status, Publication details) {
        if (reviewStatus != DraftReviewStatus.APPROVED) {
            throw new IllegalStateException("Only an approved draft may be published.");
        }
        return new DraftManifest(
                schemaVersion,
                draftId,
                nextRevision(),
                origin,
                reviewStatus,
                status,
                createdAt,
                createdBy,
                bookAuthor,
                sourcePages,
                sourceUtf16Units,
                contentFingerprint,
                intendedFilename,
                stagedFilename,
                reviewDecision,
                details
        );
    }

    private long nextRevision() {
        try {
            return Math.incrementExact(revision);
        } catch (ArithmeticException exception) {
            throw new IllegalStateException("Draft manifest revision overflow.", exception);
        }
    }

    private static String boundedText(String value, String label, boolean allowEmpty) {
        Objects.requireNonNull(value, label);
        if (!allowEmpty && value.isBlank()) {
            throw new IllegalArgumentException(label + " may not be blank.");
        }
        if (value.length() > MAXIMUM_TEXT_FIELD_LENGTH) {
            throw new IllegalArgumentException(label + " is too long.");
        }
        if (!hasWellFormedUtf16(value)) {
            throw new IllegalArgumentException(label + " contains malformed Unicode.");
        }
        return value;
    }

    private static boolean hasWellFormedUtf16(String value) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    return false;
                }
                index++;
            } else if (Character.isLowSurrogate(current)) {
                return false;
            }
        }
        return true;
    }

    private static String plainTextFilename(String value, String label) {
        String filename = boundedText(value, label, false);
        if (filename.indexOf('/') >= 0
                || filename.indexOf('\\') >= 0
                || filename.codePoints().anyMatch(Character::isISOControl)
                || !filename.toLowerCase(java.util.Locale.ROOT).endsWith(".txt")
                || filename.length() <= 4) {
            throw new IllegalArgumentException(label + " must be one plain .txt filename.");
        }
        return filename;
    }

    @Override
    public String toString() {
        return "DraftManifest[draftId=" + draftId
                + ", revision=" + revision
                + ", origin=" + origin
                + ", reviewStatus=" + reviewStatus
                + ", publicationStatus=" + publicationStatus
                + ", fingerprint=" + contentFingerprint
                + ", intendedFilename=" + intendedFilename
                + ", stagedFilename=" + stagedFilename + ']';
    }

    /** Actor metadata; UUID is absent for console or adopted legacy activity. */
    record Actor(String name, UUID uuid) {
        Actor {
            name = boundedText(name, "actor name", false);
        }

        @Override
        public String toString() {
            return "Actor[name=" + name + ", uuid=" + (uuid == null ? "none" : uuid) + ']';
        }
    }

    /** Review action over one exact fingerprint. */
    record ReviewDecision(
            Instant at,
            Actor actor,
            ContentFingerprint fingerprint,
            boolean implicit
    ) {
        ReviewDecision {
            Objects.requireNonNull(at, "at");
            Objects.requireNonNull(actor, "actor");
            Objects.requireNonNull(fingerprint, "fingerprint");
        }

        @Override
        public String toString() {
            return "ReviewDecision[at=" + at + ", actor=" + actor
                    + ", fingerprint=" + fingerprint + ", implicit=" + implicit + ']';
        }
    }

    /** Actual publication filenames and actor; no rendered book content is retained. */
    record Publication(
            Instant at,
            Actor actor,
            PublishCollisionMode collisionMode,
            String publishedFilename,
            String archiveFilename,
            String backupFilename,
            ContentFingerprint backupFingerprint
    ) {
        Publication {
            Objects.requireNonNull(at, "at");
            Objects.requireNonNull(actor, "actor");
            Objects.requireNonNull(collisionMode, "collisionMode");
            publishedFilename = plainTextFilename(publishedFilename, "publishedFilename");
            if (archiveFilename != null && !archiveFilename.isBlank()) {
                archiveFilename = plainTextFilename(archiveFilename, "archiveFilename");
            } else {
                archiveFilename = null;
            }
            if (backupFilename != null && !backupFilename.isBlank()) {
                backupFilename = plainTextFilename(backupFilename, "backupFilename");
                Objects.requireNonNull(backupFingerprint, "backupFingerprint");
            } else {
                backupFilename = null;
                if (backupFingerprint != null) {
                    throw new IllegalArgumentException("A backup fingerprint requires a backup filename.");
                }
            }
            if (collisionMode == PublishCollisionMode.REPLACE_WITH_BACKUP && backupFilename == null) {
                throw new IllegalArgumentException("Replacement publication requires backup metadata.");
            }
            if (collisionMode != PublishCollisionMode.REPLACE_WITH_BACKUP && backupFilename != null) {
                throw new IllegalArgumentException("Only replacement publication may include a backup.");
            }
        }

        Publication withArchiveFilename(String filename) {
            return new Publication(
                    at,
                    actor,
                    collisionMode,
                    publishedFilename,
                    filename,
                    backupFilename,
                    backupFingerprint
            );
        }

        @Override
        public String toString() {
            return "Publication[at=" + at
                    + ", actor=" + actor
                    + ", collisionMode=" + collisionMode
                    + ", publishedFilename=" + publishedFilename
                    + ", archiveFilename=" + archiveFilename
                    + ", backupFilename=" + backupFilename + ']';
        }
    }
}
