package com.mrfloris.exportbook;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

class BookExportInfoTest {
    private static final String DOCS_URL =
            "https://docs.1moreblock.com/custom-server-plugins/bookexport/";
    private static final String SOURCE_URL = "https://github.com/mrfdev/1MB-CMI-BookExport";

    @Test
    void stagedListStaffReceivesCompleteInformationInStableOrder() {
        List<Component> messages = info().messages(true, true, true, true);

        assertEquals(List.of(
                "BookExport 2.0.1 build 017",
                "About: Turn a held written book or book and quill into a reviewable UTF-8 CMI CustomText draft",
                "Compatibility: Paper 26.2, Java target 25",
                "Workflow: staged",
                "Server: Paper 26.2 build 60",
                "Quick start: Use the clickable commands below",
                "/bookexport help - show the commands available to you",
                "/bookexport stage [title] - hold a book, then create a draft for staff review",
                "/bookexport list staged - open the staged review queue",
                "Documentation: " + DOCS_URL,
                "Source: " + SOURCE_URL
        ), messages.stream().map(BookExportInfoTest::plainText).toList());

        assertClick(messages.get(6), ClickEvent.Action.SUGGEST_COMMAND, "/bookexport help");
        assertClick(messages.get(7), ClickEvent.Action.SUGGEST_COMMAND, "/bookexport stage [title]");
        assertClick(messages.get(8), ClickEvent.Action.SUGGEST_COMMAND, "/bookexport list staged");
        assertChildClick(messages.get(9), ClickEvent.Action.OPEN_URL, DOCS_URL);
        assertChildClick(messages.get(10), ClickEvent.Action.OPEN_URL, SOURCE_URL);
    }

    @Test
    void infoOnlyPlayerReceivesPublicInformationAndLinksWithoutDeniedCommands() {
        List<Component> messages = info().messages(false, false, false, false);
        List<String> text = messages.stream().map(BookExportInfoTest::plainText).toList();

        assertEquals(List.of(
                "BookExport 2.0.1 build 017",
                "About: Turn a held written book or book and quill into a reviewable UTF-8 CMI CustomText draft",
                "Compatibility: Paper 26.2, Java target 25",
                "Workflow: staged",
                "Server: Paper 26.2 build 60",
                "Documentation: " + DOCS_URL,
                "Source: " + SOURCE_URL
        ), text);
        assertChildClick(messages.get(5), ClickEvent.Action.OPEN_URL, DOCS_URL);
        assertChildClick(messages.get(6), ClickEvent.Action.OPEN_URL, SOURCE_URL);
    }

    @Test
    void trustedAuthorReceivesHelpAndStageButNoStaffQueue() {
        List<Component> messages = info().messages(true, true, true, false);
        List<String> text = messages.stream().map(BookExportInfoTest::plainText).toList();

        assertEquals(10, messages.size());
        assertEquals("Quick start: Use the clickable commands below", text.get(5));
        assertEquals("/bookexport help - show the commands available to you", text.get(6));
        assertEquals(
                "/bookexport stage [title] - hold a book, then create a draft for staff review",
                text.get(7)
        );
        assertFalse(text.stream().anyMatch(line -> line.startsWith("/bookexport list staged")));
        assertClick(messages.get(6), ClickEvent.Action.SUGGEST_COMMAND, "/bookexport help");
        assertClick(messages.get(7), ClickEvent.Action.SUGGEST_COMMAND, "/bookexport stage [title]");
        assertChildClick(messages.get(8), ClickEvent.Action.OPEN_URL, DOCS_URL);
        assertChildClick(messages.get(9), ClickEvent.Action.OPEN_URL, SOURCE_URL);
    }

    private static BookExportInfo info() {
        return new BookExportInfo(
                "2.0.1",
                "017",
                "26.2",
                "25",
                "staged",
                "Paper 26.2 build 60",
                DOCS_URL,
                SOURCE_URL
        );
    }

    private static String plainText(Component component) {
        StringBuilder text = new StringBuilder();
        appendText(component, text);
        return text.toString();
    }

    private static void appendText(Component component, StringBuilder text) {
        if (component instanceof TextComponent textComponent) {
            text.append(textComponent.content());
        }
        for (Component child : component.children()) {
            appendText(child, text);
        }
    }

    private static void assertChildClick(
            Component component,
            ClickEvent.Action<?> action,
            String value
    ) {
        assertNull(component.clickEvent());
        assertEquals(1, component.children().size());
        assertClick(component.children().getFirst(), action, value);
    }

    private static void assertClick(Component component, ClickEvent.Action<?> action, String value) {
        ClickEvent<?> click = component.clickEvent();
        assertEquals(action, click.action());
        ClickEvent.Payload.Text payload = assertInstanceOf(ClickEvent.Payload.Text.class, click.payload());
        assertEquals(value, payload.value());
    }
}
