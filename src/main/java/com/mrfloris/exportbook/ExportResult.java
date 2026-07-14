package com.mrfloris.exportbook;

import java.nio.file.Path;

/** Successful file write result. */
record ExportResult(Path path, int pages, int characters, int utf8Bytes) {
}
