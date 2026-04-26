package com.organizer3.javdb.enrichment;

import com.organizer3.javdb.JavdbConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.Jdbi;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/**
 * Queue table operations for {@code javdb_enrichment_queue}.
 */
@Slf4j
@RequiredArgsConstructor
public class EnrichmentQueue {

    private final Jdbi jdbi;
    private final JavdbConfig config;

    /** Enqueues a fetch_title job. No-op if a job for this title already exists and is not cancelled/failed. */
    public void enqueueTitle(long titleId, long actressId) {
        jdbi.useHandle(h -> h.createUpdate("""
                INSERT INTO javdb_enrichment_queue
                    (job_type, target_id, actress_id, status, attempts, next_attempt_at, created_at, updated_at)
                SELECT 'fetch_title', :targetId, :actressId, 'pending', 0, :now, :now, :now
                WHERE NOT EXISTS (
                    SELECT 1 FROM javdb_enrichment_queue
                    WHERE job_type = 'fetch_title' AND target_id = :targetId
                      AND status IN ('pending', 'in_flight', 'done')
                )
                """)
                .bind("targetId",  titleId)
                .bind("actressId", actressId)
                .bind("now",       now())
                .execute());
    }

    /**
     * Force-enqueues a fetch_title job even if a done job already exists.
     * Only blocks if the title is already pending or in_flight.
     */
    public void enqueueTitleForce(long titleId, long actressId) {
        jdbi.useHandle(h -> h.createUpdate("""
                INSERT INTO javdb_enrichment_queue
                    (job_type, target_id, actress_id, status, attempts, next_attempt_at, created_at, updated_at)
                SELECT 'fetch_title', :targetId, :actressId, 'pending', 0, :now, :now, :now
                WHERE NOT EXISTS (
                    SELECT 1 FROM javdb_enrichment_queue
                    WHERE job_type = 'fetch_title' AND target_id = :targetId
                      AND status IN ('pending', 'in_flight')
                )
                """)
                .bind("targetId",  titleId)
                .bind("actressId", actressId)
                .bind("now",       now())
                .execute());
    }

    /** Enqueues a fetch_actress_profile job if no active/done one already exists. */
    public void enqueueActressProfile(long actressId) {
        jdbi.useHandle(h -> h.createUpdate("""
                INSERT INTO javdb_enrichment_queue
                    (job_type, target_id, actress_id, status, attempts, next_attempt_at, created_at, updated_at)
                SELECT 'fetch_actress_profile', :actressId, :actressId, 'pending', 0, :now, :now, :now
                WHERE NOT EXISTS (
                    SELECT 1 FROM javdb_enrichment_queue
                    WHERE job_type = 'fetch_actress_profile' AND actress_id = :actressId
                      AND status IN ('pending', 'in_flight', 'done')
                )
                """)
                .bind("actressId", actressId)
                .bind("now",       now())
                .execute());
    }

    /** Force-enqueues a fetch_actress_profile job even if a done job already exists. */
    public void enqueueActressProfileForce(long actressId) {
        jdbi.useHandle(h -> h.createUpdate("""
                INSERT INTO javdb_enrichment_queue
                    (job_type, target_id, actress_id, status, attempts, next_attempt_at, created_at, updated_at)
                SELECT 'fetch_actress_profile', :actressId, :actressId, 'pending', 0, :now, :now, :now
                WHERE NOT EXISTS (
                    SELECT 1 FROM javdb_enrichment_queue
                    WHERE job_type = 'fetch_actress_profile' AND actress_id = :actressId
                      AND status IN ('pending', 'in_flight')
                )
                """)
                .bind("actressId", actressId)
                .bind("now",       now())
                .execute());
    }

