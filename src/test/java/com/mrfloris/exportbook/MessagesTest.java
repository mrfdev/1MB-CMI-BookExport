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

    private static List<Component> controlsForPage(int page) {
        Component navigation = Messages.listNavigation(ListPage.calculate(30, page, 10));
        assertNull(navigation.clickEvent());
        assertEquals(3, navigation.children().size());
        return navigation.children();
    }

    private static void assertRunCommand(Component component, String expectedCommand) {
        ClickEvent<?> clickEvent = component.clickEvent();
        assertEquals(ClickEvent.Action.RUN_COMMAND, clickEvent.action());
        ClickEvent.Payload.Text payload = assertInstanceOf(ClickEvent.Payload.Text.class, clickEvent.payload());
        assertEquals(expectedCommand, payload.value());
    }
}
