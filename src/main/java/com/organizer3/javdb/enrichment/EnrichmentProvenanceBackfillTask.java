package com.organizer3.javdb.enrichment;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.Jdbi;

/**
 * One-shot startup task that stamps provenance columns ({@code resolver_source},
 * {@code confidence}, {@code cast_validated}) on pre-existing rows in
 * {@code title_javdb_enrichment} that predate the 1B migration.
 *
 * <p>Safe to no-op: if every row already has {@code resolver_source} set, both
 * passes are skipped entirely. Subsequent startups are free.
 *
 * <p>Two passes, each in its own transaction:
 * <ol>
 *   <li><b>Pass A — UNKNOWN stamp:</b> every row with {@code resolver_source IS NULL}
 *       gets {@code resolver_source='unknown'}, {@code confidence='UNKNOWN'},
 *       {@code cast_validated=0}.</li>
 *   <li><b>Pass B — LOW scan:</b> runs the cast-doesn't-contain-actress heuristic
 *       (same SQL shape as {@code FindEnrichmentCastMismatchesTool}) and stamps
 *       matching rows {@code confidence='LOW'}. Empty-cast rows ({@code cast_json IS NULL}
 *       or empty JSON array) are excluded per spec §1B — they stay UNKNOWN for the
 *       re-validation cron to classify. Malformed cast_json (non-NULL, non-parseable
 *       as a JSON array) is a suspicious signal and is stamped LOW.</li>
 * </ol>
 */
@Slf4j
@RequiredArgsConstructor
public class EnrichmentProvenanceBackfillTask {

    private final Jdbi jdbi;

    public void run() {
        boolean needed = jdbi.withHandle(h ->
                h.createQuery("SELECT 1 FROM title_javdb_enrichment WHERE resolver_source IS NULL LIMIT 1")
                        .mapTo(Integer.class).findOne().isPresent());
        if (!needed) {
            log.debug("enrichment provenance backfill: all rows already stamped — skipping");
            return;
        }

        // Pass A: stamp every unprovenanced row as UNKNOWN.
        int stampedUnknown = jdbi.inTransaction(h ->
                h.createUpdate("""
                        UPDATE title_javdb_enrichment
                        SET resolver_source = 'unknown',
                            confidence      = 'UNKNOWN',
                            cast_validated  = 0
                        WHERE resolver_source IS NULL
                        """).execute());

        // Pass B: upgrade UNKNOWN rows that fail the cast-contains-actress check to LOW.
        // Mirrors FindEnrichmentCastMismatchesTool's MISMATCH_FROM/MISMATCH_WHERE shape.
        // Empty-cast rows (NULL or []) are explicitly excluded per spec §1B rule.
        // Malformed cast_json (json_array_length returns NULL) is included → LOW.
        int stampedLow = jdbi.inTransaction(h ->
                h.createUpdate("""
                        UPDATE title_javdb_enrichment
                        SET confidence = 'LOW'
                        WHERE confidence = 'UNKNOWN'
                          AND title_id IN (
                            SELECT DISTINCT e.title_id
                            FROM title_javdb_enrichment e
                            JOIN title_actresses ta ON ta.title_id = e.title_id
                            JOIN actresses a        ON a.id = ta.actress_id
                            WHERE COALESCE(a.is_sentinel, 0) = 0
                              AND a.stage_name IS NOT NULL
                              AND e.cast_json IS NOT NULL
                              AND NOT (e.cast_json LIKE '[%' AND json_array_length(e.cast_json) = 0)
                              AND REPLACE(e.cast_json, ' ', '') NOT LIKE '%' || REPLACE(a.stage_name, ' ', '') || '%'
                              AND (
                                a.alternate_names_json IS NULL
                                OR NOT EXISTS (
                                  SELECT 1 FROM json_each(a.alternate_names_json) alt
                                  WHERE json_extract(alt.value, '$.name') IS NOT NULL
                                    AND REPLACE(e.cast_json, ' ', '')
                                        LIKE '%' || REPLACE(json_extract(alt.value, '$.name'), ' ', '') || '%'
                                )
                              )
                          )
                        """).execute());

        // Rows stamped UNKNOWN with empty/null cast — explicitly skipped by the LOW scan.
        // Rows left at UNKNOWN because their cast was null or an empty JSON array.
        // Uses LIKE '[%' to distinguish empty arrays from malformed objects (SQLite's
        // json_array_length returns 0 for both, so the array-start check is necessary).
        int skippedEmpty = jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT COUNT(*) FROM title_javdb_enrichment
                        WHERE resolver_source = 'unknown'
                          AND confidence = 'UNKNOWN'
                          AND (cast_json IS NULL OR (cast_json LIKE '[%' AND json_array_length(cast_json) = 0))
                        """).mapTo(Integer.class).one());

        int total = jdbi.withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM title_javdb_enrichment")
                        .mapTo(Integer.class).one());

        log.info("enrichment provenance backfill complete — total={}, stamped_unknown={}, stamped_low={}, skipped_empty={}",
                total, stampedUnknown, stampedLow, skippedEmpty);
    }
}
