package com.organizer3.sync;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ActressNameExtractionTest {

    @Test
    void simpleNameAndCode() {
        assertEquals("Marin Yakuno", AbstractSyncOperation.extractActressName("Marin Yakuno (IPZZ-679)"));
    }

    @Test
    void nameWithSuffix() {
        assertEquals("Maho Uruya", AbstractSyncOperation.extractActressName("Maho Uruya - Demosaiced (DV-1239)"));
    }

    @Test
    void multiActressTakesFirst() {
        assertEquals("Maho Kitagawa",
                AbstractSyncOperation.extractActressName("Maho Kitagawa, Ran Himeno, Konomi Hirose (MUCD-304)"));
    }

    @Test
    void singleWordName() {
        assertEquals("Machiap", AbstractSyncOperation.extractActressName("Machiap (YMDD-439)"));
    }

    @Test
    void noParenthesisReturnsNull() {
        assertNull(AbstractSyncOperation.extractActressName("IPZZ-679"));
    }

    @Test
    void emptyPrefixReturnsNull() {
        assertNull(AbstractSyncOperation.extractActressName("(IPZZ-679)"));
    }
}
