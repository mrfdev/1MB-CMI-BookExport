package com.mrfloris.exportbook;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
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
