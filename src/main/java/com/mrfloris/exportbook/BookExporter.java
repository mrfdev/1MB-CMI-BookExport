package com.mrfloris.exportbook;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Snapshots, renders, and writes complete collision-safe book exports. */
final class BookExporter {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH-mm-ss", Locale.ROOT);
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT);
    private static final Pattern FILENAME_PLACEHOLDER = Pattern.compile(
            "%(?:title|book_title|author|player|uuid|date|time|timestamp|pages)%"
    );

    private final ExportBookPlugin plugin;
    private final Clock clock;
    private final DraftManifestStore manifestStore;

    BookExporter(ExportBookPlugin plugin) {
        this(plugin, Clock.systemDefaultZone());
    }

    BookExporter(ExportBookPlugin plugin, Clock clock) {
        this.plugin = plugin;
        this.clock = clock;
        this.manifestStore = new DraftManifestStore(clock);
    }

    ExportPreview preview(Player player, String requestedTitle) throws BookExportException {
        ItemStack heldItem = player.getInventory().getItemInMainHand();
        if (!BookSnapshotReader.isSupportedBook(heldItem)) {
            throw new BookExportException("Hold a written book or book and quill in your main hand.");
        }

        BookSnapshot book;
        try {
            book = BookSnapshotReader.read(heldItem);
        } catch (IllegalArgumentException exception) {
            throw new BookExportException("The held item does not contain valid book data.", exception);
        }
        if (book.pages().isEmpty()) {
            throw new BookExportException("The held book has no pages to export.");
        }

        String resolvedTitle = resolveTitle(book, requestedTitle);
        Instant createdAt = clock.instant();
        LocalDateTime exportedAt = LocalDateTime.ofInstant(createdAt, clock.getZone());
        Map<String, String> placeholders = filenamePlaceholders(player, book, resolvedTitle, exportedAt);
        ExportSettings settings = plugin.settings();
        String templatedName = replacePlaceholders(settings.filenameFormat(), placeholders);
        String filenameBase = FilenameSanitizer.sanitize(
                templatedName,
                settings.maximumFilenameLength(),
                settings.lowercaseFilenames()
        );
        if (filenameBase.isBlank()) {
            throw new BookExportException("The configured filename template produced an empty filename.");
        }

        String content = BookTextRenderer.render(player.getName(), book, exportedAt, settings);
        int bytes = content.getBytes(StandardCharsets.UTF_8).length;
        return new ExportPreview(book, resolvedTitle, filenameBase, content, bytes, createdAt);
    }

    ExportResult export(Player player, String requestedTitle) throws BookExportException {
        return write(player, requestedTitle, false);
    }

    ExportResult stage(Player player, String requestedTitle) throws BookExportException {
        return write(player, requestedTitle, true);
    }

    private ExportResult write(Player player, String requestedTitle, boolean forceStage)
            throws BookExportException {
        ExportPreview preview = preview(player, requestedTitle);
        ExportSettings settings = plugin.settings();
        FileScope scope = settings.exportScope(forceStage);
        Path path;
        DraftManifest manifest = null;
        try {
            path = BookFileStore.writeUnique(
                    settings.exportDestination(forceStage),
                    preview.filenameBase(),
                    preview.content(),
                    settings.maximumFilenameLength(),
                    scope == FileScope.STAGED ? DraftManifestStore.MANIFEST_SUFFIX : null,
                    scope == FileScope.STAGED ? DraftManifestStore.CREATION_MARKER_SUFFIX : null
            );
            if (scope == FileScope.STAGED) {
                try {
                    DraftReview review = manifestStore.createNative(
                            path,
                            preview.filenameBase() + ".txt",
                            actor(player.getName(), player.getUniqueId()),
                            preview.book().plainAuthor(),
                            preview.book().pageCount(),
                            preview.book().utf16Units(),
                            preview.createdAt()
                    );
                    manifest = review.manifest();
                } catch (IOException exception) {
                    try {
                        Files.deleteIfExists(path);
                        Files.deleteIfExists(DraftManifestStore.markerPath(path));
                    } catch (IOException cleanupException) {
                        exception.addSuppressed(cleanupException);
                    }
                    throw exception;
                }
            }
        } catch (IOException exception) {
            plugin.getLogger().log(Level.SEVERE, "Unable to write a BookExport " + scope.key() + " file.", exception);
            throw new BookExportException("Unable to write the " + scope.key() + " file. Check the server log.", exception);
        }

        if (settings.debugLogging()) {
            plugin.getLogger().info((scope == FileScope.STAGED ? "Staged " : "Published directly ")
                    + path.getFileName()
                    + " for " + player.getName()
                    + " (pages=" + preview.book().pageCount()
                    + ", utf16Units=" + preview.book().utf16Units()
                    + ", bytes=" + preview.utf8Bytes()
                    + ", manifest=" + (manifest == null ? "none" : manifest.draftId()) + ")");
        }
        return new ExportResult(
                path,
                scope,
                preview.book().pageCount(),
                preview.book().utf16Units(),
                preview.utf8Bytes(),
                manifest == null ? null : manifest.draftId(),
                manifest == null ? null : manifest.contentFingerprint().sha256()
        );
    }

    List<String> listFiles(FileScope scope) throws BookExportException {
        Path directory = scope.directory(plugin.settings());
        try {
            return BookFileStore.listTextFiles(directory);
        } catch (IOException exception) {
            plugin.getLogger().log(Level.SEVERE, "Unable to list BookExport " + scope.key() + " files.", exception);
            throw new BookExportException("Unable to list " + scope.displayName() + ". Check the server log.", exception);
        }
    }

    DraftReview reviewDraft(String stagedFilename) throws BookExportException {
        Path stagedPath = resolveStagedPath(stagedFilename);
        try {
            return manifestStore.inspect(stagedPath);
        } catch (IOException exception) {
            throw manifestFailure("inspect", stagedPath, exception);
        }
    }

    DraftReview approveDraft(
            String stagedFilename,
            DraftManifest.Actor reviewer
    ) throws BookExportException {
        Path stagedPath = resolveStagedPath(stagedFilename);
        try {
            DraftReview review = manifestStore.approve(stagedPath, reviewer);
            logReviewDecision(review, reviewer, "approved");
            return review;
        } catch (IOException exception) {
            throw manifestFailure("approve", stagedPath, exception);
        }
    }

    DraftReview requestChanges(
            String stagedFilename,
            DraftManifest.Actor reviewer
    ) throws BookExportException {
        Path stagedPath = resolveStagedPath(stagedFilename);
        try {
            DraftReview review = manifestStore.requestChanges(stagedPath, reviewer);
            logReviewDecision(review, reviewer, "changes-requested");
            return review;
        } catch (IOException exception) {
            throw manifestFailure("record changes for", stagedPath, exception);
        }
    }

    List<DraftManifest> manifestHistory() throws BookExportException {
        ExportSettings settings = plugin.settings();
        try {
            return manifestStore.listHistoryMetadata(
                    settings.stagingDirectory(),
                    settings.archiveDirectory()
            );
        } catch (IOException exception) {
            plugin.getLogger().warning("Unable to list BookExport manifest history: " + safeMessage(exception));
            throw new BookExportException("Unable to list manifest history. Check the server log.", exception);
        }
    }

    DraftManifest manifestHistory(UUID draftId) throws BookExportException {
        ExportSettings settings = plugin.settings();
        try {
            Optional<DraftManifest> match = manifestStore.findHistoryById(
                    settings.stagingDirectory(),
                    settings.publishedDirectory(),
                    settings.archiveDirectory(),
                    draftId
            );
            return match.orElseThrow(() -> new BookExportException(
                    "No manifest history record uses ID " + draftId + '.'
            ));
        } catch (IOException exception) {
            plugin.getLogger().warning("Unable to find BookExport manifest history: " + safeMessage(exception));
            throw new BookExportException("Unable to inspect manifest history. Check the server log.", exception);
        }
    }

    ManifestHealth manifestHealth() throws BookExportException {
        ExportSettings settings = plugin.settings();
        Path staging = settings.stagingDirectory();
        List<String> stagedFiles = listFiles(FileScope.STAGED);
        Set<String> pairedSidecars = new HashSet<>();
        int managed = 0;
        int untracked = 0;
        int unreviewed = 0;
        int approved = 0;
        int changesRequested = 0;
        int modified = 0;
        int publicationPending = 0;
        int errors = 0;

        for (String filename : stagedFiles) {
            Path draft = staging.resolve(filename);
            Path sidecar = DraftManifestStore.sidecarPath(draft);
            if (Files.exists(sidecar, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                pairedSidecars.add(sidecar.getFileName().toString().toLowerCase(Locale.ROOT));
            }
            try {
                DraftReview review = manifestStore.inspect(draft);
                if (!review.tracked()) {
                    untracked++;
                    continue;
                }
                managed++;
                if (review.integrity() == DraftIntegrity.CONTENT_CHANGED) {
                    modified++;
                }
                DraftManifest manifest = review.manifest();
                if (manifest.publicationStatus() == DraftPublicationStatus.PUBLISHED_ARCHIVE_PENDING) {
                    publicationPending++;
                }
                switch (manifest.reviewStatus()) {
                    case UNREVIEWED -> unreviewed++;
                    case APPROVED -> approved++;
                    case CHANGES_REQUESTED -> changesRequested++;
                }
            } catch (IOException exception) {
                errors++;
            }
        }

        int sidecarCount;
        int creationMarkerCount;
        try (Stream<Path> paths = Files.list(staging)) {
            List<String> workflowNames = paths
                    .map(Path::getFileName)
                    .map(Path::toString)
                    .toList();
            sidecarCount = Math.toIntExact(workflowNames.stream()
                    .filter(name -> name.endsWith(DraftManifestStore.MANIFEST_SUFFIX))
                    .map(name -> name.toLowerCase(Locale.ROOT))
                    .filter(name -> !pairedSidecars.contains(name))
                    .count());
            creationMarkerCount = Math.toIntExact(workflowNames.stream()
                    .filter(name -> name.toLowerCase(Locale.ROOT).endsWith(
                            DraftManifestStore.CREATION_MARKER_SUFFIX
                    ))
                    .count());
        } catch (IOException | ArithmeticException exception) {
            throw new BookExportException("Unable to inspect manifest sidecars. Check the server log.", exception);
        }

        int publishedHistory = -1;
        boolean historyAvailable = true;
        try {
            publishedHistory = Math.toIntExact(manifestStore.listHistoryMetadata(
                            settings.stagingDirectory(),
                            settings.archiveDirectory()
                    ).stream()
                    .filter(manifest -> manifest.publicationStatus() == DraftPublicationStatus.PUBLISHED)
                    .count());
        } catch (IOException | ArithmeticException exception) {
            historyAvailable = false;
            errors++;
        }
        return new ManifestHealth(
                managed,
                untracked,
                unreviewed,
                approved,
                changesRequested,
                modified,
                publicationPending,
                sidecarCount,
                creationMarkerCount,
                errors,
                publishedHistory,
                historyAvailable
        );
    }

    PublishResult publish(
            String stagedFilename,
            PublishCollisionMode collisionMode,
            DraftManifest.Actor actor
    ) throws BookExportException {
        ExportSettings settings = plugin.settings();
        Path stagedPath = resolveStagedPath(stagedFilename);
        DraftReview approved;
        try {
            approved = manifestStore.verifyOrAdoptBeforePublish(stagedPath, actor);
        } catch (IOException exception) {
            throw manifestFailure("verify for publication", stagedPath, exception);
        }

        PublishResult result;
        try {
            result = BookFileStore.publishLive(
                    settings.stagingDirectory(),
                    settings.publishedDirectory(),
                    settings.archiveDirectory(),
                    settings.backupDirectory(),
                    stagedFilename,
                    collisionMode,
                    settings.maximumFilenameLength(),
                    clock,
                    approved.manifest().effectiveFingerprint()
            );
        } catch (IOException exception) {
            plugin.getLogger().log(Level.SEVERE, "Unable to publish staged BookExport file.", exception);
            throw new BookExportException("Unable to publish the staged file. Check the server log.", exception);
        }

        DraftManifest publicationManifest = null;
        String manifestWarning = null;
        try {
            publicationManifest = manifestStore.checkpointCommittedPublication(
                    result.stagedPath(),
                    approved.manifest(),
                    result.publishedPath(),
                    result.backupPath(),
                    result.backupFingerprint(),
                    collisionMode,
                    actor
            );
            result = result.withManifest(publicationManifest, null);
        } catch (IOException exception) {
            manifestWarning = "Publication succeeded, but its manifest checkpoint could not be stored. "
                    + "The staged draft was kept; do not publish it again. Inspect the live file and server log.";
            plugin.getLogger().log(
                    Level.SEVERE,
                    "Published BookExport draft but failed to checkpoint manifest "
                            + approved.manifest().draftId() + ": " + safeMessage(exception),
                    exception
            );
            result = result.withManifest(null, manifestWarning);
        }

        if (publicationManifest != null) {
            try {
                result = BookFileStore.archivePublished(
                        result,
                        settings.archiveDirectory(),
                        clock,
                        approved.manifest().effectiveFingerprint()
                ).withManifest(publicationManifest, null);
            } catch (IOException | BookExportException exception) {
                String archiveWarning = "Publication succeeded and its manifest is safely checkpointed, "
                        + "but the staged draft could not be archived and was kept: "
                        + safeOperationMessage(exception);
                plugin.getLogger().warning(archiveWarning);
                result = result.withArchiveOutcome(null, archiveWarning)
                        .withManifest(publicationManifest, null);
            }

            if (result.archived() && !result.hasArchiveWarning()) {
                try {
                    publicationManifest = manifestStore.finalizePublication(
                            result.stagedPath(),
                            result.publishedPath(),
                            result.archivedPath(),
                            result.backupPath(),
                            result.backupFingerprint(),
                            collisionMode,
                            actor
                    );
                    result = result.withManifest(publicationManifest, null);
                } catch (IOException exception) {
                    manifestWarning = "Publication and archival succeeded, but the audit record remains "
                            + "archive-pending. Do not retry publication; inspect manifest history and the log.";
                    plugin.getLogger().log(
                            Level.SEVERE,
                            "Published and archived BookExport draft but failed to finalize manifest "
                                    + approved.manifest().draftId() + ": " + safeMessage(exception),
                            exception
                    );
                    result = result.withManifest(publicationManifest, manifestWarning);
                }
            }
        }

        plugin.getLogger().info("Published staged file " + result.stagedPath().getFileName()
                + " as " + result.publishedPath().getFileName()
                + " by " + actor.name()
                + " (collisionMode=" + collisionMode.key()
                + ", backup=" + filenameOrNone(result.backupPath())
                + ", archive=" + filenameOrNone(result.archivedPath())
                + ", manifest=" + approved.manifest().draftId()
                + ", checksum=" + approved.manifest().effectiveFingerprint().sha256() + ')');
        if (result.hasArchiveWarning()) {
            plugin.getLogger().warning(result.archiveWarning());
        }
        if (result.hasManifestWarning()) {
            plugin.getLogger().warning(result.manifestWarning());
        }
        return result;
    }

    private Path resolveStagedPath(String stagedFilename) throws BookExportException {
        try {
            return BookFileStore.resolveStagedFile(plugin.settings().stagingDirectory(), stagedFilename);
        } catch (IOException exception) {
            plugin.getLogger().warning("Unable to resolve a staged BookExport file: " + safeMessage(exception));
            throw new BookExportException("Unable to access the staged file. Check the server log.", exception);
        }
    }

    private BookExportException manifestFailure(String action, Path stagedPath, IOException exception) {
        plugin.getLogger().warning("Unable to " + action + " BookExport draft "
                + stagedPath.getFileName() + ": " + safeMessage(exception));
        return new BookExportException(safeMessage(exception), exception);
    }

    private void logReviewDecision(
            DraftReview review,
            DraftManifest.Actor actor,
            String decision
    ) {
        DraftManifest manifest = review.manifest();
        plugin.getLogger().info("Draft " + manifest.draftId() + " ("
                + review.draftPath().getFileName() + ") marked " + decision
                + " by " + actor.name()
                + " (checksum=" + manifest.reviewDecision().fingerprint().sha256() + ")");
    }

    private static DraftManifest.Actor actor(String name, UUID uuid) {
        return new DraftManifest.Actor(name, uuid);
    }

    private static String safeMessage(IOException exception) {
        return boundedSingleLineMessage(exception.getMessage(), "manifest operation failed");
    }

    private static String safeOperationMessage(Exception exception) {
        return boundedSingleLineMessage(exception.getMessage(), "archive operation failed");
    }

    private static String boundedSingleLineMessage(String message, String fallback) {
        if (message == null || message.isBlank()) {
            return fallback;
        }
        StringBuilder safe = new StringBuilder(Math.min(message.length(), 240));
        message.codePoints().limit(240).forEach(codePoint -> {
            int type = Character.getType(codePoint);
            if (Character.isISOControl(codePoint)
                    || type == Character.FORMAT
                    || type == Character.LINE_SEPARATOR
                    || type == Character.PARAGRAPH_SEPARATOR) {
                safe.append(' ');
            } else {
                safe.appendCodePoint(codePoint);
            }
        });
        String normalized = safe.toString().trim();
        return normalized.isEmpty() ? fallback : normalized;
    }

    private String resolveTitle(BookSnapshot book, String requestedTitle) throws BookExportException {
        if (requestedTitle != null && !requestedTitle.isBlank()) {
            return requestedTitle.trim();
        }
        if (!book.plainTitle().isBlank()) {
            return book.plainTitle();
        }
        throw new BookExportException("This book has no signed title. Use /bookexport export <title>.");
    }

    private Map<String, String> filenamePlaceholders(
            Player player,
            BookSnapshot book,
            String resolvedTitle,
            LocalDateTime exportedAt
    ) {
        Map<String, String> values = new HashMap<>();
        values.put("%title%", resolvedTitle);
        values.put("%book_title%", book.plainTitle().isBlank() ? "untitled" : book.plainTitle());
        values.put("%author%", book.plainAuthor().isBlank() ? "unknown" : book.plainAuthor());
        values.put("%player%", player.getName());
        values.put("%uuid%", player.getUniqueId().toString());
        values.put("%date%", DATE.format(exportedAt));
        values.put("%time%", TIME.format(exportedAt));
        values.put("%timestamp%", TIMESTAMP.format(exportedAt));
        values.put("%pages%", Integer.toString(book.pageCount()));
        return values;
    }

    static String replacePlaceholders(String template, Map<String, String> values) {
        Matcher matcher = FILENAME_PLACEHOLDER.matcher(template);
        StringBuilder output = new StringBuilder(template.length());
        while (matcher.find()) {
            String replacement = values.getOrDefault(matcher.group(), matcher.group());
            matcher.appendReplacement(output, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(output);
        return output.toString();
    }

    private static String filenameOrNone(Path path) {
        return path == null ? "none" : path.getFileName().toString();
    }

}
