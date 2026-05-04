package com.organizer3.translation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link SanitizationDetector} — pure function, no mocks or DB needed.
 *
 * <p>Tests cover the four cells of the (input-explicit × output-explicit) matrix,
 * plus edge cases for null/empty values.
 */
class SanitizationDetectorTest {

    // -------------------------------------------------------------------------
    // The four matrix cells
    // -------------------------------------------------------------------------

    /** Input HAS explicit JP token; output HAS explicit EN token → NOT sanitized (clean translation). */
    @Test
    void inputExplicit_outputExplicit_notSanitized() {
        // 中出し (creampie) is in the JP set; "creampie" is in the EN set
        assertFalse(SanitizationDetector.isSanitized(
                "中出し花野真衣",
                "Hanano Mai Creampie Series"
        ));
    }

    /** Input HAS explicit JP token; output has NO explicit EN token → sanitized (silent rewrite). */
    @Test
    void inputExplicit_outputNotExplicit_sanitized() {
        // 中出し in input, but output is a clean studio-style label with no explicit EN token
        assertTrue(SanitizationDetector.isSanitized(
                "中出し花野真衣",
                "Hanano Mai Special Feature"
        ));
    }

    /** Input has NO explicit JP token; output has explicit EN token → NOT sanitized. */
    @Test
    void inputNotExplicit_outputExplicit_notSanitized() {
        // "creampie" in output but no JP trigger in input → not sanitization
        assertFalse(SanitizationDetector.isSanitized(
                "花野真衣シリーズ",
                "Hanano Mai Creampie Series"
        ));
    }

    /** Input has NO explicit JP token; output has NO explicit EN token → NOT sanitized. */
    @Test
    void inputNotExplicit_outputNotExplicit_notSanitized() {
        assertFalse(SanitizationDetector.isSanitized(
                "花野真衣シリーズ",
                "Hanano Mai Series"
        ));
    }

    // -------------------------------------------------------------------------
    // Additional explicit JP token coverage
    // -------------------------------------------------------------------------

    @Test
    void rinkan_gangbang_notSanitized() {
        // 輪姦 → gangbang
        assertFalse(SanitizationDetector.isSanitized("輪姦パーティー", "Gangbang Party"));
    }

    @Test
    void chikan_molest_notSanitized() {
        // 痴漢 → molest
        assertFalse(SanitizationDetector.isSanitized("痴漢電車", "Molester Train"));
    }

    @Test
    void chikan_noExplicitEN_sanitized() {
        assertTrue(SanitizationDetector.isSanitized("痴漢電車", "Train Story"));
    }

    @Test
    void lewd_variant_notSanitized() {
        // 淫 → lewd
        assertFalse(SanitizationDetector.isSanitized("淫乱妻", "Lewd Wife"));
    }

    @Test
    void breed_variant_notSanitized() {
        // 種付け → breed
        assertFalse(SanitizationDetector.isSanitized("種付けプレス", "Breeding Press"));
    }

    // -------------------------------------------------------------------------
    // Edge cases: null / empty
    // -------------------------------------------------------------------------

    @Test
    void nullInput_notSanitized() {
        assertFalse(SanitizationDetector.isSanitized(null, "some output"));
    }

    @Test
    void emptyInput_notSanitized() {
        assertFalse(SanitizationDetector.isSanitized("", "some output"));
    }

    @Test
    void nullOutput_notSanitized() {
        // Null output is a hard refusal — different code path; not sanitization
        assertFalse(SanitizationDetector.isSanitized("中出し花野真衣", null));
    }

    @Test
    void emptyOutput_notSanitized() {
        // Empty output is a hard refusal — different code path; not sanitization
        assertFalse(SanitizationDetector.isSanitized("中出し花野真衣", ""));
    }

    @Test
    void blankOutput_notSanitized() {
        // Whitespace-only output is effectively empty
        assertFalse(SanitizationDetector.isSanitized("中出し花野真衣", "   "));
    }

    // -------------------------------------------------------------------------
    // Case-insensitivity of EN set
    // -------------------------------------------------------------------------

    @Test
    void explicitEN_matchesCaseInsensitive() {
        // "CREAMPIE" should match even though EN set pattern uses CASE_INSENSITIVE
        assertFalse(SanitizationDetector.isSanitized("中出し", "CREAMPIE Special"));
    }

    // -------------------------------------------------------------------------
    // Boundary: very short output (not empty) with explicit JP
    // -------------------------------------------------------------------------

    @Test
    void veryShortNonExplicitOutput_withExplicitInput_sanitized() {
        // Single word that is a legit translation of a non-explicit part — but no EN token
        assertTrue(SanitizationDetector.isSanitized("中出し花野真衣", "Mai"));
    }
}
