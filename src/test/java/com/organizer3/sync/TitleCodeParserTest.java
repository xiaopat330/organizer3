package com.organizer3.sync;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TitleCodeParserTest {

    private TitleCodeParser parser;

    @BeforeEach
    void setUp() {
        parser = new TitleCodeParser();
    }

    @Test
    void standardCode_parsesLabelAndNumber() {
        var result = parser.parse("ABP-123");
        assertEquals("ABP-123", result.code());
        assertEquals("ABP-00123", result.baseCode());
        assertEquals("ABP", result.label());
        assertEquals(123, result.seqNum());
    }

    @Test
    void code_withLeadingZeros_stripsZerosInCode_padsBaseCode() {
        var result = parser.parse("PRED-0045");
        assertEquals("PRED-0045", result.code());
        assertEquals("PRED-00045", result.baseCode());
        assertEquals("PRED", result.label());
        assertEquals(45, result.seqNum());
    }

    @Test
    void baseCode_alwaysFiveDigits() {
        var result = parser.parse("SSIS-1000");
        assertEquals("SSIS-01000", result.baseCode());
        assertEquals(1000, result.seqNum());
    }

    @Test
    void labelNormalized_toUpperCase() {
        var result = parser.parse("abp-123");
        assertEquals("ABP-123", result.code());
        assertEquals("ABP-00123", result.baseCode());
        assertEquals("ABP", result.label());
        assertEquals(123, result.seqNum());
    }

    @Test
    void suffixVariant_preserved_inCode_not_inBaseCode() {
        var result = parser.parse("ABP-123_U");
        assertEquals("ABP-123_U", result.code());
        assertEquals("ABP-00123", result.baseCode());
        assertEquals("ABP", result.label());
        assertEquals(123, result.seqNum());
    }

    @Test
    void fourKSuffix_preserved() {
        var result = parser.parse("PRED-456_4K");
        assertEquals("PRED-456_4K", result.code());
        assertEquals("PRED-00456", result.baseCode());
        assertEquals("PRED", result.label());
        assertEquals(456, result.seqNum());
    }

    @Test
    void alphanumericLabel_parsed() {
        var result = parser.parse("FC2PPV-123456");
        assertEquals("FC2PPV-123456", result.code());
        assertEquals("FC2PPV-123456", result.baseCode());
        assertEquals("FC2PPV", result.label());
        assertEquals(123456, result.seqNum());
    }

    @Test
    void noRecognizableCode_fallsBackToFolderName() {
        var result = parser.parse("some-random-folder");
        assertEquals("some-random-folder", result.code());
        assertEquals("some-random-folder", result.baseCode());
        assertNull(result.label());
        assertNull(result.seqNum());
    }

    @Test
    void seqPrefix_maxingBoxSet_parsed() {
        var result = parser.parse("Yua Ariga (MKBD-S119)");
        assertEquals("MKBD-S119", result.code());
        assertEquals("MKBD-S00119", result.baseCode());
        assertEquals("MKBD", result.label());
        assertEquals(119, result.seqNum());
    }

    @Test
    void seqPrefix_dlmkd_parsed() {
        var result = parser.parse("Ayumi Shinoda (DLMKD-S147)");
        assertEquals("DLMKD-S147", result.code());
        assertEquals("DLMKD-S00147", result.baseCode());
        assertEquals("DLMKD", result.label());
        assertEquals(147, result.seqNum());
    }

    @Test
    void seqPrefix_tokyoHotCpz69_parsed() {
        var result = parser.parse("Mea Amami (CPZ69-H005)");
        assertEquals("CPZ69-H005", result.code());
        assertEquals("CPZ69-H00005", result.baseCode());
        assertEquals("CPZ69", result.label());
        assertEquals(5, result.seqNum());
    }

    @Test
    void seqPrefix_withSuffix_preserved() {
        var result = parser.parse("Foo (MKBD-S119_4K)");
        assertEquals("MKBD-S119_4K", result.code());
        assertEquals("MKBD-S00119", result.baseCode());
        assertEquals("MKBD", result.label());
        assertEquals(119, result.seqNum());
    }

    @Test
    void noDash_fallsBackToFolderName() {
        var result = parser.parse("(GVG797)");
        assertEquals("(GVG797)", result.code());
        assertEquals("(GVG797)", result.baseCode());
        assertNull(result.label());
        assertNull(result.seqNum());
    }

    @Test
    void caribDateCode_fallsBackToFolderName() {
        var result = parser.parse("(011020-001-CARIB)");
        assertEquals("(011020-001-CARIB)", result.code());
        assertEquals("(011020-001-CARIB)", result.baseCode());
        assertNull(result.label());
        assertNull(result.seqNum());
    }

    @Test
    void codeEmbeddedInJunk_extracted() {
        var result = parser.parse("hhd800.com@PRED-456");
        assertEquals("PRED-00456", result.baseCode());
        assertEquals("PRED", result.label());
        assertEquals(456, result.seqNum());
    }

    // Regression tests for FC2PPV 8-digit code truncation bug.
    // Before the fix, digit limit of {2,6} caused FC2PPV-1040032 and FC2PPV-1040038
    // to both produce base code FC2PPV-104003, colliding on the same 7-digit prefix.

    @Test
    void fc2ppv_8digitCode_notTruncated_first() {
        // FC2-PPV-1040032 → after replacelist rewrite → FC2PPV-1040032
        var result = parser.parse("FC2PPV-1040032");
        assertEquals("FC2PPV-1040032", result.code());
        assertEquals("FC2PPV-1040032", result.baseCode());
        assertEquals("FC2PPV", result.label());
        assertEquals(1040032, result.seqNum());
    }

    @Test
    void fc2ppv_8digitCode_notTruncated_second() {
        // FC2-PPV-1040038 → after replacelist rewrite → FC2PPV-1040038
        var result = parser.parse("FC2PPV-1040038");
        assertEquals("FC2PPV-1040038", result.code());
        assertEquals("FC2PPV-1040038", result.baseCode());
        assertEquals("FC2PPV", result.label());
        assertEquals(1040038, result.seqNum());
    }

    @Test
    void fc2ppv_8digitCodes_doNotCollide() {
        // Both codes must produce distinct base codes — the regression that triggered this fix.
        var result1 = parser.parse("FC2PPV-1040032");
        var result2 = parser.parse("FC2PPV-1040038");
        assertNotEquals(result1.baseCode(), result2.baseCode(),
                "FC2PPV-1040032 and FC2PPV-1040038 must not share the same base code");
    }

    @Test
    void fc2ppv_6digitCode_stillParses() {
        var result = parser.parse("FC2PPV-123456");
        assertEquals("FC2PPV-123456", result.code());
        assertEquals("FC2PPV-123456", result.baseCode());
        assertEquals("FC2PPV", result.label());
        assertEquals(123456, result.seqNum());
    }

    @Test
    void fc2ppv_5digitCode_stillParses() {
        var result = parser.parse("FC2PPV-12345");
        assertEquals("FC2PPV-12345", result.code());
        assertEquals("FC2PPV-12345", result.baseCode());
        assertEquals("FC2PPV", result.label());
        assertEquals(12345, result.seqNum());
    }

    @Test
    void fc2ppv_9digitCode_parsesFullTail() {
        var result = parser.parse("FC2PPV-123456789");
        assertEquals("FC2PPV-123456789", result.code());
        assertEquals("FC2PPV-123456789", result.baseCode());
        assertEquals("FC2PPV", result.label());
        assertEquals(123456789, result.seqNum());
    }
}
