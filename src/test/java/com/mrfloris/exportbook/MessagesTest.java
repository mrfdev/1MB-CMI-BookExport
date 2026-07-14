package com.mrfloris.exportbook;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

class MessagesTest {
    @Test
    void helpCommandOnlySuggestsCommand() {
        Component message = Messages.command("/bookexport help", "show help");

        assertTextClick(
                message,
                ClickEvent.Action.SUGGEST_COMMAND,
                "/bookexport help"
        );
    }

    @Test
    void sourceLinkUsesOpenUrlAction() {
        Component source = Messages.source("https://example.com/bookexport");

        assertNull(source.clickEvent());
        assertEquals(1, source.children().size());
        assertEquals(ClickEvent.Action.OPEN_URL, source.children().getFirst().clickEvent().action());
    }

    @Test
    void firstPageEnablesOnlyNext() {
        List<Component> controls = controlsForPage(1);

        assertNull(controls.get(0).clickEvent());
        assertNull(controls.get(1).clickEvent());
        assertRunCommand(controls.get(2), "/bookexport list 2");
    }

    @Test
    void middlePageEnablesBothDirections() {
        List<Component> controls = controlsForPage(2);

        assertRunCommand(controls.get(0), "/bookexport list 1");
        assertNull(controls.get(1).clickEvent());
        assertRunCommand(controls.get(2), "/bookexport list 3");
    }

    @Test
    void lastPageEnablesOnlyPrevious() {
        List<Component> controls = controlsForPage(3);

        assertRunCommand(controls.get(0), "/bookexport list 2");
        assertNull(controls.get(1).clickEvent());
        assertNull(controls.get(2).clickEvent());
    }

    @Test
    void scopedNavigationPreservesStagedScope() {
        Component navigation = Messages.listNavigation(
                ListPage.calculate(30, 2, 10),
                FileScope.STAGED
        );
        List<Component> controls = navigation.children();

        assertRunCommand(controls.get(0), "/bookexport list staged 1");
        assertRunCommand(controls.get(2), "/bookexport list staged 3");
    }

    @Test
    void navigationPreservesEveryExplicitScope() {
        for (FileScope scope : List.of(FileScope.STAGED, FileScope.ARCHIVE, FileScope.BACKUPS)) {
            Component navigation = Messages.listNavigation(
                    ListPage.calculate(30, 2, 10),
                    scope
            );

            assertRunCommand(
                    navigation.children().get(0),
                    "/bookexport list " + scope.key() + " 1"
            );
            assertRunCommand(
                    navigation.children().get(2),
                    "/bookexport list " + scope.key() + " 3"
            );
        }
    }

    @Test
    void stagedFileEntryCopiesFilenameAndOnlySuggestsPublish() {
        Component entry = Messages.fileEntry("rules.txt", FileScope.STAGED, true);

        assertNull(entry.clickEvent());
        assertEquals(4, entry.children().size());
        assertTextClick(
                entry.children().get(1),
                ClickEvent.Action.COPY_TO_CLIPBOARD,
                "rules.txt"
        );
        assertTextClick(
                entry.children().get(3),
                ClickEvent.Action.SUGGEST_COMMAND,
                "/bookexport admin publish rules.txt fail"
        );
    }

    @Test
    void nonStagedFileNeverShowsPublishAction() {
        Component entry = Messages.fileEntry("rules.txt", FileScope.PUBLISHED, true);

        assertEquals(2, entry.children().size());
        assertTextClick(
                entry.children().get(1),
                ClickEvent.Action.COPY_TO_CLIPBOARD,
                "rules.txt"
        );
    }

    @Test
    void stagedFileEntryHidesPublishActionWithoutPermission() {
        Component entry = Messages.fileEntry("rules.txt", FileScope.STAGED, false);

        assertEquals(2, entry.children().size());
        assertTextClick(
                entry.children().get(1),
                ClickEvent.Action.COPY_TO_CLIPBOARD,
                "rules.txt"
        );
    }

    private static List<Component> controlsForPage(int page) {
        Component navigation = Messages.listNavigation(ListPage.calculate(30, page, 10));
        assertNull(navigation.clickEvent());
        assertEquals(3, navigation.children().size());
        return navigation.children();
    }

    private static void assertRunCommand(Component component, String expectedCommand) {
        assertTextClick(component, ClickEvent.Action.RUN_COMMAND, expectedCommand);
    }

    private static void assertTextClick(
            Component component,
            ClickEvent.Action<?> expectedAction,
            String expectedValue
    ) {
        ClickEvent<?> clickEvent = component.clickEvent();
        assertEquals(expectedAction, clickEvent.action());
        ClickEvent.Payload.Text payload = assertInstanceOf(ClickEvent.Payload.Text.class, clickEvent.payload());
        assertEquals(expectedValue, payload.value());
    }
}
