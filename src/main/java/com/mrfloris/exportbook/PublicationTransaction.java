package com.mrfloris.exportbook;

import java.time.Instant;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * One immutable, content-free publication write-ahead record.
 *
 * <p>Only basenames and fingerprints are retained. Absolute paths and rendered
 * book content must never enter this record.</p>
 */
record PublicationTransaction(
        int schemaVersion,
        UUID transactionId,
        long revision,
        PublicationTransactionState state,
        Instant createdAt,
        Instant updatedAt,
        UUID draftId,
        long approvedManifestRevision,
        DraftManifest.Actor publisher,
        PublishCollisionMode collisionMode,
        String intendedFilename,
        String stagedFilename,
        String publishedFilename,
        String archiveFilename,
        String backupFilename,
        ContentFingerprint approvedFingerprint,
        ContentFingerprint replacedFingerprint,
        String workflowRootsSha256
) {
    static final int CURRENT_SCHEMA_VERSION = 1;
    private static final int MAXIMUM_FILENAME_LENGTH = 255;
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    PublicationTransaction {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                    "Unsupported publication transaction schema version: " + schemaVersion
            );
        }
        Objects.requireNonNull(transactionId, "transactionId");
        if (revision < 1L) {
            throw new IllegalArgumentException("revision must be positive.");
        }
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(collisionMode, "collisionMode");
        long expectedRevision = expectedRevision(state, collisionMode);
        if (revision != expectedRevision) {
            throw new IllegalArgumentException(
                    "Transaction state and revision do not describe a contiguous protocol."
            );
        }
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt may not precede createdAt.");
        }
        Objects.requireNonNull(draftId, "draftId");
        if (approvedManifestRevision < 1L) {
            throw new IllegalArgumentException("approvedManifestRevision must be positive.");
        }
        Objects.requireNonNull(publisher, "publisher");
        intendedFilename = plainTextFilename(intendedFilename, "intendedFilename");
        stagedFilename = plainTextFilename(stagedFilename, "stagedFilename");
        publishedFilename = plainTextFilename(publishedFilename, "publishedFilename");
        archiveFilename = plainTextFilename(archiveFilename, "archiveFilename");
        Objects.requireNonNull(approvedFingerprint, "approvedFingerprint");

        if (backupFilename == null || backupFilename.isBlank()) {
            backupFilename = null;
            if (replacedFingerprint != null) {
                throw new IllegalArgumentException(
                        "A replaced fingerprint requires a backup filename."
                );
            }
        } else {
            backupFilename = plainTextFilename(backupFilename, "backupFilename");
            Objects.requireNonNull(replacedFingerprint, "replacedFingerprint");
        }
        if (collisionMode == PublishCollisionMode.REPLACE_WITH_BACKUP
                && backupFilename == null) {
            throw new IllegalArgumentException(
                    "Replacement publication requires planned backup metadata."
            );
        }
        if (collisionMode != PublishCollisionMode.REPLACE_WITH_BACKUP
                && backupFilename != null) {
            throw new IllegalArgumentException(
                    "Only replacement publication may include backup metadata."
            );
        }
        workflowRootsSha256 = requireSha256(workflowRootsSha256, "workflowRootsSha256");
    }

    static PublicationTransaction prepared(
            UUID transactionId,
            Instant preparedAt,
            UUID draftId,
            long approvedManifestRevision,
            DraftManifest.Actor publisher,
            PublishCollisionMode collisionMode,
            String intendedFilename,
            String stagedFilename,
            String publishedFilename,
            String archiveFilename,
            String backupFilename,
            ContentFingerprint approvedFingerprint,
            ContentFingerprint replacedFingerprint,
            String workflowRootsSha256
    ) {
        return new PublicationTransaction(
                CURRENT_SCHEMA_VERSION,
                transactionId,
                1L,
                PublicationTransactionState.PREPARED,
                preparedAt,
                preparedAt,
                draftId,
                approvedManifestRevision,
                publisher,
                collisionMode,
                intendedFilename,
                stagedFilename,
                publishedFilename,
                archiveFilename,
                backupFilename,
                approvedFingerprint,
                replacedFingerprint,
                workflowRootsSha256
        );
    }

    static PublicationTransaction prepared(
            UUID transactionId,
            Instant preparedAt,
            DraftManifest approvedManifest,
            DraftManifest.Actor publisher,
            PublishCollisionMode collisionMode,
            String publishedFilename,
            String archiveFilename,
            String backupFilename,
            ContentFingerprint replacedFingerprint,
            String workflowRootsSha256
    ) {
        Objects.requireNonNull(approvedManifest, "approvedManifest");
        if (approvedManifest.reviewStatus() != DraftReviewStatus.APPROVED
                || approvedManifest.publicationStatus() != DraftPublicationStatus.STAGED) {
            throw new IllegalArgumentException(
                    "Publication transaction requires an approved staged manifest."
            );
        }
        return prepared(
                transactionId,
                preparedAt,
                approvedManifest.draftId(),
                approvedManifest.revision(),
                publisher,
                collisionMode,
                approvedManifest.intendedFilename(),
                approvedManifest.stagedFilename(),
                publishedFilename,
                archiveFilename,
                backupFilename,
                approvedManifest.effectiveFingerprint(),
                replacedFingerprint,
                workflowRootsSha256
        );
    }

    PublicationTransaction withState(PublicationTransactionState next, Instant changedAt) {
        Objects.requireNonNull(next, "next");
        Objects.requireNonNull(changedAt, "changedAt");
        requireNextState(next);
        if (changedAt.isBefore(updatedAt)) {
            throw new IllegalArgumentException("Transaction time may not move backwards.");
        }
        long nextRevision;
        try {
            nextRevision = Math.incrementExact(revision);
        } catch (ArithmeticException exception) {
            throw new IllegalStateException("Publication transaction revision overflow.", exception);
        }
        return new PublicationTransaction(
                schemaVersion,
                transactionId,
                nextRevision,
                next,
                createdAt,
                changedAt,
                draftId,
                approvedManifestRevision,
                publisher,
                collisionMode,
                intendedFilename,
                stagedFilename,
                publishedFilename,
                archiveFilename,
                backupFilename,
                approvedFingerprint,
                replacedFingerprint,
                workflowRootsSha256
        );
    }

    /**
     * Hashes the ordered normalized workflow roots without retaining their paths.
     * Length framing prevents ambiguous path concatenation.
     */
    static String workflowRootsSha256(Path... roots) {
        Objects.requireNonNull(roots, "roots");
        if (roots.length == 0) {
            throw new IllegalArgumentException("At least one workflow root is required.");
        }
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("This Java runtime does not provide SHA-256.", exception);
        }
        digest.update("BookExport publication roots v1\n".getBytes(StandardCharsets.US_ASCII));
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(roots.length).array());
        for (Path root : roots) {
            byte[] encoded = Objects.requireNonNull(root, "workflow root")
                    .toAbsolutePath()
                    .normalize()
                    .toString()
                    .getBytes(StandardCharsets.UTF_8);
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(encoded.length).array());
            digest.update(encoded);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private void requireNextState(PublicationTransactionState next) {
        PublicationTransactionState expected = switch (state) {
            case PREPARED -> collisionMode == PublishCollisionMode.REPLACE_WITH_BACKUP
                    ? PublicationTransactionState.BACKUP_CREATED
                    : PublicationTransactionState.LIVE_COMMITTED;
            case BACKUP_CREATED -> PublicationTransactionState.LIVE_COMMITTED;
            case LIVE_COMMITTED -> PublicationTransactionState.MANIFEST_CHECKPOINTED;
            case MANIFEST_CHECKPOINTED -> PublicationTransactionState.ARCHIVE_CREATED;
            case ARCHIVE_CREATED -> PublicationTransactionState.STAGED_REMOVED;
            case STAGED_REMOVED -> PublicationTransactionState.FINALIZED;
            case FINALIZED -> null;
        };
        if (next != expected) {
            throw new IllegalStateException("Invalid publication transaction state transition.");
        }
    }

    private static String plainTextFilename(String value, String label) {
        Objects.requireNonNull(value, label);
        if (value.isBlank()
                || value.length() > MAXIMUM_FILENAME_LENGTH
                || value.indexOf('/') >= 0
                || value.indexOf('\\') >= 0
                || value.codePoints().anyMatch(Character::isISOControl)
                || !hasWellFormedUtf16(value)
                || value.length() <= 4
                || !value.toLowerCase(Locale.ROOT).endsWith(".txt")) {
            throw new IllegalArgumentException(label + " must be one plain .txt filename.");
        }
        return value;
    }

    private static boolean hasWellFormedUtf16(String value) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= value.length()
                        || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    return false;
                }
                index++;
            } else if (Character.isLowSurrogate(current)) {
                return false;
            }
        }
        return true;
    }

    private static long expectedRevision(
            PublicationTransactionState state,
            PublishCollisionMode collisionMode
    ) {
        boolean replacement = collisionMode == PublishCollisionMode.REPLACE_WITH_BACKUP;
        return switch (state) {
            case PREPARED -> 1L;
            case BACKUP_CREATED -> {
                if (!replacement) {
                    throw new IllegalArgumentException(
                            "Only replacement publication may record a backup checkpoint."
                    );
                }
                yield 2L;
            }
            case LIVE_COMMITTED -> replacement ? 3L : 2L;
            case MANIFEST_CHECKPOINTED -> replacement ? 4L : 3L;
            case ARCHIVE_CREATED -> replacement ? 5L : 4L;
            case STAGED_REMOVED -> replacement ? 6L : 5L;
            case FINALIZED -> replacement ? 7L : 6L;
        };
    }

    private static String requireSha256(String value, String label) {
        Objects.requireNonNull(value, label);
        if (!SHA_256.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    label + " must be 64 lowercase hexadecimal characters."
            );
        }
        return value;
    }

    @Override
    public String toString() {
        return "PublicationTransaction[transactionId=" + transactionId
                + ", revision=" + revision
                + ", state=" + state
                + ", draftId=" + draftId
                + ", collisionMode=" + collisionMode
                + ", stagedFilename=" + stagedFilename
                + ", publishedFilename=" + publishedFilename
                + ", archiveFilename=" + archiveFilename
                + ", backupFilename=" + backupFilename + ']';
    }
}
