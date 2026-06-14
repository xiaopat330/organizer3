package com.organizer3.javdb;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JavdbCodeTest {

    @Test
    void collapsesAllPaddingVariantsToOneKey() {
        // 5-pad, 3-pad, and 2-pad of the same integer must normalize to one key.
        assertEquals("SEND-2", JavdbCode.normalizeForMatch("SEND-00002"));
        assertEquals("SEND-2", JavdbCode.normalizeForMatch("SEND-002"));
        assertEquals("SEND-2", JavdbCode.normalizeForMatch("SEND-02"));
        assertEquals("SEND-2", JavdbCode.normalizeForMatch("SEND-2"));
    }

    @Test
    void stripsLeadingZerosOnLargerNumbers() {
        assertEquals("SCOP-515", JavdbCode.normalizeForMatch("SCOP-00515"));
        assertEquals("SCOP-515", JavdbCode.normalizeForMatch("SCOP-515"));
    }

    @Test
    void preservesSeqPrefixLetter() {
        // The optional single-letter seq prefix (Maxing S-box sets) must survive normalization.
        assertEquals("MKBD-S119", JavdbCode.normalizeForMatch("MKBD-S00119"));
        assertEquals("MKBD-S119", JavdbCode.normalizeForMatch("MKBD-S119"));
    }

    @Test
    void stripsVariantSuffix() {
        assertEquals("SONE-38", JavdbCode.normalizeForMatch("SONE-038_4K"));
        assertEquals("ABP-123", JavdbCode.normalizeForMatch("ABP-00123_U"));
    }

    @Test
    void uppercasesLabel() {
        assertEquals("SEND-2", JavdbCode.normalizeForMatch("send-002"));
        assertEquals("MKBD-S119", JavdbCode.normalizeForMatch("mkbd-s00119"));
    }

    @Test
    void allZerosCollapsesToSingleZero() {
        assertEquals("ABC-0", JavdbCode.normalizeForMatch("ABC-000"));
        assertEquals("ABC-0", JavdbCode.normalizeForMatch("ABC-0"));
    }

    @Test
    void unparseableInputReturnedTrimmedUnchanged() {
        assertEquals("not a code", JavdbCode.normalizeForMatch("  not a code  "));
        assertEquals("FC2-PPV", JavdbCode.normalizeForMatch("FC2-PPV"));
    }

    @Test
    void nullReturnsNull() {
        assertNull(JavdbCode.normalizeForMatch(null));
    }

    @Test
    void distinctIntegersNeverCollide() {
        assertNotEquals(
                JavdbCode.normalizeForMatch("SEND-002"),
                JavdbCode.normalizeForMatch("SEND-003"));
    }
}
