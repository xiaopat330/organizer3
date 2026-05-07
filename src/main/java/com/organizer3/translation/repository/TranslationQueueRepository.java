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

    /**
     * Mark a queue row as {@code tier_2_pending} after tier-1 refusal or sanitization.
     * The row will be picked up by the {@code Tier2BatchSweeper} when the batch threshold is met.
     *
     * @param id        queue row id
     * @param reason    short description: {@code "refused"} or {@code "sanitized"}
     * @param now       ISO-8601 UTC timestamp string (used to record last_error timestamp)
     */
    void markTier2Pending(long id, String reason, String now);

    /**
     * Count and list rows with {@code status='tier_2_pending'}, ordered by submitted_at ascending.
     */
    List<TranslationQueueRow> findTier2Pending();

    /**
     * Count rows in {@code tier_2_pending} status.
     */
    int countTier2Pending();

    /**
     * Return the submitted_at timestamp of the oldest {@code tier_2_pending} row,
     * or {@code null} if none exist.
     */
    String oldestTier2PendingSubmittedAt();

    /**
     * Return true if a non-terminal queue row exists for the given source text and strategy,
     * i.e. a row with {@code status IN ('pending', 'in_flight', 'tier_2_pending')}.
     * Used by the bulk-enqueue endpoint to avoid duplicate submissions.
     */
    boolean existsActiveForSourceAndStrategy(String sourceText, long strategyId);

    /**
     * Delete queue rows linked (via {@code source_text} + {@code strategy_id}) to cache rows
     * whose {@code failure_reason} matches exactly. Returns the number of queue rows deleted.
     * Used by the manual force-retry path: with the queue row gone, upstream sweepers see the
     * source as un-attempted and re-enqueue it on their next tick.
     */
    int deleteForCacheFailureReason(String reason);

    /**
     * Delete all queue rows for a given callback target — any status. Used by the
     * force-translate path to clear prior attempts (pending / in_flight / done / failed)
     * before re-submitting fresh work. Returns the number of rows deleted.
     */
    int deleteForCallback(String callbackKind, long callbackId);

    /**
     * Return the next {@code limit} rows with {@code status IN ('pending', 'in_flight')},
     * ordered by priority descending then submitted_at ascending (i.e. in claim order).
     * Used by the queue-preview UI panel.
     */
    List<TranslationQueueRow> findNextPending(int limit);

    /**
     * Returns {@code true} if a non-terminal queue row ({@code status IN ('pending', 'in_flight')})
     * exists for the given strategy name and source text.
     *
     * <p>Used by the stage-name-status endpoint to differentiate {@code "queued"} from {@code "missing"}.
     *
     * @param strategyName the {@code translation_strategies.name} key (e.g. {@code "label_basic"})
     * @param sourceText   the normalized source text to check
     */
    boolean hasPending(String strategyName, String sourceText);

    /**
     * Insert a new {@code pending} queue row only if no row with {@code status IN ('pending',
     * 'in_flight')} already exists for the same {@code (strategy_id, source_text)} pair.
     *
     * <p>Uses a single transaction (check-then-insert) to prevent duplicate LLM calls for the
     * same input. Terminal states ({@code done}, {@code failed}, {@code tier_2_pending}) are not
     * contention — a completed translation may be re-queued when appropriate.
     *
     * <p>If a matching {@code pending} row already exists with a lower priority, its
     * {@code priority} column is bumped to {@code max(existing, priority)}. This lets callers
     * promote a previously-enqueued row without re-inserting. The return value is still
     * {@code false} when no new row was inserted (priority bump is transparent to the caller).
     *
     * @param priority lane priority — higher values are claimed first; 0 is the normal lane;
     *                 stage-name enqueues use 10 to jump the queue
     * @return {@code true} if a new row was inserted; {@code false} if a matching non-terminal
     *         row already exists (with or without a priority bump)
     */
    boolean enqueueIfAbsent(String sourceText,
                            long strategyId,
                            String submittedAt,
                            String status,
                            String callbackKind,
                            Long callbackId,
                            int priority);

    /**
     * Delete all {@code pending} queue rows for the given {@code (strategyId, sourceText)} pair.
     * Called by the synchronous translation path after it has written the cache and suggestion
     * rows, so the background worker does not re-do the work.
     *
     * <p>Only {@code pending} rows are deleted; {@code in_flight} rows are left alone because
     * a worker may already be processing them.
     *
     * @return number of rows deleted
     */
    int deletePendingForSource(long strategyId, String sourceText);
}
