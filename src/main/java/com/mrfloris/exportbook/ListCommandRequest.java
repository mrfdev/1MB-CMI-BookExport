package com.mrfloris.exportbook;

/** Pure parser for both top-level and admin scoped-list command arguments. */
record ListCommandRequest(FileScope scope, int page) {
    static ListCommandRequest parse(String[] args, int firstArgument) {
        int remaining = args.length - firstArgument;
        if (remaining < 0 || remaining > 2) {
            throw new IllegalArgumentException(
                    "Usage: /bookexport list [published|staged|archive|backups] [page]"
            );
        }
        if (remaining == 0) {
            return new ListCommandRequest(FileScope.PUBLISHED, 1);
        }

        String first = args[firstArgument];
        if (isIntegerToken(first)) {
            if (remaining != 1) {
                throw new IllegalArgumentException("A numeric page cannot be followed by another list argument.");
            }
            return new ListCommandRequest(FileScope.PUBLISHED, requirePositivePage(first));
        }

        FileScope parsedScope = FileScope.parse(first).orElseThrow(() -> new IllegalArgumentException(
                "List scope must be published, staged, archive, or backups."
        ));
        int parsedPage = remaining == 2 ? requirePositivePage(args[firstArgument + 1]) : 1;
        return new ListCommandRequest(parsedScope, parsedPage);
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
        throw new IllegalArgumentException("List page must be a positive number.");
    }

    private static boolean isIntegerToken(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        int offset = value.charAt(0) == '+' || value.charAt(0) == '-' ? 1 : 0;
        if (offset == value.length()) {
            return false;
        }
        for (; offset < value.length(); offset++) {
            if (!Character.isDigit(value.charAt(offset))) {
                return false;
            }
        }
        return true;
    }
}
