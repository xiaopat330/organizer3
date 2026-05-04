package com.organizer3.translation.repository;

import com.organizer3.translation.StageNameLookupRow;

import java.util.List;
import java.util.Optional;

/**
 * Persistence operations for the {@code stage_name_lookup} curated kanji→romaji table.
 *
 * <p>The lookup key is {@code kanji_form}, which must be NFKC-normalised before querying.
 * The table is atomically re-seeded at startup via {@link #clearAndSeed}.
 */
public interface StageNameLookupRepository {

    /**
     * Look up the romanized form for a given kanji stage name.
     * Returns empty if not in the curated table.
     *
     * @param kanjiForm NFKC-normalised kanji stage name
     */
    Optional<String> findRomanizedFor(String kanjiForm);

    /**
     * Insert or replace a single entry (upsert semantics on {@code kanji_form}).
     *
     * @param kanjiForm    NFKC-normalised kanji stage name (the unique key)
     * @param romanizedForm  romanized name (e.g. "Yua Aida")
     * @param actressSlug  the actress YAML slug, or null if unknown
     * @param source       provenance label (e.g. "yaml_seed")
     */
    void upsert(String kanjiForm, String romanizedForm, String actressSlug, String source);

    /** Total number of rows in the lookup table. */
    long countAll();

    /**
     * Atomically replace the entire table contents.
     * Deletes all existing rows and inserts the provided list within a single transaction.
     *
     * @param rows new rows to insert; {@code id} and {@code seededAt} are populated by the DB
     */
    void clearAndSeed(List<StageNameLookupRow> rows);
}
