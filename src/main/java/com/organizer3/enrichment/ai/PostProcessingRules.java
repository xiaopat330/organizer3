package com.organizer3.enrichment.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Phase 4 Track B — deterministic Java-side rules that run around the LLM ensemble.
 *
 * <p>Two integration points:
 * <ul>
 *   <li>{@link #prefilterCandidates(AssistPromptBuilder.Input, List)} runs BEFORE the ensemble
 *       call and can remove candidates that are clearly unsuitable (rules 3 + 2).</li>
 *   <li>{@link #applyOverrides(AssistPromptBuilder.Input, AssistResult, List)} runs AFTER the
 *       ensemble call and can swap an {@code agreed} pick for a clearly-better shorter
 *       canonical title (rule 1).</li>
 * </ul>
 *
 * <p><b>Safety net</b>: if a prefilter would remove every candidate, the original list is
 * returned unchanged so the ensemble still gets a vote. Rules are conservative — only fire
 * when the heuristic is clearly applicable.
 *
 * <p>When constructed with {@code enabled=false} both methods are no-op pass-throughs.
 *
 * @see <a href="../../../../../spec/PLAN_AI_PICKER_ASSIST_PHASE4.md">PLAN_AI_PICKER_ASSIST_PHASE4.md</a>
 */
public class PostProcessingRules {

    private static final Logger log = LoggerFactory.getLogger(PostProcessingRules.class);

    /** Minimum prefix-length delta for rule 1 to fire (chars). */
    private static final int RULE1_MIN_SHORTER_BY = 5;

    private final boolean enabled;

    public PostProcessingRules(boolean enabled) {
        this.enabled = enabled;
    }

    // ------------------------------------------------------------------ prefilter

    /**
     * Apply rules 3 (empty-cast deprioritization) then 2 (exact-code-in-title isolation).
     * Returns a possibly-reduced list; never returns an empty list — if either rule would
     * remove every candidate the ORIGINAL input list is returned and an INFO line is logged.
     */
    public List<AssistPromptBuilder.Input.Candidate> prefilterCandidates(
            AssistPromptBuilder.Input input,
            List<AssistPromptBuilder.Input.Candidate> candidates) {
        if (!enabled || candidates == null || candidates.isEmpty()) {
            return candidates;
        }
        String code = input == null ? null : input.code();

        // Rule 3 — drop empty-cast candidates when at least one non-empty-cast peer exists.
        List<AssistPromptBuilder.Input.Candidate> afterRule3 = applyRule3(candidates);
        if (afterRule3.isEmpty()) {
            log.info("[ai-assist] postprocess: filters would remove all candidates for code={}, keeping originals",
                    code);
            return candidates;
        }
        if (afterRule3.size() != candidates.size()) {
            log.info("[ai-assist] postprocess rule-3 (empty-cast deprio) code={}: {} -> {} candidates",
                    code, candidates.size(), afterRule3.size());
        }

        // Rule 2 — exact-code-in-title with cast-match isolation, only when exactly one matches.
        List<AssistPromptBuilder.Input.Candidate> afterRule2 = applyRule2(input, afterRule3);
        if (afterRule2.isEmpty()) {
            log.info("[ai-assist] postprocess: filters would remove all candidates for code={}, keeping originals",
                    code);
            return candidates;
        }
        if (afterRule2.size() != afterRule3.size()) {
            log.info("[ai-assist] postprocess rule-2 (exact-code isolation) code={}: {} -> {} candidate",
                    code, afterRule3.size(), afterRule2.size());
        }

        return afterRule2;
    }

    /** Remove empty-cast candidates iff at least one non-empty-cast candidate exists. */
    private static List<AssistPromptBuilder.Input.Candidate> applyRule3(
            List<AssistPromptBuilder.Input.Candidate> candidates) {
        boolean anyHasCast = candidates.stream().anyMatch(c -> hasCast(c));
        boolean anyEmpty   = candidates.stream().anyMatch(c -> !hasCast(c));
        if (!anyHasCast || !anyEmpty) {
            // Either all have cast (no-op) or all are empty (preserve to avoid empty result).
            return candidates;
        }
        List<AssistPromptBuilder.Input.Candidate> out = new ArrayList<>(candidates.size());
        for (AssistPromptBuilder.Input.Candidate c : candidates) {
            if (hasCast(c)) out.add(c);
        }
        return out;
    }

    /**
     * If EXACTLY ONE candidate has the linked actress in cast AND its title_original (uppercased)
     * contains the product code, return just that candidate. Otherwise return the input unchanged.
     */
    private static List<AssistPromptBuilder.Input.Candidate> applyRule2(
            AssistPromptBuilder.Input input,
            List<AssistPromptBuilder.Input.Candidate> candidates) {
        if (input == null || input.code() == null || input.code().isBlank()) {
            return candidates;
        }
        String upperCode = input.code().trim().toUpperCase(Locale.ROOT);
        List<String> actressNames = input.actressNames();
        AssistPromptBuilder.Input.Candidate match = null;
        int matchCount = 0;
        for (AssistPromptBuilder.Input.Candidate c : candidates) {
            if (!castIncludesAny(c, actressNames)) continue;
            String title = c.titleOriginal();
            if (title == null) continue;
            if (title.toUpperCase(Locale.ROOT).contains(upperCode)) {
                match = c;
                matchCount++;
                if (matchCount > 1) break;
            }
        }
        if (matchCount == 1) {
            return List.of(match);
        }
        return candidates;
    }

    // ------------------------------------------------------------------ override

    /**
     * Rule 1: when the ensemble {@code agreed} on a "bonus" candidate whose title_original
     * strictly contains (or is suffixed from) a shorter peer candidate that ALSO has the
     * linked actress in cast, prefer the shorter (canonical) candidate. The shorter title
     * must be ≥ {@value #RULE1_MIN_SHORTER_BY} characters shorter to fire.
     *
     * <p>If multiple peers qualify, the shortest one is chosen. The returned
     * {@link AssistResult} preserves the original {@code phi4Pick}/{@code gemmaPick} indices
     * — they represent what the models actually voted; the override is signaled by the new
     * {@code outcome="agreed_with_override"} value and the swapped {@code suggestedSlug}.
     */
    public AssistResult applyOverrides(
            AssistPromptBuilder.Input input,
            AssistResult original,
            List<AssistPromptBuilder.Input.Candidate> candidates) {
        if (!enabled || original == null || candidates == null || candidates.isEmpty()) {
            return original;
        }
        if (!"agreed".equals(original.outcome()) || original.suggestedSlug() == null) {
            return original;
        }

        AssistPromptBuilder.Input.Candidate agreed = findBySlug(candidates, original.suggestedSlug());
        if (agreed == null || agreed.titleOriginal() == null) {
            return original;
        }
        String agreedTitle = agreed.titleOriginal();
        String agreedTitleUpper = agreedTitle.toUpperCase(Locale.ROOT);
        List<String> actressNames = input == null ? null : input.actressNames();

        AssistPromptBuilder.Input.Candidate best = null;
        for (AssistPromptBuilder.Input.Candidate c : candidates) {
            if (c == agreed) continue;
            if (c.slug() == null || c.slug().equals(agreed.slug())) continue;
            if (!castIncludesAny(c, actressNames)) continue;
            String t = c.titleOriginal();
            if (t == null || t.isBlank()) continue;
            int delta = agreedTitle.length() - t.length();
            if (delta < RULE1_MIN_SHORTER_BY) continue;
            if (!agreedTitleUpper.contains(t.toUpperCase(Locale.ROOT))) continue;
            if (best == null || t.length() < best.titleOriginal().length()) {
                best = c;
            }
        }
        if (best == null) {
            return original;
        }
        String reason = "Both models agreed on bonus version; rule 1 preferred shorter canonical title ("
                + best.titleOriginal() + ")";
        String code = input == null ? null : input.code();
        log.info("[ai-assist] postprocess rule-1 (bonus override) code={}: {} -> {} (lengths {}->{})",
                code, agreed.slug(), best.slug(), agreedTitle.length(), best.titleOriginal().length());
        return new AssistResult(
                "agreed_with_override",
                original.confidence(),
                best.slug(),
                reason,
                original.phi4Pick(),
                original.gemmaPick(),
                original.phi4Slug(),
                original.gemmaSlug());
    }

    // ------------------------------------------------------------------ helpers

    private static boolean hasCast(AssistPromptBuilder.Input.Candidate c) {
        return c != null && c.castNames() != null && !c.castNames().isEmpty();
    }

    /**
     * Case-insensitive plain substring match: returns true if any of {@code names} appears
     * (as a substring) inside any of the candidate's cast names. No kanji bridging — that
     * remains the ensemble's responsibility.
     */
    private static boolean castIncludesAny(AssistPromptBuilder.Input.Candidate c, List<String> names) {
        if (names == null || names.isEmpty()) return false;
        if (c == null || c.castNames() == null || c.castNames().isEmpty()) return false;
        for (String want : names) {
            if (want == null || want.isBlank()) continue;
            String wantLower = want.toLowerCase(Locale.ROOT);
            for (String have : c.castNames()) {
                if (have == null) continue;
                if (have.toLowerCase(Locale.ROOT).contains(wantLower)) return true;
            }
        }
        return false;
    }

    private static AssistPromptBuilder.Input.Candidate findBySlug(
            List<AssistPromptBuilder.Input.Candidate> candidates, String slug) {
        if (slug == null) return null;
        for (AssistPromptBuilder.Input.Candidate c : candidates) {
            if (slug.equals(c.slug())) return c;
        }
        return null;
    }
}
