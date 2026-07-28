package com.mrfloris.exportbook;

import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable, content-free publication paths and checksums selected before the
 * first live, backup, or archive mutation.
 */
record PublicationPlan(
        UUID transactionId,
        Path stagedPath,
        Path publishedPath,
        Path archivePath,
        Path backupPath,
        ContentFingerprint expectedFingerprint,
        ContentFingerprint originalPublishedFingerprint,
        PublishCollisionMode collisionMode
) {
    PublicationPlan {
        Objects.requireNonNull(transactionId, "transactionId");
        stagedPath = normalizedPath(stagedPath, "stagedPath");
        publishedPath = normalizedPath(publishedPath, "publishedPath");
        archivePath = normalizedPath(archivePath, "archivePath");
        Objects.requireNonNull(expectedFingerprint, "expectedFingerprint");
        Objects.requireNonNull(collisionMode, "collisionMode");

        boolean replacement = collisionMode == PublishCollisionMode.REPLACE_WITH_BACKUP;
        if (replacement) {
            backupPath = normalizedPath(backupPath, "backupPath");
            Objects.requireNonNull(originalPublishedFingerprint, "originalPublishedFingerprint");
        } else if (backupPath != null || originalPublishedFingerprint != null) {
            throw new IllegalArgumentException(
                    "Only replace-with-backup plans may contain backup publication metadata."
            );
        }
    }

    boolean replacesPublishedFile() {
        return collisionMode == PublishCollisionMode.REPLACE_WITH_BACKUP;
    }

    @Override
    public String toString() {
        return "PublicationPlan[transactionId=" + transactionId
                + ", stagedFilename=" + stagedPath.getFileName()
                + ", publishedFilename=" + publishedPath.getFileName()
                + ", archiveFilename=" + archivePath.getFileName()
                + ", backupFilename=" + (backupPath == null ? null : backupPath.getFileName())
                + ", collisionMode=" + collisionMode + ']';
    }

    private static Path normalizedPath(Path path, String label) {
        Objects.requireNonNull(path, label);
        Path normalized = path.normalize();
        if (normalized.getFileName() == null || normalized.getParent() == null) {
            throw new IllegalArgumentException(label + " must identify a file inside a directory.");
        }
        return normalized;
    }
}
