package com.mrfloris.exportbook;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One read-only, content-free recovery finding. Paths and exception messages
 * are intentionally excluded so command output cannot disclose server layout
 * or rendered book text.
 */
record PublicationRecoveryEntry(
        String journalFilename,
        UUID filenameTransactionId,
        PublicationTransaction transaction,
        PublicationRecoveryAssessment assessment,
        ArtifactObservation stagedArtifact,
        ArtifactObservation publishedArtifact,
        ArtifactObservation archiveArtifact,
        ArtifactObservation backupArtifact,
        ManifestObservation manifestObservation,
        Instant sortTimestamp
) {
    enum ManifestObservation {
        NOT_FOUND,
        APPROVED_STAGED,
        PUBLICATION_CHECKPOINTED,
        FINALIZED,
        CONFLICT_OR_UNREADABLE
    }

    PublicationRecoveryEntry {
        journalFilename = requireBasename(journalFilename);
        Objects.requireNonNull(assessment, "assessment");
        Objects.requireNonNull(stagedArtifact, "stagedArtifact");
        Objects.requireNonNull(publishedArtifact, "publishedArtifact");
        Objects.requireNonNull(archiveArtifact, "archiveArtifact");
        Objects.requireNonNull(backupArtifact, "backupArtifact");
        Objects.requireNonNull(manifestObservation, "manifestObservation");
        Objects.requireNonNull(sortTimestamp, "sortTimestamp");

        if (transaction != null) {
            if (filenameTransactionId == null
                    || !filenameTransactionId.equals(transaction.transactionId())) {
                throw new IllegalArgumentException(
                        "A readable journal must match its filename transaction ID."
                );
            }
            if (assessment == PublicationRecoveryAssessment.UNREADABLE_REQUIRES_MANUAL_REVIEW) {
                throw new IllegalArgumentException("A readable journal may not be assessed as unreadable.");
            }
        } else {
            if (assessment != PublicationRecoveryAssessment.UNREADABLE_REQUIRES_MANUAL_REVIEW) {
                throw new IllegalArgumentException("An unreadable journal requires the unreadable assessment.");
            }
            if (stagedArtifact != ArtifactObservation.NOT_APPLICABLE
                    || publishedArtifact != ArtifactObservation.NOT_APPLICABLE
                    || archiveArtifact != ArtifactObservation.NOT_APPLICABLE
                    || backupArtifact != ArtifactObservation.NOT_APPLICABLE
                    || manifestObservation != ManifestObservation.CONFLICT_OR_UNREADABLE) {
                throw new IllegalArgumentException(
                        "An unreadable journal may not claim artifact or manifest observations."
                );
            }
        }
    }

    UUID transactionId() {
        return transaction == null ? filenameTransactionId : transaction.transactionId();
    }

    RecoverySeverity severity() {
        return assessment.severity();
    }

    boolean metadataAvailable() {
        return transaction != null;
    }

    boolean globallyBlocksPublication() {
        return transaction == null;
    }

    boolean blocksPublication(UUID draftId, String stagedFilename, String publishedFilename) {
        if (assessment == PublicationRecoveryAssessment.COMPLETED_JOURNAL_REMAINS) {
            return false;
        }
        if (globallyBlocksPublication()) {
            return true;
        }
        return transaction.draftId().equals(draftId)
                || equalsFilename(transaction.stagedFilename(), stagedFilename)
                || equalsFilename(transaction.publishedFilename(), publishedFilename);
    }

    private static boolean equalsFilename(String left, String right) {
        return left != null && right != null && left.equalsIgnoreCase(right);
    }

    private static String requireBasename(String value) {
        Objects.requireNonNull(value, "journalFilename");
        if (value.isBlank()
                || value.indexOf('/') >= 0
                || value.indexOf('\\') >= 0
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("journalFilename must be one plain basename.");
        }
        return value;
    }
}
