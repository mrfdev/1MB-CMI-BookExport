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

    int characterCount() {
        return pages.stream().mapToInt(String::length).sum();
    }
}
