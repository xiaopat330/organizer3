package com.organizer3.javdb.enrichment;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link CastJsonFilter}.
 *
 * All tests exercise the two public helpers in isolation without any DB or mocking.
 */
class CastJsonFilterTest {

    // ── femaleOnlyCast ─────────────────────────────────────────────────────────

    @Test
    void femaleOnlyCast_null_returnsNull() {
        assertNull(CastJsonFilter.femaleOnlyCast(null));
    }

    @Test
    void femaleOnlyCast_blank_returnsBlank() {
        String blank = "  ";
        assertEquals(blank, CastJsonFilter.femaleOnlyCast(blank));
    }

    @Test
    void femaleOnlyCast_emptyArray_returnsEmptyArray() throws Exception {
        String result = CastJsonFilter.femaleOnlyCast("[]");
        assertEquals("[]", result);
    }

    @Test
    void femaleOnlyCast_allFemale_returnsAll() throws Exception {
        String input = "[{\"slug\":\"a\",\"name\":\"A\",\"gender\":\"F\"},{\"slug\":\"b\",\"name\":\"B\",\"gender\":\"F\"}]";
        String result = CastJsonFilter.femaleOnlyCast(input);
        assertTrue(result.contains("\"a\""));
        assertTrue(result.contains("\"b\""));
    }

    @Test
    void femaleOnlyCast_mixedGenders_dropsMalesAndUnknowns() throws Exception {
        String input = "[" +
                "{\"slug\":\"f1\",\"name\":\"Female\",\"gender\":\"F\"}," +
                "{\"slug\":\"m1\",\"name\":\"Male\",\"gender\":\"M\"}," +
                "{\"slug\":\"u1\",\"name\":\"Unknown\",\"gender\":\"U\"}," +
                "{\"slug\":\"n1\",\"name\":\"NoGender\"}" +
                "]";
        String result = CastJsonFilter.femaleOnlyCast(input);
        assertTrue(result.contains("f1"),  "female slug must be kept");
        assertFalse(result.contains("m1"), "male slug must be dropped");
        assertFalse(result.contains("u1"), "unknown gender slug must be dropped");
        assertFalse(result.contains("n1"), "missing gender slug must be dropped");
    }

    @Test
    void femaleOnlyCast_malformedJson_returnsOriginal() {
        String bad = "not-json{{{";
        assertEquals(bad, CastJsonFilter.femaleOnlyCast(bad));
    }

    @Test
    void femaleOnlyCast_nonArray_returnsOriginal() {
        String obj = "{\"slug\":\"x\",\"gender\":\"F\"}";
        assertEquals(obj, CastJsonFilter.femaleOnlyCast(obj));
    }

    // ── femaleOnlyDetailCandidateCast ──────────────────────────────────────────

    @Test
    void femaleOnlyDetailCandidateCast_null_returnsNull() {
        assertNull(CastJsonFilter.femaleOnlyDetailCandidateCast(null));
    }

    @Test
    void femaleOnlyDetailCandidateCast_blank_returnsBlank() {
        String blank = "  ";
        assertEquals(blank, CastJsonFilter.femaleOnlyDetailCandidateCast(blank));
    }

    @Test
    void femaleOnlyDetailCandidateCast_malformedJson_returnsOriginal() {
        String bad = "not-json{{{";
        assertEquals(bad, CastJsonFilter.femaleOnlyDetailCandidateCast(bad));
    }

    @Test
    void femaleOnlyDetailCandidateCast_noCandidatesField_returnsOriginal() {
        String obj = "{\"code\":\"AAA-001\",\"linked_slugs\":[]}";
        // No candidates array — should come back unchanged.
        String result = CastJsonFilter.femaleOnlyDetailCandidateCast(obj);
        assertTrue(result.contains("AAA-001"));
    }

    @Test
    void femaleOnlyDetailCandidateCast_filtersMalesInsideEachCandidate() throws Exception {
        String detail = "{" +
                "\"code\":\"AAA-001\"," +
                "\"linked_slugs\":[]," +
                "\"candidates\":[" +
                "  {\"slug\":\"cand1\",\"cast\":[" +
                "    {\"slug\":\"f1\",\"name\":\"Female\",\"gender\":\"F\"}," +
                "    {\"slug\":\"m1\",\"name\":\"Male\",\"gender\":\"M\"}" +
                "  ]}," +
                "  {\"slug\":\"cand2\",\"cast\":[" +
                "    {\"slug\":\"m2\",\"name\":\"Male2\",\"gender\":\"M\"}" +
                "  ]}" +
                "]}";

        String result = CastJsonFilter.femaleOnlyDetailCandidateCast(detail);

        // Top-level structure preserved.
        assertTrue(result.contains("\"AAA-001\""), "code field must be preserved");
        assertTrue(result.contains("\"cand1\""),   "candidate slug cand1 must be preserved");
        assertTrue(result.contains("\"cand2\""),   "candidate slug cand2 must be preserved");

        // Female kept, males dropped.
        assertTrue(result.contains("\"f1\""),   "female cast entry must be present");
        assertFalse(result.contains("\"m1\""),  "male cast entry m1 must be filtered");
        assertFalse(result.contains("\"m2\""),  "male cast entry m2 must be filtered");
    }

    @Test
    void femaleOnlyDetailCandidateCast_candidateWithNoCastArrayLeftIntact() throws Exception {
        String detail = "{" +
                "\"code\":\"AAA-002\"," +
                "\"linked_slugs\":[]," +
                "\"candidates\":[" +
                "  {\"slug\":\"cand-no-cast\"}" +
                "]}";
        String result = CastJsonFilter.femaleOnlyDetailCandidateCast(detail);
        assertTrue(result.contains("cand-no-cast"), "candidate without cast array must survive");
    }

    @Test
    void femaleOnlyDetailCandidateCast_storedDataNotMutated() throws Exception {
        // Verify the method does not mutate the input string (reference identity is new).
        String detail = "{\"code\":\"X\",\"candidates\":[{\"slug\":\"c\",\"cast\":[{\"slug\":\"m\",\"gender\":\"M\"}]}]}";
        String result = CastJsonFilter.femaleOnlyDetailCandidateCast(detail);
        // Original must still contain the male.
        assertTrue(detail.contains("\"m\""), "original must be unchanged");
        // Output must not contain the male.
        assertFalse(result.contains("\"m\""), "filtered output must drop the male");
    }
}
