package com.mrfloris.exportbook;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Stream;

/** Bukkit-free, collision-safe storage for drafts, publication, archives, and backups. */
final class BookFileStore {
    private static final int MAXIMUM_COLLISION_ATTEMPTS = 10_000;
    private static final int HISTORY_FILENAME_LENGTH = 160;
    private static final DateTimeFormatter HISTORY_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS", Locale.ROOT);

    private BookFileStore() {
    }

    static synchronized Path writeUnique(
            Path directory,
            String baseName,
            String content,
            int maximumFilenameLength
    ) throws IOException, BookExportException {
        return writeUnique(directory, baseName, content, maximumFilenameLength, null);
    }

    static synchronized Path writeUnique(
            Path directory,
            String baseName,
            String content,
            int maximumFilenameLength,
            String reservedCompanionSuffix
    ) throws IOException, BookExportException {
        return writeUnique(
                directory,
                baseName,
                content,
                maximumFilenameLength,
                reservedCompanionSuffix,
                null
        );
    }

    static synchronized Path writeUnique(
            Path directory,
            String baseName,
            String content,
            int maximumFilenameLength,
            String reservedCompanionSuffix,
            String creationMarkerSuffix
    ) throws IOException, BookExportException {
        ensureDirectory(directory);
        Path temporaryFile = null;
        try {
            temporaryFile = Files.createTempFile(directory, ".bookexport-write-", ".tmp");
            Files.writeString(temporaryFile, content, StandardCharsets.UTF_8);
            return moveTemporaryToUnique(
                    temporaryFile,
                    directory,
                    baseName,
                    maximumFilenameLength,
                    reservedCompanionSuffix,
                    creationMarkerSuffix
            );
        } finally {
            if (temporaryFile != null) {
                Files.deleteIfExists(temporaryFile);
            }
        }
    }

    static List<String> listTextFiles(Path directory) throws IOException {
        ensureDirectory(directory);
        try (Stream<Path> paths = Files.list(directory)) {
            return paths
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .map(Path::getFileName)
                    .map(Path::toString)
                    .filter(BookFileStore::isTextFilename)
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList();
        }
    }

    /**
     * Selects every publication filename and fingerprints the staged and (for
     * replacement) current live bytes before a caller creates its durable
     * transaction record. This method creates/validates workflow directories,
     * but does not mutate any draft, live, archive, or backup file.
     */
    static synchronized PublicationPlan planPublication(
            Path stagingDirectory,
            Path publishedDirectory,
            Path archiveDirectory,
            Path backupDirectory,
            String requestedFilename,
            PublishCollisionMode collisionMode,
            int maximumFilenameLength,
            Clock clock,
            UUID transactionId,
            ContentFingerprint expectedFingerprint
    ) throws IOException, BookExportException {
        if (collisionMode == null) {
            throw new IllegalArgumentException("collisionMode must not be null.");
        }
        if (clock == null) {
            throw new IllegalArgumentException("clock must not be null.");
        }
        if (transactionId == null) {
            throw new IllegalArgumentException("transactionId must not be null.");
        }
        if (maximumFilenameLength < 1) {
            throw new IllegalArgumentException("maximumFilenameLength must be positive.");
        }

        ensureDirectory(stagingDirectory);
        ensureDirectory(publishedDirectory);
        ensureDirectory(archiveDirectory);
        ensureDirectory(backupDirectory);

        Path stagedPath = resolveStagedFile(stagingDirectory, requestedFilename).normalize();
        ContentFingerprint currentFingerprint = ContentFingerprint.from(stagedPath);
        ContentFingerprint approvedFingerprint = expectedFingerprint == null
                ? currentFingerprint : expectedFingerprint;
        if (!approvedFingerprint.equals(currentFingerprint)) {
            throw new BookExportException(
                    "The staged draft no longer matches the reviewed checksum. Approve its current bytes first."
            );
        }

        String stagedFilename = stagedPath.getFileName().toString();
        Path existingTarget = findSingleCaseInsensitive(publishedDirectory, stagedFilename);
        Path publishedPath = null;
        Path backupPath = null;
        ContentFingerprint originalPublishedFingerprint = null;

        switch (collisionMode) {
            case FAIL -> {
                if (existingTarget != null) {
                    throw new BookExportException("A published file already uses " + stagedFilename
                            + ". Choose unique or ask a trusted administrator to replace it.");
                }
                publishedPath = directChild(publishedDirectory, stagedFilename);
            }
            case UNIQUE -> publishedPath = planUniqueTarget(
                    publishedDirectory,
                    stripTextExtension(stagedFilename),
                    maximumFilenameLength
            );
            case REPLACE_WITH_BACKUP -> {
                if (existingTarget == null) {
                    throw new BookExportException("Nothing currently exists to replace for " + stagedFilename
                            + ". Publish it with fail or unique instead.");
                }
                requireRegularFile(existingTarget, "Published replacement target");
                publishedPath = existingTarget.normalize();
                originalPublishedFingerprint = ContentFingerprint.from(publishedPath);
                backupPath = planExactHistoryTarget(
                        publishedPath,
                        backupDirectory,
                        "backup",
                        clock,
                        transactionId,
                        false
                );
            }
        }
        if (publishedPath == null) {
            throw new IllegalStateException("Publication collision mode did not select a live target.");
        }

        Path archivePath = planExactHistoryTarget(
                stagedPath,
                archiveDirectory,
                "published",
                clock,
                transactionId,
                true
        );
        return new PublicationPlan(
                transactionId,
                stagedPath,
                publishedPath,
                archivePath,
                backupPath,
                approvedFingerprint,
                originalPublishedFingerprint,
                collisionMode
        );
    }

