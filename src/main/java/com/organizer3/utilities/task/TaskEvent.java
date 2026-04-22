package com.organizer3.utilities.task;

import java.time.Instant;

/**
 * Structured event emitted by a running task. Events are serialized to the frontend (via SSE)
 * and stored in the {@link TaskRun} history so a client arriving late can replay to current state.
 *
 * <p>The sealed hierarchy keeps the wire format exhaustive: adding a new event kind requires
 * a code change, which is the intent — the UI renders events by kind.
 */
public sealed interface TaskEvent {

    Instant at();

    /** Task has started. Emitted exactly once at the very beginning. */
    record TaskStarted(Instant at) implements TaskEvent {}

    /** A named phase of a multi-phase task has begun. */
    record PhaseStarted(Instant at, String phaseId, String label) implements TaskEvent {}

    /** Optional progress tick for a running phase. {@code total} may be {@code -1} for indeterminate. */
    record PhaseProgress(Instant at, String phaseId, int current, int total, String detail)
            implements TaskEvent {}

    /** A line of raw output from the underlying command. */
    record PhaseLog(Instant at, String phaseId, String line) implements TaskEvent {}

    /** A phase has ended. {@code status} is either {@code "ok"} or {@code "failed"}. */
    record PhaseEnded(Instant at, String phaseId, String status, long durationMs, String summary)
            implements TaskEvent {}

    /**
     * Task has ended. {@code status} is {@code "ok"}, {@code "failed"}, or {@code "partial"}.
     * {@code summary} is a human-readable one-liner for the UI banner.
     */
    record TaskEnded(Instant at, String status, String summary) implements TaskEvent {}
}
