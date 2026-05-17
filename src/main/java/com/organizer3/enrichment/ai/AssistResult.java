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
 */
public record AssistResult(
        String outcome,
        String confidence,
        String suggestedSlug,
        String reason,
        Integer phi4Pick,
        Integer gemmaPick
) {}
