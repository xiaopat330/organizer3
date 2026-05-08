package com.organizer3.repository;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link StageNameNormalizer}.
 */
class StageNameNormalizerTest {

    @Test
    void nullInputReturnsNull() {
        assertNull(StageNameNormalizer.normalize(null));
    }

    @Test
    void emptyStringReturnsNull() {
        assertNull(StageNameNormalizer.normalize(""));
    }

    @Test
    void whitespaceOnlyReturnsNull() {
        assertNull(StageNameNormalizer.normalize("   "));
        assertNull(StageNameNormalizer.normalize("\t\n"));
        // ideographic-space only
        assertNull(StageNameNormalizer.normalize("　"));
    }

    @Test
    void leadingAndTrailingSpacesAreTrimmed() {
        assertEquals("椎名そら", StageNameNormalizer.normalize("  椎名そら  "));
    }

    @Test
    void internalAsciiSpaceIsRemoved() {
        // The whitespace-drift case: "椎名 そら" stored, javdb uses "椎名そら"
        assertEquals("椎名そら", StageNameNormalizer.normalize("椎名 そら"));
    }

    @Test
    void internalIdeographicSpaceIsRemoved() {
        // U+3000 ideographic space
        assertEquals("椎名そら", StageNameNormalizer.normalize("椎名　そら"));
    }

    @Test
    void nfcNormalizationCollapsesCombiningCharacters() {
        // ガ can be represented as か (U+304B) + combining dakuten (U+3099).
        // After NFC it should collapse to ガ (U+30AC).
        String decomposed = "が"; // か + combining dakuten → が (hiragana)
        String expected = "が";         // が precomposed
        assertEquals(expected, StageNameNormalizer.normalize(decomposed));
    }

    @Test
    void alreadyNormalValueIsUnchanged() {
        assertEquals("椎名そら", StageNameNormalizer.normalize("椎名そら"));
        assertEquals("三上悠亜", StageNameNormalizer.normalize("三上悠亜"));
    }

    @Test
    void mixedLeadingTrailingAndInternalSpaces() {
        // leading + internal ASCII + trailing
        assertEquals("椎名そら", StageNameNormalizer.normalize("  椎名 そら  "));
    }
}
