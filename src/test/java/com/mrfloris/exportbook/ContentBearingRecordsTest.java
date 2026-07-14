package com.mrfloris.exportbook;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ContentBearingRecordsTest {
    private static final String SECRET_SENTINEL = "TOP_SECRET_BOOK_PAGE_7F91";

    @Test
    void bookSnapshotToStringReportsCountsWithoutExposingBookText() {
        BookSnapshot snapshot = new BookSnapshot(
                true,
                SECRET_SENTINEL + " title",
                SECRET_SENTINEL + " styled title",
                SECRET_SENTINEL + " author",
                SECRET_SENTINEL + " styled author",
                List.of("Page containing " + SECRET_SENTINEL, "Unicode 📘")
        );

        String output = snapshot.toString();

        assertFalse(output.contains(SECRET_SENTINEL));
        assertEquals(
                "BookSnapshot[signed=true, hasTitle=true, hasAuthor=true, pages=2, utf16Units="
                        + snapshot.utf16Units() + ']',
                output
        );
    }

    @Test
    void exportPreviewToStringReportsMetadataWithoutExposingRenderedContent() {
        BookSnapshot snapshot = new BookSnapshot(
                false,
                "",
                "",
                "",
                "",
                List.of("Raw page " + SECRET_SENTINEL)
        );
        ExportPreview preview = new ExportPreview(
                snapshot,
                SECRET_SENTINEL + " resolved title",
                "safe_filename",
                "<AutoPage>\nRendered " + SECRET_SENTINEL,
                123,
                Instant.parse("2026-07-14T12:00:00Z")
        );

        String output = preview.toString();

        assertFalse(output.contains(SECRET_SENTINEL));
        assertEquals(
                "ExportPreview[filenameBase=safe_filename, pages=1, utf16Units="
                        + snapshot.utf16Units() + ", utf8Bytes=123]",
                output
        );
    }
}
