package com.mrfloris.exportbook;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Snapshots, renders, and writes complete collision-safe book exports. */
final class BookExporter {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH-mm-ss", Locale.ROOT);
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT);
    private static final int MAXIMUM_COLLISION_ATTEMPTS = 10_000;
    private static final Pattern FILENAME_PLACEHOLDER = Pattern.compile(
            "%(?:title|book_title|author|player|uuid|date|time|timestamp|pages)%"
    );

    private final ExportBookPlugin plugin;

    BookExporter(ExportBookPlugin plugin) {
        this.plugin = plugin;
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
        LocalDateTime exportedAt = LocalDateTime.now();
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
        ExportPreview preview = preview(player, requestedTitle);
        ExportSettings settings = plugin.settings();
        Path path = writeUnique(
                settings.exportDirectory(),
                preview.filenameBase(),
                preview.content(),
                settings.maximumFilenameLength()
        );

        if (settings.debugLogging()) {
            plugin.getLogger().info(() -> "Exported " + path.getFileName()
                    + " for " + player.getName()
                    + " (pages=" + preview.book().pageCount()
                    + ", utf16Units=" + preview.book().utf16Units()
                    + ", bytes=" + preview.utf8Bytes() + ")");
        }
        return new ExportResult(
                path,
                preview.book().pageCount(),
                preview.book().utf16Units(),
                preview.utf8Bytes()
        );
    }

    List<String> listExports() throws BookExportException {
        Path directory = plugin.settings().exportDirectory();
        try (Stream<Path> paths = Files.list(directory)) {
            return paths
                    .filter(Files::isRegularFile)
                    .map(Path::getFileName)
                    .map(Path::toString)
                    .filter(name -> name.toLowerCase(Locale.ROOT).endsWith(".txt"))
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList();
        } catch (IOException exception) {
            plugin.getLogger().log(Level.SEVERE, "Unable to list the BookExport output directory.", exception);
            throw new BookExportException("Unable to list exported files. Check the server log.", exception);
        }
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

    private Path writeUnique(
            Path directory,
            String baseName,
            String content,
            int maximumFilenameLength
    ) throws BookExportException {
        try {
            Files.createDirectories(directory);
        } catch (IOException exception) {
            plugin.getLogger().log(Level.SEVERE, "Unable to create the BookExport output directory.", exception);
            throw new BookExportException("Unable to create the export directory. Check the server log.", exception);
        }

        Set<String> existingNames = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        try (Stream<Path> paths = Files.list(directory)) {
            paths.map(Path::getFileName).map(Path::toString).forEach(existingNames::add);
        } catch (IOException exception) {
            plugin.getLogger().log(Level.SEVERE, "Unable to inspect the BookExport output directory.", exception);
            throw new BookExportException("Unable to inspect the export directory. Check the server log.", exception);
        }

        Path temporaryFile;
        try {
            temporaryFile = Files.createTempFile(directory, ".bookexport-", ".tmp");
            Files.writeString(temporaryFile, content, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            plugin.getLogger().log(Level.SEVERE, "Unable to stage a complete BookExport file.", exception);
            throw new BookExportException("Unable to write the export. Check the server log.", exception);
        }

        try {
            for (int counter = 0; counter < MAXIMUM_COLLISION_ATTEMPTS; counter++) {
                String suffix = counter == 0 ? "" : "_" + counter;
                String boundedBase = FilenameSanitizer.appendCollisionSuffix(
                        baseName,
                        suffix,
                        maximumFilenameLength
                );
                String fileName = boundedBase + ".txt";
                if (existingNames.contains(fileName)) {
                    continue;
                }

                Path candidate = directory.resolve(fileName).normalize();
                if (!candidate.getParent().equals(directory.normalize())) {
                    throw new BookExportException("The generated filename left the export directory.");
                }
                try {
                    Files.move(temporaryFile, candidate);
                    return candidate;
                } catch (FileAlreadyExistsException ignored) {
                    existingNames.add(fileName);
                } catch (IOException exception) {
                    plugin.getLogger().log(Level.SEVERE, "Unable to publish book export " + fileName, exception);
                    throw new BookExportException("Unable to write the export. Check the server log.", exception);
                }
            }
            throw new BookExportException("Too many files already use this title. Choose a different title.");
        } finally {
            try {
                Files.deleteIfExists(temporaryFile);
            } catch (IOException exception) {
                plugin.getLogger().log(Level.WARNING, "Unable to remove a staged BookExport temporary file.", exception);
            }
        }
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

}
