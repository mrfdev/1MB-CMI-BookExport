package com.mrfloris.exportbook;

/** Aggregate, content-free health counters for active and finalized manifests. */
record ManifestHealth(
        int managed,
        int untracked,
        int unreviewed,
        int approved,
        int changesRequested,
        int modified,
        int publicationPending,
        int orphanSidecars,
        int creationMarkers,
        int errors,
        int publishedHistory,
        boolean historyAvailable
) {
}
