package com.organizer3.translation.repository;

import com.organizer3.translation.StageNameSuggestionRow;

import java.util.List;
import java.util.Optional;

/**
 * Persistence operations for the {@code stage_name_suggestion} table.
 *
 * <p>Records LLM-produced romaji candidates for Japanese stage names.
 * Suggestions are reviewed by humans; accepted ones feed back into {@link StageNameLookupRepository}.
 */
public interface StageNameSuggestionRepository {

    /**
     * Record a suggestion, unless the exact (kanjiForm, suggestedRomaji) pair already exists.
     * Idempotent: duplicate suggestions are silently ignored.
     *
     * @param kanjiForm      NFKC-normalised kanji stage name
     * @param suggestedRomaji romaji produced by the LLM
     * @param suggestedAt    ISO-8601 timestamp string
     */
    void recordSuggestion(String kanjiForm, String suggestedRomaji, String suggestedAt);

    /**
     * Find all suggestion rows for a given kanji form, ordered by {@code suggested_at DESC}.
     */
    List<StageNameSuggestionRow> findByKanji(String kanjiForm);

    /**
     * Find the most recent accepted suggestion for a kanji form, if any.
     * "Accepted" means {@code review_decision = 'accepted'}.
     */
    Optional<String> findAcceptedRomaji(String kanjiForm);

    /**
     * Return up to {@code limit} unreviewed suggestions (those with {@code review_decision IS NULL}),
     * ordered by {@code suggested_at DESC}.
     */
    List<StageNameSuggestionRow> findUnreviewed(int limit);

    /** Count of rows with {@code review_decision IS NULL}. */
    long countUnreviewed();
}
