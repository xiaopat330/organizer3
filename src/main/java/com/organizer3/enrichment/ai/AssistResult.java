package com.organizer3.enrichment.ai;

/**
 * Outcome of a single ensemble assist evaluation.
 *
 * @param outcome        one of {@code agreed}, {@code phi4_only}, {@code gemma_only},
 *                       {@code conflict}, {@code both_abstain}
 * @param confidence     one of {@code high}, {@code medium}, {@code low}, or {@code null}
 *                       when both models abstained
 * @param suggestedSlug  selected candidate slug; {@code null} when abstained or in conflict
 * @param reason         short one-sentence rationale from the model(s)
 * @param phi4Pick       1-based pick index from phi4, or {@code null} if it abstained
 * @param gemmaPick      1-based pick index from gemma3, or {@code null} if it abstained
 * @param phi4Slug       slug chosen by phi4 (resolved from {@code phi4Pick}), or {@code null} if abstained
 * @param gemmaSlug      slug chosen by gemma3 (resolved from {@code gemmaPick}), or {@code null} if abstained
 */
public record AssistResult(
        String outcome,
        String confidence,
        String suggestedSlug,
        String reason,
        Integer phi4Pick,
        Integer gemmaPick,
        String phi4Slug,
        String gemmaSlug
) {
    /** Backward-compatible constructor for callers that do not supply per-model slugs. */
    public AssistResult(String outcome, String confidence, String suggestedSlug,
                        String reason, Integer phi4Pick, Integer gemmaPick) {
        this(outcome, confidence, suggestedSlug, reason, phi4Pick, gemmaPick, null, null);
    }
}
