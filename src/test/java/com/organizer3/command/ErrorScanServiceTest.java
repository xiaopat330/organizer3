package com.organizer3.command;

import com.organizer3.model.Actress;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ErrorScanServiceTest {

    private ErrorScanService svc;

    @BeforeEach
    void setUp() {
        svc = new ErrorScanService();
    }

    // ── exact match ────────────────────────────────────────────────────────────

    @Test
    void exactMatch_isSkipped() {
        var r = svc.scan(List.of("Yuna Shiina"), List.of(actress(1, "Yuna Shiina")));
        assertFalse(r.hasIssues());
        assertEquals(1, r.totalScanned());
    }

    @Test
    void exactMatch_isCaseInsensitive() {
        var r = svc.scan(List.of("yuna shiina"), List.of(actress(1, "Yuna Shiina")));
        assertFalse(r.hasIssues());
    }

    @Test
    void emptyFolderList_returnsEmptyResults() {
        var r = svc.scan(List.of(), List.of(actress(1, "Yuna Shiina")));
        assertFalse(r.hasIssues());
        assertEquals(0, r.totalScanned());
    }

    // ── swap detection ────────────────────────────────────────────────────────

    @Test
    void swap_twoWordName_detected() {
        var r = svc.scan(List.of("Shiina Yuna"), List.of(actress(1, "Yuna Shiina")));
        assertEquals(1, r.swaps().size());
        assertEquals("Shiina Yuna", r.swaps().get(0).folderName());
        assertEquals("Yuna Shiina", r.swaps().get(0).dbMatch().getCanonicalName());
        assertTrue(r.typos().isEmpty());
        assertTrue(r.unknowns().isEmpty());
    }

    @Test
    void swap_singleWordNameWithNoDbMatch_isUnknown() {
        // "Rio" reversed is still "Rio" — no match, falls to UNKNOWN
        var r = svc.scan(List.of("Rio"), List.of(actress(1, "Yuna Shiina")));
        assertEquals(1, r.unknowns().size());
        assertTrue(r.swaps().isEmpty());
    }

    @Test
    void swap_threeWordName_notDetectedAsSwap() {
        // Three-word names can't be swapped with this logic → no swap result
        var r = svc.scan(List.of("A B C"), List.of(actress(1, "C B A")));
        // "C B A" is in DB, "A B C" folder: swapWords("A B C") → null → no swap checked
        // ndist("abc", "cba") may or may not be ≤ 1; just verify no SWAP
        assertTrue(r.swaps().isEmpty());
    }

    // ── typo detection ────────────────────────────────────────────────────────

    @Test
    void typo_distOne_detected() {
        // "Aoy" vs "Aoi": normalize("Aoy Sola") = "aoy sola", normalize("Aoi Sola") = "aoi sola"
        // levenshtein = 1 (y→i)
        var r = svc.scan(List.of("Aoy Sola"), List.of(actress(1, "Aoi Sola")));
        assertEquals(1, r.typos().size());
        assertEquals("Aoy Sola", r.typos().get(0).folderName());
        assertEquals("Aoi Sola", r.typos().get(0).dbMatch().getCanonicalName());
        assertEquals(1, r.typos().get(0).dist());
    }

    @Test
    void typo_romanizationVariant_distZero() {
        // "Ryou Tanaka" → normalize → "ryo tanaka"; DB "Ryo Tanaka" → "ryo tanaka"
        // No exact match (literal case-insensitive), but ndist=0 → TYPO with dist=0
        var r = svc.scan(List.of("Ryou Tanaka"), List.of(actress(1, "Ryo Tanaka")));
        assertEquals(1, r.typos().size());
        assertEquals(0, r.typos().get(0).dist());
        assertEquals("Ryo Tanaka", r.typos().get(0).dbMatch().getCanonicalName());
    }

    @Test
    void typo_distTwoNotReported() {
        // "Aab Sola" vs "Aoi Sola": levenshtein > 1 after normalize → not a typo
        var r = svc.scan(List.of("Aab Sola"), List.of(actress(1, "Aoi Sola")));
        assertTrue(r.typos().isEmpty());
    }

    // ── unknown ───────────────────────────────────────────────────────────────

    @Test
    void unknown_noCloseMatch() {
        var r = svc.scan(
                List.of("Completely Different Name"),
                List.of(actress(1, "Yuna Shiina")));
        assertEquals(1, r.unknowns().size());
        assertEquals("Completely Different Name", r.unknowns().get(0).folderName());
    }

    // ── precedence ────────────────────────────────────────────────────────────

    @Test
    void swapTakesPrecedenceOverTypo() {
        // "Sola Aoi" reversed → "Aoi Sola" — exact DB hit; must not also appear as TYPO
        var r = svc.scan(List.of("Sola Aoi"), List.of(actress(1, "Aoi Sola")));
        assertEquals(1, r.swaps().size());
        assertTrue(r.typos().isEmpty());
    }

    // ── mixed ─────────────────────────────────────────────────────────────────

    @Test
    void multipleFolder_mixedResults() {
        List<Actress> db = List.of(
                actress(1, "Yuna Shiina"),
                actress(2, "Aoi Sola")
        );
        var r = svc.scan(List.of("Yuna Shiina", "Shiina Yuna", "Aoy Sola"), db);
        // "Yuna Shiina" → exact → skip
        // "Shiina Yuna" → SWAP of "Yuna Shiina"
        // "Aoy Sola"    → TYPO of "Aoi Sola" dist=1
        assertEquals(1, r.swaps().size());
        assertEquals(1, r.typos().size());
        assertTrue(r.unknowns().isEmpty());
        assertEquals(3, r.totalScanned());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static Actress actress(long id, String canonicalName) {
        return Actress.builder()
                .id(id)
                .canonicalName(canonicalName)
                .tier(Actress.Tier.LIBRARY)
                .build();
    }
}