    /**
     * Atomically claims the next eligible pending job by setting it to in_flight.
     *
     * <p>Only picks jobs where {@code next_attempt_at <= now}.
     */
    public Optional<EnrichmentJob> claimNextJob() {
        return jdbi.inTransaction(h -> {
            Optional<Long> id = h.createQuery("""
                    SELECT id FROM javdb_enrichment_queue
                    WHERE status = 'pending' AND next_attempt_at <= :now
                    ORDER BY next_attempt_at ASC
                    LIMIT 1
                    """)
                    .bind("now", now())
                    .mapTo(Long.class)
                    .findOne();

            if (id.isEmpty()) return Optional.empty();

            h.createUpdate("""
                    UPDATE javdb_enrichment_queue
                    SET status = 'in_flight', updated_at = :now
                    WHERE id = :id
                    """)
                    .bind("id",  id.get())
                    .bind("now", now())
                    .execute();

            return h.createQuery("SELECT * FROM javdb_enrichment_queue WHERE id = :id")
                    .bind("id", id.get())
                    .map(this::mapJob)
                    .findOne();
        });
    }

    public void markDone(long id) {
        jdbi.useHandle(h -> h.createUpdate("""
                UPDATE javdb_enrichment_queue SET status = 'done', updated_at = :now WHERE id = :id
                """)
                .bind("id", id)
                .bind("now", now())
                .execute());
    }

    /**
     * Records a transient failure. Applies exponential backoff; promotes to permanent
     * 'failed' once max attempts is reached.
     */
    public void markAttemptFailed(long id, String error) {
        jdbi.useHandle(h -> {
            int attempts = h.createQuery("SELECT attempts FROM javdb_enrichment_queue WHERE id = :id")
                    .bind("id", id).mapTo(Integer.class).one();

            int newAttempts = attempts + 1;
            int maxAttempts = config.maxAttemptsOrDefault();
            int[] backoff = config.backoffMinutesOrDefault();

            if (newAttempts >= maxAttempts) {
                h.createUpdate("""
                        UPDATE javdb_enrichment_queue
                        SET status = 'failed', attempts = :attempts, last_error = :error, updated_at = :now
                        WHERE id = :id
                        """)
                        .bind("id",       id)
                        .bind("attempts", newAttempts)
                        .bind("error",    error)
                        .bind("now",      now())
                        .execute();
            } else {
                int backoffIdx = Math.min(newAttempts - 1, backoff.length - 1);
                String nextAttempt = Instant.now().plus(backoff[backoffIdx], ChronoUnit.MINUTES).toString();
                h.createUpdate("""
                        UPDATE javdb_enrichment_queue
                        SET status = 'pending', attempts = :attempts, last_error = :error,
                            next_attempt_at = :nextAttempt, updated_at = :now
                        WHERE id = :id
                        """)
                        .bind("id",          id)
                        .bind("attempts",    newAttempts)
                        .bind("error",       error)
                        .bind("nextAttempt", nextAttempt)
                        .bind("now",         now())
                        .execute();
            }
        });
    }

    /** Permanently fails a job (parse error, 404 not found, etc.). Does not consume retry attempts. */
    public void markPermanentlyFailed(long id, String error) {
        jdbi.useHandle(h -> h.createUpdate("""
                UPDATE javdb_enrichment_queue
                SET status = 'failed', last_error = :error, updated_at = :now
                WHERE id = :id
                """)
                .bind("id",    id)
                .bind("error", error)
                .bind("now",   now())
                .execute());
    }

    /**
     * Releases a job from in_flight back to pending without burning the attempt count.
     * Used when a 429 rate-limit is hit — the job is re-tried after the pause.
     */
    public void releaseToRetry(long id) {
        jdbi.useHandle(h -> h.createUpdate("""
                UPDATE javdb_enrichment_queue
                SET status = 'pending', updated_at = :now
                WHERE id = :id
                """)
                .bind("id",  id)
                .bind("now", now())
                .execute());
    }

