package com.mrfloris.exportbook;

/** Read-only export calculation used by export and debug preview commands. */
record ExportPreview(BookSnapshot book, String resolvedTitle, String filenameBase, String content, int utf8Bytes) {
}
