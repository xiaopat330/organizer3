package com.organizer3.translation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link TranslationNormalization} — pure function, no DB needed.
 */
class TranslationNormalizationTest {

    @Test
    void normalize_trimsLeadingAndTrailingWhitespace() {
        assertEquals("テスト", TranslationNormalization.normalize("  テスト  "));
    }

    @Test
    void normalize_collapsesInternalWhitespace() {
        assertEquals("テスト text", TranslationNormalization.normalize("テスト  text"));
    }

    @Test
    void normalize_nfkcHalfWidthKatakanaToFullWidth() {
        // ｱｲｳ (half-width katakana) → アイウ (full-width)
        assertEquals("アイウ", TranslationNormalization.normalize("ｱｲｳ"));
    }

    @Test
    void normalize_nfkcFullWidthDigitToAscii() {
        // ４２ (full-width) → 42 (ASCII)
        assertEquals("42", TranslationNormalization.normalize("４２"));
    }

    @Test
    void normalize_doesNotLowercase() {
        // Case should be preserved — NFKC does not change case
        assertEquals("ABC test", TranslationNormalization.normalize("ABC test"));
    }

    @Test
    void normalize_nfkcConvertsFullwidthPunctuation() {
        // NFKC normalizes full-width ！ → ASCII ! — this is intentional per the spec.
        // The spec's "do not strip punctuation" means punctuation is not deleted, but NFKC
        // canonical equivalents (full-width ↔ half-width) are applied for consistency.
        assertEquals("ABC!", TranslationNormalization.normalize("ABC！"));
    }

    @Test
    void normalize_emptyInputReturnsEmpty() {
        assertEquals("", TranslationNormalization.normalize(""));
    }

    @Test
    void normalize_nullInputReturnsEmpty() {
        assertEquals("", TranslationNormalization.normalize(null));
    }

    @Test
    void hashOf_sameNormalisedInputProducesIdenticalHash() {
        // Trailing space variant should produce same hash as trimmed
        String h1 = TranslationNormalization.hashOf("テスト");
        String h2 = TranslationNormalization.hashOf("テスト ");
        assertEquals(h1, h2);
    }

    @Test
    void hashOf_halfAndFullWidthProduceSameHash() {
        String h1 = TranslationNormalization.hashOf("ｱｲｳ");
        String h2 = TranslationNormalization.hashOf("アイウ");
        assertEquals(h1, h2);
    }

    @Test
    void hashOf_differentInputsProduceDifferentHashes() {
        String h1 = TranslationNormalization.hashOf("テスト1");
        String h2 = TranslationNormalization.hashOf("テスト2");
        assertNotEquals(h1, h2);
    }

    @Test
    void hashOf_is64HexChars() {
        String hash = TranslationNormalization.hashOf("テスト");
        assertEquals(64, hash.length());
        assertTrue(hash.matches("[0-9a-f]+"), "Hash should be lowercase hex");
    }
}
