package com.organizer3.media;

import lombok.RequiredArgsConstructor;
import lombok.Value;
import org.jdbi.v3.core.Jdbi;

import java.util.List;

/**
 * Ranks videos that are candidates for background thumbnail pre-generation.
 *
 * <p>Scoring reflects user attention (favorites, bookmarks, visit recency). Titles
 * with no attention signal are excluded — the point of the worker is to pre-warm
 * content the user is likely to revisit, not to pre-warm the entire library.
 *
 * <p>The returned score is informational (for logging / status) — callers should
 * treat the iteration order as the source of truth.
 */
@RequiredArgsConstructor
public class BackgroundThumbnailQueue {

    private final Jdbi jdbi;

    /** Query result: just enough to drive generation for one video. */
    @Value
    public static class Candidate {
        long    videoId;
        long    titleId;
        String  titleCode;
        String  videoFilename;
        double  score;
    }

    /**
     * Top-N candidate videos, ordered by descending attention score.
     *
     * <p>Score formula (sum of active signals):
     * <ul>
     *   <li>title bookmark: +1000</li>
     *   <li>title favorite: +500</li>
     *   <li>any favorited actress on title: +500</li>
     *   <li>title last_visited_at: exp decay, 30-day half-life, 0..300</li>
     *   <li>actress last_visited_at (max across linked): exp decay, 30-day half-life, 0..200</li>
     *   <li>title visit_count: log-scaled, 0..100</li>
     *   <li>recently added (video.last_seen_at within 14 days): linear 0..50</li>
     * </ul>
     * Score = 0 rows are filtered out.
     */
    public List<Candidate> topCandidates(int limit) {
        // SQLite date math: julianday('now') - julianday(x) yields fractional days.
        // exp(-days / half) for decay; we approximate exp via SQL-friendly math using the
        // identity exp(-d/30) = pow(2, -d/30) * constant... actually sqlite has no pow/exp.
        // Simpler: use a piecewise linear approximation that's good enough for ranking.
        //
        //   days <= 1   -> factor = 1.0
        //   1..30       -> factor = 1.0 - 0.5 * (days-1)/29     (down to 0.5 at 30d)
        //   30..90      -> factor = 0.5 - 0.5 * (days-30)/60    (down to 0.0 at 90d)
        //   >90         -> factor = 0.0
        //
        // title_visit_count log scale: min(ln(1+n) * 25, 100). Approximate with
        //   CASE WHEN n<=0 THEN 0 WHEN n<=1 THEN 25 WHEN n<=3 THEN 50 WHEN n<=10 THEN 75 ELSE 100 END
        String sql = """
            WITH title_actress_signal AS (
                SELECT ta.title_id,
                       MAX(CASE WHEN a.favorite=1 THEN 1 ELSE 0 END)  AS any_fav_actress,
                       MAX(julianday('now') - julianday(a.last_visited_at)) AS max_actress_age
                FROM title_actresses ta
                JOIN actresses a ON a.id = ta.actress_id
                GROUP BY ta.title_id
            ),
            per_title_score AS (
                SELECT t.id AS title_id,
                       t.code AS title_code,
                       (CASE WHEN t.bookmark = 1 THEN 1000 ELSE 0 END)
                     + (CASE WHEN t.favorite = 1 THEN  500 ELSE 0 END)
                     + (CASE WHEN COALESCE(tas.any_fav_actress,0) = 1 THEN 500 ELSE 0 END)
                     + (CASE
                          WHEN t.last_visited_at IS NULL THEN 0
                          ELSE 300.0 * (
                            CASE
                              WHEN (julianday('now') - julianday(t.last_visited_at)) <= 1  THEN 1.0
                              WHEN (julianday('now') - julianday(t.last_visited_at)) <= 30 THEN 1.0 - 0.5 * ((julianday('now') - julianday(t.last_visited_at)) - 1) / 29.0
                              WHEN (julianday('now') - julianday(t.last_visited_at)) <= 90 THEN 0.5 - 0.5 * ((julianday('now') - julianday(t.last_visited_at)) - 30) / 60.0
                              ELSE 0.0
                            END)
                        END)
                     + (CASE
                          WHEN tas.max_actress_age IS NULL THEN 0
                          ELSE 200.0 * (
                            CASE
                              WHEN tas.max_actress_age <= 1  THEN 1.0
                              WHEN tas.max_actress_age <= 30 THEN 1.0 - 0.5 * (tas.max_actress_age - 1) / 29.0
                              WHEN tas.max_actress_age <= 90 THEN 0.5 - 0.5 * (tas.max_actress_age - 30) / 60.0
                              ELSE 0.0
                            END)
                        END)
                     + (CASE
                          WHEN t.visit_count <= 0  THEN 0
                          WHEN t.visit_count <= 1  THEN 25
                          WHEN t.visit_count <= 3  THEN 50
                          WHEN t.visit_count <= 10 THEN 75
                          ELSE 100
                        END)
                     AS attention_score
                FROM titles t
                LEFT JOIN title_actress_signal tas ON tas.title_id = t.id
            )
            SELECT v.id AS video_id,
                   v.title_id,
                   pts.title_code,
                   v.filename AS video_filename,
                   pts.attention_score
                 + (CASE
                      WHEN v.last_seen_at IS NULL THEN 0
                      WHEN (julianday('now') - julianday(v.last_seen_at)) > 14 THEN 0
                      ELSE 50.0 * (1.0 - (julianday('now') - julianday(v.last_seen_at)) / 14.0)
                    END)
                   AS score
            FROM videos v
            JOIN per_title_score pts ON pts.title_id = v.title_id
            WHERE pts.attention_score > 0
            ORDER BY score DESC, v.id ASC
            LIMIT :limit
            """;

        return jdbi.withHandle(h -> h.createQuery(sql)
                .bind("limit", limit)
                .map((rs, ctx) -> new Candidate(
                        rs.getLong("video_id"),
                        rs.getLong("title_id"),
                        rs.getString("title_code"),
                        rs.getString("video_filename"),
                        rs.getDouble("score")))
                .list());
    }
}
