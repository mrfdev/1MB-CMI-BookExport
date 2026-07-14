package com.mrfloris.exportbook;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** Pure renderer that preserves source pages and emits correct CMI boundaries. */
final class BookTextRenderer {
    private BookTextRenderer() {
    }

    static String render(
            String playerName,
            BookSnapshot book,
            LocalDateTime exportedAt,
            ExportSettings settings
    ) {
        StringBuilder output = new StringBuilder(Math.max(256, book.utf16Units() + 128));
        if (settings.colorMode() == ColorMode.CMI) {
            appendLine(output, settings.cmiDocumentHeader());
        }

        if (settings.includeBookMetadata()) {
            appendLine(output, transform("Title: " + valueOr(book.styledTitle(), "Untitled Book"), settings));
            appendLine(output, transform("Author: " + valueOr(book.styledAuthor(), "Unknown"), settings));
            appendLine(output, "Exported by: " + playerName);
            appendLine(output, "Exported at: " + DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(exportedAt));
            appendLine(output, "Pages: " + book.pageCount());
            appendLine(output, "UTF-16 units: " + book.utf16Units());
            output.append('\n');
        }

        for (int index = 0; index < book.pages().size(); index++) {
            int pageNumber = index + 1;
            if (settings.pagination() && (index > 0 || settings.paginationOnFirstPage())) {
                String markup = settings.paginationMarkup()
                        .replace("%pageNumber%", Integer.toString(pageNumber))
                        .replace("%pages%", Integer.toString(book.pageCount()));
                appendLine(output, transform(markup, settings));
            } else if (!settings.pagination() && index > 0) {
                output.append('\n');
            }

            appendPage(output, transform(book.pages().get(index), settings));
        }
        return output.toString();
    }

    private static String transform(String value, ExportSettings settings) {
        return ColorCodeTransformer.transform(value, settings.colorMode());
    }

    private static String valueOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static void appendLine(StringBuilder output, String line) {
        output.append(line).append('\n');
    }

    private static void appendPage(StringBuilder output, String page) {
        output.append(page);
        if (page.isEmpty() || page.charAt(page.length() - 1) != '\n') {
            output.append('\n');
        }
    }
}
