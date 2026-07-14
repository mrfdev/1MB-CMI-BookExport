package com.mrfloris.exportbook;

import java.time.Instant;

/** Read-only export calculation used by export and debug preview commands. */
record ExportPreview(
        BookSnapshot book,
        String resolvedTitle,
        String filenameBase,
        String content,
        int utf8Bytes,
        Instant createdAt
) {
    /** Returns useful export diagnostics without exposing the rendered book text. */
    @Override
    public String toString() {
        return "ExportPreview[filenameBase=" + filenameBase
                + ", pages=" + book.pageCount()
                + ", utf16Units=" + book.utf16Units()
                + ", utf8Bytes=" + utf8Bytes + ']';
    }
}
