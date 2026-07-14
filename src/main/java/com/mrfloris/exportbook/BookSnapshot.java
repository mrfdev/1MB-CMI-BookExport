package com.mrfloris.exportbook;

import java.util.List;

/** Immutable content copied from the held Bukkit item before file I/O starts. */
record BookSnapshot(
        boolean signed,
        String plainTitle,
        String styledTitle,
        String plainAuthor,
        String styledAuthor,
        List<String> pages
) {
    BookSnapshot {
        pages = List.copyOf(pages);
    }

    int pageCount() {
        return pages.size();
    }

    int utf16Units() {
        return pages.stream().mapToInt(String::length).sum();
    }

    /**
     * Returns content-free diagnostics. Record-generated output would otherwise
     * include every raw page plus the title and author component serializations.
     */
    @Override
    public String toString() {
        return "BookSnapshot[signed=" + signed
                + ", hasTitle=" + !plainTitle.isBlank()
                + ", hasAuthor=" + !plainAuthor.isBlank()
                + ", pages=" + pageCount()
                + ", utf16Units=" + utf16Units() + ']';
    }
}
