package com.mrfloris.exportbook;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class WorkflowEnumsTest {
    @Test
    void collisionModeKeysRoundTrip() {
        for (PublishCollisionMode mode : PublishCollisionMode.values()) {
            assertEquals(mode, PublishCollisionMode.parse(mode.key()).orElseThrow());
        }

        assertEquals(
                PublishCollisionMode.REPLACE_WITH_BACKUP,
                PublishCollisionMode.parse("  REPLACE  ").orElseThrow()
        );
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "overwrite", "replace_without_backup", "unknown"})
    void collisionModeRejectsUnknownValues(String value) {
        assertFalse(PublishCollisionMode.parse(value).isPresent());
    }

    @Test
    void workflowModeKeysRoundTripCaseInsensitively() {
        for (WorkflowMode mode : WorkflowMode.values()) {
            assertEquals(mode, WorkflowMode.parse(mode.key()).orElseThrow());
            assertEquals(mode, WorkflowMode.parse("  " + mode.name() + "  ").orElseThrow());
        }
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "review", "legacy", "unknown"})
    void workflowModeRejectsUnknownValues(String value) {
        assertFalse(WorkflowMode.parse(value).isPresent());
    }

    @Test
    void fileScopeKeysRoundTripAndHaveDescriptions() {
        for (FileScope scope : FileScope.values()) {
            assertEquals(scope, FileScope.parse(scope.key()).orElseThrow());
            assertEquals(scope, FileScope.parse("  " + scope.key().toUpperCase() + "  ").orElseThrow());
            assertFalse(scope.displayName().isBlank());
        }
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "draft", "backup", "unknown"})
    void fileScopeRejectsUnknownValues(String value) {
        assertFalse(FileScope.parse(value).isPresent());
    }
}
