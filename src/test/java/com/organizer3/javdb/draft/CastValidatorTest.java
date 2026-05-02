package com.organizer3.javdb.draft;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link CastValidator}, covering all 3 cast modes × valid/invalid cases
 * from spec/PROPOSAL_DRAFT_MODE.md §5.1.
 *
 * <p>The first argument to validate() is always the javdb stage_name count (the original
 * number of javdb cast entries), which determines which mode rule applies.
 */
class CastValidatorTest {

    private final CastValidator validator = new CastValidator();

    // ── Mode 0 (sentinel-only): 0 javdb stage_names ──────────────────────────

    @Test
    void mode0_exactlyOneSentinel_isValid() {
        var errors = validator.validate(0, List.of("sentinel:42"));
        assertTrue(errors.isEmpty(), "Exactly 1 sentinel should be valid in mode 0");
    }

    @Test
    void mode0_noResolutions_isInvalid() {
        var errors = validator.validate(0, List.of());
        assertTrue(errors.contains("CAST_MODE_VIOLATION"), "No sentinel in mode 0 is invalid");
    }

    @Test
    void mode0_twoSentinels_isInvalid() {
        var errors = validator.validate(0, List.of("sentinel:1", "sentinel:2"));
        assertTrue(errors.contains("CAST_MODE_VIOLATION"), "Multiple sentinels in mode 0 are invalid");
    }

    @Test
    void mode0_sentinelPlusRealActress_isInvalid() {
        var errors = validator.validate(0, List.of("sentinel:1", "pick"));
        assertTrue(errors.contains("CAST_MODE_VIOLATION"));
    }

    @Test
    void mode0_sentinelPlusSkip_isInvalid() {
        var errors = validator.validate(0, List.of("sentinel:1", "skip"));
        assertTrue(errors.contains("CAST_MODE_VIOLATION"));
    }

    @Test
    void mode0_justRealActress_isInvalid() {
        var errors = validator.validate(0, List.of("pick"));
        assertTrue(errors.contains("CAST_MODE_VIOLATION"), "pick with no sentinel in mode 0 is invalid");
    }

    // ── Mode 1 (strict): 1 javdb stage_name ───────────────────────────────────

    @Test
    void mode1_pick_isValid() {
        var errors = validator.validate(1, List.of("pick"));
        assertTrue(errors.isEmpty(), "single pick is valid in mode 1");
    }

    @Test
    void mode1_createNew_isValid() {
        var errors = validator.validate(1, List.of("create_new"));
        assertTrue(errors.isEmpty(), "single create_new is valid in mode 1");
    }

    @Test
    void mode1_pickAndCreateNew_isValid() {
        // 1 javdb stage_name, but 2 resolution slots? Unusual but should still be valid
        // if ≥1 real actress and no sentinel/skip.
        var errors = validator.validate(1, List.of("pick", "create_new"));
        assertTrue(errors.isEmpty());
    }

    @Test
    void mode1_sentinel_isInvalid() {
        var errors = validator.validate(1, List.of("sentinel:99"));
        assertTrue(errors.contains("CAST_MODE_VIOLATION"), "sentinel in mode 1 is forbidden");
    }

    @Test
    void mode1_skip_isInvalid() {
        var errors = validator.validate(1, List.of("skip"));
        assertTrue(errors.contains("CAST_MODE_VIOLATION"), "skip in mode 1 is forbidden");
    }

    @Test
    void mode1_noRealActress_isInvalid() {
        // No resolutions at all for a mode-1 draft
        var errors = validator.validate(1, List.of());
        assertTrue(errors.contains("CAST_MODE_VIOLATION"), "no real actress in mode 1 is invalid");
    }

    @Test
    void mode1_unresolved_hasUnresolvedError() {
        var errors = validator.validate(1, List.of("unresolved"));
        assertTrue(errors.contains("UNRESOLVED_CAST_SLOT"));
        assertTrue(errors.contains("CAST_MODE_VIOLATION"));
    }

    // ── Mode ≥2 (relaxed) — Path A (real actresses) ──────────────────────────

    @Test
    void mode2_twoPicksPathA_isValid() {
        var errors = validator.validate(2, List.of("pick", "pick"));
        assertTrue(errors.isEmpty());
    }

    @Test
    void mode2_pickAndCreateNewPathA_isValid() {
        var errors = validator.validate(2, List.of("pick", "create_new"));
        assertTrue(errors.isEmpty());
    }

    @Test
    void mode2_pickAndSkipPathA_isValid() {
        var errors = validator.validate(2, List.of("pick", "skip"));
        assertTrue(errors.isEmpty(), "pick + skip is path A (≥1 real, optional skips)");
    }

    @Test
    void mode2_allSkipsPathA_isInvalid() {
        var errors = validator.validate(2, List.of("skip", "skip"));
        assertTrue(errors.contains("CAST_MODE_VIOLATION"), "all skips with no real actress is invalid");
    }

    @Test
    void mode2_threeActressesWithOneSkip_isValid() {
        var errors = validator.validate(3, List.of("pick", "create_new", "skip"));
        assertTrue(errors.isEmpty());
    }

    @Test
    void mode2_noResolutions_isInvalid() {
        var errors = validator.validate(2, List.of());
        assertTrue(errors.contains("CAST_MODE_VIOLATION"), "no resolutions in mode ≥2 is invalid");
    }

    // ── Mode ≥2 (relaxed) — Path B (sentinel only) ───────────────────────────

    @Test
    void mode2_oneSentinelPathB_isValid() {
        // javdbStageNameCount=2, only 1 sentinel resolution slot (user chose "replace cast with sentinel")
        var errors = validator.validate(2, List.of("sentinel:5"));
        assertTrue(errors.isEmpty(), "1 sentinel with 2 javdb stage_names is valid pathB");
    }

    @Test
    void mode2_twoSentinelsPathB_isInvalid() {
        var errors = validator.validate(2, List.of("sentinel:1", "sentinel:2"));
        assertTrue(errors.contains("CAST_MODE_VIOLATION"), "2 sentinels violates pathB (need exactly 1)");
    }

    @Test
    void mode2_sentinelAndPickMixing_isInvalid() {
        var errors = validator.validate(2, List.of("sentinel:1", "pick"));
        assertTrue(errors.contains("CAST_MODE_VIOLATION"), "mixing sentinel and pick is invalid");
    }

    @Test
    void mode2_sentinelAndSkipMixing_isInvalid() {
        // pathB requires: sentinelCount=1, realCount=0, skipCount=0 — no skips allowed in pathB
        var errors = validator.validate(2, List.of("sentinel:1", "skip"));
        assertTrue(errors.contains("CAST_MODE_VIOLATION"), "sentinel + skip = mixing, invalid pathB");
    }

    @Test
    void mode3_sentinelPathB_isValid() {
        var errors = validator.validate(3, List.of("sentinel:7"));
        assertTrue(errors.isEmpty(), "1 sentinel with 3 javdb stage_names is valid pathB");
    }

    // ── Unresolved slot detection (cross-mode) ────────────────────────────────

    @Test
    void unresolvedSlotAlwaysProducesError() {
        var errors = validator.validate(2, List.of("pick", "unresolved"));
        assertTrue(errors.contains("UNRESOLVED_CAST_SLOT"));
    }

    @Test
    void multipleResolutionsAllValid_producesNoErrors() {
        var errors = validator.validate(4, List.of("pick", "create_new", "skip", "skip"));
        assertTrue(errors.isEmpty());
    }
}
