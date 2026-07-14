package com.mrfloris.exportbook;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;

/** Atomic sidecar storage and content-free review transitions for staged drafts. */
final class DraftManifestStore {
    static final String MANIFEST_SUFFIX = ".bookexport-manifest.properties";
    static final String CREATION_MARKER_SUFFIX = ".bookexport-creating";
    private static final String TEMPORARY_PREFIX = ".bookexport-manifest-";
    private static final String TEMPORARY_SUFFIX = ".tmp";

    private final Clock clock;
    private final Supplier<UUID> draftIds;
    private final DraftManifestCodec codec;

    DraftManifestStore() {
        this(Clock.systemUTC());
    }

    DraftManifestStore(Clock clock) {
        this(clock, UUID::randomUUID, new DraftManifestCodec());
    }

    DraftManifestStore(Clock clock, Supplier<UUID> draftIds, DraftManifestCodec codec) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.draftIds = Objects.requireNonNull(draftIds, "draftIds");
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    static Path sidecarPath(Path textPath) {
        Objects.requireNonNull(textPath, "textPath");
        Path filename = textPath.getFileName();
        if (filename == null || !isTextFilename(filename.toString())) {
            throw new IllegalArgumentException("Manifest association requires one .txt filename.");
        }
        return textPath.resolveSibling(filename + MANIFEST_SUFFIX);
    }

    static Path markerPath(Path textPath) {
        Objects.requireNonNull(textPath, "textPath");
        Path filename = textPath.getFileName();
        if (filename == null || !isTextFilename(filename.toString())) {
            throw new IllegalArgumentException("Creation marker association requires one .txt filename.");
        }
        return textPath.resolveSibling(filename + CREATION_MARKER_SUFFIX);
    }

    synchronized DraftReview createNative(
            Path stagedPath,
            String intendedFilename,
            DraftManifest.Actor creator,
            String bookAuthor,
            int sourcePages,
            int sourceUtf16Units,
            Instant createdAt
    ) throws IOException {
        Objects.requireNonNull(createdAt, "createdAt");
        Path draft = normalized(stagedPath);
        requireRegularFile(draft, "Staged draft");
        Path sidecar = sidecarPath(draft);
        requireNoCaseInsensitiveMatch(sidecar, "A manifest already exists for this staged draft.");

        ContentFingerprint before = ContentFingerprint.from(draft);
        UUID draftId = Objects.requireNonNull(draftIds.get(), "draftIds returned null");
        DraftManifest manifest;
        try {
            manifest = DraftManifest.nativeDraft(
                    draftId,
                    createdAt,
                    creator,
                    bookAuthor,
                    sourcePages,
                    sourceUtf16Units,
                    before,
                    intendedFilename,
                    draft.getFileName().toString()
            );
        } catch (IllegalArgumentException exception) {
            throw new IOException("Unable to create valid native draft metadata.", exception);
        }

        createAtomically(sidecar, manifest);
        try {
            ContentFingerprint after = ContentFingerprint.from(draft);
            if (!before.equals(after)) {
                throw new IOException("Staged draft changed while its manifest was being created.");
            }
            DraftReview created = review(draft, sidecar, manifest, after);
            deleteCreationMarkerIfPresent(draft);
            return created;
        } catch (IOException exception) {
            try {
                deleteIfMatchingManifest(sidecar, manifest);
            } catch (IOException cleanupException) {
                exception.addSuppressed(cleanupException);
            }
            throw exception;
        }
    }

    synchronized DraftReview inspect(Path draftPath) throws IOException {
        Path draft = normalized(draftPath);
        Path expectedSidecar = sidecarPath(draft);
        Optional<Path> resolvedSidecar = findUniqueCaseInsensitiveMatch(expectedSidecar);
        Path sidecar = resolvedSidecar.orElse(expectedSidecar);
        boolean manifestExists = resolvedSidecar.isPresent();
        boolean draftExists = Files.exists(draft, LinkOption.NOFOLLOW_LINKS);

        if (!manifestExists) {
            Optional<Path> creationMarker = findUniqueCaseInsensitiveMatch(markerPath(draft));
            if (creationMarker.isPresent()) {
                throw new IOException("Staged draft creation is incomplete; it may not be adopted or published.");
            }
            if (!draftExists) {
                return new DraftReview(draft, sidecar, null, null, DraftIntegrity.DRAFT_MISSING);
            }
            requireRegularFile(draft, "Legacy staged draft");
            ContentFingerprint actual = ContentFingerprint.from(draft);
            return new DraftReview(
                    draft,
                    sidecar,
                    null,
                    actual,
                    DraftIntegrity.LEGACY_UNTRACKED
            );
        }

        DraftManifest manifest = codec.read(sidecar);
        if (!draftExists) {
            return new DraftReview(draft, sidecar, manifest, null, DraftIntegrity.DRAFT_MISSING);
        }
        requireRegularFile(draft, "Staged draft");
        ContentFingerprint actual = ContentFingerprint.from(draft);
        return review(draft, sidecar, manifest, actual);
    }

    synchronized DraftReview approve(Path draftPath, DraftManifest.Actor reviewer) throws IOException {
        DraftReview current = inspect(draftPath);
        if (current.integrity() == DraftIntegrity.LEGACY_UNTRACKED) {
            current = adoptLegacy(current.draftPath(), reviewer);
        }
        requireApprovable(current);
        DraftManifest updated = current.manifest().withApproval(
                new DraftManifest.ReviewDecision(
                        clock.instant(),
                        reviewer,
                        current.actualFingerprint(),
                        false
                )
        );
        updateAtomically(current.manifestPath(), current.manifest(), updated);
        DraftReview approved = inspect(current.draftPath());
        requireDecisionStillMatches(approved, "Draft changed again immediately after approval.");
        if (!approved.publishable()) {
            throw new IOException("Approved draft did not reach a verified publishable state.");
        }
        return approved;
    }

