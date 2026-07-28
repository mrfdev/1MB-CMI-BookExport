package com.mrfloris.exportbook;

import java.util.Locale;
import java.util.Optional;

/** Durable publication checkpoints recorded by the crash-recovery journal. */
enum PublicationTransactionState {
    PREPARED("prepared"),
    BACKUP_CREATED("backup-created"),
    LIVE_COMMITTED("live-committed"),
    MANIFEST_CHECKPOINTED("manifest-checkpointed"),
    ARCHIVE_CREATED("archive-created"),
    STAGED_REMOVED("staged-removed"),
    FINALIZED("finalized");

    private final String key;

    PublicationTransactionState(String key) {
        this.key = key;
    }

    String key() {
        return key;
    }

    static Optional<PublicationTransactionState> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (PublicationTransactionState state : values()) {
            if (state.key.equals(normalized)) {
                return Optional.of(state);
            }
        }
        return Optional.empty();
    }
}