    /** Creates and durably exposes the exact replacement backup selected by the plan. */
    static synchronized void createReplacementBackup(PublicationPlan plan)
            throws IOException, BookExportException {
        requireReplacementPlan(plan);
        verifyFingerprint(
                plan.publishedPath(),
                plan.originalPublishedFingerprint(),
                "The published replacement target changed before its backup was created."
        );
        copyExactNoReplace(
                plan.publishedPath(),
                plan.backupPath(),
                plan.originalPublishedFingerprint(),
                null,
                "replacement backup"
        );
    }

    /**
     * Commits the already planned live filename. The staged source is retained;
     * archive creation and staged removal are deliberately separate phases.
     */
    static synchronized PublishResult commitLive(PublicationPlan plan)
            throws IOException, BookExportException {
        if (plan == null) {
            throw new IllegalArgumentException("plan must not be null.");
        }
        verifyFingerprint(
                plan.stagedPath(),
                plan.expectedFingerprint(),
                "The staged draft no longer matches the reviewed checksum. Approve its current bytes first."
        );

        if (plan.replacesPublishedFile()) {
            verifyReplacementBackup(plan);
            verifyFingerprint(
                    plan.publishedPath(),
                    plan.originalPublishedFingerprint(),
                    "The published replacement target changed after its backup was created."
            );
        } else {
            requireExactTargetAvailable(plan.publishedPath(), null);
        }

        Path temporaryPublish = null;
        boolean liveCommitted = false;
        try {
            temporaryPublish = createVerifiedTemporaryCopy(
                    plan.stagedPath(),
                    plan.publishedPath().getParent(),
                    ".bookexport-publish-",
                    plan.expectedFingerprint(),
                    "The staged draft changed while publication was being prepared; no live file was changed."
            );
            verifyFingerprint(
                    plan.stagedPath(),
                    plan.expectedFingerprint(),
                    "The staged draft changed while publication was being prepared; no live file was changed."
            );

            if (plan.replacesPublishedFile()) {
                // Close the longest practical race window immediately before the atomic replacement.
                verifyFingerprint(
                        plan.publishedPath(),
                        plan.originalPublishedFingerprint(),
                        "The published replacement target changed after its backup was created."
                );
                moveTemporaryReplacing(temporaryPublish, plan.publishedPath());
                liveCommitted = true;
                temporaryPublish = null;
                forceDirectory(plan.publishedPath().getParent());
            } else {
                installTemporaryNoReplace(temporaryPublish, plan.publishedPath());
                liveCommitted = true;
                temporaryPublish = null;
            }

            verifyFingerprint(
                    plan.publishedPath(),
                    plan.expectedFingerprint(),
                    "The committed live file does not match the reviewed checksum."
            );
        } catch (IOException | BookExportException exception) {
            if (liveCommitted) {
                throw new BookExportException(
                        "The live target " + plan.publishedPath().getFileName()
                                + " was committed but durability or checksum verification failed. "
                                + "Inspect the transaction before retrying.",
                        exception
                );
            }
            throw exception;
        } finally {
            if (temporaryPublish != null) {
                Files.deleteIfExists(temporaryPublish);
            }
        }

        return new PublishResult(
                plan.stagedPath(),
                plan.publishedPath(),
                null,
                plan.backupPath(),
                plan.originalPublishedFingerprint(),
                plan.collisionMode(),
                null,
                null,
                null
        );
    }

