package com.mrfloris.exportbook;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;

/** Adventure message helpers with no legacy/Bungee chat dependency. */
final class Messages {
    private Messages() {
    }

    static Component header(String text) {
        return Component.text(text, NamedTextColor.GOLD);
    }

    static Component success(String text) {
        return prefixed(Component.text(text, NamedTextColor.GREEN));
    }

    static Component error(String text) {
        return prefixed(Component.text(text, NamedTextColor.RED));
    }

    static Component info(String label, String value) {
        return Component.text(label + ": ", NamedTextColor.YELLOW)
                .append(Component.text(value, NamedTextColor.GRAY));
    }

    static Component command(String command, String description) {
        TextComponent clickable = Component.text(command, NamedTextColor.YELLOW)
                .clickEvent(ClickEvent.suggestCommand(command))
                .hoverEvent(HoverEvent.showText(Component.text("Click to suggest", NamedTextColor.GRAY)));
        return clickable.append(Component.text(" - " + description, NamedTextColor.GRAY));
    }

    static Component source(String url) {
        return Component.text("Source: ", NamedTextColor.YELLOW)
                .append(Component.text(url, NamedTextColor.AQUA)
                        .clickEvent(ClickEvent.openUrl(url))
                        .hoverEvent(HoverEvent.showText(Component.text("Open the BookExport repository"))));
    }

    private static Component prefixed(Component message) {
        return Component.text("[BookExport] ", NamedTextColor.GOLD).append(message);
    }
}
