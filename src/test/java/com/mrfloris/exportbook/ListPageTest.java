package com.mrfloris.exportbook;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ListPageTest {
    @Test
    void calculatesEmptyAndSinglePageBounds() {
        assertPage(ListPage.calculate(0, 1, 10), 1, 1, 0, 0, false, false);
        assertPage(ListPage.calculate(1, 1, 10), 1, 1, 0, 1, false, false);
        assertPage(ListPage.calculate(10, 1, 10), 1, 1, 0, 10, false, false);
    }

    @Test
    void calculatesFirstMiddleAndLastPageBounds() {
        assertPage(ListPage.calculate(21, 1, 10), 1, 3, 0, 10, false, true);
        assertPage(ListPage.calculate(21, 2, 10), 2, 3, 10, 20, true, true);
        assertPage(ListPage.calculate(21, 3, 10), 3, 3, 20, 21, true, false);
    }

    @Test
    void exactMultiplesDoNotCreateAnEmptyPage() {
        assertPage(ListPage.calculate(20, 2, 10), 2, 2, 10, 20, true, false);
    }

    @Test
    void clampsRequestsPastTheLastPage() {
        assertPage(ListPage.calculate(11, 99, 10), 2, 2, 10, 11, true, false);
    }

    @Test
    void supportsSingleItemPages() {
        assertPage(ListPage.calculate(3, 2, 1), 2, 3, 1, 2, true, true);
    }

    @Test
    void remainsOverflowSafeForMaximumItemCount() {
        ListPage page = ListPage.calculate(Integer.MAX_VALUE, Integer.MAX_VALUE, 50);

        assertEquals(42_949_673, page.pageCount());
        assertEquals(page.pageCount(), page.page());
        assertEquals(2_147_483_600, page.fromIndex());
        assertEquals(Integer.MAX_VALUE, page.toIndex());
    }

    @Test
    void exposesAdjacentPagesOnlyWhenTheyExist() {
        ListPage middle = ListPage.calculate(30, 2, 10);

        assertEquals(1, middle.previousPage());
        assertEquals(3, middle.nextPage());
        assertThrows(IllegalStateException.class, () -> ListPage.calculate(30, 1, 10).previousPage());
        assertThrows(IllegalStateException.class, () -> ListPage.calculate(30, 3, 10).nextPage());
    }

    @Test
    void rejectsInvalidInputs() {
        assertThrows(IllegalArgumentException.class, () -> ListPage.calculate(-1, 1, 10));
        assertThrows(IllegalArgumentException.class, () -> ListPage.calculate(1, 0, 10));
        assertThrows(IllegalArgumentException.class, () -> ListPage.calculate(1, 1, 0));
    }

    private static void assertPage(
            ListPage page,
            int expectedPage,
            int expectedPageCount,
            int expectedFrom,
            int expectedTo,
            boolean expectedPrevious,
            boolean expectedNext
    ) {
        assertEquals(expectedPage, page.page());
        assertEquals(expectedPageCount, page.pageCount());
        assertEquals(expectedFrom, page.fromIndex());
        assertEquals(expectedTo, page.toIndex());
        assertEquals(expectedPrevious, page.hasPrevious());
        assertEquals(expectedNext, page.hasNext());
        if (expectedPrevious) {
            assertTrue(page.previousPage() >= 1);
        } else {
            assertFalse(page.hasPrevious());
        }
        if (expectedNext) {
            assertTrue(page.nextPage() <= page.pageCount());
        } else {
            assertFalse(page.hasNext());
        }
    }
}