    synchronized DraftReview requestChanges(Path draftPath, DraftManifest.Actor reviewer) throws IOException {
        DraftReview current = inspect(draftPath);
        if (current.integrity() == DraftIntegrity.LEGACY_UNTRACKED) {
            current = adoptLegacy(current.draftPath(), reviewer);
        }
        requireExistingTrackedDraft(current);
        if (current.manifest().publicationStatus() != DraftPublicationStatus.STAGED) {
            throw new IOException("A published draft may not receive another review decision.");
        }
        DraftManifest updated = current.manifest().withReview(
                DraftReviewStatus.CHANGES_REQUESTED,
                new DraftManifest.ReviewDecision(
                        clock.instant(),
                        reviewer,
                        current.actualFingerprint(),
                        false
                )
        );
        updateAtomically(current.manifestPath(), current.manifest(), updated);
        DraftReview changes = inspect(current.draftPath());
        requireDecisionStillMatches(changes, "Draft changed again while changes were requested.");
        return changes;
    }

    synchronized DraftReview verifyOrAdoptBeforePublish(
            Path draftPath,
            DraftManifest.Actor publisher
    ) throws IOException {
        DraftReview current = inspect(draftPath);
        if (current.integrity() == DraftIntegrity.LEGACY_UNTRACKED) {
            current = adoptLegacy(current.draftPath(), publisher);
        }
        requireReviewable(current);
        if (current.manifest().reviewStatus() == DraftReviewStatus.CHANGES_REQUESTED) {
            throw new IOException("This draft has requested changes and may not be published.");
        }
        if (current.manifest().reviewStatus() == DraftReviewStatus.UNREVIEWED) {
            DraftManifest updated = current.manifest().withReview(
                    DraftReviewStatus.APPROVED,
                    new DraftManifest.ReviewDecision(
                            clock.instant(),
                            publisher,
                            current.actualFingerprint(),
                            true
                    )
            );
            updateAtomically(current.manifestPath(), current.manifest(), updated);
            current = inspect(current.draftPath());
            requireDecisionStillMatches(
                    current,
                    "Draft changed immediately after implicit publication approval."
            );
        }
        if (!current.publishable()) {
            throw new IOException("Draft review state does not permit publication.");
        }
        return current;
    }

    synchronized DraftManifest markPublicationPending(
            Path stagedPath,
            Path publishedPath,
            Path backupPath,
            PublishCollisionMode collisionMode,
            DraftManifest.Actor publisher
    ) throws IOException {
        ContentFingerprint capturedBackup = backupPath == null
                ? null : ContentFingerprint.from(normalized(backupPath));
        return markPublicationPending(
                stagedPath,
                publishedPath,
                backupPath,
                capturedBackup,
                collisionMode,
                publisher
        );
    }

    synchronized DraftManifest markPublicationPending(
            Path stagedPath,
            Path publishedPath,
            Path backupPath,
            ContentFingerprint backupFingerprint,
            PublishCollisionMode collisionMode,
            DraftManifest.Actor publisher
    ) throws IOException {
        Path draft = normalized(stagedPath);
        Path sidecar = requireExistingCaseInsensitiveMatch(sidecarPath(draft));
        DraftManifest current = readActiveManifest(draft, sidecar);
        requireExpectedFingerprint(current, ContentFingerprint.from(draft), "Staged draft");
        ContentFingerprint publishedFingerprint = ContentFingerprint.from(normalized(publishedPath));
        requireExpectedFingerprint(current, publishedFingerprint, "Published file");

        DraftManifest.Publication publication = publication(
                current,
                publishedPath,
                null,
                backupPath,
                backupFingerprint,
                collisionMode,
                publisher
        );
        if (current.publicationStatus() == DraftPublicationStatus.PUBLISHED_ARCHIVE_PENDING) {
            requireSamePublication(current.publication(), publication, false);
            return current;
        }
        requireApprovedStaged(current);
        DraftManifest updated = current.withPublication(
                DraftPublicationStatus.PUBLISHED_ARCHIVE_PENDING,
                publication
        );
        updateAtomically(sidecar, current, updated);
        return updated;
    }

    /**
     * Checkpoints a live commit immediately after the caller atomically moved a
     * preverified temporary file. The caller guarantees that the committed live
     * bytes matched the manifest; later archival/finalization verifies them again.
     */
    synchronized DraftManifest checkpointCommittedPublication(
            Path stagedPath,
            Path publishedPath,
            Path backupPath,
            PublishCollisionMode collisionMode,
            DraftManifest.Actor publisher
    ) throws IOException {
        ContentFingerprint capturedBackup = backupPath == null
                ? null : ContentFingerprint.from(normalized(backupPath));
        return checkpointCommittedPublication(
                stagedPath,
                publishedPath,
                backupPath,
                capturedBackup,
                collisionMode,
                publisher
        );
    }

    /** See the caller contract on the overload without a captured backup. */
    synchronized DraftManifest checkpointCommittedPublication(
            Path stagedPath,
            Path publishedPath,
            Path backupPath,
            ContentFingerprint backupFingerprint,
            PublishCollisionMode collisionMode,
            DraftManifest.Actor publisher
    ) throws IOException {
        Path draft = normalized(stagedPath);
        Path sidecar = requireExistingCaseInsensitiveMatch(sidecarPath(draft));
        DraftManifest current = readActiveManifest(draft, sidecar);
        requireExpectedFingerprint(current, ContentFingerprint.from(draft), "Staged draft");

        DraftManifest.Publication publication = publication(
                current,
                publishedPath,
                null,
                backupPath,
                backupFingerprint,
                collisionMode,
                publisher
        );
        if (current.publicationStatus() == DraftPublicationStatus.PUBLISHED_ARCHIVE_PENDING) {
            requireSamePublication(current.publication(), publication, false);
            return current;
        }
        requireApprovedStaged(current);
        DraftManifest updated = current.withPublication(
                DraftPublicationStatus.PUBLISHED_ARCHIVE_PENDING,
                publication
        );
        updateAtomically(sidecar, current, updated);
        return updated;
    }

