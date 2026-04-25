package com.organizer3.javdb.enrichment;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.Jdbi;

/**
 * Applies auto-promote rules from javdb staging to canonical tables.
 *
 * <p>Rules (spec §Hybrid import):
 * <ul>
 *   <li>{@code titles.title_original} — when NULL, fill from javdb_title_staging.</li>
 *   <li>{@code titles.release_date}   — when NULL, fill from javdb_title_staging.</li>
 *   <li>{@code actresses.stage_name}  — when NULL, fill from the first cast_json entry
 *       whose javdb slug matches the actress's javdb_actress_staging row.</li>
 * </ul>
 * All three rules are idempotent and never overwrite an existing non-null value.
 */
@Slf4j
@RequiredArgsConstructor
public class AutoPromoter {

    private final Jdbi jdbi;

    /**
     * Called after a {@code fetch_title} job completes.
     * Promotes title_original and release_date for the title, then tries stage_name for the actress.
     */
    public void promoteFromTitle(long titleId, long actressId) {
        int titleOriginalUpdated = jdbi.withHandle(h -> h.createUpdate("""
                UPDATE titles
                SET title_original = (SELECT title_original FROM javdb_title_staging WHERE title_id = :titleId)
                WHERE id = :titleId AND title_original IS NULL
                  AND (SELECT title_original FROM javdb_title_staging WHERE title_id = :titleId) IS NOT NULL
                """)
                .bind("titleId", titleId)
                .execute());

        int releaseDateUpdated = jdbi.withHandle(h -> h.createUpdate("""
                UPDATE titles
                SET release_date = (SELECT release_date FROM javdb_title_staging WHERE title_id = :titleId)
                WHERE id = :titleId AND release_date IS NULL
                  AND (SELECT release_date FROM javdb_title_staging WHERE title_id = :titleId) IS NOT NULL
                """)
                .bind("titleId", titleId)
                .execute());

        if (titleOriginalUpdated > 0) log.info("javdb: promoted title_original for title {}", titleId);
        if (releaseDateUpdated > 0)   log.info("javdb: promoted release_date for title {}", titleId);

        promoteActressStageName(actressId);
    }

    /**
     * Called after a {@code fetch_actress_profile} job completes, and after every title fetch.
     * Promotes stage_name for the actress from a cast_json entry whose slug matches her staging slug.
     */
    public void promoteActressStageName(long actressId) {
        int updated = jdbi.withHandle(h -> h.createUpdate("""
                UPDATE actresses
                SET stage_name = (
                    SELECT json_extract(je.value, '$.name')
                    FROM title_actresses ta
                    JOIN javdb_title_staging jts ON jts.title_id = ta.title_id
                    JOIN javdb_actress_staging jas ON jas.actress_id = :actressId
                    JOIN json_each(jts.cast_json) je
                        ON json_extract(je.value, '$.slug') = jas.javdb_slug
                    WHERE ta.actress_id = :actressId
                      AND jts.cast_json IS NOT NULL
                      AND jas.javdb_slug IS NOT NULL
                    LIMIT 1
                )
                WHERE id = :actressId AND stage_name IS NULL
                  AND EXISTS (
                    SELECT 1
                    FROM title_actresses ta
                    JOIN javdb_title_staging jts ON jts.title_id = ta.title_id
                    JOIN javdb_actress_staging jas ON jas.actress_id = :actressId
                    JOIN json_each(jts.cast_json) je
                        ON json_extract(je.value, '$.slug') = jas.javdb_slug
                    WHERE ta.actress_id = :actressId
                      AND jts.cast_json IS NOT NULL
                      AND jas.javdb_slug IS NOT NULL
                )
                """)
                .bind("actressId", actressId)
                .execute());

        if (updated > 0) log.info("javdb: promoted stage_name for actress {}", actressId);
    }
}
