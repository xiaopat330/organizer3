package com.organizer3.command;

import com.organizer3.model.Actress;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ActressNameCheckServiceTest {

    private ActressNameCheckService svc;

    @BeforeEach
    void setUp() {
        svc = new ActressNameCheckService();
    }

    // ── normalization ──────────────────────────────────────────────────────

    @Test
    void normalize_collapses_ou_oo_oh() {
        assertEquals("ryo", ActressNameCheckService.normalize("Ryou"));
        assertEquals("ryo", ActressNameCheckService.normalize("Ryoo"));
        assertEquals("ryo", ActressNameCheckService.normalize("Ryoh"));
    }

    @Test
    void normalize_collapses_yuki_variants() {
        assertEquals("yuki", ActressNameCheckService.normalize("Yuuki"));
        assertEquals("yuki", ActressNameCheckService.normalize("Yuki"));
    }

    @Test
    void normalize_collapses_tsu_shi_chi() {
        assertEquals("matuda", ActressNameCheckService.normalize("Matsuda"));
        assertEquals("nisi",   ActressNameCheckService.normalize("Nishi"));
        assertEquals("aci",    ActressNameCheckService.normalize("Achi"));
    }

    // ── levenshtein ────────────────────────────────────────────────────────

    @Test
    void levenshtein_sameString_zero() {
        assertEquals(0, ActressNameCheckService.levenshtein("abc", "abc"));
    }

    @Test
    void levenshtein_oneSubstitution() {
        // Yuna → Yuma: one substitution (n→m)
        assertEquals(1, ActressNameCheckService.levenshtein("Yuna", "Yuma"));
    }

    @Test
    void levenshtein_oneDeletion() {
        // Asuaka → Asuka: one deletion (extra 'a')
        assertEquals(1, ActressNameCheckService.levenshtein("Asuaka", "Asuka"));
    }

    @Test
    void ndist_normalizes_before_compare() {
        // "Ryou" and "Ryo" normalize to "ryo" and "ryo" — dist 0
        assertEquals(0, ActressNameCheckService.ndist("Ryou", "Ryo"));
        // "Yuuki" → "yuki", "Yuki" → "yuki" — dist 0
        assertEquals(0, ActressNameCheckService.ndist("Yuuki", "Yuki"));
    }

    // ── swap detection ─────────────────────────────────────────────────────

    @Test
    void findSwaps_detectsReversedPair() {
        Actress a = actress(1, "Yuna Shiina");
        Actress b = actress(2, "Shiina Yuna");
        Map<Long, Integer> counts = Map.of(1L, 10, 2L, 2);

        List<ActressNameCheckService.SwapPair> result = svc.findSwaps(List.of(a, b), counts);

        assertEquals(1, result.size());
        var pair = result.get(0);
        assertEquals("Yuna Shiina", pair.canonical().getCanonicalName()); // more titles
        assertEquals("Shiina Yuna", pair.suspect().getCanonicalName());
    }

    @Test
    void findSwaps_noDuplicatePairs() {
        Actress a = actress(1, "Yuna Shiina");
        Actress b = actress(2, "Shiina Yuna");
        Map<Long, Integer> counts = Map.of(1L, 10, 2L, 2);

        List<ActressNameCheckService.SwapPair> result = svc.findSwaps(List.of(a, b), counts);

        assertEquals(1, result.size()); // not 2
    }

    @Test
    void findSwaps_ignoresSingleWordNames() {
        Actress a = actress(1, "Rio");
        Map<Long, Integer> counts = Map.of(1L, 50);
        assertTrue(svc.findSwaps(List.of(a), counts).isEmpty());
    }

    @Test
    void findSwaps_noMatchWhenNoCounterpart() {
        Actress a = actress(1, "Yuna Shiina");
        Map<Long, Integer> counts = Map.of(1L, 5);
        assertTrue(svc.findSwaps(List.of(a), counts).isEmpty());
    }

    // ── typo detection ─────────────────────────────────────────────────────

    @Test
    void findTypos_detectsSameSurnameGivenTypo() {
        // "Aoi Sola" (big) vs "Aoy Sola" (1 title) — same surname, given dist=1 after normalization
        Actress canonical = actress(1, "Aoi Sola");
        Actress suspect   = actress(2, "Aoy Sola");
        Map<Long, Integer> counts = Map.of(1L, 50, 2L, 1);

        List<ActressNameCheckService.TypoPair> result = svc.findTypos(List.of(canonical, suspect), counts);

        assertEquals(1, result.size());
        var pair = result.get(0);
        assertEquals("Aoi Sola", pair.canonical().getCanonicalName());
        assertEquals("Aoy Sola", pair.suspect().getCanonicalName());
        assertTrue(pair.sameSurname());
    }

    @Test
    void findTypos_detectsSameGivenSurnameTypo() {
        // "Yuna Nishino" (big) vs "Yuna Nishina" (1 title) — same given, surname normalized dist=1
        // Nishino→ shi→si → nisino; Nishina → shi→si → nisina; dist("nisino","nisina") = 1 (o→a)
        Actress canonical = actress(1, "Yuna Nishino");
        Actress suspect   = actress(2, "Yuna Nishina");
        Map<Long, Integer> counts = Map.of(1L, 20, 2L, 1);

        List<ActressNameCheckService.TypoPair> result = svc.findTypos(List.of(canonical, suspect), counts);

        assertEquals(1, result.size());
        var pair = result.get(0);
        assertEquals("Yuna Nishino", pair.canonical().getCanonicalName());
        assertFalse(pair.sameSurname());
    }

    @Test
    void findTypos_noneWhenBothSidesHighCount() {
        // Both have many titles — no suspect entry, should not report
        Actress a = actress(1, "Aoi Sola");
        Actress b = actress(2, "Aoy Sola");
        Map<Long, Integer> counts = Map.of(1L, 50, 2L, 10);

        assertTrue(svc.findTypos(List.of(a, b), counts).isEmpty());
    }

    @Test
    void findTypos_normalizationCollapsesPreventsDistOne() {
        // "Ryou Tanaka" and "Ryo Tanaka" normalize to same given name — dist=0 not reported
        Actress a = actress(1, "Ryou Tanaka");
        Actress b = actress(2, "Ryo Tanaka");
        Map<Long, Integer> counts = Map.of(1L, 50, 2L, 1);

        // dist=0 after normalization → not a typo, should not appear
        assertTrue(svc.findTypos(List.of(a, b), counts).isEmpty());
    }

    @Test
    void findTypos_noDuplicatePairsAcrossSurnameAndGivenPasses() {
        // A pair caught by the surname-pass should not also appear in the given-pass
        Actress a = actress(1, "Aoi Sola");
        Actress b = actress(2, "Aoy Sola");
        Map<Long, Integer> counts = Map.of(1L, 50, 2L, 1);

        assertEquals(1, svc.findTypos(List.of(a, b), counts).size());
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static Actress actress(long id, String canonicalName) {
        return Actress.builder()
                .id(id)
                .canonicalName(canonicalName)
                .tier(Actress.Tier.LIBRARY)
                .build();
    }
}
