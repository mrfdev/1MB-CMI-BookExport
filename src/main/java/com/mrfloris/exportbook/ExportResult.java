package com.mrfloris.exportbook;

import java.nio.file.Path;

/** Successful file write result. */
record ExportResult(Path path, int pages, int utf16Units, int utf8Bytes) {
}