    /**
     * Crash-closes an already committed live move using the exact approved
     * manifest and backup fingerprint captured before that move. This method
     * deliberately does not reread mutable staged, live, or backup bytes.
     */
    synchronized DraftManifest checkpointCommittedPublication(
            Path stagedPath,
            DraftManifest expectedApproved,
            Path publishedPath,
            Path backupPath,
            ContentFingerprint capturedBackupFingerprint,
            PublishCollisionMode collisionMode,
            DraftManifest.Actor publisher
    ) throws IOException {
        Objects.requireNonNull(expectedApproved, "expectedApproved");
        Path draft = normalized(stagedPath);
        Path sidecar = requireExistingCaseInsensitiveMatch(sidecarPath(draft));
        DraftManifest current = readActiveManifest(draft, sidecar);
        BackupMetadata backup = capturedBackup(backupPath, capturedBackupFingerprint);

        if (current.publicationStatus() == DraftPublicationStatus.PUBLISHED_ARCHIVE_PENDING) {
            DraftManifest expectedPending = expectedApproved.withPublication(
                    DraftPublicationStatus.PUBLISHED_ARCHIVE_PENDING,
                    current.publication()
            );
            if (!current.equals(expectedPending)) {
                throw new IOException("Pending manifest is not the exact successor of the approved checkpoint token.");
            }
            DraftManifest.Publication supplied = new DraftManifest.Publication(
                    current.publication().at(),
                    current.publication().actor(),
                    collisionMode,
                    normalized(publishedPath).getFileName().toString(),
                    null,
                    backup.filename(),
                    backup.fingerprint()
            );
            requireSamePublication(current.publication(), supplied, true);
            return current;
        }

        if (!current.equals(expectedApproved)) {
            throw new IOException("Active manifest changed after publication preflight; checkpoint fails closed.");
        }
        requireApprovedStaged(current);
        DraftManifest.Publication publication = new DraftManifest.Publication(
                clock.instant(),
                publisher,
                collisionMode,
                normalized(publishedPath).getFileName().toString(),
                null,
                backup.filename(),
                backup.fingerprint()
        );
        DraftManifest updated = current.withPublication(
                DraftPublicationStatus.PUBLISHED_ARCHIVE_PENDING,
                publication
        );
        updateAtomically(sidecar, current, updated);
        return updated;
    }

    synchronized DraftManifest finalizePublication(
            Path stagedPath,
            Path publishedPath,
            Path archivedPath,
            Path backupPath,
            PublishCollisionMode collisionMode,
            DraftManifest.Actor publisher
    ) throws IOException {
        ContentFingerprint capturedBackup = backupPath == null
                ? null : ContentFingerprint.from(normalized(backupPath));
        return finalizePublication(
                stagedPath,
                publishedPath,
                archivedPath,
                backupPath,
                capturedBackup,
                collisionMode,
                publisher
        );
    }

    synchronized DraftManifest finalizePublication(
            Path stagedPath,
            Path publishedPath,
            Path archivedPath,
            Path backupPath,
            ContentFingerprint backupFingerprint,
            PublishCollisionMode collisionMode,
            DraftManifest.Actor publisher
    ) throws IOException {
        Path draft = normalized(stagedPath);
        Path expectedActiveSidecar = sidecarPath(draft);
        Optional<Path> resolvedActiveSidecar = findUniqueCaseInsensitiveMatch(expectedActiveSidecar);
        Path activeSidecar = resolvedActiveSidecar.orElse(expectedActiveSidecar);
        Path archive = normalized(archivedPath);
        Path expectedHistorySidecar = sidecarPath(archive);
        Optional<Path> resolvedHistorySidecar = findUniqueCaseInsensitiveMatch(expectedHistorySidecar);
        Path historySidecar = resolvedHistorySidecar.orElse(expectedHistorySidecar);
        if (samePathCaseInsensitive(activeSidecar, historySidecar)) {
            throw new IOException("Staging and archive manifest paths must be distinct.");
        }

        if (resolvedActiveSidecar.isEmpty()) {
            if (resolvedHistorySidecar.isEmpty()) {
                throw new IOException("No active or finalized manifest exists for this publication.");
            }
            DraftManifest finalized = codec.read(historySidecar);
            verifyFinalizedManifest(finalized, publishedPath, archive);
            verifyPublicationArguments(
                    finalized.publication(),
                    publishedPath,
                    archive,
                    backupPath,
                    backupFingerprint,
                    collisionMode
            );
            return finalized;
        }

        DraftManifest current = readActiveManifest(draft, activeSidecar);
        if (Files.exists(draft, LinkOption.NOFOLLOW_LINKS)) {
            requireExpectedFingerprint(current, ContentFingerprint.from(draft), "Staged draft");
        }
        requireExpectedFingerprint(current, ContentFingerprint.from(normalized(publishedPath)), "Published file");
        requireExpectedFingerprint(current, ContentFingerprint.from(archive), "Archived draft");

        if (resolvedHistorySidecar.isPresent()) {
            DraftManifest existing = codec.read(historySidecar);
            DraftManifest expected = expectedFinalSuccessor(
                    current,
                    existing,
                    publishedPath,
                    archive,
                    backupPath,
                    backupFingerprint,
                    collisionMode
            );
            requireSameFinalManifest(expected, existing);
            deleteActiveSidecar(activeSidecar);
            return existing;
        }

        DraftManifest.Publication completePublication;
        if (current.publicationStatus() == DraftPublicationStatus.PUBLISHED_ARCHIVE_PENDING) {
            DraftManifest.Publication supplied = publication(
                    current,
                    publishedPath,
                    archive,
                    backupPath,
                    backupFingerprint,
                    collisionMode,
                    publisher
            );
            requireSamePublication(current.publication(), supplied, false);
            completePublication = current.publication().withArchiveFilename(archive.getFileName().toString());
        } else {
            requireApprovedStaged(current);
            completePublication = publication(
                    current,
                    publishedPath,
                    archive,
                    backupPath,
                    backupFingerprint,
                    collisionMode,
                    publisher
            );
        }

        DraftManifest finalized = current.withPublication(
                DraftPublicationStatus.PUBLISHED,
                completePublication
        );
        createAtomically(historySidecar, finalized);
        deleteActiveSidecar(activeSidecar);
        return finalized;
    }

    synchronized List<DraftManifest> listArchiveHistory(Path archiveDirectory) throws IOException {
        return listManifests(archiveDirectory, Set.of(DraftPublicationStatus.PUBLISHED), true, true);
    }

    synchronized Optional<DraftManifest> findArchiveById(
            Path archiveDirectory,
            UUID draftId
    ) throws IOException {
        Objects.requireNonNull(draftId, "draftId");
        return uniqueById(listArchiveHistory(archiveDirectory), draftId);
    }

    synchronized List<DraftManifest> listHistory(
            Path stagingDirectory,
            Path archiveDirectory
    ) throws IOException {
        List<DraftManifest> history = new ArrayList<>(listHistoryMetadata(stagingDirectory, archiveDirectory));
        for (DraftManifest manifest : history) {
            verifyHistoryContent(manifest, stagingDirectory, archiveDirectory);
        }
        return List.copyOf(history);
    }

