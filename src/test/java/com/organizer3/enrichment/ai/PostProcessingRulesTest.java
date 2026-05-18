package com.organizer3.enrichment.ai;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class PostProcessingRulesTest {

    private static final String CODE = "ABC-123";
    private static final String ACTRESS = "Yu Tano";

    private final PostProcessingRules rules = new PostProcessingRules(true);
    private final PostProcessingRules disabled = new PostProcessingRules(false);

    // ------------------------------------------------------------------ helpers

    private static AssistPromptBuilder.Input.Candidate cand(
            String slug, String title, List<String> cast) {
        return new AssistPromptBuilder.Input.Candidate(
                slug, title, null, null, cast, null, null, null);
    }

    private static AssistPromptBuilder.Input inputFor(
            List<AssistPromptBuilder.Input.Candidate> candidates) {
        return new AssistPromptBuilder.Input(
                CODE, "ABC", null, List.of(ACTRESS), List.of(), candidates);
    }

    private static AssistResult agreedOn(String slug) {
        return new AssistResult("agreed", "high", slug, "both agreed", 1, 1);
    }

    // ------------------------------------------------------------------ Rule 3

    @Test
    void rule3_dropsEmptyCastWhenAtLeastOneNonEmptyPeerExists() {
        var a = cand("a", "Title A", List.of(ACTRESS));
        var b = cand("b", "Title B", List.of());
        var c = cand("c", "Title C", List.of("Mika Azuma"));
        var input = inputFor(List.of(a, b, c));

        var out = rules.prefilterCandidates(input, List.of(a, b, c));

        assertEquals(List.of(a, c), out);
    }

    @Test
    void rule3_allHaveCast_returnsUnchanged() {
        var a = cand("a", "Title A", List.of(ACTRESS));
        var b = cand("b", "Title B", List.of("Other"));
        var input = inputFor(List.of(a, b));

        var list = List.of(a, b);
        var out = rules.prefilterCandidates(input, list);

        assertEquals(list, out);
    }

    @Test
    void rule3_allEmptyCast_returnsUnchangedForSafety() {
        var a = cand("a", "Title A", List.of());
        var b = cand("b", "Title B", List.of());
        var input = inputFor(List.of(a, b));

        var list = List.of(a, b);
        var out = rules.prefilterCandidates(input, list);

        assertEquals(list, out);
    }

    // ------------------------------------------------------------------ Rule 2

    @Test
    void rule2_exactlyOneCandidateMatchesCodeAndCast_isolatesIt() {
        // 3 candidates, all with the linked actress in cast; only one mentions ABC-123 in title.
        var a = cand("a", "Some title", List.of(ACTRESS));
        var b = cand("b", "Bonus version ABC-123 extra", List.of(ACTRESS));
        var c = cand("c", "Another title", List.of(ACTRESS));
        var input = inputFor(List.of(a, b, c));

        var out = rules.prefilterCandidates(input, List.of(a, b, c));

        assertEquals(1, out.size());
        assertEquals("b", out.get(0).slug());
    }

    @Test
    void rule2_zeroCodeMatches_returnsUnchanged() {
        var a = cand("a", "Some title", List.of(ACTRESS));
        var b = cand("b", "Bonus version", List.of(ACTRESS));
        var input = inputFor(List.of(a, b));

        var list = List.of(a, b);
        var out = rules.prefilterCandidates(input, list);

        assertEquals(list, out);
    }

    @Test
    void rule2_multipleCodeMatches_returnsUnchanged() {
        var a = cand("a", "ABC-123 short", List.of(ACTRESS));
        var b = cand("b", "ABC-123 extended bonus cut", List.of(ACTRESS));
        var input = inputFor(List.of(a, b));

        var list = List.of(a, b);
        var out = rules.prefilterCandidates(input, list);

        assertEquals(list, out);
    }

    // ------------------------------------------------------------------ Rule 1

    @Test
    void rule1_agreedOnBonusVersion_swapsToShorterCanonical() {
        var shortCand = cand("a", "ABC-123 Canonical", List.of(ACTRESS));
        var longCand  = cand("b", "ABC-123 Canonical Bonus Extended Edition", List.of(ACTRESS));
        var input = inputFor(List.of(shortCand, longCand));

        AssistResult original = agreedOn("b");
        AssistResult overridden = rules.applyOverrides(input, original, List.of(shortCand, longCand));

        assertEquals("agreed_with_override", overridden.outcome());
        assertEquals("a", overridden.suggestedSlug());
        assertEquals("high", overridden.confidence());
        // Pick indices preserved — they record what the models actually voted.
        assertEquals(Integer.valueOf(1), overridden.phi4Pick());
        assertEquals(Integer.valueOf(1), overridden.gemmaPick());
        assertNotNull(overridden.reason());
    }

    @Test
    void rule1_agreedOnShorterCanonical_returnsUnchanged() {
        var shortCand = cand("a", "ABC-123 Canonical", List.of(ACTRESS));
        var longCand  = cand("b", "ABC-123 Canonical Bonus Extended Edition", List.of(ACTRESS));
        var input = inputFor(List.of(shortCand, longCand));

        AssistResult original = agreedOn("a");
        AssistResult result = rules.applyOverrides(input, original, List.of(shortCand, longCand));

        assertSame(original, result);
    }

    @Test
    void rule1_otherCandidateLacksActress_returnsUnchanged() {
        var shortCand = cand("a", "ABC-123 Canonical", List.of("Other Actress"));
        var longCand  = cand("b", "ABC-123 Canonical Bonus Extended Edition", List.of(ACTRESS));
        var input = inputFor(List.of(shortCand, longCand));

        AssistResult original = agreedOn("b");
        AssistResult result = rules.applyOverrides(input, original, List.of(shortCand, longCand));

        assertSame(original, result);
    }

    @Test
    void rule1_conflictOutcome_returnsUnchanged() {
        var shortCand = cand("a", "ABC-123 Canonical", List.of(ACTRESS));
        var longCand  = cand("b", "ABC-123 Canonical Bonus Extended Edition", List.of(ACTRESS));
        var input = inputFor(List.of(shortCand, longCand));

        AssistResult conflict = new AssistResult("conflict", null, null, "disagreed", 1, 2);
        AssistResult result = rules.applyOverrides(input, conflict, List.of(shortCand, longCand));

        assertSame(conflict, result);
    }

    @Test
    void rule1_phi4OnlyOutcome_returnsUnchanged() {
        var shortCand = cand("a", "ABC-123 Canonical", List.of(ACTRESS));
        var longCand  = cand("b", "ABC-123 Canonical Bonus Extended Edition", List.of(ACTRESS));
        var input = inputFor(List.of(shortCand, longCand));

        AssistResult phi4Only = new AssistResult("phi4_only", "medium", "b", "phi4 only", 2, null);
        AssistResult result = rules.applyOverrides(input, phi4Only, List.of(shortCand, longCand));

        assertSame(phi4Only, result);
    }

    @Test
    void rule1_shorterCandidateNotContainedInLonger_returnsUnchanged() {
        // shorter title is NOT a substring of the longer one — different wording
        var shortCand = cand("a", "Completely Different Title", List.of(ACTRESS));
        var longCand  = cand("b", "ABC-123 Bonus Extended Edition", List.of(ACTRESS));
        var input = inputFor(List.of(shortCand, longCand));

        AssistResult original = agreedOn("b");
        AssistResult result = rules.applyOverrides(input, original, List.of(shortCand, longCand));

        assertSame(original, result);
    }

    @Test
    void rule1_shorterByLessThanThreshold_returnsUnchanged() {
        // 4-character delta is below the 5-char threshold
        var shortCand = cand("a", "ABC-123 Canonical Cut", List.of(ACTRESS));
        var longCand  = cand("b", "ABC-123 Canonical Cut!!!!", List.of(ACTRESS));
        var input = inputFor(List.of(shortCand, longCand));

        AssistResult original = agreedOn("b");
        AssistResult result = rules.applyOverrides(input, original, List.of(shortCand, longCand));

        assertSame(original, result);
    }

    @Test
    void rule1_multipleQualifyingPeers_picksShortest() {
        var shortest = cand("a", "ABC-123 Canon", List.of(ACTRESS));
        var medium   = cand("b", "ABC-123 Canon Medium", List.of(ACTRESS));
        var longCand = cand("c", "ABC-123 Canon Medium Extra Long Bonus Edition", List.of(ACTRESS));
        var input = inputFor(List.of(shortest, medium, longCand));

        AssistResult original = agreedOn("c");
        AssistResult result = rules.applyOverrides(input, original, List.of(shortest, medium, longCand));

        assertEquals("agreed_with_override", result.outcome());
        assertEquals("a", result.suggestedSlug());
    }

    // ------------------------------------------------------------------ disabled mode

    @Test
    void disabled_prefilterPassThrough() {
        var a = cand("a", "Title A", List.of(ACTRESS));
        var b = cand("b", "Title B", List.of());
        var input = inputFor(List.of(a, b));

        var list = List.of(a, b);
        var out = disabled.prefilterCandidates(input, list);

        assertSame(list, out);
    }

    @Test
    void disabled_overrideReturnsOriginal() {
        var shortCand = cand("a", "ABC-123 Canonical", List.of(ACTRESS));
        var longCand  = cand("b", "ABC-123 Canonical Bonus Extended Edition", List.of(ACTRESS));
        var input = inputFor(List.of(shortCand, longCand));

        AssistResult original = agreedOn("b");
        AssistResult result = disabled.applyOverrides(input, original, List.of(shortCand, longCand));

        assertSame(original, result);
    }

    // ------------------------------------------------------------------ edge cases

    @Test
    void prefilter_emptyCandidates_passThroughUnchanged() {
        var out = rules.prefilterCandidates(inputFor(List.of()), List.of());
        assertEquals(List.of(), out);
    }

    @Test
    void prefilter_orderOfOps_rule3RunsBeforeRule2() {
        // Rule 3 drops the empty-cast one; then Rule 2 isolates the single code-matching survivor.
        var noCast = cand("x", "ABC-123 also has code", List.of());
        var aHasCast = cand("a", "Some title", List.of(ACTRESS));
        var bHasCast = cand("b", "ABC-123 the one", List.of(ACTRESS));
        var input = inputFor(List.of(noCast, aHasCast, bHasCast));

        var out = rules.prefilterCandidates(input, List.of(noCast, aHasCast, bHasCast));

        // Rule 3 keeps {a, b}; Rule 2 sees exactly one (b) match cast+code → isolates b.
        assertEquals(1, out.size());
        assertEquals("b", out.get(0).slug());
    }

    @Test
    void rule1_nullActressNames_returnsUnchanged() {
        var shortCand = cand("a", "ABC-123 Canonical", List.of("Yu Tano"));
        var longCand  = cand("b", "ABC-123 Canonical Bonus Extended Edition", List.of("Yu Tano"));
        var input = new AssistPromptBuilder.Input(
                CODE, "ABC", null, null, List.of(), List.of(shortCand, longCand));

        AssistResult original = agreedOn("b");
        AssistResult result = rules.applyOverrides(input, original, List.of(shortCand, longCand));

        // No actress names → no candidate can be confirmed to share the linked actress → no swap.
        assertSame(original, result);
    }

    @Test
    void rule1_agreedSlugNotInCandidates_returnsUnchanged() {
        var a = cand("a", "ABC-123 Canonical", List.of(ACTRESS));
        var b = cand("b", "ABC-123 Canonical Bonus Extended Edition", List.of(ACTRESS));
        var input = inputFor(List.of(a, b));

        AssistResult phantom = agreedOn("not-in-list");
        AssistResult result = rules.applyOverrides(input, phantom, List.of(a, b));

        assertSame(phantom, result);
    }

    @Test
    void rule1_abstainOutcomes_returnUnchanged() {
        var a = cand("a", "ABC-123 Canonical", List.of(ACTRESS));
        var b = cand("b", "ABC-123 Canonical Bonus Extended Edition", List.of(ACTRESS));
        var input = inputFor(List.of(a, b));

        AssistResult bothAbstain = new AssistResult("both_abstain", null, null, "both abstained", null, null);
        assertSame(bothAbstain, rules.applyOverrides(input, bothAbstain, List.of(a, b)));

        AssistResult gemmaOnly = new AssistResult("gemma_only", "low", "b", "gemma only", null, 2);
        assertSame(gemmaOnly, rules.applyOverrides(input, gemmaOnly, List.of(a, b)));
    }

    @Test
    void rule1_nullSuggestedSlugOnAgreed_returnsUnchanged() {
        var a = cand("a", "ABC-123 Canonical", List.of(ACTRESS));
        var input = inputFor(List.of(a));
        // Synthetic: agreed outcome but slug null — defensive guard.
        AssistResult weird = new AssistResult("agreed", "high", null, "?", 1, 1);
        assertNull(rules.applyOverrides(input, weird, List.of(a)).suggestedSlug());
    }
}
