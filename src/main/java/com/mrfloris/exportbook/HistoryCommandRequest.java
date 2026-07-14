package com.mrfloris.exportbook;

import java.util.UUID;

/** Pure parser for paged manifest history and stable-ID detail requests. */
record HistoryCommandRequest(Action action, int page, UUID manifestId) {
    enum Action {
        LIST,
        SHOW
    }

    HistoryCommandRequest {
        if (action == null) {
            throw new IllegalArgumentException("History action is required.");
        }
        if (action == Action.LIST && (page < 1 || manifestId != null)) {
            throw new IllegalArgumentException("A history list request requires a positive page only.");
        }
        if (action == Action.SHOW && (page != 1 || manifestId == null)) {
            throw new IllegalArgumentException("A history detail request requires one manifest ID.");
        }
    }

    static HistoryCommandRequest parse(String[] args, int firstArgument) {
        if (firstArgument < 0 || firstArgument > args.length) {
            throw new IllegalArgumentException(historyUsage());
        }

        int remaining = args.length - firstArgument;
        if (remaining == 0) {
            return list(1);
        }
        if (remaining == 1) {
            return list(requirePositivePage(args[firstArgument]));
        }
        if (remaining == 2 && args[firstArgument].equalsIgnoreCase("show")) {
            return show(requireCanonicalUuid(args[firstArgument + 1]));
        }
        throw new IllegalArgumentException(historyUsage());
    }

    boolean isShow() {
        return action == Action.SHOW;
    }

    private static HistoryCommandRequest list(int page) {
        return new HistoryCommandRequest(Action.LIST, page, null);
    }

    private static HistoryCommandRequest show(UUID manifestId) {
        return new HistoryCommandRequest(Action.SHOW, 1, manifestId);
    }

    private static int requirePositivePage(String value) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed > 0) {
                return parsed;
            }
        } catch (NumberFormatException ignored) {
            // The controlled validation message below also covers overflow.
        }
        throw new IllegalArgumentException("History page must be a positive number.");
    }

    private static UUID requireCanonicalUuid(String value) {
        try {
            UUID parsed = UUID.fromString(value);
            if (parsed.toString().equalsIgnoreCase(value)) {
                return parsed;
            }
        } catch (IllegalArgumentException ignored) {
            // The controlled validation message below covers malformed UUIDs.
        }
        throw new IllegalArgumentException("Manifest ID must be a complete UUID.");
    }

    private static String historyUsage() {
        return "Usage: /bookexport admin history [page] or /bookexport admin history show <manifest-id>";
    }
}
