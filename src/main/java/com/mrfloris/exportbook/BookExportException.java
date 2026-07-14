package com.mrfloris.exportbook;

/** Expected validation or filesystem failure suitable for a player-facing message. */
final class BookExportException extends Exception {
    private static final long serialVersionUID = 1L;

    BookExportException(String message) {
        super(message);
    }

    BookExportException(String message, Throwable cause) {
        super(message, cause);
    }
}
