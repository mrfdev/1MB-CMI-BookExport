package com.mrfloris.exportbook;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Strictly read-only reconciliation of durable publication journals against
 * checksums and manifest metadata. This class never repairs, deletes, moves,
 * republishes, rolls back, or reloads anything.
 */
final class PublicationRecoveryScanner {
    private static final String DIRECTORY_FAILURE_NAME = "journal-directory-unreadable";

    private final ExportSettings settings;
    private final PublicationTransactionStore transactionStore;
    private final Clock clock;
    private final DraftManifestCodec manifestCodec;
    private final String currentWorkflowRootsSha256;

    PublicationRecoveryScanner(
            ExportSettings settings,
            PublicationTransactionStore transactionStore
    ) {
        this(settings, transactionStore, Clock.systemUTC());
    }

    PublicationRecoveryScanner(
            ExportSettings settings,
            PublicationTransactionStore transactionStore,
            Clock clock
    ) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.transactionStore = Objects.requireNonNull(transactionStore, "transactionStore");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.manifestCodec = new DraftManifestCodec();
        this.currentWorkflowRootsSha256 = PublicationTransaction.workflowRootsSha256(
                settings.stagingDirectory(),
                settings.publishedDirectory(),
                settings.archiveDirectory(),
                settings.backupDirectory(),
                settings.transactionDirectory()
        );
    }

    PublicationRecoveryReport scan() {
        Instant scannedAt = clock.instant();
        if (!Files.isDirectory(settings.transactionDirectory(), LinkOption.NOFOLLOW_LINKS)) {
            return new PublicationRecoveryReport(scannedAt, List.of(unreadableDirectory(scannedAt)));
        }
        List<PublicationTransactionStore.JournalEntry> journals;
        try {
            journals = transactionStore.scan();
        } catch (IOException | RuntimeException exception) {
            return new PublicationRecoveryReport(scannedAt, List.of(unreadableDirectory(scannedAt)));
        }

        List<PublicationRecoveryEntry> findings = new ArrayList<>(journals.size());
        for (PublicationTransactionStore.JournalEntry journal : journals) {
            if (!journal.readable()) {
                findings.add(unreadable(journal, scannedAt));
                continue;
            }
            try {
                findings.add(inspectReadable(journal));
            } catch (RuntimeException exception) {
                // A typed journal passed the codec but violated a scanner invariant.
                // Keep the finding global and content-free rather than surfacing details.
                findings.add(unreadable(journal, scannedAt));
            }
        }
        findings.sort(Comparator
                .comparing(PublicationRecoveryEntry::sortTimestamp)
                .reversed()
                .thenComparing(PublicationRecoveryEntry::journalFilename));
        return new PublicationRecoveryReport(scannedAt, findings);
    }

    private PublicationRecoveryEntry inspectReadable(
            PublicationTransactionStore.JournalEntry journal
    ) {
        PublicationTransaction transaction = Objects.requireNonNull(
                journal.transaction(),
                "readable journal transaction"
        );

        ArtifactObservation staged = observeArtifact(
                settings.stagingDirectory(),
                transaction.stagedFilename(),
                transaction.approvedFingerprint(),
                null
        );
        ArtifactObservation published = observeArtifact(
                settings.publishedDirectory(),
                transaction.publishedFilename(),
                transaction.approvedFingerprint(),
                transaction.replacedFingerprint()
        );
        ArtifactObservation archive = observeArtifact(
                settings.archiveDirectory(),
                transaction.archiveFilename(),
                transaction.approvedFingerprint(),
                null
        );
        ArtifactObservation backup = transaction.backupFilename() == null
                ? ArtifactObservation.NOT_APPLICABLE
                : observeArtifact(
                        settings.backupDirectory(),
                        transaction.backupFilename(),
                        transaction.replacedFingerprint(),
                        null
                );
        PublicationRecoveryEntry.ManifestObservation manifest = observeManifest(transaction);

        PublicationRecoveryAssessment assessment = assess(
                transaction,
                staged,
                published,
                archive,
                backup,
                manifest
        );
        return new PublicationRecoveryEntry(
                canonicalJournalName(journal.filenameTransactionId()),
                journal.filenameTransactionId(),
                transaction,
                assessment,
                staged,
                published,
                archive,
                backup,
                manifest,
                transaction.updatedAt()
        );
    }

    private PublicationRecoveryAssessment assess(
            PublicationTransaction transaction,
            ArtifactObservation staged,
            ArtifactObservation published,
            ArtifactObservation archive,
            ArtifactObservation backup,
            PublicationRecoveryEntry.ManifestObservation manifest
    ) {
        if (!transactionMetadataConsistent(transaction)
                || !transaction.workflowRootsSha256().equals(currentWorkflowRootsSha256)
                || staged.requiresManualReview()
                || published.requiresManualReview()
                || archive.requiresManualReview()
                || backup.requiresManualReview()
                || manifest == PublicationRecoveryEntry.ManifestObservation.NOT_FOUND
                || manifest == PublicationRecoveryEntry.ManifestObservation.CONFLICT_OR_UNREADABLE) {
            return PublicationRecoveryAssessment.CONFLICT_REQUIRES_MANUAL_REVIEW;
        }

        boolean replacement = transaction.collisionMode() == PublishCollisionMode.REPLACE_WITH_BACKUP;
        boolean stagedMatches = staged == ArtifactObservation.MATCHES_EXPECTED;
        boolean stagedMissing = staged == ArtifactObservation.MISSING;
        boolean liveMatches = published == ArtifactObservation.MATCHES_EXPECTED;
        boolean liveBeforeCommit = replacement
                ? published == ArtifactObservation.MATCHES_ORIGINAL
                : published == ArtifactObservation.MISSING;
        boolean archiveMatches = archive == ArtifactObservation.MATCHES_EXPECTED;
        boolean archiveMissing = archive == ArtifactObservation.MISSING;
        boolean backupMatches = replacement && backup == ArtifactObservation.MATCHES_EXPECTED;
        boolean backupMissing = replacement && backup == ArtifactObservation.MISSING;

        if ((!stagedMatches && !stagedMissing)
                || (!liveMatches && !liveBeforeCommit)
                || (!archiveMatches && !archiveMissing)
                || (replacement && !backupMatches && !backupMissing)
                || (!replacement && backup != ArtifactObservation.NOT_APPLICABLE)) {
            return PublicationRecoveryAssessment.CONFLICT_REQUIRES_MANUAL_REVIEW;
        }

        // Replacement commits are never allowed before their backup exists.
        if (replacement && liveMatches && !backupMatches) {
            return PublicationRecoveryAssessment.CONFLICT_REQUIRES_MANUAL_REVIEW;
        }

        int factMilestone;
        switch (manifest) {
            case APPROVED_STAGED -> {
                if (!stagedMatches || archiveMatches) {
                    return PublicationRecoveryAssessment.CONFLICT_REQUIRES_MANUAL_REVIEW;
                }
                factMilestone = liveMatches ? 1 : 0;
            }
            case PUBLICATION_CHECKPOINTED -> {
                if (!liveMatches) {
                    return PublicationRecoveryAssessment.CONFLICT_REQUIRES_MANUAL_REVIEW;
                }
                if (archiveMatches) {
                    factMilestone = stagedMissing ? 4 : 3;
                } else {
                    if (!stagedMatches) {
                        return PublicationRecoveryAssessment.CONFLICT_REQUIRES_MANUAL_REVIEW;
                    }
                    factMilestone = 2;
                }
            }
            case FINALIZED -> {
                if (!liveMatches || !archiveMatches || !stagedMissing) {
                    return PublicationRecoveryAssessment.CONFLICT_REQUIRES_MANUAL_REVIEW;
                }
                factMilestone = 5;
            }
            default -> {
                return PublicationRecoveryAssessment.CONFLICT_REQUIRES_MANUAL_REVIEW;
            }
        }

        int stateMilestone = stateMilestone(transaction.state());
        if (factMilestone < stateMilestone || factMilestone > stateMilestone + 1) {
            return PublicationRecoveryAssessment.CONFLICT_REQUIRES_MANUAL_REVIEW;
        }
        if (transaction.state() == PublicationTransactionState.PREPARED
                && replacement
                && factMilestone > 0) {
            // A replacement must durably record BACKUP_CREATED before live commit.
            return PublicationRecoveryAssessment.CONFLICT_REQUIRES_MANUAL_REVIEW;
        }
        if (stateAtLeast(transaction.state(), PublicationTransactionState.BACKUP_CREATED)
                && replacement
                && !backupMatches) {
            return PublicationRecoveryAssessment.CONFLICT_REQUIRES_MANUAL_REVIEW;
        }

        return switch (factMilestone) {
            case 0 -> backupMatches
                    ? PublicationRecoveryAssessment.BACKUP_CREATED_NO_LIVE_COMMIT
                    : PublicationRecoveryAssessment.ABANDONED_BEFORE_LIVE_COMMIT;
            case 1 -> PublicationRecoveryAssessment.LIVE_COMMIT_NEEDS_CHECKPOINT;
            case 2 -> PublicationRecoveryAssessment.CHECKPOINT_NEEDS_ARCHIVE;
            case 3 -> PublicationRecoveryAssessment.ARCHIVE_NEEDS_SOURCE_CLEANUP;
            case 4 -> PublicationRecoveryAssessment.ARCHIVE_NEEDS_MANIFEST_FINALIZE;
            case 5 -> PublicationRecoveryAssessment.COMPLETED_JOURNAL_REMAINS;
            default -> PublicationRecoveryAssessment.CONFLICT_REQUIRES_MANUAL_REVIEW;
        };
    }

    private PublicationRecoveryEntry.ManifestObservation observeManifest(
            PublicationTransaction transaction
    ) {
        NamedFile active = locateNamed(
                settings.stagingDirectory(),
                transaction.stagedFilename() + DraftManifestStore.MANIFEST_SUFFIX
        );
        NamedFile history = locateNamed(
                settings.archiveDirectory(),
                transaction.archiveFilename() + DraftManifestStore.MANIFEST_SUFFIX
        );
        if (active.status() == NamedFileStatus.AMBIGUOUS_OR_UNREADABLE
                || history.status() == NamedFileStatus.AMBIGUOUS_OR_UNREADABLE) {
            return PublicationRecoveryEntry.ManifestObservation.CONFLICT_OR_UNREADABLE;
        }
        if (active.path() == null && history.path() == null) {
            return PublicationRecoveryEntry.ManifestObservation.NOT_FOUND;
        }

        try {
            DraftManifest activeManifest = active.path() == null
                    ? null : manifestCodec.read(active.path());
            DraftManifest finalManifest = history.path() == null
                    ? null : manifestCodec.read(history.path());

            PublicationRecoveryEntry.ManifestObservation activeStatus = activeManifest == null
                    ? null : validateManifest(transaction, activeManifest, false);
            PublicationRecoveryEntry.ManifestObservation finalStatus = finalManifest == null
                    ? null : validateManifest(transaction, finalManifest, true);

            if (activeManifest != null && finalManifest != null) {
                if (activeStatus
                        != PublicationRecoveryEntry.ManifestObservation.PUBLICATION_CHECKPOINTED
                        || finalStatus != PublicationRecoveryEntry.ManifestObservation.FINALIZED) {
                    return PublicationRecoveryEntry.ManifestObservation.CONFLICT_OR_UNREADABLE;
                }
                DraftManifest expectedFinal = activeManifest.withPublication(
                        DraftPublicationStatus.PUBLISHED,
                        activeManifest.publication().withArchiveFilename(transaction.archiveFilename())
                );
                return expectedFinal.equals(finalManifest)
                        ? PublicationRecoveryEntry.ManifestObservation.FINALIZED
                        : PublicationRecoveryEntry.ManifestObservation.CONFLICT_OR_UNREADABLE;
            }
            return activeStatus == null ? finalStatus : activeStatus;
        } catch (IOException | RuntimeException exception) {
            return PublicationRecoveryEntry.ManifestObservation.CONFLICT_OR_UNREADABLE;
        }
    }

    private static PublicationRecoveryEntry.ManifestObservation validateManifest(
            PublicationTransaction transaction,
            DraftManifest manifest,
            boolean archivedSidecar
    ) {
        if (!manifest.draftId().equals(transaction.draftId())
                || !manifest.intendedFilename().equals(transaction.intendedFilename())
                || !manifest.stagedFilename().equals(transaction.stagedFilename())
                || manifest.reviewStatus() != DraftReviewStatus.APPROVED
                || !manifest.effectiveFingerprint().equals(transaction.approvedFingerprint())) {
            return PublicationRecoveryEntry.ManifestObservation.CONFLICT_OR_UNREADABLE;
        }

        if (!archivedSidecar && manifest.publicationStatus() == DraftPublicationStatus.STAGED) {
            return manifest.revision() == transaction.approvedManifestRevision()
                    ? PublicationRecoveryEntry.ManifestObservation.APPROVED_STAGED
                    : PublicationRecoveryEntry.ManifestObservation.CONFLICT_OR_UNREADABLE;
        }

        DraftManifest.Publication publication = manifest.publication();
        if (publication == null
                || !publication.actor().equals(transaction.publisher())
                || publication.collisionMode() != transaction.collisionMode()
                || !publication.publishedFilename().equals(transaction.publishedFilename())
                || !Objects.equals(publication.backupFilename(), transaction.backupFilename())
                || !Objects.equals(publication.backupFingerprint(), transaction.replacedFingerprint())) {
            return PublicationRecoveryEntry.ManifestObservation.CONFLICT_OR_UNREADABLE;
        }

        long checkpointRevision = safeIncrement(transaction.approvedManifestRevision());
        if (!archivedSidecar
                && manifest.publicationStatus() == DraftPublicationStatus.PUBLISHED_ARCHIVE_PENDING
                && publication.archiveFilename() == null
                && manifest.revision() == checkpointRevision) {
            return PublicationRecoveryEntry.ManifestObservation.PUBLICATION_CHECKPOINTED;
        }

        long finalizedRevision = safeIncrement(checkpointRevision);
        if (archivedSidecar
                && manifest.publicationStatus() == DraftPublicationStatus.PUBLISHED
                && transaction.archiveFilename().equals(publication.archiveFilename())
                && manifest.revision() == finalizedRevision) {
            return PublicationRecoveryEntry.ManifestObservation.FINALIZED;
        }
        return PublicationRecoveryEntry.ManifestObservation.CONFLICT_OR_UNREADABLE;
    }

    private static long safeIncrement(long value) {
        try {
            return Math.incrementExact(value);
        } catch (ArithmeticException exception) {
            return Long.MIN_VALUE;
        }
    }

    private static int stateMilestone(PublicationTransactionState state) {
        return switch (state) {
            case PREPARED, BACKUP_CREATED -> 0;
            case LIVE_COMMITTED -> 1;
            case MANIFEST_CHECKPOINTED -> 2;
            case ARCHIVE_CREATED -> 3;
            case STAGED_REMOVED -> 4;
            case FINALIZED -> 5;
        };
    }

    private static boolean transactionMetadataConsistent(PublicationTransaction transaction) {
        if (transaction.collisionMode() != PublishCollisionMode.REPLACE_WITH_BACKUP
                && transaction.state() == PublicationTransactionState.BACKUP_CREATED) {
            return false;
        }
        long expectedRevision = 1L + switch (transaction.state()) {
            case PREPARED -> 0L;
            case BACKUP_CREATED -> 1L;
            case LIVE_COMMITTED -> transaction.collisionMode()
                    == PublishCollisionMode.REPLACE_WITH_BACKUP ? 2L : 1L;
            case MANIFEST_CHECKPOINTED -> transaction.collisionMode()
                    == PublishCollisionMode.REPLACE_WITH_BACKUP ? 3L : 2L;
            case ARCHIVE_CREATED -> transaction.collisionMode()
                    == PublishCollisionMode.REPLACE_WITH_BACKUP ? 4L : 3L;
            case STAGED_REMOVED -> transaction.collisionMode()
                    == PublishCollisionMode.REPLACE_WITH_BACKUP ? 5L : 4L;
            case FINALIZED -> transaction.collisionMode()
                    == PublishCollisionMode.REPLACE_WITH_BACKUP ? 6L : 5L;
        };
        return transaction.revision() == expectedRevision
                && (transaction.revision() != 1L
                || transaction.createdAt().equals(transaction.updatedAt()));
    }

    private static boolean stateAtLeast(
            PublicationTransactionState actual,
            PublicationTransactionState threshold
    ) {
        return actual.ordinal() >= threshold.ordinal();
    }

    private static ArtifactObservation observeArtifact(
            Path directory,
            String filename,
            ContentFingerprint expected,
            ContentFingerprint alternate
    ) {
        NamedFile located = locateNamed(directory, filename);
        if (located.status() == NamedFileStatus.MISSING) {
            return ArtifactObservation.MISSING;
        }
        if (located.status() == NamedFileStatus.AMBIGUOUS_OR_UNREADABLE) {
            return ArtifactObservation.AMBIGUOUS;
        }
        try {
            ContentFingerprint actual = ContentFingerprint.from(located.path());
            if (expected.equals(actual)) {
                return ArtifactObservation.MATCHES_EXPECTED;
            }
            if (alternate != null && alternate.equals(actual)) {
                return ArtifactObservation.MATCHES_ORIGINAL;
            }
            return ArtifactObservation.MISMATCH;
        } catch (IOException | RuntimeException exception) {
            return ArtifactObservation.UNREADABLE;
        }
    }

    private static NamedFile locateNamed(Path directory, String filename) {
        try {
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                return new NamedFile(NamedFileStatus.AMBIGUOUS_OR_UNREADABLE, null);
            }
            List<Path> matches;
            try (Stream<Path> paths = Files.list(directory)) {
                matches = paths
                        .filter(path -> path.getFileName().toString().equalsIgnoreCase(filename))
                        .limit(2)
                        .toList();
            }
            if (matches.isEmpty()) {
                return new NamedFile(NamedFileStatus.MISSING, null);
            }
            if (matches.size() != 1
                    || !Files.isRegularFile(matches.getFirst(), LinkOption.NOFOLLOW_LINKS)) {
                return new NamedFile(NamedFileStatus.AMBIGUOUS_OR_UNREADABLE, null);
            }
            return new NamedFile(NamedFileStatus.PRESENT, matches.getFirst());
        } catch (IOException | RuntimeException exception) {
            return new NamedFile(NamedFileStatus.AMBIGUOUS_OR_UNREADABLE, null);
        }
    }

    private PublicationRecoveryEntry unreadable(
            PublicationTransactionStore.JournalEntry journal,
            Instant fallbackTimestamp
    ) {
        UUID filenameId = journal.filenameTransactionId();
        Instant timestamp = fallbackTimestamp;
        try {
            timestamp = Files.getLastModifiedTime(
                    journal.path(),
                    LinkOption.NOFOLLOW_LINKS
            ).toInstant();
        } catch (IOException | RuntimeException ignored) {
            // Stable scan time is sufficient when even metadata cannot be read.
        }
        return new PublicationRecoveryEntry(
                filenameId == null ? "unreadable-journal" : canonicalJournalName(filenameId),
                filenameId,
                null,
                PublicationRecoveryAssessment.UNREADABLE_REQUIRES_MANUAL_REVIEW,
                ArtifactObservation.NOT_APPLICABLE,
                ArtifactObservation.NOT_APPLICABLE,
                ArtifactObservation.NOT_APPLICABLE,
                ArtifactObservation.NOT_APPLICABLE,
                PublicationRecoveryEntry.ManifestObservation.CONFLICT_OR_UNREADABLE,
                timestamp
        );
    }

    private static PublicationRecoveryEntry unreadableDirectory(Instant timestamp) {
        return new PublicationRecoveryEntry(
                DIRECTORY_FAILURE_NAME,
                null,
                null,
                PublicationRecoveryAssessment.UNREADABLE_REQUIRES_MANUAL_REVIEW,
                ArtifactObservation.NOT_APPLICABLE,
                ArtifactObservation.NOT_APPLICABLE,
                ArtifactObservation.NOT_APPLICABLE,
                ArtifactObservation.NOT_APPLICABLE,
                PublicationRecoveryEntry.ManifestObservation.CONFLICT_OR_UNREADABLE,
                timestamp
        );
    }

    private static String canonicalJournalName(UUID transactionId) {
        return transactionId + PublicationTransactionStore.JOURNAL_SUFFIX;
    }

    private enum NamedFileStatus {
        MISSING,
        PRESENT,
        AMBIGUOUS_OR_UNREADABLE
    }

    private record NamedFile(NamedFileStatus status, Path path) {
    }
}