    synchronized List<DraftManifest> listHistoryMetadata(
            Path stagingDirectory,
            Path archiveDirectory
    ) throws IOException {
        List<DraftManifest> combined = new ArrayList<>();
        combined.addAll(listActiveHistoryManifests(stagingDirectory));
        combined.addAll(listManifests(
                archiveDirectory,
                Set.of(DraftPublicationStatus.PUBLISHED),
                true,
                false
        ));
        List<DraftManifest> reconciled = reconcileHistory(combined);
        reconciled.sort(historyComparator());
        return List.copyOf(reconciled);
    }

    synchronized Optional<DraftManifest> findHistoryById(
            Path stagingDirectory,
            Path archiveDirectory,
            UUID draftId
    ) throws IOException {
        Objects.requireNonNull(draftId, "draftId");
        return uniqueById(listHistory(stagingDirectory, archiveDirectory), draftId);
    }

    synchronized Optional<DraftManifest> findHistoryById(
            Path stagingDirectory,
            Path publishedDirectory,
            Path archiveDirectory,
            UUID draftId
    ) throws IOException {
        Objects.requireNonNull(draftId, "draftId");
        Optional<DraftManifest> found = uniqueById(
                listHistoryMetadata(stagingDirectory, archiveDirectory),
                draftId
        );
        if (found.isPresent()) {
            verifyHistoryRecordWithPublished(
                    found.get(),
                    stagingDirectory,
                    publishedDirectory,
                    archiveDirectory
            );
        }
        return found;
    }

    private DraftReview adoptLegacy(Path draft, DraftManifest.Actor adopter) throws IOException {
        DraftReview legacy = inspect(draft);
        if (legacy.integrity() != DraftIntegrity.LEGACY_UNTRACKED) {
            return legacy;
        }
        DraftManifest manifest = DraftManifest.adoptedLegacy(
                Objects.requireNonNull(draftIds.get(), "draftIds returned null"),
                clock.instant(),
                adopter,
                legacy.actualFingerprint(),
                draft.getFileName().toString()
        );
        createAtomically(legacy.manifestPath(), manifest);
        DraftReview adopted = inspect(draft);
        if (adopted.integrity() != DraftIntegrity.VERIFIED) {
            throw new IOException("Legacy draft changed while it was being adopted.");
        }
        return adopted;
    }

    private DraftManifest readActiveManifest(Path draft, Path sidecar) throws IOException {
        DraftManifest manifest = codec.read(sidecar);
        if (!manifest.stagedFilename().equalsIgnoreCase(draft.getFileName().toString())) {
            throw new IOException("Manifest staged-filename does not match its active sidecar.");
        }
        return manifest;
    }

    private DraftReview review(
            Path draft,
            Path sidecar,
            DraftManifest manifest,
            ContentFingerprint actual
    ) {
        DraftIntegrity integrity;
        if (!manifest.stagedFilename().equalsIgnoreCase(draft.getFileName().toString())) {
            integrity = DraftIntegrity.ASSOCIATION_MISMATCH;
        } else if (!manifest.effectiveFingerprint().equals(actual)) {
            integrity = DraftIntegrity.CONTENT_CHANGED;
        } else {
            integrity = DraftIntegrity.VERIFIED;
        }
        return new DraftReview(draft, sidecar, manifest, actual, integrity);
    }

    private static void requireReviewable(DraftReview review) throws IOException {
        requireExistingTrackedDraft(review);
        if (review.integrity() != DraftIntegrity.VERIFIED) {
            throw new IOException("Draft integrity is " + review.integrity().name().toLowerCase(java.util.Locale.ROOT)
                    + "; publication fails closed.");
        }
        if (review.manifest().publicationStatus() != DraftPublicationStatus.STAGED) {
            throw new IOException("Draft has already reached a publication outcome.");
        }
    }

    private static void requireApprovable(DraftReview review) throws IOException {
        requireExistingTrackedDraft(review);
        if (review.integrity() != DraftIntegrity.VERIFIED
                && review.integrity() != DraftIntegrity.CONTENT_CHANGED) {
            throw new IOException("Draft integrity is "
                    + review.integrity().name().toLowerCase(java.util.Locale.ROOT)
                    + "; approval fails closed.");
        }
        if (review.manifest().publicationStatus() != DraftPublicationStatus.STAGED) {
            throw new IOException("A published draft may not receive another approval.");
        }
    }

    private static void requireExistingTrackedDraft(DraftReview review) throws IOException {
        if (!review.tracked()) {
            throw new IOException("Draft does not have a managed manifest.");
        }
        if (review.integrity() == DraftIntegrity.DRAFT_MISSING) {
            throw new IOException("Managed draft file is missing.");
        }
        if (review.integrity() == DraftIntegrity.ASSOCIATION_MISMATCH) {
            throw new IOException("Managed draft filename does not match its manifest association.");
        }
        if (review.actualFingerprint() == null) {
            throw new IOException("Managed draft cannot be fingerprinted.");
        }
    }

    private static void requireDecisionStillMatches(DraftReview review, String message) throws IOException {
        requireExistingTrackedDraft(review);
        DraftManifest.ReviewDecision decision = review.manifest().reviewDecision();
        if (decision == null
                || review.actualFingerprint() == null
                || !decision.fingerprint().equals(review.actualFingerprint())) {
            throw new IOException(message);
        }
    }

    private DraftManifest.Publication publication(
            DraftManifest current,
            Path publishedPath,
            Path archivedPath,
            Path backupPath,
            ContentFingerprint capturedBackupFingerprint,
            PublishCollisionMode collisionMode,
            DraftManifest.Actor publisher
    ) throws IOException {
        Objects.requireNonNull(collisionMode, "collisionMode");
        Objects.requireNonNull(publisher, "publisher");
        Path published = normalized(publishedPath);
        String archiveFilename = archivedPath == null
                ? null : normalized(archivedPath).getFileName().toString();
        String backupFilename = null;
        ContentFingerprint backupFingerprint = null;
        if ((backupPath == null) != (capturedBackupFingerprint == null)) {
            throw new IOException("Backup path and captured fingerprint must be supplied together.");
        }
        if (backupPath != null) {
            Path backup = normalized(backupPath);
            backupFilename = backup.getFileName().toString();
            ContentFingerprint actualBackupFingerprint = ContentFingerprint.from(backup);
            if (!actualBackupFingerprint.equals(capturedBackupFingerprint)) {
                throw new IOException("Replacement backup changed after its checksum was captured.");
            }
            backupFingerprint = capturedBackupFingerprint;
        }
        Instant publishedAt = current.publication() == null
                ? clock.instant() : current.publication().at();
        DraftManifest.Actor recordedPublisher = current.publication() == null
                ? publisher : current.publication().actor();
        return new DraftManifest.Publication(
                publishedAt,
                recordedPublisher,
                collisionMode,
                published.getFileName().toString(),
                archiveFilename,
                backupFilename,
                backupFingerprint
        );
    }

