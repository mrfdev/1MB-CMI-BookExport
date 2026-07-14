package com.mrfloris.exportbook;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorkflowCommandRequestTest {
    @Test
    void emptyListArgumentsUsePublishedFirstPage() {
        ListCommandRequest request = ListCommandRequest.parse(new String[]{"list"}, 1);

        assertEquals(FileScope.PUBLISHED, request.scope());
        assertEquals(1, request.page());
    }

    @Test
    void legacyNumericListArgumentUsesPublishedScope() {
        ListCommandRequest request = ListCommandRequest.parse(new String[]{"list", "12"}, 1);

        assertEquals(FileScope.PUBLISHED, request.scope());
        assertEquals(12, request.page());
    }

    @Test
    void adminScopedListParsesPage() {
        ListCommandRequest request = ListCommandRequest.parse(
                new String[]{"admin", "list", "archive", "3"},
                2
        );

        assertEquals(FileScope.ARCHIVE, request.scope());
        assertEquals(3, request.page());
    }

    @Test
    void nonPositiveAndOverflowPagesHaveControlledError() {
        for (String page : new String[]{"0", "-1", "+0", "999999999999999999999"}) {
            IllegalArgumentException exception = assertThrows(
                    IllegalArgumentException.class,
                    () -> ListCommandRequest.parse(new String[]{"list", page}, 1)
            );
            assertEquals("List page must be a positive number.", exception.getMessage());
        }
    }

    @Test
    void listParserRejectsUnknownScopeAndExtraArguments() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ListCommandRequest.parse(new String[]{"list", "drafts"}, 1)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> ListCommandRequest.parse(new String[]{"list", "staged", "2", "extra"}, 1)
        );
    }

    @Test
    void publishParserSupportsFilenameWithoutMode() {
        PublishCommandRequest request = PublishCommandRequest.parse(
                new String[]{"admin", "publish", "rules.txt"},
                2
        );

        assertEquals("rules.txt", request.stagedFilename());
        assertNull(request.collisionModeOverride());
        assertEquals(
                PublishCollisionMode.UNIQUE,
                request.effectiveMode(PublishCollisionMode.UNIQUE)
        );
    }

    @Test
    void publishParserSupportsSafeModeAndFilenameContainingSpaces() {
        PublishCommandRequest request = PublishCommandRequest.parse(
                new String[]{"admin", "publish", "Server", "Rules.txt", "fail"},
                2
        );

        assertEquals("Server Rules.txt", request.stagedFilename());
        assertEquals(PublishCollisionMode.FAIL, request.collisionModeOverride());
        assertEquals(
                PublishCollisionMode.FAIL,
                request.effectiveMode(PublishCollisionMode.REPLACE_WITH_BACKUP)
        );
    }

    @Test
    void publishParserRecognizesReplaceAlias() {
        PublishCommandRequest request = PublishCommandRequest.parse(
                new String[]{"admin", "publish", "rules.txt", "replace"},
                2
        );

        assertEquals(PublishCollisionMode.REPLACE_WITH_BACKUP, request.collisionModeOverride());
    }

    @Test
    void publishParserRejectsMissingFilename() {
        assertThrows(
                IllegalArgumentException.class,
                () -> PublishCommandRequest.parse(new String[]{"admin", "publish"}, 2)
        );
    }
}