    /** Creates and durably exposes the plan's exact archive copy without deleting the draft. */
    static synchronized Path createArchive(PublicationPlan plan)
            throws IOException, BookExportException {
        if (plan == null) {
            throw new IllegalArgumentException("plan must not be null.");
        }
        if (plan.replacesPublishedFile()) {
            verifyReplacementBackup(plan);
        }
        verifyFingerprint(
                plan.publishedPath(),
                plan.expectedFingerprint(),
                "The published target does not match the reviewed checksum."
        );
        verifyFingerprint(
                plan.stagedPath(),
                plan.expectedFingerprint(),
                "The live file was published, but the staged draft changed before archival and was kept."
        );
        copyExactNoReplace(
                plan.stagedPath(),
                plan.archivePath(),
                plan.expectedFingerprint(),
                DraftManifestStore.MANIFEST_SUFFIX,
                "publication archive"
        );
        return plan.archivePath();
    }

    /** Removes the staged source only after all three content copies re-verify. */
    static synchronized void removeStagedAfterArchive(PublicationPlan plan)
            throws IOException, BookExportException {
        if (plan == null) {
            throw new IllegalArgumentException("plan must not be null.");
        }
        if (plan.replacesPublishedFile()) {
            verifyReplacementBackup(plan);
        }
        verifyFingerprint(
                plan.publishedPath(),
                plan.expectedFingerprint(),
                "The published target changed after archival; the staged draft was kept."
        );
        verifyFingerprint(
                plan.archivePath(),
                plan.expectedFingerprint(),
                "The publication archive is missing or does not match; the staged draft was kept."
        );
        verifyFingerprint(
                plan.stagedPath(),
                plan.expectedFingerprint(),
                "The staged draft changed after archival and was kept."
        );
        Files.delete(plan.stagedPath());
        forceDirectory(plan.stagedPath().getParent());
    }

    static synchronized PublishResult publish(
            Path stagingDirectory,
            Path publishedDirectory,
            Path archiveDirectory,
            Path backupDirectory,
            String requestedFilename,
            PublishCollisionMode collisionMode,
            int maximumFilenameLength,
            Clock clock
    ) throws IOException, BookExportException {
        return publish(
                stagingDirectory,
                publishedDirectory,
                archiveDirectory,
                backupDirectory,
                requestedFilename,
                collisionMode,
                maximumFilenameLength,
                clock,
                null
        );
    }

    static synchronized PublishResult publish(
            Path stagingDirectory,
            Path publishedDirectory,
            Path archiveDirectory,
            Path backupDirectory,
            String requestedFilename,
            PublishCollisionMode collisionMode,
            int maximumFilenameLength,
            Clock clock,
            ContentFingerprint expectedFingerprint
    ) throws IOException, BookExportException {
        PublishResult liveResult = publishLive(
                stagingDirectory,
                publishedDirectory,
                archiveDirectory,
                backupDirectory,
                requestedFilename,
                collisionMode,
                maximumFilenameLength,
                clock,
                expectedFingerprint
        );
        return archivePublished(liveResult, archiveDirectory, clock, expectedFingerprint);
    }

