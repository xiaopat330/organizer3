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
     * Record a suggestion (idempotent — same as {@link #recordSuggestion}) and return
     * the row id. On an {@code INSERT OR IGNORE} no-op (duplicate), returns the id of
     * the existing row with that (kanjiForm, suggestedRomaji) pair.
     *
     * @return the {@code stage_name_suggestion.id} for the (kanjiForm, suggestedRomaji) pair
     */
    long recordSuggestionAndGetId(String kanjiForm, String suggestedRomaji, String suggestedAt);

    /**
     * Look up a suggestion row by its primary key. Returns empty if the row has been deleted.
     */
    java.util.Optional<StageNameSuggestionRow> findById(long id);

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

    /**
     * Find the best available romaji for a kanji form, including unreviewed suggestions.
     *
     * <p>Priority (per spec §3.2):
     * <ol>
     *   <li>{@code final_romaji} when {@code review_decision = 'accepted'} and non-null
     *       (human-corrected wins).</li>
     *   <li>{@code suggested_romaji} when {@code review_decision = 'accepted'}.</li>
     *   <li>{@code suggested_romaji} when {@code review_decision IS NULL} (unreviewed —
     *       acceptable for pre-fill).</li>
     *   <li>Empty when {@code review_decision = 'rejected'} — never pre-fill a rejected
     *       guess.</li>
     * </ol>
     *
     * <p>Orders by {@code id DESC} and takes the first usable row.
     *
     * @param normalizedKanji NFKC-normalised kanji stage name
     */
    Optional<String> findLatestUsableSuggestion(String normalizedKanji);

    /**
     * FIX 3a: Persists a corrected given-first romaji order for a kanji form.
     *
     * <p>When a REVERSAL-rule fuzzy match fires, the LLM produced the romaji in
     * surname-first order, but the canonical actress name is given-first. This method
     * writes {@code correctedRomaji} into the {@code final_romaji} column of the most
     * recent suggestion row for {@code normalizedKanji}, so that future pre-fills and
     * the review UI show the correct order.
     *
     * <p>No-op if no suggestion row exists for the given kanji form.
     *
     * @param normalizedKanji NFKC-normalised kanji stage name
     * @param correctedRomaji the canonical given-first romaji (from the matched actress)
     */
    void recordFinalRomaji(String normalizedKanji, String correctedRomaji);
}
