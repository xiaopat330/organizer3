package com.organizer3.javdb.enrichment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;

import java.util.List;
import java.util.Map;

/**
 * Append-only audit log for {@code title_javdb_enrichment} mutations.
 *
 * <p>Every overwrite or delete snapshots the prior row into
 * {@code title_javdb_enrichment_history} so the forensic trail survives
 * enrichment overwrites and even title deletion (no FK to {@code titles}).
 */
@Slf4j
@RequiredArgsConstructor
public class EnrichmentHistoryRepository {

    private final Jdbi jdbi;
    private final ObjectMapper json;

    /**
     * Snapshots the current {@code title_javdb_enrichment} row (if any) into the
     * history table. Must be called <em>before</em> the destructive operation so the
     * prior state is captured. No-op when no enrichment row exists for the title.
     *
     * @param titleId the title whose enrichment row may be about to change
     * @param reason  why the snapshot is being taken (e.g. "enrichment_runner",
     *                "cleanup", "title_deleted")
     * @param h       the open JDBI handle — caller owns the transaction boundary
     */
    public void appendIfExists(long titleId, String reason, Handle h) {
        // One query joining titles so we snapshot title_code even if the title is
        // deleted in the same transaction after this call.
        Map<String, Object> row = h.createQuery("""
                SELECT e.*, t.code AS _title_code
                FROM title_javdb_enrichment e
                JOIN titles t ON t.id = e.title_id
                WHERE e.title_id = :titleId
                """)
                .bind("titleId", titleId)
                .mapToMap()
                .findOne()
                .orElse(null);

        if (row == null) {
            return; // no enrichment row — first-write case, nothing to snapshot
        }

        String titleCode = (String) row.remove("_title_code");
        String priorSlug = (String) row.get("javdb_slug");
        String priorPayload = serialize(row);

        h.createUpdate("""
                INSERT INTO title_javdb_enrichment_history
                    (title_id, title_code, reason, prior_slug, prior_payload)
                VALUES (:titleId, :titleCode, :reason, :priorSlug, :priorPayload)
                """)
                .bind("titleId",      titleId)
                .bind("titleCode",    titleCode)
                .bind("reason",       reason)
                .bind("priorSlug",    priorSlug)
                .bind("priorPayload", priorPayload)
                .execute();

        log.debug("enrichment-history: snapshotted prior row for title {} (reason={})", titleId, reason);
    }

    /** Number of history rows for a title — primarily for tests and future 2A UI. */
    public int countForTitle(long titleId) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM title_javdb_enrichment_history WHERE title_id = :id")
                        .bind("id", titleId)
                        .mapTo(Integer.class)
                        .one());
    }

    /** Most-recent history rows for a title, ordered by {@code changed_at} DESC. */
    public List<HistoryRow> recentForTitle(long titleId, int limit) {
        return jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT id, title_id, title_code, changed_at, reason, prior_slug, prior_payload
                        FROM title_javdb_enrichment_history
                        WHERE title_id = :id
                        ORDER BY changed_at DESC
                        LIMIT :limit
                        """)
                        .bind("id",    titleId)
                        .bind("limit", limit)
                        .map((rs, ctx) -> new HistoryRow(
                                rs.getLong("id"),
                                rs.getLong("title_id"),
                                rs.getString("title_code"),
                                rs.getString("changed_at"),
                                rs.getString("reason"),
                                rs.getString("prior_slug"),
                                rs.getString("prior_payload")))
                        .list());
    }

    private String serialize(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize enrichment history payload", e);
        }
    }

    public record HistoryRow(
            long id,
            long titleId,
            String titleCode,
            String changedAt,
            String reason,
            String priorSlug,
            String priorPayload
    ) {}
}
