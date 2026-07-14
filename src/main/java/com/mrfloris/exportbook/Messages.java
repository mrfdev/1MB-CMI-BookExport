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

    static Component copyableInfo(String label, String value) {
        return Component.text(label + ": ", NamedTextColor.YELLOW)
                .append(Component.text(value, NamedTextColor.AQUA)
                        .clickEvent(ClickEvent.copyToClipboard(value))
                        .hoverEvent(HoverEvent.showText(Component.text(
                                "Click to copy",
                                NamedTextColor.GRAY
                        ))));
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
        return pageNavigation(listPage, previous, next);
    }

    static Component historyNavigation(ListPage listPage) {
        Component previous = listPage.hasPrevious()
                ? historyPageButton("← Previous", listPage.previousPage())
                : Component.text("[← Previous]", NamedTextColor.DARK_GRAY);
        Component next = listPage.hasNext()
                ? historyPageButton("Next →", listPage.nextPage())
                : Component.text("[Next →]", NamedTextColor.DARK_GRAY);
        return pageNavigation(listPage, previous, next);
    }

    static Component fileEntry(String filename, FileScope scope) {
        return Component.text()
                .append(Component.text("- ", NamedTextColor.GRAY))
                .append(copyableFilename(filename, "Click to copy this " + scope.key() + " filename"))
                .build();
    }

    static Component stagedFileEntry(
            String filename,
            DraftListStatus status,
            boolean canReview,
            boolean canApprove,
            boolean canPublish
    ) {
        TextComponent.Builder entry = Component.text()
                .append(Component.text("- ", NamedTextColor.GRAY))
                .append(copyableFilename(filename, "Click to copy this staged filename"))
                .append(Component.space())
                .append(statusBadge(status));

        if (canReview) {
            entry.append(Component.space()).append(action(
                    "Review",
                    NamedTextColor.AQUA,
                    ClickEvent.runCommand("/bookexport admin review " + filename),
                    "Open content-free manifest and checksum details"
            ));
        }
        if (canApprove && status.approveSuggestionAllowed()) {
            entry.append(Component.space()).append(action(
                    "Approve",
                    NamedTextColor.GREEN,
                    ClickEvent.suggestCommand("/bookexport admin approve " + filename),
                    "Suggest approval of the current exact bytes"
            ));
        }
        if (canApprove && status.changesSuggestionAllowed()) {
            entry.append(Component.space()).append(action(
                    "Changes",
                    NamedTextColor.GOLD,
                    ClickEvent.suggestCommand("/bookexport admin changes " + filename),
                    "Suggest a changes-requested decision"
            ));
        }
        if (canPublish && status.publishSuggestionAllowed()) {
            entry.append(Component.space()).append(action(
                    "Publish",
                    NamedTextColor.GREEN,
                    ClickEvent.suggestCommand("/bookexport admin publish " + filename + " fail"),
                    "Suggest collision-safe publication; review it, then press Enter"
            ));
        }
        return entry.build();
    }

    static Component historyEntry(DraftManifest manifest) {
        String id = manifest.draftId().toString();
        String filename = manifest.publication() == null
                ? manifest.stagedFilename()
                : manifest.publication().publishedFilename();
        String state = manifest.publicationStatus().key() + "/" + manifest.reviewStatus().key();
        return Component.text()
                .append(Component.text("- ", NamedTextColor.GRAY))
                .append(Component.text(id.substring(0, 8), NamedTextColor.AQUA)
                        .clickEvent(ClickEvent.copyToClipboard(id))
                        .hoverEvent(HoverEvent.showText(Component.text(
                                "Click to copy full manifest ID",
                                NamedTextColor.GRAY
                        ))))
                .append(Component.space())
                .append(Component.text('[' + state + ']', historyColor(manifest))
                        .hoverEvent(HoverEvent.showText(Component.text(
                                "Created " + manifest.createdAt() + " by "
                                        + displayMetadata(manifest.createdBy().name()),
                                NamedTextColor.GRAY
                        ))))
                .append(Component.space())
                .append(copyableFilename(filename, "Click to copy this filename"))
                .append(Component.space())
                .append(action(
                        "Show",
                        NamedTextColor.AQUA,
                        ClickEvent.runCommand("/bookexport admin history show " + id),
                        "Open this content-free manifest record"
                ))
                .build();
    }

    static Component source(String url) {
        return link("Source", url, "Open the BookExport repository");
    }

    static Component link(String label, String url, String hoverText) {
        return Component.text(label + ": ", NamedTextColor.YELLOW)
                .append(Component.text(url, NamedTextColor.AQUA)
                        .clickEvent(ClickEvent.openUrl(url))
                        .hoverEvent(HoverEvent.showText(Component.text(hoverText, NamedTextColor.GRAY))));
    }

    private static Component copyableFilename(String filename, String hoverText) {
        return Component.text(filename, NamedTextColor.AQUA)
                .clickEvent(ClickEvent.copyToClipboard(filename))
                .hoverEvent(HoverEvent.showText(Component.text(hoverText, NamedTextColor.GRAY)));
    }

    private static Component statusBadge(DraftListStatus status) {
        return Component.text('[' + status.label() + ']', statusColor(status))
                .hoverEvent(HoverEvent.showText(Component.text(statusHover(status), NamedTextColor.GRAY)));
    }

    private static Component action(
            String label,
            NamedTextColor color,
            ClickEvent<?> clickEvent,
            String hoverText
    ) {
        return Component.text('[' + label + ']', color)
                .clickEvent(clickEvent)
                .hoverEvent(HoverEvent.showText(Component.text(hoverText, NamedTextColor.GRAY)));
    }

    private static Component pageNavigation(ListPage listPage, Component previous, Component next) {
        return Component.text()
                .append(previous)
                .append(Component.text(
                        "  Page " + listPage.page() + '/' + listPage.pageCount() + "  ",
                        NamedTextColor.GRAY
                ))
                .append(next)
                .build();
    }

    private static Component listPageButton(String label, int page, FileScope scope) {
        String command = scope == FileScope.PUBLISHED
                ? "/bookexport list " + page
                : "/bookexport list " + scope.key() + ' ' + page;
        return Component.text('[' + label + ']', NamedTextColor.AQUA)
                .clickEvent(ClickEvent.runCommand(command))
                .hoverEvent(HoverEvent.showText(Component.text("Open list page " + page, NamedTextColor.GRAY)));
    }

    private static Component historyPageButton(String label, int page) {
        return Component.text('[' + label + ']', NamedTextColor.AQUA)
                .clickEvent(ClickEvent.runCommand("/bookexport admin history " + page))
                .hoverEvent(HoverEvent.showText(Component.text(
                        "Open history page " + page,
                        NamedTextColor.GRAY
                )));
    }

    private static NamedTextColor statusColor(DraftListStatus status) {
        return switch (status) {
            case APPROVED -> NamedTextColor.GREEN;
            case UNREVIEWED -> NamedTextColor.YELLOW;
            case LEGACY_UNTRACKED -> NamedTextColor.AQUA;
            case CHANGES_REQUESTED, PUBLICATION_PENDING -> NamedTextColor.GOLD;
            case CONTENT_CHANGED, ASSOCIATION_MISMATCH, DRAFT_MISSING, CORRUPT -> NamedTextColor.RED;
        };
    }

    private static String statusHover(DraftListStatus status) {
        return switch (status) {
            case UNREVIEWED -> "Managed draft; explicit approval is recommended but not required";
            case APPROVED -> "Current bytes match the explicitly approved SHA-256";
            case CHANGES_REQUESTED -> "Publication is blocked until the current bytes are approved";
            case CONTENT_CHANGED -> "Current bytes changed; review and approve them before publishing";
            case LEGACY_UNTRACKED -> "No managed sidecar; compatible implicit approval is available";
            case PUBLICATION_PENDING -> "This draft already reached a live publication outcome; do not retry";
            case ASSOCIATION_MISMATCH -> "Manifest and staged filename association do not match";
            case DRAFT_MISSING -> "Manifest exists, but its staged draft is missing";
            case CORRUPT -> "Manifest could not be parsed or validated; publication is blocked";
        };
    }

    private static NamedTextColor historyColor(DraftManifest manifest) {
        if (manifest.publicationStatus() == DraftPublicationStatus.PUBLISHED) {
            return NamedTextColor.GREEN;
        }
        if (manifest.publicationStatus() == DraftPublicationStatus.PUBLISHED_ARCHIVE_PENDING) {
            return NamedTextColor.GOLD;
        }
        return manifest.reviewStatus() == DraftReviewStatus.CHANGES_REQUESTED
                ? NamedTextColor.GOLD
                : NamedTextColor.YELLOW;
    }

    private static String displayMetadata(String value) {
        StringBuilder visible = new StringBuilder();
        value.codePoints().limit(96).forEach(codePoint -> {
            int type = Character.getType(codePoint);
            if (codePoint == '\n') {
                visible.append("\\n");
            } else if (codePoint == '\r') {
                visible.append("\\r");
            } else if (codePoint == '\t') {
                visible.append("\\t");
            } else if (Character.isISOControl(codePoint)
                    || type == Character.FORMAT
                    || type == Character.LINE_SEPARATOR
                    || type == Character.PARAGRAPH_SEPARATOR) {
                visible.append("\\u").append(String.format(java.util.Locale.ROOT, "%04x", codePoint));
            } else {
                visible.appendCodePoint(codePoint);
            }
        });
        if (value.codePointCount(0, value.length()) > 96) {
            visible.append('…');
        }
        return visible.toString();
    }

    private static Component prefixed(Component message) {
        return Component.text("[BookExport] ", NamedTextColor.GOLD).append(message);
    }
}
