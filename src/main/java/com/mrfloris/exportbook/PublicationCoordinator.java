package com.mrfloris.exportbook;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

/** Coordinates the durable reviewed-publication protocol as one in-process writer. */
final class PublicationCoordinator {
    private final Clock clock;
    private final DraftManifestStore manifestStore;
    private final PublicationTransactionStore transactionStore;
    private final PublicationFaultInjector faultInjector;

    PublicationCoordinator(
            Clock clock,
            DraftManifestStore manifestStore,
            PublicationTransactionStore transactionStore
    ) {
        this(clock, manifestStore, transactionStore, PublicationFaultInjector.none());
    }

    PublicationCoordinator(
            Clock clock,
            DraftManifestStore manifestStore,
            PublicationTransactionStore transactionStore,
            PublicationFaultInjector faultInjector
    ) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.manifestStore = Objects.requireNonNull(manifestStore, "manifestStore");
        this.transactionStore = Objects.requireNonNull(transactionStore, "transactionStore");
        this.faultInjector = Objects.requireNonNull(faultInjector, "faultInjector");
    }

    synchronized PublishResult publish(
            ExportSettings settings,
            String stagedFilename,
            PublishCollisionMode collisionMode,
            DraftManifest.Actor publisher
    ) throws BookExportException {
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(collisionMode, "collisionMode");
        Objects.requireNonNull(publisher, "publisher");

        Path stagedPath = resolveStaged(settings, stagedFilename);
        DraftReview approved = verifyApproved(stagedPath, publisher);
        UUID transactionId = UUID.randomUUID();
        PublicationPlan plan;
        try {
            plan = BookFileStore.planPublication(
                    settings.stagingDirectory(),
                    settings.publishedDirectory(),
                    settings.archiveDirectory(),
                    settings.backupDirectory(),
                    stagedFilename,
                    collisionMode,
                    settings.maximumFilenameLength(),
                    clock,
                    transactionId,
                    approved.manifest().effectiveFingerprint()
            );
        } catch (IOException exception) {
            throw new BookExportException(
                    "Unable to prepare the reviewed publication. No live file was changed.",
                    exception
            );
        }

        PublicationRecoveryReport recovery = recoveryReport(settings);
        if (recovery.hasGlobalBlocker()) {
            throw new BookExportException(
                    "Publication is blocked because the recovery journal contains unreadable records. "
                            + "A recovery auditor must inspect it first."
            );
        }
        if (recovery.blocksPublication(
                approved.manifest().draftId(),
                plan.stagedPath().getFileName().toString(),
                plan.publishedPath().getFileName().toString()
        )) {
            throw new BookExportException(
                    "This draft or planned live filename has an unresolved publication transaction. "
                            + "Do not retry it until a recovery auditor reconciles the journal."
            );
        }

        String workflowRoots = PublicationTransaction.workflowRootsSha256(
                settings.stagingDirectory(),
                settings.publishedDirectory(),
                settings.archiveDirectory(),
                settings.backupDirectory(),
                settings.transactionDirectory()
        );
        PublicationTransaction transaction = PublicationTransaction.prepared(
                transactionId,
                clock.instant(),
                approved.manifest(),
                publisher,
                collisionMode,
                plan.publishedPath().getFileName().toString(),
                plan.archivePath().getFileName().toString(),
                filename(plan.backupPath()),
                plan.originalPublishedFingerprint(),
                workflowRoots
        );
        try {
            transaction = transactionStore.create(transaction);
            faultInjector.check(PublicationBoundary.PREPARED_DURABLE);
        } catch (IOException exception) {
            throw new BookExportException(
                    "Unable to durably prepare publication; no backup or live mutation was authorized.",
                    exception
            );
        }

        if (plan.replacesPublishedFile()) {
            try {
                BookFileStore.createReplacementBackup(plan);
                faultInjector.check(PublicationBoundary.BACKUP_FILE_COMMITTED);
                transaction = transactionStore.update(
                        transaction,
                        PublicationTransactionState.BACKUP_CREATED
                );
                faultInjector.check(PublicationBoundary.BACKUP_CREATED_DURABLE);
            } catch (IOException | BookExportException exception) {
                throw unresolvedBeforeLive(exception);
            }
        }

        PublishResult result = null;
        try {
            result = BookFileStore.commitLive(plan);
            faultInjector.check(PublicationBoundary.LIVE_FILE_COMMITTED);
        } catch (IOException | BookExportException exception) {
            if (result != null) {
                return result.withManifest(null, liveWarning());
            }
            throw new BookExportException(
                    "Publication stopped with an unresolved transaction. The live outcome may be uncertain; "
                            + "do not retry before recovery review.",
                    exception
            );
        }

        try {
            transaction = transactionStore.update(
                    transaction,
                    PublicationTransactionState.LIVE_COMMITTED
            );
            faultInjector.check(PublicationBoundary.LIVE_COMMITTED_DURABLE);
        } catch (IOException exception) {
            return result.withManifest(null, liveWarning());
        }

        DraftManifest pendingManifest;
        try {
            pendingManifest = manifestStore.checkpointCommittedPublication(
                    result.stagedPath(),
                    approved.manifest(),
                    result.publishedPath(),
                    result.backupPath(),
                    result.backupFingerprint(),
                    collisionMode,
                    publisher
            );
            faultInjector.check(PublicationBoundary.MANIFEST_CHECKPOINT_COMMITTED);
        } catch (IOException exception) {
            return result.withManifest(null, liveWarning());
        }
        result = result.withManifest(pendingManifest, null);

        try {
            transaction = transactionStore.update(
                    transaction,
                    PublicationTransactionState.MANIFEST_CHECKPOINTED
            );
            faultInjector.check(PublicationBoundary.MANIFEST_CHECKPOINTED_DURABLE);
        } catch (IOException exception) {
            return result.withManifest(pendingManifest, journalWarning("manifest checkpoint"));
        }

        Path archivedPath = null;
        try {
            archivedPath = BookFileStore.createArchive(plan);
            faultInjector.check(PublicationBoundary.ARCHIVE_FILE_COMMITTED);
        } catch (IOException | BookExportException exception) {
            String warning = archivedPath == null
                    ? "Publication is live and checkpointed, but archival is incomplete. "
                    : "The archive exists, but its transaction checkpoint is incomplete. ";
            return result.withArchiveOutcome(archivedPath, warning
                    + "The staged source was not deliberately removed; do not retry publication.");
        }
        result = result.withArchiveOutcome(archivedPath, null);

        try {
            transaction = transactionStore.update(
                    transaction,
                    PublicationTransactionState.ARCHIVE_CREATED
            );
            faultInjector.check(PublicationBoundary.ARCHIVE_CREATED_DURABLE);
        } catch (IOException exception) {
            return result.withArchiveOutcome(
                    archivedPath,
                    journalWarning("archive creation")
            );
        }

        try {
            BookFileStore.removeStagedAfterArchive(plan);
            faultInjector.check(PublicationBoundary.STAGED_FILE_REMOVED);
        } catch (IOException | BookExportException exception) {
            return result.withArchiveOutcome(
                    archivedPath,
                    "The live file and archive exist, but staged-source cleanup is incomplete or uncertain. "
                            + "Do not retry publication; inspect recovery status."
            );
        }

        try {
            transaction = transactionStore.update(
                    transaction,
                    PublicationTransactionState.STAGED_REMOVED
            );
            faultInjector.check(PublicationBoundary.STAGED_REMOVED_DURABLE);
        } catch (IOException exception) {
            return result.withManifest(
                    pendingManifest,
                    journalWarning("staged-source cleanup")
            );
        }

        DraftManifest finalizedManifest;
        try {
            finalizedManifest = manifestStore.finalizePublication(
                    plan.stagedPath(),
                    plan.publishedPath(),
                    plan.archivePath(),
                    plan.backupPath(),
                    plan.originalPublishedFingerprint(),
                    collisionMode,
                    publisher,
                    faultInjector
            );
            faultInjector.check(PublicationBoundary.MANIFEST_FINALIZED);
        } catch (IOException exception) {
            return result.withManifest(
                    pendingManifest,
                    "The live file and archive exist, but manifest finalization is incomplete or uncertain. "
                            + "Do not retry publication; inspect recovery status."
            );
        }
        result = result.withManifest(finalizedManifest, null);

        try {
            transaction = transactionStore.update(
                    transaction,
                    PublicationTransactionState.FINALIZED
            );
            faultInjector.check(PublicationBoundary.FINALIZED_DURABLE);
        } catch (IOException exception) {
            return result.withManifest(
                    finalizedManifest,
                    journalWarning("manifest finalization")
            );
        }

        try {
            transactionStore.delete(transaction);
            faultInjector.check(PublicationBoundary.JOURNAL_DELETED);
        } catch (IOException exception) {
            return result.withManifest(
                    finalizedManifest,
                    "Publication is fully finalized, but its completed transaction journal remains. "
                            + "Read-only recovery inspection can verify the residual record."
            );
        }
        return result;
    }

    PublicationRecoveryReport recoveryReport(ExportSettings settings) {
        return new PublicationRecoveryScanner(settings, transactionStore, clock).scan();
    }

    private Path resolveStaged(ExportSettings settings, String stagedFilename)
            throws BookExportException {
        try {
            return BookFileStore.resolveStagedFile(settings.stagingDirectory(), stagedFilename);
        } catch (IOException exception) {
            throw new BookExportException("Unable to access the staged file.", exception);
        }
    }

    private DraftReview verifyApproved(Path stagedPath, DraftManifest.Actor publisher)
            throws BookExportException {
        try {
            return manifestStore.verifyOrAdoptBeforePublish(stagedPath, publisher);
        } catch (IOException exception) {
            String message = exception.getMessage();
            throw new BookExportException(
                    message == null || message.isBlank()
                            ? "Unable to verify the staged draft for publication."
                            : message,
                    exception
            );
        }
    }

    private static BookExportException unresolvedBeforeLive(Exception cause) {
        return new BookExportException(
                "Publication stopped before the live commit, but a durable transaction remains for "
                        + "backup/checksum review. Do not retry it yet.",
                cause
        );
    }

    private static String liveWarning() {
        return "The live file was committed, but the durable publication workflow is incomplete. "
                + "Do not publish this draft again; inspect read-only recovery status.";
    }

    private static String journalWarning(String completedPhase) {
        return "The " + completedPhase + " completed, but its journal state could not be advanced. "
                + "Do not retry publication; inspect read-only recovery status.";
    }

    private static String filename(Path path) {
        return path == null ? null : path.getFileName().toString();
    }
}
