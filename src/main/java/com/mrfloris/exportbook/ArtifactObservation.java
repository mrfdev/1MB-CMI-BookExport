package com.mrfloris.exportbook;

/** Checksum-based, content-free observation of one journal-named artifact. */
enum ArtifactObservation {
    NOT_APPLICABLE,
    MISSING,
    MATCHES_EXPECTED,
    MATCHES_ORIGINAL,
    MISMATCH,
    AMBIGUOUS,
    UNREADABLE;

    boolean requiresManualReview() {
        return this == MISMATCH || this == AMBIGUOUS || this == UNREADABLE;
    }
}
