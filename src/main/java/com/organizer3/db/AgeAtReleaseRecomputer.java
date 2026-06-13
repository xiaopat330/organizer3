package com.organizer3.db;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.Jdbi;

import java.util.List;

/**
 * Recomputes {@code title_actresses.age_at_release} — the credited actress's age in
 * whole years on the title's release date — using the birthday-aware formula:
 *
 * <pre>
 *   (strftime('%Y%m%d', release) - strftime('%Y%m%d', dob)) / 10000
 * </pre>
 *
 * <p>Release-date precedence: {@code title_javdb_enrichment.release_date} (canonical)
 * then {@code titles.release_date} (fallback); empty strings are treated as NULL.
 *
 * <p>The {@link #recomputeAll()} update is a full-table idempotent overwrite: rows that
 * become non-computable (missing DOB or release date) are explicitly set to NULL so stale
 * values are never left behind.
 */
@Slf4j
@RequiredArgsConstructor
public class AgeAtReleaseRecomputer {

    private static final String RECOMPUTE_SQL = """
            UPDATE title_actresses SET age_at_release = (
                SELECT CASE
                    WHEN a.date_of_birth IS NULL OR a.date_of_birth = '' THEN NULL
                    WHEN COALESCE(NULLIF(e.release_date,''), NULLIF(t.release_date,'')) IS NULL THEN NULL
                    ELSE (CAST(strftime('%Y%m%d', COALESCE(NULLIF(e.release_date,''), NULLIF(t.release_date,''))) AS INTEGER)
                        - CAST(strftime('%Y%m%d', a.date_of_birth) AS INTEGER)) / 10000
                END
                FROM titles t
                JOIN actresses a ON a.id = title_actresses.actress_id
                LEFT JOIN title_javdb_enrichment e ON e.title_id = t.id
                WHERE t.id = title_actresses.title_id
            )""";

    /**
     * SQL that counts rows whose stored {@code age_at_release} differs from the freshly
     * derived value (including NULL → non-NULL and non-NULL → NULL transitions).
     */
    private static final String COUNT_DIFF_SQL = """
            SELECT COUNT(*) FROM title_actresses ta
            LEFT JOIN titles t ON t.id = ta.title_id
            LEFT JOIN actresses a ON a.id = ta.actress_id
            LEFT JOIN title_javdb_enrichment e ON e.title_id = t.id
            WHERE
                CASE
                    WHEN a.date_of_birth IS NULL OR a.date_of_birth = '' THEN NULL
                    WHEN COALESCE(NULLIF(e.release_date,''), NULLIF(t.release_date,'')) IS NULL THEN NULL
                    ELSE (CAST(strftime('%Y%m%d', COALESCE(NULLIF(e.release_date,''), NULLIF(t.release_date,''))) AS INTEGER)
                        - CAST(strftime('%Y%m%d', a.date_of_birth) AS INTEGER)) / 10000
                END IS NOT ta.age_at_release
                OR (
                    CASE
                        WHEN a.date_of_birth IS NULL OR a.date_of_birth = '' THEN NULL
                        WHEN COALESCE(NULLIF(e.release_date,''), NULLIF(t.release_date,'')) IS NULL THEN NULL
                        ELSE (CAST(strftime('%Y%m%d', COALESCE(NULLIF(e.release_date,''), NULLIF(t.release_date,''))) AS INTEGER)
                            - CAST(strftime('%Y%m%d', a.date_of_birth) AS INTEGER)) / 10000
                    END IS NULL AND ta.age_at_release IS NOT NULL
                )
                OR (
                    CASE
                        WHEN a.date_of_birth IS NULL OR a.date_of_birth = '' THEN NULL
                        WHEN COALESCE(NULLIF(e.release_date,''), NULLIF(t.release_date,'')) IS NULL THEN NULL
                        ELSE (CAST(strftime('%Y%m%d', COALESCE(NULLIF(e.release_date,''), NULLIF(t.release_date,''))) AS INTEGER)
                            - CAST(strftime('%Y%m%d', a.date_of_birth) AS INTEGER)) / 10000
                    END IS NOT NULL AND ta.age_at_release IS NULL
                )""";

    private final Jdbi jdbi;

    /**
     * Recomputes {@code age_at_release} for every row in {@code title_actresses} in a single
     * transaction.
     *
     * @return the number of rows whose stored value actually changed (not simply "rows touched")
     */
    public int recomputeAll() {
        return jdbi.inTransaction(h -> {
            int changed = h.createQuery(COUNT_DIFF_SQL)
                    .mapTo(Integer.class)
                    .one();
            h.execute(RECOMPUTE_SQL);
            log.info("age_at_release recompute complete: {} rows changed", changed);
            return changed;
        });
    }

    /**
     * Returns credit rows where the computed age is outside the plausible range
     * [18, 70] — useful for a repair / audit tool.
     *
     * @return list of implausible rows (title_id, actress_id, age_at_release)
     */
    public List<ImplausibleRow> findImplausible() {
        return jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT title_id, actress_id, age_at_release
                        FROM title_actresses
                        WHERE age_at_release IS NOT NULL
                          AND (age_at_release < 18 OR age_at_release > 70)
                        ORDER BY title_id, actress_id""")
                        .map((rs, ctx) -> new ImplausibleRow(
                                rs.getLong("title_id"),
                                rs.getLong("actress_id"),
                                rs.getInt("age_at_release")))
                        .list());
    }

    /**
     * A single implausible credit row returned by {@link #findImplausible()}.
     */
    public record ImplausibleRow(long titleId, long actressId, int age) {}
}
