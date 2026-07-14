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

    static Component warning(String text) {
        return prefixed(Component.text(text, NamedTextColor.YELLOW));
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

    static Component listNavigation(ListPage listPage) {
        return listNavigation(listPage, FileScope.PUBLISHED);
    }

    static Component listNavigation(ListPage listPage, FileScope scope) {
        Component previous = listPage.hasPrevious()
                ? listPageButton("← Previous", listPage.previousPage(), scope)
                : Component.text("[← Previous]", NamedTextColor.DARK_GRAY);
        Component next = listPage.hasNext()
                ? listPageButton("Next →", listPage.nextPage(), scope)
                : Component.text("[Next →]", NamedTextColor.DARK_GRAY);

        return Component.text()
                .append(previous)
                .append(Component.text(
                        "  Page " + listPage.page() + '/' + listPage.pageCount() + "  ",
                        NamedTextColor.GRAY
                ))
                .append(next)
                .build();
    }

    static Component fileEntry(String filename, FileScope scope, boolean canPublish) {
        Component filenameComponent = Component.text(filename, NamedTextColor.AQUA)
                .clickEvent(ClickEvent.copyToClipboard(filename))
                .hoverEvent(HoverEvent.showText(Component.text(
                        "Click to copy this filename",
                        NamedTextColor.GRAY
                )));
        TextComponent.Builder entry = Component.text()
                .append(Component.text("- ", NamedTextColor.GRAY))
                .append(filenameComponent);
        if (scope == FileScope.STAGED && canPublish) {
            String command = "/bookexport admin publish " + filename + " fail";
            entry.append(Component.space())
                    .append(Component.text("[Publish]", NamedTextColor.GREEN)
                            .clickEvent(ClickEvent.suggestCommand(command))
                            .hoverEvent(HoverEvent.showText(Component.text(
                                    "Suggest the publish command; review it, then press Enter",
                                    NamedTextColor.GRAY
                            ))));
        }
        return entry.build();
    }

    static Component source(String url) {
        return Component.text("Source: ", NamedTextColor.YELLOW)
                .append(Component.text(url, NamedTextColor.AQUA)
                        .clickEvent(ClickEvent.openUrl(url))
                        .hoverEvent(HoverEvent.showText(Component.text("Open the BookExport repository"))));
    }

    private static Component listPageButton(String label, int page, FileScope scope) {
        String command = scope == FileScope.PUBLISHED
                ? "/bookexport list " + page
                : "/bookexport list " + scope.key() + ' ' + page;
        return Component.text('[' + label + ']', NamedTextColor.AQUA)
                .clickEvent(ClickEvent.runCommand(command))
                .hoverEvent(HoverEvent.showText(Component.text("Open list page " + page, NamedTextColor.GRAY)));
    }

    private static Component prefixed(Component message) {
        return Component.text("[BookExport] ", NamedTextColor.GOLD).append(message);
    }
}
