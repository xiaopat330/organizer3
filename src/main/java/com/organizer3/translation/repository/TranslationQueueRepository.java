package com.organizer3.translation.repository;

import com.organizer3.translation.TranslationQueueRow;

import java.util.List;
import java.util.Optional;

/**
 * Persistence operations for the {@code translation_queue} table.
 *
 * <p>The queue implements a single-worker consume pattern:
 * <ol>
 *   <li>Worker calls {@link #claimNext()} — atomically transitions one {@code pending} row to
 *       {@code in_flight} and returns it. Returns empty if nothing is pending, or if a concurrent
 *       caller won the race.</li>
 *   <li>Worker processes the row.</li>
 *   <li>On success: calls {@link #markDone}.</li>
 *   <li>On failure: calls {@link #incrementAttempt} (re-queues by leaving pending) or
 *       {@link #markFailed} when attempt limit is reached.</li>
 * </ol>
 */
public interface TranslationQueueRepository {

    /**
     * Insert a new queue row with the given status and return the assigned id.
     *
     * @param sourceText   text to translate
     * @param strategyId   the translation strategy to use
     * @param submittedAt  ISO-8601 UTC timestamp string
     * @param status       initial status — typically {@code "pending"} or {@code "done"}
     * @param callbackKind nullable callback kind identifier
     * @param callbackId   nullable catalog row id to update on success
     * @return the assigned row id
     */
    long enqueue(String sourceText,
                 long strategyId,
                 String submittedAt,
                 String status,
                 String callbackKind,
                 Long callbackId);

    /**
     * Atomically claim one {@code pending} row — marks it {@code in_flight} and returns it.
     *
     * <p>Uses {@code BEGIN IMMEDIATE} transaction semantics: reads one pending row, then
     * updates it {@code WHERE id=? AND status='pending'}. If {@code rows-affected == 0},
     * another worker raced and won; returns empty so the caller retries.
     *
     * @return the claimed row, or empty if no pending rows exist or a race was lost
     */
    Optional<TranslationQueueRow> claimNext();

    /**
     * Mark the row as {@code done}.
     *
     * @param id          queue row id
     * @param completedAt ISO-8601 UTC timestamp string
     */
    void markDone(long id, String completedAt);

    /**
     * Mark the row as {@code failed} (permanent — won't be retried by the worker).
     *
     * @param id          queue row id
     * @param error       human-readable error description
     * @param completedAt ISO-8601 UTC timestamp string
     */
    void markFailed(long id, String error, String completedAt);

    /**
     * Bump {@code attempt_count}, record {@code lastError}, and reset status to {@code pending}
     * so the row will be retried.
     *
     * @param id        queue row id
     * @param lastError last error message to record
     */
    void incrementAttempt(long id, String lastError);

    /**
     * Return all queue rows whose status is {@code in_flight} and whose {@code started_at}
     * is older than {@code thresholdSeconds} ago. Used by the sweeper to detect stuck workers.
     */
    List<TranslationQueueRow> findStuckInFlight(int thresholdSeconds);

    /**
     * Reset all stuck {@code in_flight} rows (older than threshold) back to {@code pending}.
     *
     * @return number of rows reset
     */
    int resetStuckToRetry(int thresholdSeconds);

    /**
     * Count rows by status. Used for operational stats.
     */
    int countByStatus(String status);
}
