package com.mrfloris.exportbook;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HistoryCommandRequestTest {
    private static final UUID MANIFEST_ID = UUID.fromString("9d578fa6-53ee-4d6a-8e1f-e7eef96ea7ce");

    @Test
    void omittedPageListsTheFirstHistoryPage() {
        HistoryCommandRequest request = HistoryCommandRequest.parse(
                new String[]{"admin", "history"},
                2
        );

        assertEquals(HistoryCommandRequest.Action.LIST, request.action());
        assertEquals(1, request.page());
        assertNull(request.manifestId());
        assertFalse(request.isShow());
    }

    @Test
    void positivePageListsThatHistoryPage() {
        HistoryCommandRequest request = HistoryCommandRequest.parse(
                new String[]{"admin", "history", "12"},
                2
        );

        assertEquals(HistoryCommandRequest.Action.LIST, request.action());
        assertEquals(12, request.page());
        assertNull(request.manifestId());
    }

    @Test
    void showAcceptsACompleteUuidCaseInsensitively() {
        HistoryCommandRequest request = HistoryCommandRequest.parse(
                new String[]{"admin", "history", "SHOW", MANIFEST_ID.toString().toUpperCase()},
                2
        );

        assertEquals(HistoryCommandRequest.Action.SHOW, request.action());
        assertEquals(1, request.page());
        assertEquals(MANIFEST_ID, request.manifestId());
        assertTrue(request.isShow());
    }

    @Test
    void nonPositiveOverflowAndNonNumericPagesHaveControlledError() {
        for (String page : new String[]{"0", "-1", "+0", "999999999999999999999", "two"}) {
            IllegalArgumentException exception = assertThrows(
                    IllegalArgumentException.class,
                    () -> HistoryCommandRequest.parse(
                            new String[]{"admin", "history", page},
                            2
                    )
            );
            assertEquals("History page must be a positive number.", exception.getMessage());
        }
    }

    @Test
    void showRejectsTruncatedMalformedAndMissingIds() {
        for (String id : new String[]{"1-1-1-1-1", "not-a-uuid", MANIFEST_ID + "-extra"}) {
            IllegalArgumentException exception = assertThrows(
                    IllegalArgumentException.class,
                    () -> HistoryCommandRequest.parse(
                            new String[]{"admin", "history", "show", id},
                            2
                    )
            );
            assertEquals("Manifest ID must be a complete UUID.", exception.getMessage());
        }

        assertThrows(
                IllegalArgumentException.class,
                () -> HistoryCommandRequest.parse(
                        new String[]{"admin", "history", "show"},
                        2
                )
        );
    }

    @Test
    void parserRejectsUnknownActionsExtraArgumentsAndInvalidOffsets() {
        assertThrows(
                IllegalArgumentException.class,
                () -> HistoryCommandRequest.parse(
                        new String[]{"admin", "history", "search", MANIFEST_ID.toString()},
                        2
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> HistoryCommandRequest.parse(
                        new String[]{"admin", "history", "show", MANIFEST_ID.toString(), "extra"},
                        2
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> HistoryCommandRequest.parse(new String[]{"admin", "history"}, -1)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> HistoryCommandRequest.parse(new String[]{"admin", "history"}, 3)
        );
    }
}