    private DraftManifest expectedFinalSuccessor(
            DraftManifest current,
            DraftManifest existing,
            Path publishedPath,
            Path archivedPath,
            Path backupPath,
            ContentFingerprint capturedBackupFingerprint,
            PublishCollisionMode collisionMode
    ) throws IOException {
        if (existing.publicationStatus() != DraftPublicationStatus.PUBLISHED
                || existing.publication() == null) {
            throw new IOException("Existing archive sidecar is not a finalized publication.");
        }
        DraftManifest.Publication supplied = publicationWithIdentity(
                existing.publication().at(),
                existing.publication().actor(),
                publishedPath,
                archivedPath,
                backupPath,
                capturedBackupFingerprint,
                collisionMode
        );
        if (current.publicationStatus() == DraftPublicationStatus.PUBLISHED_ARCHIVE_PENDING) {
            requireSamePublication(current.publication(), supplied, false);
            supplied = current.publication().withArchiveFilename(
                    normalized(archivedPath).getFileName().toString()
            );
        } else {
            requireApprovedStaged(current);
        }
        return current.withPublication(DraftPublicationStatus.PUBLISHED, supplied);
    }

    private DraftManifest.Publication publicationWithIdentity(
            Instant publishedAt,
            DraftManifest.Actor publisher,
            Path publishedPath,
            Path archivedPath,
            Path backupPath,
            ContentFingerprint capturedBackupFingerprint,
            PublishCollisionMode collisionMode
    ) throws IOException {
        Objects.requireNonNull(publishedAt, "publishedAt");
        Objects.requireNonNull(publisher, "publisher");
        Objects.requireNonNull(collisionMode, "collisionMode");
        Path published = normalized(publishedPath);
        String archiveFilename = archivedPath == null
                ? null : normalized(archivedPath).getFileName().toString();
        BackupMetadata backup = verifiedBackup(backupPath, capturedBackupFingerprint);
        return new DraftManifest.Publication(
                publishedAt,
                publisher,
                collisionMode,
                published.getFileName().toString(),
                archiveFilename,
                backup.filename(),
                backup.fingerprint()
        );
    }

    private static void verifyPublicationArguments(
            DraftManifest.Publication publication,
            Path publishedPath,
            Path archivedPath,
            Path backupPath,
            ContentFingerprint capturedBackupFingerprint,
            PublishCollisionMode collisionMode
    ) throws IOException {
        if (publication.collisionMode() != collisionMode
                || !publication.publishedFilename().equalsIgnoreCase(
                        normalized(publishedPath).getFileName().toString()
                )
                || !publication.archiveFilename().equalsIgnoreCase(
                        normalized(archivedPath).getFileName().toString()
                )) {
            throw new IOException("Finalized publication metadata conflicts with supplied files.");
        }
        BackupMetadata backup = verifiedBackup(backupPath, capturedBackupFingerprint);
        if (!Objects.equals(publication.backupFilename(), backup.filename())
                || !Objects.equals(publication.backupFingerprint(), backup.fingerprint())) {
            throw new IOException("Finalized publication backup metadata conflicts with supplied backup.");
        }
    }

    private static BackupMetadata verifiedBackup(
            Path backupPath,
            ContentFingerprint capturedBackupFingerprint
    ) throws IOException {
        if ((backupPath == null) != (capturedBackupFingerprint == null)) {
            throw new IOException("Backup path and captured fingerprint must be supplied together.");
        }
        if (backupPath == null) {
            return new BackupMetadata(null, null);
        }
        Path backup = normalized(backupPath);
        ContentFingerprint actual = ContentFingerprint.from(backup);
        if (!actual.equals(capturedBackupFingerprint)) {
            throw new IOException("Replacement backup changed after its checksum was captured.");
        }
        return new BackupMetadata(backup.getFileName().toString(), capturedBackupFingerprint);
    }

    private static BackupMetadata capturedBackup(
            Path backupPath,
            ContentFingerprint capturedBackupFingerprint
    ) throws IOException {
        if ((backupPath == null) != (capturedBackupFingerprint == null)) {
            throw new IOException("Backup path and captured fingerprint must be supplied together.");
        }
        if (backupPath == null) {
            return new BackupMetadata(null, null);
        }
        return new BackupMetadata(
                normalized(backupPath).getFileName().toString(),
                capturedBackupFingerprint
        );
    }

    private static void deleteActiveSidecar(Path activeSidecar) throws IOException {
        try {
            Files.delete(activeSidecar);
        } catch (IOException exception) {
            throw new IOException("Publication history was finalized, but its active sidecar remains.", exception);
        }
    }

    private static void requireApprovedStaged(DraftManifest manifest) throws IOException {
        if (manifest.reviewStatus() != DraftReviewStatus.APPROVED
                || manifest.publicationStatus() != DraftPublicationStatus.STAGED) {
            throw new IOException("Only an approved staged draft may receive publication metadata.");
        }
    }

    private static void requireExpectedFingerprint(
            DraftManifest manifest,
            ContentFingerprint actual,
            String label
    ) throws IOException {
        if (!manifest.effectiveFingerprint().equals(actual)) {
            throw new IOException(label + " does not match the reviewed draft checksum.");
        }
    }

