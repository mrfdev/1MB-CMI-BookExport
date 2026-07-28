package com.mrfloris.exportbook;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecoveryCommandRequestTest {
    private static final UUID TRANSACTION_ID = UUID.fromString("01234567-89ab-cdef-0123-456789abcdef");

    @Test
    void defaultsToFirstListPage() {
        RecoveryCommandRequest request = RecoveryCommandRequest.parse(
                new String[]{"admin", "recovery"},
                2
        );

        assertEquals(RecoveryCommandRequest.Action.LIST, request.action());
        assertEquals(1, request.page());
        assertFalse(request.isShow());
    }

    @Test
    void acceptsExplicitListAndPositivePageCaseInsensitively() {
        RecoveryCommandRequest first = RecoveryCommandRequest.parse(
                new String[]{"admin", "recovery", "LiSt"},
                2
        );
        RecoveryCommandRequest later = RecoveryCommandRequest.parse(
                new String[]{"admin", "recovery", "list", "4"},
                2
        );

        assertEquals(1, first.page());
        assertEquals(4, later.page());
    }

    @Test
    void acceptsOnlyCompleteCanonicalUuidForShow() {
        RecoveryCommandRequest request = RecoveryCommandRequest.parse(
                new String[]{"admin", "recovery", "SHOW", TRANSACTION_ID.toString().toUpperCase()},
                2
        );

        assertTrue(request.isShow());
        assertEquals(TRANSACTION_ID, request.transactionId());
    }

    @Test
    void rejectsBarePagePrefixAndMalformedRequests() {
        assertThrows(IllegalArgumentException.class, () -> RecoveryCommandRequest.parse(
                new String[]{"admin", "recovery", "2"}, 2
        ));
        assertThrows(IllegalArgumentException.class, () -> RecoveryCommandRequest.parse(
                new String[]{"admin", "recovery", "show", "01234567"}, 2
        ));
        assertThrows(IllegalArgumentException.class, () -> RecoveryCommandRequest.parse(
                new String[]{"admin", "recovery", "list", "0"}, 2
        ));
        assertThrows(IllegalArgumentException.class, () -> RecoveryCommandRequest.parse(
                new String[]{"admin", "recovery", "show", TRANSACTION_ID.toString(), "extra"}, 2
        ));
        assertThrows(IllegalArgumentException.class, () -> RecoveryCommandRequest.parse(
                new String[]{"admin", "recovery", "unknown"}, 2
        ));
    }

    @Test
    void rejectsInvalidParserOffset() {
        assertThrows(IllegalArgumentException.class, () -> RecoveryCommandRequest.parse(new String[0], -1));
        assertThrows(IllegalArgumentException.class, () -> RecoveryCommandRequest.parse(new String[0], 1));
    }
}