    /**
     * Resets ALL in_flight jobs to pending on startup. Any job still in_flight at boot was
     * orphaned by the previous process dying — no worker is executing it, so the time-based
     * stall guard used by {@link #resetStuckInFlightJobs} is irrelevant here.
     */
    public int resetOrphanedInFlightJobs() {
        int count = jdbi.withHandle(h -> h.createUpdate("""
                UPDATE javdb_enrichment_queue
                SET status = 'pending', updated_at = :now
                WHERE status = 'in_flight'
                """)
                .bind("now", now())
                .execute());
        if (count > 0) {
            log.warn("javdb: reset {} orphaned in_flight jobs on startup", count);
        }
        return count;
    }

    /** Resets jobs stuck in in_flight for longer than {@code stallMinutes}. */
    public void resetStuckInFlightJobs(int stallMinutes) {
        String threshold = Instant.now().minus(stallMinutes, ChronoUnit.MINUTES).toString();
        int count = jdbi.withHandle(h -> h.createUpdate("""
                UPDATE javdb_enrichment_queue
                SET status = 'pending', updated_at = :now
                WHERE status = 'in_flight' AND updated_at <= :threshold
                """)
                .bind("threshold", threshold)
                .bind("now",       now())
                .execute());
        if (count > 0) {
            log.warn("javdb: reset {} stuck in_flight jobs", count);
        }
    }

    public void cancelForActress(long actressId) {
        jdbi.useHandle(h -> h.createUpdate("""
                UPDATE javdb_enrichment_queue
                SET status = 'cancelled', updated_at = :now
                WHERE actress_id = :actressId AND status = 'pending'
                """)
                .bind("actressId", actressId)
                .bind("now",       now())
                .execute());
    }

    public void cancelAll() {
        jdbi.useHandle(h -> h.createUpdate("""
                UPDATE javdb_enrichment_queue
                SET status = 'cancelled', updated_at = :now
                WHERE status = 'pending'
                """)
                .bind("now", now())
                .execute());
    }

    public int countPending() {
        return jdbi.withHandle(h -> h.createQuery(
                "SELECT COUNT(*) FROM javdb_enrichment_queue WHERE status IN ('pending', 'in_flight')")
                .mapTo(Integer.class).one());
    }

    /** Returns all failed jobs for the given actress, ordered by updated_at desc. */
    public List<EnrichmentJob> listFailedForActress(long actressId) {
        return jdbi.withHandle(h -> h.createQuery("""
                SELECT * FROM javdb_enrichment_queue
                WHERE actress_id = :actressId AND status = 'failed'
                ORDER BY updated_at DESC
                """)
                .bind("actressId", actressId)
                .map(this::mapJob)
                .list());
    }

    /** Resets all failed jobs for the actress back to pending so they will be retried. */
    public void resetFailedForActress(long actressId) {
        jdbi.useHandle(h -> h.createUpdate("""
                UPDATE javdb_enrichment_queue
                SET status = 'pending', next_attempt_at = :now, updated_at = :now
                WHERE actress_id = :actressId AND status = 'failed'
                """)
                .bind("actressId", actressId)
                .bind("now",       now())
                .execute());
    }

    public int countPendingForActress(long actressId) {
        return jdbi.withHandle(h -> h.createQuery("""
                SELECT COUNT(*) FROM javdb_enrichment_queue
                WHERE actress_id = :actressId AND status IN ('pending', 'in_flight')
                """)
                .bind("actressId", actressId)
                .mapTo(Integer.class).one());
    }

    private EnrichmentJob mapJob(java.sql.ResultSet rs, org.jdbi.v3.core.statement.StatementContext ctx)
            throws java.sql.SQLException {
        return new EnrichmentJob(
                rs.getLong("id"),
                rs.getString("job_type"),
                rs.getLong("target_id"),
                rs.getLong("actress_id"),
                rs.getString("status"),
                rs.getInt("attempts"),
                rs.getString("next_attempt_at"),
                rs.getString("last_error"),
                rs.getString("created_at"),
                rs.getString("updated_at")
        );
    }

    private String now() {
        return Instant.now().toString();
    }
}
