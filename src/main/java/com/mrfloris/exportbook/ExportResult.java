package com.mrfloris.exportbook;

import java.nio.file.Path;
import java.util.UUID;

/** Successful file write result. */
record ExportResult(
        Path path,
        FileScope scope,
        int pages,
        int utf16Units,
        int utf8Bytes,
        UUID draftId,
        String sha256
) {
    boolean managedDraft() {
        return draftId != null;
    }
}