    static synchronized PublishResult publishLive(
            Path stagingDirectory,
            Path publishedDirectory,
            Path archiveDirectory,
            Path backupDirectory,
            String requestedFilename,
            PublishCollisionMode collisionMode,
            int maximumFilenameLength,
            Clock clock,
            ContentFingerprint expectedFingerprint
    ) throws IOException, BookExportException {
        ensureDirectory(stagingDirectory);
        ensureDirectory(publishedDirectory);
        ensureDirectory(archiveDirectory);
        ensureDirectory(backupDirectory);

        Path stagedPath = resolveStagedFile(stagingDirectory, requestedFilename);
        verifyExpectedFingerprint(
                stagedPath,
                expectedFingerprint,
                "The staged draft no longer matches the reviewed checksum. Approve its current bytes first."
        );
        String stagedFilename = stagedPath.getFileName().toString();
        Path existingTarget = findSingleCaseInsensitive(publishedDirectory, stagedFilename);

        Path target;
        Path backup = null;
        ContentFingerprint backupFingerprint = null;
        if (collisionMode == PublishCollisionMode.FAIL) {
            if (existingTarget != null) {
                throw new BookExportException("A published file already uses " + stagedFilename
                        + ". Choose unique or ask a trusted administrator to replace it.");
            }
            target = publishedDirectory.resolve(stagedFilename);
        } else if (collisionMode == PublishCollisionMode.UNIQUE) {
            target = null;
        } else {
            if (existingTarget == null) {
                throw new BookExportException("Nothing currently exists to replace for " + stagedFilename
                        + ". Publish it with fail or unique instead.");
            }
            requireRegularFile(existingTarget, "Published replacement target");
            backup = copyToHistory(existingTarget, backupDirectory, "backup", clock);
            target = existingTarget;
        }

        Path temporaryPublish = null;
        Path publishedPath;
        try {
            temporaryPublish = Files.createTempFile(publishedDirectory, ".bookexport-publish-", ".tmp");
            Files.copy(stagedPath, temporaryPublish, StandardCopyOption.REPLACE_EXISTING);
            verifySameContent(
                    stagedPath,
                    temporaryPublish,
                    "The staged draft changed or could not be verified while preparing publication."
            );
            verifyExpectedFingerprint(
                    temporaryPublish,
                    expectedFingerprint,
                    "The staged draft changed while publication was being prepared; no live file was changed."
            );

            if (collisionMode == PublishCollisionMode.UNIQUE) {
                String baseName = stripTextExtension(stagedFilename);
                publishedPath = moveTemporaryToUnique(
                        temporaryPublish,
                        publishedDirectory,
                        baseName,
                        maximumFilenameLength,
                        null
                );
            } else if (collisionMode == PublishCollisionMode.REPLACE_WITH_BACKUP) {
                requireRegularFile(target, "Published replacement target");
                verifySameContent(
                        target,
                        backup,
                        "The published replacement target changed after its backup was created."
                );
                backupFingerprint = ContentFingerprint.from(backup);
                publishedPath = moveTemporaryReplacing(temporaryPublish, target);
            } else {
                try {
                    publishedPath = Files.move(temporaryPublish, target);
                } catch (FileAlreadyExistsException exception) {
                    throw new BookExportException("The published filename was claimed while publishing; "
                            + "the staged draft was kept.", exception);
                }
            }
        } finally {
            if (temporaryPublish != null) {
                Files.deleteIfExists(temporaryPublish);
            }
        }

        // The file moved into place is the already verified temporary copy. The
        // caller checkpoints this committed live outcome before a second read or
        // any attempt to archive/delete the staged source.
        return new PublishResult(
                stagedPath,
                publishedPath,
                null,
                backup,
                backupFingerprint,
                collisionMode,
                null,
                null,
                null
        );
    }

    static synchronized PublishResult archivePublished(
            PublishResult liveResult,
            Path archiveDirectory,
            Clock clock,
            ContentFingerprint expectedFingerprint
    ) throws IOException, BookExportException {
        if (liveResult.archived() || liveResult.hasArchiveWarning()) {
            throw new IllegalArgumentException("Publication result has already completed archival.");
        }
        ensureDirectory(archiveDirectory);
        verifyPublishedTarget(
                liveResult.stagedPath(),
                liveResult.publishedPath(),
                expectedFingerprint
        );
        ArchiveOutcome archive = archiveDraft(
                liveResult.stagedPath(),
                archiveDirectory,
                clock,
                expectedFingerprint
        );
        return new PublishResult(
                liveResult.stagedPath(),
                liveResult.publishedPath(),
                archive.path(),
                liveResult.backupPath(),
                liveResult.backupFingerprint(),
                liveResult.collisionMode(),
                archive.warning(),
                liveResult.manifest(),
                liveResult.manifestWarning()
        );
    }

    static String normalizeRequestedTextFilename(String requestedFilename) throws BookExportException {
        if (requestedFilename == null || requestedFilename.isBlank()) {
            throw new BookExportException("Specify a staged .txt filename to publish.");
        }

        String filename = requestedFilename.trim();
        if (filename.equals(".") || filename.equals("..") || filename.equalsIgnoreCase(".txt")) {
            throw new BookExportException("The staged filename must be one plain .txt filename.");
        }
        if (!isTextFilename(filename)) {
            filename += ".txt";
        }
        if (filename.toLowerCase(Locale.ROOT).startsWith(".bookexport-")
                || filename.indexOf('/') >= 0
                || filename.indexOf('\\') >= 0
                || filename.codePoints().anyMatch(Character::isISOControl)) {
            throw new BookExportException("The staged filename must be one plain .txt filename.");
        }

        try {
            Path path = Path.of(filename);
            if (path.isAbsolute() || path.getNameCount() != 1
                    || !path.getFileName().toString().equals(filename)
                    || filename.equals(".") || filename.equals("..")) {
                throw new BookExportException("The staged filename must be one plain .txt filename.");
            }
        } catch (InvalidPathException exception) {
            throw new BookExportException("The staged filename is not valid on this filesystem.", exception);
        }
        return filename;
    }

