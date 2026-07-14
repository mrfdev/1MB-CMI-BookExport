package com.mrfloris.exportbook;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    BookExporter(ExportBookPlugin plugin) {
        this(plugin, Clock.systemDefaultZone());
    }

    BookExporter(ExportBookPlugin plugin, Clock clock) {
        this.plugin = plugin;
        this.clock = clock;
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
        LocalDateTime exportedAt = LocalDateTime.now(clock);
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
        return new ExportPreview(book, resolvedTitle, filenameBase, content, bytes);
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
        try {
            path = BookFileStore.writeUnique(
                    settings.exportDestination(forceStage),
                    preview.filenameBase(),
                    preview.content(),
                    settings.maximumFilenameLength()
            );
        } catch (IOException exception) {
            plugin.getLogger().log(Level.SEVERE, "Unable to write a BookExport " + scope.key() + " file.", exception);
            throw new BookExportException("Unable to write the " + scope.key() + " file. Check the server log.", exception);
        }

        if (settings.debugLogging()) {
            plugin.getLogger().info(() -> (scope == FileScope.STAGED ? "Staged " : "Published directly ")
                    + path.getFileName()
                    + " for " + player.getName()
                    + " (pages=" + preview.book().pageCount()
                    + ", utf16Units=" + preview.book().utf16Units()
                    + ", bytes=" + preview.utf8Bytes() + ")");
        }
        return new ExportResult(
                path,
                scope,
                preview.book().pageCount(),
                preview.book().utf16Units(),
                preview.utf8Bytes()
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

    PublishResult publish(
            String stagedFilename,
            PublishCollisionMode collisionMode,
            String actor
    ) throws BookExportException {
        ExportSettings settings = plugin.settings();
        PublishResult result;
        try {
            result = BookFileStore.publish(
                    settings.stagingDirectory(),
                    settings.publishedDirectory(),
                    settings.archiveDirectory(),
                    settings.backupDirectory(),
                    stagedFilename,
                    collisionMode,
                    settings.maximumFilenameLength(),
                    clock
            );
        } catch (IOException exception) {
            plugin.getLogger().log(Level.SEVERE, "Unable to publish staged BookExport file.", exception);
            throw new BookExportException("Unable to publish the staged file. Check the server log.", exception);
        }

        plugin.getLogger().info(() -> "Published staged file " + result.stagedPath().getFileName()
                + " as " + result.publishedPath().getFileName()
                + " by " + actor
                + " (collisionMode=" + collisionMode.key()
                + ", backup=" + filenameOrNone(result.backupPath())
                + ", archive=" + filenameOrNone(result.archivedPath()) + ')');
        if (result.hasArchiveWarning()) {
            plugin.getLogger().warning(result.archiveWarning());
        }
        return result;
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
