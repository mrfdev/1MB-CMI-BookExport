package com.mrfloris.exportbook;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BookExporterTest {
    @Test
    void replacesFilenameTokensOnceWithoutReinterpretingValues() {
        assertEquals(
                "%player%_Alice",
                BookExporter.replacePlaceholders(
                        "%title%_%player%",
                        Map.of("%title%", "%player%", "%player%", "Alice")
                )
        );
    }
}