    static Path resolveStagedFile(Path stagingDirectory, String requestedFilename)
            throws IOException, BookExportException {
        String filename = normalizeRequestedTextFilename(requestedFilename);
        Path match = findSingleCaseInsensitive(stagingDirectory, filename);
        if (match == null) {
            throw new BookExportException("No staged draft named " + filename + " was found.");
        }
        requireRegularFile(match, "Staged draft");
        return match;
    }

    private static Path findSingleCaseInsensitive(Path directory, String filename)
            throws IOException, BookExportException {
        List<Path> matches = new ArrayList<>();
        try (Stream<Path> paths = Files.list(directory)) {
            paths.filter(path -> path.getFileName().toString().equalsIgnoreCase(filename))
                    .forEach(matches::add);
        }
        if (matches.size() > 1) {
            throw new BookExportException("Multiple case-insensitive files match " + filename
                    + "; resolve the ambiguity manually.");
        }
        return matches.isEmpty() ? null : matches.getFirst();
    }

    private static Path moveTemporaryToUnique(
            Path temporaryFile,
            Path directory,
            String baseName,
            int maximumFilenameLength,
            String reservedCompanionSuffix
    ) throws IOException, BookExportException {
        return moveTemporaryToUnique(
                temporaryFile,
                directory,
                baseName,
                maximumFilenameLength,
                reservedCompanionSuffix,
                null
        );
    }

    private static Path moveTemporaryToUnique(
            Path temporaryFile,
            Path directory,
            String baseName,
            int maximumFilenameLength,
            String reservedCompanionSuffix,
            String creationMarkerSuffix
    ) throws IOException, BookExportException {
        Set<String> occupiedNames = caseInsensitiveNames(directory);
        for (int counter = 0; counter < MAXIMUM_COLLISION_ATTEMPTS; counter++) {
            String suffix = counter == 0 ? "" : "_" + counter;
            String boundedBase = FilenameSanitizer.appendCollisionSuffix(
                    baseName,
                    suffix,
                    maximumFilenameLength
            );
            String filename = boundedBase + ".txt";
            if (occupiedNames.contains(filename)
                    || (reservedCompanionSuffix != null
                    && occupiedNames.contains(filename + reservedCompanionSuffix))
                    || (creationMarkerSuffix != null
                    && occupiedNames.contains(filename + creationMarkerSuffix))) {
                continue;
            }

            Path candidate = directory.resolve(filename).normalize();
            requireDirectChild(directory, candidate);
            Path creationMarker = creationMarkerSuffix == null
                    ? null : directory.resolve(filename + creationMarkerSuffix).normalize();
            if (creationMarker != null) {
                requireDirectChild(directory, creationMarker);
                try {
                    Files.createFile(creationMarker);
                } catch (FileAlreadyExistsException ignored) {
                    occupiedNames.add(creationMarker.getFileName().toString());
                    continue;
                }
            }
            boolean moved = false;
            try {
                Path result = Files.move(temporaryFile, candidate);
                moved = true;
                return result;
            } catch (FileAlreadyExistsException ignored) {
                occupiedNames.add(filename);
            } finally {
                if (!moved && creationMarker != null) {
                    Files.deleteIfExists(creationMarker);
                }
            }
        }
        throw new BookExportException("Too many files already use this filename.");
    }

    private static Path moveTemporaryReplacing(Path temporaryFile, Path target) throws IOException {
        try {
            return Files.move(
                    temporaryFile,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
            );
        } catch (AtomicMoveNotSupportedException exception) {
            throw new IOException("This filesystem cannot atomically replace the published file; "
                    + "the existing file and staged draft were kept.", exception);
        }
    }

    private static Path planUniqueTarget(
            Path directory,
            String baseName,
            int maximumFilenameLength
    ) throws IOException, BookExportException {
        Set<String> occupiedNames = caseInsensitiveNames(directory);
        for (int counter = 0; counter < MAXIMUM_COLLISION_ATTEMPTS; counter++) {
            String suffix = counter == 0 ? "" : "_" + counter;
            String boundedBase = FilenameSanitizer.appendCollisionSuffix(
                    baseName,
                    suffix,
                    maximumFilenameLength
            );
            String filename = boundedBase + ".txt";
            if (!occupiedNames.contains(filename)) {
                return directChild(directory, filename);
            }
        }
        throw new BookExportException("Too many files already use this filename.");
    }

