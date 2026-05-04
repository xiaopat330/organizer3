package com.organizer3.translation;

/**
 * Row from the {@code stage_name_suggestion} table.
 *
 * <p>Captures LLM-produced romaji for a Japanese stage name candidate, pending human review.
 * {@code reviewDecision} is {@code null} (unreviewed), {@code "accepted"}, or {@code "rejected"}.
 */
public record StageNameSuggestionRow(
        long id,
        String kanjiForm,
        String suggestedRomaji,
        String suggestedAt,
        String reviewedAt,
        String reviewDecision,
        String finalRomaji
) {}
