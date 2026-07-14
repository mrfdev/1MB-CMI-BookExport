package com.mrfloris.exportbook;

import java.nio.file.Path;

/** Result of a reviewed staged-file publication. */
record PublishResult(
        Path stagedPath,
        Path publishedPath,
        Path archivedPath,
        Path backupPath,
        ContentFingerprint backupFingerprint,
        PublishCollisionMode collisionMode,
        String archiveWarning,
        DraftManifest manifest,
        String manifestWarning
) {
    boolean archived() {
        return archivedPath != null;
    }

    boolean replaced() {
        return backupPath != null;
    }

    boolean hasArchiveWarning() {
        return archiveWarning != null && !archiveWarning.isBlank();
    }

    boolean hasManifestWarning() {
        return manifestWarning != null && !manifestWarning.isBlank();
    }

    PublishResult withManifest(DraftManifest finalizedManifest, String warning) {
        return new PublishResult(
                stagedPath,
                publishedPath,
                archivedPath,
                backupPath,
                backupFingerprint,
                collisionMode,
                archiveWarning,
                finalizedManifest,
                warning
        );
    }

    PublishResult withArchiveOutcome(Path archive, String warning) {
        return new PublishResult(
                stagedPath,
                publishedPath,
                archive,
                backupPath,
                backupFingerprint,
                collisionMode,
                warning,
                manifest,
                manifestWarning
        );
    }
}