    private static Path planExactHistoryTarget(
            Path source,
            Path historyDirectory,
            String kind,
            Clock clock,
            UUID transactionId,
            boolean reserveManifestCompanion
    ) throws IOException, BookExportException {
        String timestamp = HISTORY_TIMESTAMP.format(LocalDateTime.now(clock));
        String originalBase = stripTextExtension(source.getFileName().toString());
        String safeStem = FilenameSanitizer.sanitize(
                timestamp + '_' + kind + '_' + originalBase,
                HISTORY_FILENAME_LENGTH,
                false
        );
        String exactStem = FilenameSanitizer.appendCollisionSuffix(
                safeStem,
                "_" + transactionId,
                HISTORY_FILENAME_LENGTH
        );
        Path target = directChild(historyDirectory, exactStem + ".txt");
        requireExactTargetAvailable(
                target,
                reserveManifestCompanion ? DraftManifestStore.MANIFEST_SUFFIX : null
        );
        return target;
    }

    private static Path directChild(Path directory, String filename) throws BookExportException {
        Path target = directory.resolve(filename).normalize();
        requireDirectChild(directory, target);
        return target;
    }

    private static void requireReplacementPlan(PublicationPlan plan) {
        if (plan == null) {
            throw new IllegalArgumentException("plan must not be null.");
        }
        if (!plan.replacesPublishedFile()) {
            throw new IllegalArgumentException("Only replace-with-backup plans have a replacement backup.");
        }
    }

    private static void verifyReplacementBackup(PublicationPlan plan)
            throws IOException, BookExportException {
        verifyFingerprint(
                plan.backupPath(),
                plan.originalPublishedFingerprint(),
                "The planned replacement backup is missing or does not match the original live bytes."
        );
    }

    private static void requireExactTargetAvailable(Path target, String reservedCompanionSuffix)
            throws IOException, BookExportException {
        Path directory = target.getParent();
        if (directory == null) {
            throw new BookExportException("The planned target has no workflow directory.");
        }
        requireDirectChild(directory, target);
        Path existing = findSingleCaseInsensitive(directory, target.getFileName().toString());
        if (existing != null) {
            throw new BookExportException("The planned filename " + target.getFileName()
                    + " was claimed before its transaction phase completed.");
        }
        if (reservedCompanionSuffix != null) {
            String companionFilename = target.getFileName() + reservedCompanionSuffix;
            Path companion = findSingleCaseInsensitive(directory, companionFilename);
            if (companion != null) {
                throw new BookExportException("A metadata companion already reserves the planned filename "
                        + target.getFileName() + '.');
            }
        }
    }

