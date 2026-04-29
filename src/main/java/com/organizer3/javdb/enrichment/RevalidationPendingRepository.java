package com.organizer3.javdb.enrichment;

import lombok.RequiredArgsConstructor;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;

import java.util.List;

/**
 * Dirty queue for priority enrichment re-validation.
 *
 * <p>Rows are enqueued by {@link JdbiJavdbActressFilmographyRepository} when drift is detected
 * (actress slug changed or filmography entry vanished while still referenced by an enriched title).
 * The {@link RevalidationService} drains the queue in FIFO order before running the safety-net
 * sweep over UNKNOWN/stale rows.
 *
 * <p>{@code INSERT OR IGNORE} on the PRIMARY KEY makes enqueue idempotent — duplicate enqueues
 * for the same title collapse naturally so the queue stays compact even under rapid re-fetches.
 */
@RequiredArgsConstructor
public class RevalidationPendingRepository {

    private final Jdbi jdbi;

    public record Pending(long titleId, String reason, String enqueuedAt) {}

    /** Enqueues a title for re-validation using its own JDBI handle. Idempotent. */
    public void enqueue(long titleId, String reason) {
        jdbi.useHandle(h -> enqueue(titleId, reason, h));
    }

    /** Enqueues a title for re-validation on the caller's handle (for use within a transaction). */
    public void enqueue(long titleId, String reason, Handle h) {
        h.createUpdate("""
                INSERT OR IGNORE INTO revalidation_pending (title_id, reason)
                VALUES (:titleId, :reason)
                """)
                .bind("titleId", titleId)
                .bind("reason",  reason)
                .execute();
    }

    /**
     * Atomically claims up to {@code limit} pending rows (FIFO) and removes them.
     * Returns the claimed rows; returns an empty list if the queue is empty.
     */
    public List<Pending> drainBatch(int limit) {
        return jdbi.inTransaction(h -> {
            List<Pending> batch = h.createQuery("""
                    SELECT title_id, reason, enqueued_at
                    FROM revalidation_pending
                    ORDER BY enqueued_at ASC
                    LIMIT :limit
                    """)
                    .bind("limit", limit)
                    .map((rs, ctx) -> new Pending(
                            rs.getLong("title_id"),
                            rs.getString("reason"),
                            rs.getString("enqueued_at")))
                    .list();

            if (!batch.isEmpty()) {
                List<Long> ids = batch.stream().map(Pending::titleId).toList();
                h.createUpdate("DELETE FROM revalidation_pending WHERE title_id IN (<ids>)")
                        .bindList("ids", ids)
                        .execute();
            }
            return batch;
        });
    }

    /** Total count of pending entries. */
    public int countPending() {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM revalidation_pending")
                        .mapTo(Integer.class).one());
    }
}
