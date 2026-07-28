package com.mrfloris.exportbook;

/**
 * Audited, read-only interpretation of a durable publication journal and the
 * artifacts it names. No value in this enum authorizes automatic recovery.
 */
enum PublicationRecoveryAssessment {
    ABANDONED_BEFORE_LIVE_COMMIT(RecoverySeverity.WARN),
    BACKUP_CREATED_NO_LIVE_COMMIT(RecoverySeverity.WARN),
    LIVE_COMMIT_NEEDS_CHECKPOINT(RecoverySeverity.CRITICAL),
    CHECKPOINT_NEEDS_ARCHIVE(RecoverySeverity.WARN),
    ARCHIVE_NEEDS_SOURCE_CLEANUP(RecoverySeverity.WARN),
    ARCHIVE_NEEDS_MANIFEST_FINALIZE(RecoverySeverity.WARN),
    COMPLETED_JOURNAL_REMAINS(RecoverySeverity.INFO),
    CONFLICT_REQUIRES_MANUAL_REVIEW(RecoverySeverity.CRITICAL),
    UNREADABLE_REQUIRES_MANUAL_REVIEW(RecoverySeverity.CRITICAL);

    private final RecoverySeverity severity;

    PublicationRecoveryAssessment(RecoverySeverity severity) {
        this.severity = severity;
    }

    RecoverySeverity severity() {
        return severity;
    }
}
