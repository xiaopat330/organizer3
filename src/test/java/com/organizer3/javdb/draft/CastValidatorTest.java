package com.organizer3.javdb.draft;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link CastValidator} — count-independent rule.
 *
 * <p>Path A: ≥1 real actress (pick / create_new), any skips, no sentinel.
 * Path B: exactly 1 sentinel, no real actresses, no skips.
 * Empty list → CAST_MODE_VIOLATION.
 * Unresolved slots always produce UNRESOLVED_CAST_SLOT.
 */
class CastValidatorTest {

    private final CastValidator validator = new CastValidator();

    // ── Path A — real actresses ───────────────────────────────────────────────

    @Test
    void pathA_singlePick_isValid() {
        var errors = validator.validate(List.of("pick"));
        assertTrue(errors.isEmpty(), "single pick should be valid (path A)");
    }

    @Test
    void pathA_pickAndCreateNew_isValid() {
        var errors = validator.validate(List.of("pick", "create_new"));
        assertTrue(errors.isEmpty(), "pick + create_new should be valid (path A)");
    }

    @Test
    void pathA_pickAndSkip_isValid() {
        var errors = validator.validate(List.of("pick", "skip"));
        assertTrue(errors.isEmpty(), "pick + skip should be valid (path A — skips allowed alongside real)");
    }

    // ── Path B — sentinel only ────────────────────────────────────────────────

    @Test
    void pathB_singleSentinel_isValid() {
        var errors = validator.validate(List.of("sentinel:42"));
        assertTrue(errors.isEmpty(), "exactly 1 sentinel with no others should be valid (path B)");
    }

    // ── CAST_MODE_VIOLATION cases ─────────────────────────────────────────────

    @Test
    void empty_isCastModeViolation() {
        var errors = validator.validate(List.of());
        assertTrue(errors.contains("CAST_MODE_VIOLATION"),
                "empty list must yield CAST_MODE_VIOLATION (no real actress and no sentinel)");
    }

    @Test
    void sentinelPlusPick_isCastModeViolation() {
        var errors = validator.validate(List.of("sentinel:1", "pick"));
        assertTrue(errors.contains("CAST_MODE_VIOLATION"), "mixing sentinel and pick violates both paths");
    }

    @Test
    void sentinelPlusSkip_isCastModeViolation() {
        var errors = validator.validate(List.of("sentinel:1", "skip"));
        assertTrue(errors.contains("CAST_MODE_VIOLATION"), "sentinel + skip violates path B (no skips allowed)");
    }

    @Test
    void twoSentinels_isCastModeViolation() {
        var errors = validator.validate(List.of("sentinel:1", "sentinel:2"));
        assertTrue(errors.contains("CAST_MODE_VIOLATION"), "two sentinels violate path B (exactly 1 required)");
    }

    @Test
    void allSkips_isCastModeViolation() {
        var errors = validator.validate(List.of("skip", "skip"));
        assertTrue(errors.contains("CAST_MODE_VIOLATION"), "all skips with no real actress is invalid");
    }

    // ── UNRESOLVED_CAST_SLOT ──────────────────────────────────────────────────

    @Test
    void pickPlusUnresolved_hasUnresolvedSlotError() {
        var errors = validator.validate(List.of("pick", "unresolved"));
        assertTrue(errors.contains("UNRESOLVED_CAST_SLOT"),
                "unresolved slot must always produce UNRESOLVED_CAST_SLOT");
        // path A is satisfied (pick present, no sentinel) — no mode violation
        assertFalse(errors.contains("CAST_MODE_VIOLATION"));
    }

    @Test
    void unresolvedOnly_hasBothErrors() {
        var errors = validator.validate(List.of("unresolved"));
        assertTrue(errors.contains("UNRESOLVED_CAST_SLOT"),
                "unresolved slot must produce UNRESOLVED_CAST_SLOT");
        assertTrue(errors.contains("CAST_MODE_VIOLATION"),
                "unresolved-only list has no real actress and no sentinel → CAST_MODE_VIOLATION");
    }
}
