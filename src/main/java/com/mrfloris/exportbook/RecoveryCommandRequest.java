package com.mrfloris.exportbook;

import java.util.Objects;
import java.util.UUID;

/** Pure parser for read-only publication recovery list and detail requests. */
record RecoveryCommandRequest(Action action, int page, UUID transactionId) {
    enum Action {
        LIST,
        SHOW
    }

    RecoveryCommandRequest {
        Objects.requireNonNull(action, "action");
        if (action == Action.LIST && (page < 1 || transactionId != null)) {
            throw new IllegalArgumentException("A recovery list request requires a positive page only.");
        }
        if (action == Action.SHOW && (page != 1 || transactionId == null)) {
            throw new IllegalArgumentException("A recovery detail request requires one transaction ID.");
        }
    }

    static RecoveryCommandRequest parse(String[] args, int firstArgument) {
        Objects.requireNonNull(args, "args");
        if (firstArgument < 0 || firstArgument > args.length) {
            throw new IllegalArgumentException(recoveryUsage());
        }

        int remaining = args.length - firstArgument;
        if (remaining == 0) {
            return list(1);
        }

        String action = args[firstArgument];
        if (remaining == 1 && action.equalsIgnoreCase("list")) {
            return list(1);
        }
        if (remaining == 2 && action.equalsIgnoreCase("list")) {
            return list(requirePositivePage(args[firstArgument + 1]));
        }
        if (remaining == 2 && action.equalsIgnoreCase("show")) {
            return show(requireCanonicalUuid(args[firstArgument + 1]));
        }
        throw new IllegalArgumentException(recoveryUsage());
    }

    boolean isShow() {
        return action == Action.SHOW;
    }

    private static RecoveryCommandRequest list(int page) {
        return new RecoveryCommandRequest(Action.LIST, page, null);
    }

    private static RecoveryCommandRequest show(UUID transactionId) {
        return new RecoveryCommandRequest(Action.SHOW, 1, transactionId);
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
        throw new IllegalArgumentException("Recovery page must be a positive number.");
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
        throw new IllegalArgumentException("Transaction ID must be a complete UUID.");
    }

    private static String recoveryUsage() {
        return "Usage: /bookexport admin recovery [list [page]|show <transaction-id>]";
    }
}
