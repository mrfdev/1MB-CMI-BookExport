package com.mrfloris.exportbook;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void stagedFileEntryUsesSafeReviewAndMutationActions() {
        Component entry = Messages.stagedFileEntry(
                "rules.txt",
                DraftListStatus.UNREVIEWED,
                true,
                true,
                true
        );

        assertNull(entry.clickEvent());
        assertTextClick(
                entry.children().get(1),
                ClickEvent.Action.COPY_TO_CLIPBOARD,
                "rules.txt"
        );
        assertHasTextClick(
                entry,
                ClickEvent.Action.RUN_COMMAND,
                "/bookexport admin review rules.txt"
        );
        assertHasTextClick(
                entry,
                ClickEvent.Action.SUGGEST_COMMAND,
                "/bookexport admin approve rules.txt"
        );
        assertHasTextClick(
                entry,
                ClickEvent.Action.SUGGEST_COMMAND,
                "/bookexport admin changes rules.txt"
        );
        assertHasTextClick(
                entry,
                ClickEvent.Action.SUGGEST_COMMAND,
                "/bookexport admin publish rules.txt fail"
        );
    }

    @Test
    void nonStagedFileNeverShowsPublishAction() {
        Component entry = Messages.fileEntry("rules.txt", FileScope.PUBLISHED);

        assertEquals(2, entry.children().size());
        assertTextClick(
                entry.children().get(1),
                ClickEvent.Action.COPY_TO_CLIPBOARD,
                "rules.txt"
        );
    }

    @Test
    void stagedFileEntryHidesAllActionsWithoutPermission() {
        Component entry = Messages.stagedFileEntry(
                "rules.txt",
                DraftListStatus.APPROVED,
                false,
                false,
                false
        );

        assertTextClick(
                entry.children().get(1),
                ClickEvent.Action.COPY_TO_CLIPBOARD,
                "rules.txt"
        );
        assertFalse(entry.children().stream().skip(2).anyMatch(child -> child.clickEvent() != null));
    }

    @Test
    void unsafeStateOffersReadOnlyReviewButNoMutation() {
        Component entry = Messages.stagedFileEntry(
                "rules.txt",
                DraftListStatus.CORRUPT,
                true,
                true,
                true
        );

        assertHasTextClick(
                entry,
                ClickEvent.Action.RUN_COMMAND,
                "/bookexport admin review rules.txt"
        );
        assertFalse(hasClickValue(entry, "/bookexport admin approve rules.txt"));
        assertFalse(hasClickValue(entry, "/bookexport admin changes rules.txt"));
        assertFalse(hasClickValue(entry, "/bookexport admin publish rules.txt fail"));
    }

    @Test
    void historyNavigationUsesAdminHistoryRoute() {
        Component navigation = Messages.historyNavigation(ListPage.calculate(30, 2, 10));

        assertRunCommand(navigation.children().get(0), "/bookexport admin history 1");
        assertRunCommand(navigation.children().get(2), "/bookexport admin history 3");
    }

    @Test
    void historyEntryCopiesIdAndRunsOnlyReadOnlyShow() {
        DraftManifest manifest = nativeManifest();
        Component entry = Messages.historyEntry(manifest);

        assertNull(entry.clickEvent());
        assertTextClick(
                entry.children().get(1),
                ClickEvent.Action.COPY_TO_CLIPBOARD,
                manifest.draftId().toString()
        );
        assertHasTextClick(
                entry,
                ClickEvent.Action.RUN_COMMAND,
                "/bookexport admin history show " + manifest.draftId()
        );
        assertFalse(entry.children().stream()
                .map(Component::clickEvent)
                .filter(click -> click != null)
                .anyMatch(click -> click.action() == ClickEvent.Action.SUGGEST_COMMAND));
    }

    @Test
    void copyableInfoKeepsClickOnValueOnly() {
        Component info = Messages.copyableInfo("SHA-256", "abcdef");

        assertNull(info.clickEvent());
        assertEquals(1, info.children().size());
        assertTextClick(
                info.children().getFirst(),
                ClickEvent.Action.COPY_TO_CLIPBOARD,
                "abcdef"
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

    private static void assertHasTextClick(
            Component component,
            ClickEvent.Action<?> expectedAction,
            String expectedValue
    ) {
        assertTrue(component.children().stream().anyMatch(child -> clickMatches(
                child,
                expectedAction,
                expectedValue
        )));
    }

    private static boolean hasClickValue(Component component, String expectedValue) {
        return component.children().stream().anyMatch(child -> {
            ClickEvent<?> clickEvent = child.clickEvent();
            if (clickEvent == null || !(clickEvent.payload() instanceof ClickEvent.Payload.Text payload)) {
                return false;
            }
            return expectedValue.equals(payload.value());
        });
    }

    private static boolean clickMatches(
            Component component,
            ClickEvent.Action<?> expectedAction,
            String expectedValue
    ) {
        ClickEvent<?> clickEvent = component.clickEvent();
        if (clickEvent == null || clickEvent.action() != expectedAction
                || !(clickEvent.payload() instanceof ClickEvent.Payload.Text payload)) {
            return false;
        }
        return expectedValue.equals(payload.value());
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

    private static DraftManifest nativeManifest() {
        return DraftManifest.nativeDraft(
                UUID.fromString("123e4567-e89b-12d3-a456-426614174000"),
                Instant.parse("2026-07-14T12:00:00Z"),
                new DraftManifest.Actor("Author", UUID.fromString("223e4567-e89b-12d3-a456-426614174000")),
                "Book Author",
                2,
                24,
                new ContentFingerprint(
                        12L,
                        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
                ),
                "rules.txt",
                "rules.txt"
        );
    }
}
