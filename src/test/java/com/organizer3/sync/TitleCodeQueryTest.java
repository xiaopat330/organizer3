package com.organizer3.sync;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TitleCodeQueryTest {

    @Test
    void parsesNullAndBlankAsEmpty() {
        assertTrue(TitleCodeQuery.parse(null).isEmpty());
        assertTrue(TitleCodeQuery.parse("").isEmpty());
        assertTrue(TitleCodeQuery.parse("   ").isEmpty());
    }

    @Test
    void parsesLabelOnlyInputs() {
        TitleCodeQuery.ParsedQuery q = TitleCodeQuery.parse("SNIS");
        assertEquals("SNIS", q.labelPrefix());
        assertEquals("", q.seqPrefix());

        q = TitleCodeQuery.parse("SN");
        assertEquals("SN", q.labelPrefix());
        assertEquals("", q.seqPrefix());
    }

    @Test
    void parsesTrailingDashAsLabelOnly() {
        TitleCodeQuery.ParsedQuery q = TitleCodeQuery.parse("SNIS-");
        assertEquals("SNIS", q.labelPrefix());
        assertEquals("", q.seqPrefix());
    }

    @Test
    void normalizesLeadingZerosInSeqNum() {
        assertEquals("1", TitleCodeQuery.parse("SNIS-1").seqPrefix());
        assertEquals("1", TitleCodeQuery.parse("SNIS-01").seqPrefix());
        assertEquals("1", TitleCodeQuery.parse("SNIS-001").seqPrefix());
        assertEquals("1", TitleCodeQuery.parse("SNIS-00001").seqPrefix());
    }

    @Test
    void dashlessFormsAreEquivalentToDashedForms() {
        assertEquals(TitleCodeQuery.parse("SNIS-1"),   TitleCodeQuery.parse("SNIS1"));
        assertEquals(TitleCodeQuery.parse("SNIS-1"),   TitleCodeQuery.parse("SNIS01"));
        assertEquals(TitleCodeQuery.parse("SNIS-1"),   TitleCodeQuery.parse("SNIS001"));
        assertEquals(TitleCodeQuery.parse("SNIS-100"), TitleCodeQuery.parse("SNIS100"));
    }

    @Test
    void labelIsUppercased() {
        TitleCodeQuery.ParsedQuery q = TitleCodeQuery.parse("snis-1");
        assertEquals("SNIS", q.labelPrefix());
        assertEquals("1", q.seqPrefix());
    }

    @Test
    void whitespaceIsStripped() {
        TitleCodeQuery.ParsedQuery q = TitleCodeQuery.parse("  SNIS - 1  ");
        assertEquals("SNIS", q.labelPrefix());
        assertEquals("1", q.seqPrefix());
    }

    @Test
    void preservesNonLeadingZeros() {
        assertEquals("10",  TitleCodeQuery.parse("SNIS-10").seqPrefix());
        assertEquals("10",  TitleCodeQuery.parse("SNIS-010").seqPrefix());
        assertEquals("100", TitleCodeQuery.parse("SNIS-100").seqPrefix());
        assertEquals("100", TitleCodeQuery.parse("SNIS-0100").seqPrefix());
    }

    @Test
    void labelsWithDigitsRequireDashToDisambiguate() {
        // Without dash, "FC2PPV" doesn't match letters-then-digits → fallback label prefix
        TitleCodeQuery.ParsedQuery q = TitleCodeQuery.parse("FC2PPV");
        assertEquals("FC2PPV", q.labelPrefix());
        assertEquals("", q.seqPrefix());

        // With dash, split is unambiguous
        q = TitleCodeQuery.parse("FC2PPV-01");
        assertEquals("FC2PPV", q.labelPrefix());
        assertEquals("1", q.seqPrefix());
    }

    @Test
    void partialSeqIsKept() {
        // User typing "SNIS-10" is a prefix — matches SNIS-10, SNIS-100, SNIS-109...
        assertEquals("10", TitleCodeQuery.parse("SNIS-10").seqPrefix());
    }

    @Test
    void allZerosInSeqCollapsesToEmpty() {
        // "SNIS-0" and "SNIS-0000" mean "any seq" (since stripping zeros gives nothing)
        assertEquals("", TitleCodeQuery.parse("SNIS-0").seqPrefix());
        assertEquals("", TitleCodeQuery.parse("SNIS-0000").seqPrefix());
    }
}