    private static Path createVerifiedTemporaryCopy(
            Path source,
            Path targetDirectory,
            String prefix,
            ContentFingerprint expectedFingerprint,
            String mismatchMessage
    ) throws IOException, BookExportException {
        requireRegularFile(source, "Publication source");
        Path temporary = Files.createTempFile(targetDirectory, prefix, ".tmp");
        boolean complete = false;
        try {
            Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
            forceFile(temporary);
            verifyFingerprint(temporary, expectedFingerprint, mismatchMessage);
            complete = true;
            return temporary;
        } finally {
            if (!complete) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    private static void copyExactNoReplace(
            Path source,
            Path target,
            ContentFingerprint expectedFingerprint,
            String reservedCompanionSuffix,
            String label
    ) throws IOException, BookExportException {
        requireExactTargetAvailable(target, reservedCompanionSuffix);
        verifyFingerprint(source, expectedFingerprint, "The source bytes changed before the " + label + '.');

        Path temporary = null;
        try {
            temporary = createVerifiedTemporaryCopy(
                    source,
                    target.getParent(),
                    ".bookexport-history-",
                    expectedFingerprint,
                    "The " + label + " does not match its expected checksum."
            );
            verifyFingerprint(source, expectedFingerprint, "The source bytes changed while creating the "
                    + label + '.');
            requireExactTargetAvailable(target, reservedCompanionSuffix);
            installTemporaryNoReplace(temporary, target);
            temporary = null;
            requireOnlyCaseInsensitiveMatch(target);
            if (reservedCompanionSuffix != null) {
                Path companion = findSingleCaseInsensitive(
                        target.getParent(),
                        target.getFileName() + reservedCompanionSuffix
                );
                if (companion != null) {
                    throw new BookExportException(
                            "The " + label + " was committed, but a conflicting metadata companion appeared. "
                                    + "Inspect the transaction before retrying."
                    );
                }
            }
            verifyFingerprint(
                    target,
                    expectedFingerprint,
                    "The committed " + label + " does not match its expected checksum."
            );
        } finally {
            if (temporary != null) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    /**
     * Atomically adds a no-replace directory entry for a fully written inode.
     * A hard link is used because an ATOMIC_MOVE without REPLACE_EXISTING is
     * allowed by the Java contract to have implementation-specific existing
     * target semantics. Source and target are deliberately in one directory.
     */
    private static void installTemporaryNoReplace(Path temporary, Path target)
            throws IOException, BookExportException {
        requireDirectChild(target.getParent(), target);
        try {
            Files.createLink(target, temporary);
        } catch (FileAlreadyExistsException exception) {
            throw new BookExportException("The planned filename " + target.getFileName()
                    + " was claimed while committing it; the existing file was kept.", exception);
        } catch (UnsupportedOperationException exception) {
            throw new IOException("This filesystem cannot atomically install a no-replace publication file.",
                    exception);
        }

        boolean directoryForced = false;
        try {
            forceDirectory(target.getParent());
            directoryForced = true;
        } finally {
            // Removing the private temporary name never removes the committed inode.
            Files.deleteIfExists(temporary);
            if (directoryForced) {
                forceDirectory(target.getParent());
            }
        }
    }

    private static void forceFile(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    private static void forceDirectory(Path directory) throws IOException {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (UnsupportedOperationException exception) {
            throw new IOException("This filesystem cannot durably flush publication directory metadata: "
                    + directory.getFileName(), exception);
        }
    }

    private static void verifyFingerprint(
            Path path,
            ContentFingerprint expectedFingerprint,
            String message
    ) throws IOException, BookExportException {
        if (expectedFingerprint == null) {
            throw new IllegalArgumentException("expectedFingerprint must not be null.");
        }
        try {
            requireOnlyCaseInsensitiveMatch(path);
            requireRegularFile(path, "Publication artifact");
            if (!expectedFingerprint.matches(path)) {
                throw new BookExportException(message);
            }
        } catch (IOException exception) {
            throw new BookExportException(message, exception);
        }
    }

    private static void requireOnlyCaseInsensitiveMatch(Path path)
            throws IOException, BookExportException {
        Path directory = path.getParent();
        if (directory == null) {
            throw new BookExportException("Publication artifact has no workflow directory.");
        }
        Path match = findSingleCaseInsensitive(directory, path.getFileName().toString());
        if (match == null) {
            throw new BookExportException("Publication artifact is missing: " + path.getFileName());
        }
        if (!Files.isSameFile(match, path)) {
            throw new BookExportException("A different case-insensitive file claimed the publication artifact "
                    + path.getFileName() + '.');
        }
    }

    private static Path copyToHistory(Path source, Path historyDirectory, String kind, Clock clock)
            throws IOException, BookExportException {
        return copyToHistory(source, historyDirectory, kind, clock, null);
    }

    private static Path copyToHistory(
            Path source,
            Path historyDirectory,
            String kind,
            Clock clock,
            ContentFingerprint expectedFingerprint
    ) throws IOException, BookExportException {
        String historyBase = historyBaseName(source.getFileName().toString(), kind, clock);
        Path temporaryFile = null;
        Path historyFile = null;
        try {
            requireRegularFile(source, kind.equals("backup")
                    ? "Published replacement target"
                    : "Staged draft");
            temporaryFile = Files.createTempFile(historyDirectory, ".bookexport-history-", ".tmp");
            Files.copy(source, temporaryFile, StandardCopyOption.REPLACE_EXISTING);
            historyFile = moveTemporaryToUnique(
                    temporaryFile,
                    historyDirectory,
                    historyBase,
                    HISTORY_FILENAME_LENGTH,
                    kind.equals("published") ? DraftManifestStore.MANIFEST_SUFFIX : null
            );
            verifySameContent(
                    source,
                    historyFile,
                    "The " + kind + " copy does not match its source."
            );
            verifyExpectedFingerprint(
                    historyFile,
                    expectedFingerprint,
                    "The " + kind + " copy does not match the reviewed checksum."
            );
            return historyFile;
        } catch (IOException | BookExportException exception) {
            if (historyFile != null) {
                try {
                    Files.deleteIfExists(historyFile);
                } catch (IOException cleanupException) {
                    exception.addSuppressed(cleanupException);
                }
            }
            throw exception;
        } finally {
            if (temporaryFile != null) {
                Files.deleteIfExists(temporaryFile);
            }
        }
    }

    private static ArchiveOutcome archiveDraft(
            Path stagedPath,
            Path archiveDirectory,
            Clock clock,
            ContentFingerprint expectedFingerprint
    ) {
        try {
            verifyExpectedFingerprint(
                    stagedPath,
                    expectedFingerprint,
                    "The live file was published, but the staged draft changed before archival and was kept."
            );
            Path archivedPath = copyToHistory(
                    stagedPath,
                    archiveDirectory,
                    "published",
                    clock,
                    expectedFingerprint
            );
            verifyExpectedFingerprint(
                    stagedPath,
                    expectedFingerprint,
                    "The live file was published and archived, but the staged draft changed and was kept."
            );
            try {
                Files.delete(stagedPath);
                return new ArchiveOutcome(archivedPath, null);
            } catch (IOException exception) {
                return new ArchiveOutcome(
                        archivedPath,
                        "The published draft was archived, but the staged copy could not be removed: "
                                + exception.getMessage()
                );
            }
        } catch (IOException | BookExportException exception) {
            return new ArchiveOutcome(
                    null,
                    "Publication succeeded, but the draft could not be archived and remains staged: "
                            + exception.getMessage()
            );
        }
    }

    private static String historyBaseName(String filename, String kind, Clock clock) {
        String timestamp = HISTORY_TIMESTAMP.format(LocalDateTime.now(clock));
        String originalBase = stripTextExtension(filename);
        return FilenameSanitizer.sanitize(
                timestamp + '_' + kind + '_' + originalBase,
                HISTORY_FILENAME_LENGTH,
                false
        );
    }

    private static Set<String> caseInsensitiveNames(Path directory) throws IOException {
        Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        try (Stream<Path> paths = Files.list(directory)) {
            paths.map(Path::getFileName).map(Path::toString).forEach(names::add);
        }
        return names;
    }

    private static void ensureDirectory(Path directory) throws IOException {
        Files.createDirectories(directory);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Workflow path is not a regular directory: " + directory);
        }
        if (!Files.isWritable(directory)) {
            throw new IOException("Workflow directory is not writable: " + directory);
        }
    }

    private static void requireRegularFile(Path path, String label) throws BookExportException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new BookExportException(label + " is not a regular non-symbolic-link file: "
                    + path.getFileName());
        }
    }

    private static void verifySameContent(Path source, Path copy, String message)
            throws IOException, BookExportException {
        if (Files.mismatch(source, copy) != -1L) {
            throw new BookExportException(message);
        }
    }

    private static void verifyPublishedTarget(
            Path stagedPath,
            Path publishedPath,
            ContentFingerprint expectedFingerprint
    )
            throws BookExportException {
        try {
            requireRegularFile(stagedPath, "Staged draft");
            requireRegularFile(publishedPath, "Published target");
            if (expectedFingerprint == null) {
                verifySameContent(
                        stagedPath,
                        publishedPath,
                        "The published target does not match the reviewed staged draft."
                );
            } else {
                verifyExpectedFingerprint(
                        publishedPath,
                        expectedFingerprint,
                        "The published target does not match the reviewed checksum."
                );
            }
        } catch (IOException | BookExportException exception) {
            throw new BookExportException(
                    "The live target " + publishedPath.getFileName()
                            + " was written but could not be verified; the staged draft was kept. "
                            + "Inspect the live file before retrying.",
                    exception
            );
        }
    }

    private static void verifyExpectedFingerprint(
            Path path,
            ContentFingerprint expectedFingerprint,
            String message
    ) throws IOException, BookExportException {
        if (expectedFingerprint != null && !expectedFingerprint.matches(path)) {
            throw new BookExportException(message);
        }
    }

    private static void requireDirectChild(Path directory, Path child) throws BookExportException {
        if (child.getParent() == null || !child.getParent().equals(directory.normalize())) {
            throw new BookExportException("The generated filename left its configured workflow directory.");
        }
    }

    private static boolean isTextFilename(String filename) {
        return filename.toLowerCase(Locale.ROOT).endsWith(".txt") && filename.length() > 4;
    }

    private static String stripTextExtension(String filename) {
        return filename.substring(0, filename.length() - 4);
    }

    private record ArchiveOutcome(Path path, String warning) {
    }
}
