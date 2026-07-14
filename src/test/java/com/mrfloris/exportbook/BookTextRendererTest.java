package com.mrfloris.exportbook;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class BookTextRendererTest {
    private static final LocalDateTime EXPORTED_AT = LocalDateTime.of(2026, 7, 14, 12, 0);

    @Test
    void cmiHeaderOwnsFirstLineAndSeparatorOccursOnlyBetweenPages() {
        BookSnapshot book = book(List.of("Page one", "Page two", "Page three"));

        String rendered = BookTextRenderer.render("Tester", book, EXPORTED_AT, cmiSettings());

        assertEquals(
                "<AutoPage>\nPage one\n<NextPage>\nPage two\n<NextPage>\nPage three\n",
                rendered
        );
        assertFalse(rendered.startsWith("<AutoPage>\n<NextPage>"));
    }

    @Test
    void onePageCmiExportHasNoNextPageMarker() {
        String rendered = BookTextRenderer.render(
                "Tester",
                book(List.of("Only page")),
                EXPORTED_AT,
                cmiSettings()
        );

        assertEquals("<AutoPage>\nOnly page\n", rendered);
    }

    @Test
    void visiblePageHeadingsCanIncludeFirstPageAndTotalPages() {
        ExportSettings settings = settings(
                false,
                "=== Page %pageNumber% of %pages% ===",
                true,
                "",
                ColorMode.STRIP
        );

        String rendered = BookTextRenderer.render(
                "Tester",
                book(List.of("One", "Two")),
                EXPORTED_AT,
                settings
        );

        assertEquals("=== Page 1 of 2 ===\nOne\n=== Page 2 of 2 ===\nTwo\n", rendered);
    }

    private static BookSnapshot book(List<String> pages) {
        return new BookSnapshot(true, "Title", "Title", "Author", "Author", pages);
    }

    private static ExportSettings cmiSettings() {
        return settings(true, "<NextPage>", false, "<AutoPage>", ColorMode.CMI);
    }

    private static ExportSettings settings(
            boolean lowercaseFilenames,
            String paginationMarkup,
            boolean paginationOnFirstPage,
            String cmiDocumentHeader,
            ColorMode colorMode
    ) {
        return new ExportSettings(
                3,
                false,
                WorkflowMode.STAGED,
                Path.of("staging"),
                Path.of("published"),
                Path.of("archive"),
                Path.of("backups"),
                PublishCollisionMode.FAIL,
                "%title%",
                lowercaseFilenames,
                96,
                true,
                paginationMarkup,
                paginationOnFirstPage,
                cmiDocumentHeader,
                false,
                colorMode,
                10,
                false
        );
    }
}
