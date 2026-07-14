package com.mrfloris.exportbook;

/** Overflow-safe page bounds for the exported-file list. */
record ListPage(
        int page,
        int pageCount,
        int fromIndex,
        int toIndex
) {
    static ListPage calculate(int itemCount, int requestedPage, int pageSize) {
        if (itemCount < 0) {
            throw new IllegalArgumentException("Item count cannot be negative.");
        }
        if (requestedPage < 1) {
            throw new IllegalArgumentException("Requested page must be positive.");
        }
        if (pageSize < 1) {
            throw new IllegalArgumentException("Page size must be positive.");
        }

        int pageCount = itemCount == 0 ? 1 : 1 + (itemCount - 1) / pageSize;
        int page = Math.min(requestedPage, pageCount);
        long fromIndex = (long) (page - 1) * pageSize;
        long toIndex = Math.min((long) itemCount, fromIndex + pageSize);
        return new ListPage(page, pageCount, (int) fromIndex, (int) toIndex);
    }

    boolean hasPrevious() {
        return page > 1;
    }

    boolean hasNext() {
        return page < pageCount;
    }

    int previousPage() {
        if (!hasPrevious()) {
            throw new IllegalStateException("The first page has no previous page.");
        }
        return page - 1;
    }

    int nextPage() {
        if (!hasNext()) {
            throw new IllegalStateException("The last page has no next page.");
        }
        return page + 1;
    }
}
