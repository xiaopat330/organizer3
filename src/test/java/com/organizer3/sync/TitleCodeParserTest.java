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
    void actressNameWithAlphanumCode_fallsBackToFolderName() {
        var result = parser.parse("Aoi Mizuno (MKBD-S91)");
        assertEquals("Aoi Mizuno (MKBD-S91)", result.code());
        assertEquals("Aoi Mizuno (MKBD-S91)", result.baseCode());
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
}
