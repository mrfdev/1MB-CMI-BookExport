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
        ExportSettings settings = new ExportSettings(
                Path.of("."), "%title%", false, 96,
                true, "=== Page %pageNumber% of %pages% ===", true, "",
                false, ColorMode.STRIP, 10, false
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
        return new ExportSettings(
                Path.of("."), "%title%", true, 96,
                true, "<NextPage>", false, "<AutoPage>",
                false, ColorMode.CMI, 10, false
        );
    }
}
