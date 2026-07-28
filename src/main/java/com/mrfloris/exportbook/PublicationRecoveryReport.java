package com.mrfloris.exportbook;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Immutable result of one strictly read-only recovery scan. */
record PublicationRecoveryReport(Instant scannedAt, List<PublicationRecoveryEntry> entries) {
    PublicationRecoveryReport {
        Objects.requireNonNull(scannedAt, "scannedAt");
        entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
    }

    static PublicationRecoveryReport empty(Instant scannedAt) {
        return new PublicationRecoveryReport(scannedAt, List.of());
    }

    int count(RecoverySeverity severity) {
        Objects.requireNonNull(severity, "severity");
        return Math.toIntExact(entries.stream().filter(entry -> entry.severity() == severity).count());
    }

    int count(PublicationRecoveryAssessment assessment) {
        Objects.requireNonNull(assessment, "assessment");
        return Math.toIntExact(entries.stream().filter(entry -> entry.assessment() == assessment).count());
    }

    int infoCount() {
        return count(RecoverySeverity.INFO);
    }

    int warningCount() {
        return count(RecoverySeverity.WARN);
    }

    int criticalCount() {
        return count(RecoverySeverity.CRITICAL);
    }

    boolean hasGlobalBlocker() {
        return entries.stream().anyMatch(PublicationRecoveryEntry::globallyBlocksPublication);
    }

    boolean blocksPublication(UUID draftId, String stagedFilename, String publishedFilename) {
        Objects.requireNonNull(draftId, "draftId");
        return entries.stream().anyMatch(entry -> entry.blocksPublication(
                draftId,
                stagedFilename,
                publishedFilename
        ));
    }

    Optional<PublicationRecoveryEntry> find(UUID transactionId) {
        Objects.requireNonNull(transactionId, "transactionId");
        PublicationRecoveryEntry match = null;
        for (PublicationRecoveryEntry entry : entries) {
            if (!transactionId.equals(entry.transactionId())) {
                continue;
            }
            if (match != null) {
                // Duplicate IDs are deliberately not collapsed into one detail view.
                return Optional.empty();
            }
            match = entry;
        }
        return Optional.ofNullable(match);
    }
}
