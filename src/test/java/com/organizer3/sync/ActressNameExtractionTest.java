package com.organizer3.sync;

import org.junit.jupiter.api.Test;

import static com.organizer3.sync.scanner.ScannerSupport.extractActressName;
import static org.junit.jupiter.api.Assertions.*;

class ActressNameExtractionTest {

    @Test
    void simpleNameAndCode() {
        assertEquals("Marin Yakuno", extractActressName("Marin Yakuno (IPZZ-679)"));
    }

    @Test
    void nameWithSuffix() {
        assertEquals("Maho Uruya", extractActressName("Maho Uruya - Demosaiced (DV-1239)"));
    }

    @Test
    void multiActressTakesFirst() {
        assertEquals("Maho Kitagawa",
                extractActressName("Maho Kitagawa, Ran Himeno, Konomi Hirose (MUCD-304)"));
    }

    @Test
    void singleWordName() {
        assertEquals("Machiap", extractActressName("Machiap (YMDD-439)"));
    }

    @Test
    void noParenthesisReturnsNull() {
        assertNull(extractActressName("IPZZ-679"));
    }

    @Test
    void emptyPrefixReturnsNull() {
        assertNull(extractActressName("(IPZZ-679)"));
    }
}