    private static void requireSamePublication(
            DraftManifest.Publication recorded,
            DraftManifest.Publication supplied,
            boolean compareArchive
    ) throws IOException {
        boolean same = recorded.actor().equals(supplied.actor())
                && recorded.collisionMode() == supplied.collisionMode()
                && recorded.publishedFilename().equalsIgnoreCase(supplied.publishedFilename())
                && equalNullableFilename(recorded.backupFilename(), supplied.backupFilename())
                && Objects.equals(recorded.backupFingerprint(), supplied.backupFingerprint());
        if (compareArchive) {
            same = same && equalNullableFilename(recorded.archiveFilename(), supplied.archiveFilename());
        }
        if (!same) {
            throw new IOException("Publication metadata conflicts with its existing pending outcome.");
        }
    }

    private void verifyFinalizedManifest(
            DraftManifest manifest,
            Path publishedPath,
            Path archivedPath
    ) throws IOException {
        if (manifest.publicationStatus() != DraftPublicationStatus.PUBLISHED) {
            throw new IOException("History sidecar is not finalized.");
        }
        requireExpectedFingerprint(manifest, ContentFingerprint.from(normalized(publishedPath)), "Published file");
        requireExpectedFingerprint(manifest, ContentFingerprint.from(normalized(archivedPath)), "Archived draft");
        if (!manifest.publication().publishedFilename().equalsIgnoreCase(
                normalized(publishedPath).getFileName().toString()
        ) || !manifest.publication().archiveFilename().equalsIgnoreCase(
                normalized(archivedPath).getFileName().toString()
        )) {
            throw new IOException("Finalized manifest filenames do not match the supplied publication files.");
        }
    }

    private static void requireSameFinalManifest(
            DraftManifest expected,
            DraftManifest existing
    ) throws IOException {
        if (!expected.equals(existing)) {
            throw new IOException("Archive sidecar conflicts with this draft publication.");
        }
    }

    private static boolean equalNullableFilename(String left, String right) {
        return left == null ? right == null : right != null && left.equalsIgnoreCase(right);
    }

    private record BackupMetadata(String filename, ContentFingerprint fingerprint) {
    }

