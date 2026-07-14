package com.mrfloris.exportbook;

import java.nio.file.Path;

/** Result of a reviewed staged-file publication. */
record PublishResult(
        Path stagedPath,
        Path publishedPath,
        Path archivedPath,
        Path backupPath,
        PublishCollisionMode collisionMode,
        String archiveWarning
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
}
