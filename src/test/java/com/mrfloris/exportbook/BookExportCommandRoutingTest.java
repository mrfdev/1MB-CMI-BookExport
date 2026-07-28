package com.mrfloris.exportbook;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BookExportCommandRoutingTest {
    @Test
    void versionIsAnExactInformationAlias() {
        assertEquals(BookExportCommand.RootRoute.INFO, BookExportCommand.rootRoute("version"));
        assertEquals(BookExportCommand.RootRoute.INFO, BookExportCommand.rootRoute("VeRsIoN"));
    }

    @Test
    void statusIsAnExactAdminStatusAlias() {
        assertEquals(BookExportCommand.RootRoute.STATUS, BookExportCommand.rootRoute("status"));
        assertEquals(BookExportCommand.RootRoute.STATUS, BookExportCommand.rootRoute("STATUS"));
    }

    @Test
    void unknownWordsRemainLegacyCustomTitles() {
        assertEquals(BookExportCommand.RootRoute.LEGACY_TITLE, BookExportCommand.rootRoute("release-notes"));
        assertEquals(BookExportCommand.RootRoute.LEGACY_TITLE, BookExportCommand.rootRoute("my-book"));
    }
}