    private List<DraftManifest> listManifests(
            Path directory,
            Set<DraftPublicationStatus> includedStatuses,
            boolean verifyArchiveAssociation,
            boolean verifyArchiveContent
    ) throws IOException {
        Path normalizedDirectory = normalized(directory);
        if (!Files.exists(normalizedDirectory, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        if (!Files.isDirectory(normalizedDirectory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Manifest history path is not a regular directory: " + normalizedDirectory);
        }

        List<DraftManifest> manifests = new ArrayList<>();
        try (Stream<Path> paths = Files.list(normalizedDirectory)) {
            List<Path> sidecars = paths
                    .filter(path -> isManifestFilename(path.getFileName().toString()))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
            rejectCaseInsensitivePathAmbiguity(sidecars);
            for (Path sidecar : sidecars) {
                DraftManifest manifest = codec.read(sidecar);
                if (!includedStatuses.contains(manifest.publicationStatus())) {
                    continue;
                }
                if (verifyArchiveAssociation) {
                    verifyArchiveAssociation(sidecar, manifest, verifyArchiveContent);
                } else {
                    verifyActiveAssociation(sidecar, manifest, verifyArchiveContent);
                }
                manifests.add(manifest);
            }
        }
        rejectDuplicateIds(manifests);
        manifests.sort(historyComparator());
        return List.copyOf(manifests);
    }

    /**
     * Discovers valid publication checkpoints without letting an unrelated,
     * malformed active draft make finalized history unavailable. Invalid or
     * case-ambiguous active sidecars are omitted here only; direct draft
     * inspection and every mutating workflow continue to reject them.
     */
    private List<DraftManifest> listActiveHistoryManifests(Path directory) throws IOException {
        Path normalizedDirectory = normalized(directory);
        if (!Files.exists(normalizedDirectory, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        if (!Files.isDirectory(normalizedDirectory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Manifest history path is not a regular directory: " + normalizedDirectory);
        }

        List<Path> sidecars;
        try (Stream<Path> paths = Files.list(normalizedDirectory)) {
            sidecars = paths
                    .filter(path -> isManifestFilename(path.getFileName().toString()))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        }
        Set<Path> ambiguous = caseInsensitivePathAmbiguities(sidecars);
        List<DraftManifest> manifests = new ArrayList<>();
        for (Path sidecar : sidecars) {
            if (ambiguous.contains(sidecar)) {
                continue;
            }
            try {
                DraftManifest manifest = codec.read(sidecar);
                if (manifest.publicationStatus()
                        != DraftPublicationStatus.PUBLISHED_ARCHIVE_PENDING) {
                    continue;
                }
                verifyActiveAssociation(sidecar, manifest, false);
                manifests.add(manifest);
            } catch (IOException ignored) {
                // This record remains inaccessible through direct fail-closed operations.
            }
        }
        rejectDuplicateIds(manifests);
        manifests.sort(historyComparator());
        return List.copyOf(manifests);
    }

    private static void verifyArchiveAssociation(
            Path sidecar,
            DraftManifest manifest,
            boolean verifyContent
    ) throws IOException {
        String associatedFilename = associatedTextFilename(sidecar);
        if (manifest.publication() == null
                || !associatedFilename.equalsIgnoreCase(manifest.publication().archiveFilename())) {
            throw new IOException("Archive manifest association does not match its archived filename.");
        }
        if (verifyContent) {
            Path archived = requireExistingCaseInsensitiveMatch(sidecar.resolveSibling(associatedFilename));
            requireRegularFile(archived, "Archived draft");
            requireExpectedFingerprint(manifest, ContentFingerprint.from(archived), "Archived draft");
        }
    }

    private static void verifyActiveAssociation(
            Path sidecar,
            DraftManifest manifest,
            boolean verifyContent
    ) throws IOException {
        String associatedFilename = associatedTextFilename(sidecar);
        if (!associatedFilename.equalsIgnoreCase(manifest.stagedFilename())) {
            throw new IOException("Active manifest association does not match its staged filename.");
        }
        if (verifyContent) {
            Path staged = requireExistingCaseInsensitiveMatch(sidecar.resolveSibling(associatedFilename));
            requireRegularFile(staged, "Staged draft");
            requireExpectedFingerprint(manifest, ContentFingerprint.from(staged), "Staged draft");
        }
    }

    private static String associatedTextFilename(Path sidecar) throws IOException {
        String filename = sidecar.getFileName().toString();
        if (!isManifestFilename(filename)) {
            throw new IOException("Path is not a BookExport manifest sidecar.");
        }
        String associated = filename.substring(0, filename.length() - MANIFEST_SUFFIX.length());
        if (!isTextFilename(associated)) {
            throw new IOException("Manifest sidecar does not identify a .txt file.");
        }
        return associated;
    }

    private static Optional<DraftManifest> uniqueById(
            List<DraftManifest> manifests,
            UUID draftId
    ) throws IOException {
        DraftManifest match = null;
        for (DraftManifest manifest : manifests) {
            if (!manifest.draftId().equals(draftId)) {
                continue;
            }
            if (match != null) {
                throw new IOException("Multiple history manifests use draft ID " + draftId + '.');
            }
            match = manifest;
        }
        return Optional.ofNullable(match);
    }

    private static void rejectDuplicateIds(List<DraftManifest> manifests) throws IOException {
        Set<UUID> ids = new HashSet<>();
        for (DraftManifest manifest : manifests) {
            if (!ids.add(manifest.draftId())) {
                throw new IOException("Multiple history manifests use draft ID " + manifest.draftId() + '.');
            }
        }
    }

    private static List<DraftManifest> reconcileHistory(List<DraftManifest> manifests) throws IOException {
        Map<UUID, DraftManifest> byId = new LinkedHashMap<>();
        for (DraftManifest manifest : manifests) {
            DraftManifest existing = byId.putIfAbsent(manifest.draftId(), manifest);
            if (existing == null) {
                continue;
            }
            DraftManifest pending;
            DraftManifest published;
            if (existing.publicationStatus() == DraftPublicationStatus.PUBLISHED_ARCHIVE_PENDING
                    && manifest.publicationStatus() == DraftPublicationStatus.PUBLISHED) {
                pending = existing;
                published = manifest;
            } else if (manifest.publicationStatus() == DraftPublicationStatus.PUBLISHED_ARCHIVE_PENDING
                    && existing.publicationStatus() == DraftPublicationStatus.PUBLISHED) {
                pending = manifest;
                published = existing;
            } else {
                throw new IOException("Multiple history manifests use the same draft ID.");
            }

            DraftManifest expected = pending.withPublication(
                    DraftPublicationStatus.PUBLISHED,
                    pending.publication().withArchiveFilename(published.publication().archiveFilename())
            );
            if (!expected.equals(published)) {
                throw new IOException("Pending and finalized history records conflict for one draft ID.");
            }
            byId.put(manifest.draftId(), published);
        }
        return new ArrayList<>(byId.values());
    }

    private static void verifyHistoryContent(
            DraftManifest manifest,
            Path stagingDirectory,
            Path archiveDirectory
    ) throws IOException {
        Path expected;
        String label;
        if (manifest.publicationStatus() == DraftPublicationStatus.PUBLISHED_ARCHIVE_PENDING) {
            expected = normalized(stagingDirectory).resolve(manifest.stagedFilename());
            label = "Staged draft";
        } else if (manifest.publicationStatus() == DraftPublicationStatus.PUBLISHED) {
            expected = normalized(archiveDirectory).resolve(manifest.publication().archiveFilename());
            label = "Archived draft";
        } else {
            throw new IOException("History contains a non-publication manifest.");
        }
        Path content = requireExistingCaseInsensitiveMatch(expected);
        requireRegularFile(content, label);
        requireExpectedFingerprint(manifest, ContentFingerprint.from(content), label);
    }

    private static void verifyHistoryRecordWithPublished(
            DraftManifest manifest,
            Path stagingDirectory,
            Path publishedDirectory,
            Path archiveDirectory
    ) throws IOException {
        if (manifest.publicationStatus() == DraftPublicationStatus.PUBLISHED) {
            verifyHistoryContent(manifest, stagingDirectory, archiveDirectory);
            return;
        }
        if (manifest.publicationStatus() != DraftPublicationStatus.PUBLISHED_ARCHIVE_PENDING) {
            throw new IOException("History contains a non-publication manifest.");
        }

        Path live = requireExistingCaseInsensitiveMatch(
                normalized(publishedDirectory).resolve(manifest.publication().publishedFilename())
        );
        requireRegularFile(live, "Published file");
        requireExpectedFingerprint(manifest, ContentFingerprint.from(live), "Published file");
    }

    private static Comparator<DraftManifest> historyComparator() {
        return Comparator
                .comparing(DraftManifestStore::publicationTime)
                .reversed()
                .thenComparing(manifest -> manifest.draftId().toString());
    }

    private static Instant publicationTime(DraftManifest manifest) {
        return manifest.publication() == null ? manifest.createdAt() : manifest.publication().at();
    }

    private void createAtomically(Path target, DraftManifest manifest) throws IOException {
        requireNoCaseInsensitiveMatch(target, "Draft manifest sidecar already exists.");
        writeAtomically(target, manifest, false);
    }

    private void updateAtomically(
            Path target,
            DraftManifest expected,
            DraftManifest replacement
    ) throws IOException {
        DraftManifest current = codec.read(target);
        if (!current.equals(expected)) {
            throw new IOException("Draft manifest changed concurrently; retry the operation.");
        }
        if (replacement.revision() != expected.revision() + 1L) {
            throw new IOException("Draft manifest update must advance exactly one revision.");
        }
        writeAtomically(target, replacement, true);
    }

    private void writeAtomically(Path target, DraftManifest manifest, boolean replace) throws IOException {
        Path parent = target.getParent();
        if (parent == null || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Manifest parent is not a regular directory.");
        }
        if (Files.isSymbolicLink(target)) {
            throw new IOException("Manifest sidecar may not be a symbolic link.");
        }

        byte[] encoded = codec.encode(manifest);
        Path temporary = Files.createTempFile(parent, TEMPORARY_PREFIX, TEMPORARY_SUFFIX);
        byte[] reservation = null;
        boolean reservationActive = false;
        Throwable failure = null;
        try {
            writeAndForce(temporary, encoded);
            try {
                if (replace) {
                    Files.move(
                            temporary,
                            target,
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING
                    );
                } else {
                    reservation = createReservation(target);
                    reservationActive = true;
                    if (!reservationMatches(target, reservation)) {
                        throw new IOException("Draft manifest reservation changed concurrently.");
                    }
                    Files.move(
                            temporary,
                            target,
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING
                    );
                    reservationActive = false;
                }
            } catch (AtomicMoveNotSupportedException exception) {
                throw new IOException("This filesystem cannot atomically store draft manifests.", exception);
            } catch (FileAlreadyExistsException exception) {
                throw new IOException("Draft manifest sidecar was claimed concurrently.", exception);
            }
        } catch (IOException | RuntimeException exception) {
            failure = exception;
            throw exception;
        } finally {
            try {
                Files.deleteIfExists(temporary);
                if (reservationActive) {
                    deleteReservationIfOwned(target, reservation);
                }
            } catch (IOException cleanupException) {
                if (failure != null) {
                    failure.addSuppressed(cleanupException);
                } else {
                    throw cleanupException;
                }
            }
        }
    }

    private static byte[] createReservation(Path target) throws IOException {
        byte[] marker = ("BookExport manifest reservation " + UUID.randomUUID())
                .getBytes(StandardCharsets.US_ASCII);
        boolean created = false;
        boolean complete = false;
        Throwable failure = null;
        try {
            try (FileChannel channel = FileChannel.open(
                    target,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS
            )) {
                created = true;
                ByteBuffer buffer = ByteBuffer.wrap(marker);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            complete = true;
            return marker;
        } catch (IOException | RuntimeException exception) {
            failure = exception;
            throw exception;
        } finally {
            if (created && !complete) {
                try {
                    deletePartialReservationIfOwned(target, marker);
                } catch (IOException cleanupException) {
                    if (failure != null) {
                        failure.addSuppressed(cleanupException);
                    } else {
                        throw cleanupException;
                    }
                }
            }
        }
    }

    private static boolean reservationMatches(Path target, byte[] marker) throws IOException {
        if (marker == null
                || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                || Files.size(target) != marker.length) {
            return false;
        }
        return Arrays.equals(marker, Files.readAllBytes(target));
    }

    private static void deleteReservationIfOwned(Path target, byte[] marker) throws IOException {
        if (reservationMatches(target, marker)) {
            Files.deleteIfExists(target);
        }
    }

    private static void deletePartialReservationIfOwned(Path target, byte[] marker) throws IOException {
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        long size = Files.size(target);
        if (size < 0L || size > marker.length) {
            return;
        }
        byte[] existing = Files.readAllBytes(target);
        for (int index = 0; index < existing.length; index++) {
            if (existing[index] != marker[index]) {
                return;
            }
        }
        Files.deleteIfExists(target);
    }

    private static void writeAndForce(Path path, byte[] bytes) throws IOException {
        try (FileChannel channel = FileChannel.open(
                path,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING
        )) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
    }

    private static void requireRegularFile(Path path, String label) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(label + " is not a regular non-symbolic-link file: "
                    + safeFilename(path));
        }
    }

    private static Path normalized(Path path) {
        return Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
    }

    private static Optional<Path> findUniqueCaseInsensitiveMatch(Path expected) throws IOException {
        Path normalizedExpected = normalized(expected);
        Path parent = normalizedExpected.getParent();
        Path filename = normalizedExpected.getFileName();
        if (parent == null || filename == null
                || !Files.exists(parent, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Manifest association parent is not a regular directory.");
        }

        List<Path> matches;
        try (Stream<Path> paths = Files.list(parent)) {
            matches = paths
                    .filter(path -> path.getFileName().toString().equalsIgnoreCase(filename.toString()))
                    .toList();
        }
        if (matches.size() > 1) {
            throw new IOException("Multiple case-insensitive filesystem entries match a manifest association.");
        }
        return matches.isEmpty() ? Optional.empty() : Optional.of(matches.getFirst());
    }

    private static Path requireExistingCaseInsensitiveMatch(Path expected) throws IOException {
        return findUniqueCaseInsensitiveMatch(expected)
                .orElseThrow(() -> new IOException("Managed manifest sidecar is missing."));
    }

    private static void requireNoCaseInsensitiveMatch(Path expected, String message) throws IOException {
        if (findUniqueCaseInsensitiveMatch(expected).isPresent()) {
            throw new IOException(message);
        }
    }

    private static void rejectCaseInsensitivePathAmbiguity(List<Path> paths) throws IOException {
        Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (Path path : paths) {
            if (!names.add(path.getFileName().toString())) {
                throw new IOException("Multiple case-insensitive manifest sidecars are ambiguous.");
            }
        }
    }

    private static Set<Path> caseInsensitivePathAmbiguities(List<Path> paths) {
        Set<Path> ambiguous = new HashSet<>();
        for (int left = 0; left < paths.size(); left++) {
            String leftName = paths.get(left).getFileName().toString();
            for (int right = left + 1; right < paths.size(); right++) {
                if (leftName.equalsIgnoreCase(paths.get(right).getFileName().toString())) {
                    ambiguous.add(paths.get(left));
                    ambiguous.add(paths.get(right));
                }
            }
        }
        return Set.copyOf(ambiguous);
    }

    private static boolean samePathCaseInsensitive(Path left, Path right) {
        return normalized(left).toString().equalsIgnoreCase(normalized(right).toString());
    }

    private void deleteIfMatchingManifest(Path path, DraftManifest expected) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        DraftManifest actual = codec.read(path);
        if (actual.equals(expected)) {
            Files.delete(path);
        }
    }

    private static boolean isManifestFilename(String filename) {
        return filename.length() > MANIFEST_SUFFIX.length()
                && filename.regionMatches(
                        true,
                        filename.length() - MANIFEST_SUFFIX.length(),
                        MANIFEST_SUFFIX,
                        0,
                        MANIFEST_SUFFIX.length()
                );
    }

    private static void deleteCreationMarkerIfPresent(Path draft) throws IOException {
        Optional<Path> marker = findUniqueCaseInsensitiveMatch(markerPath(draft));
        if (marker.isEmpty()) {
            return;
        }
        Path resolved = marker.get();
        if (!Files.isRegularFile(resolved, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Draft creation marker is not a regular non-symbolic-link file.");
        }
        Files.delete(resolved);
    }

    private static boolean isTextFilename(String filename) {
        return filename.length() > 4
                && filename.toLowerCase(java.util.Locale.ROOT).endsWith(".txt")
                && filename.indexOf('/') < 0
                && filename.indexOf('\\') < 0
                && filename.codePoints().noneMatch(Character::isISOControl);
    }

    private static String safeFilename(Path path) {
        Path filename = path == null ? null : path.getFileName();
        return filename == null ? "<unknown>" : filename.toString();
    }
}
