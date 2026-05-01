package com.organizer3.avstars.repository;

import com.organizer3.avstars.model.AvScreenshotQueueRow;

import java.util.Optional;

public interface AvScreenshotQueueRepository {

    /** Inserts a PENDING row for this video. Returns true if inserted; false if already present. */
    boolean enqueueIfAbsent(long actressId, long videoId);

    /**
     * Atomically claims the oldest PENDING row: sets status=IN_PROGRESS, started_at=now.
     * Uses a single UPDATE...RETURNING statement (SQLite 3.35+) to avoid a race between
     * the startup orphan-reset and concurrent shutdown.
     */
    Optional<AvScreenshotQueueRow> claimNextPending();

    void markDone(long id);
    void markFailed(long id, String error);

    /** On startup: reset any IN_PROGRESS rows left by a prior crash back to PENDING. Returns count reset. */
    int resetOrphanedInFlightJobs();

    /** Transition this actress's PENDING rows to PAUSED. Returns rows affected. */
    int pauseActress(long actressId);

    /** Transition this actress's PAUSED rows back to PENDING. Returns rows affected. */
    int resumeActress(long actressId);

    /** Delete this actress's PENDING and PAUSED rows ("stop"). Returns rows deleted. */
    int clearForActress(long actressId);

    /** Per-actress queue stats for the progress endpoint. */
    ActressProgress progressForActress(long actressId);

    /** Total PENDING + IN_PROGRESS rows across all actresses. */
    int globalDepth();

    record ActressProgress(
            int pending,
            int inProgress,
            int paused,
            int done,
            int failed,
            int total,
            Long currentVideoId   // non-null only when this actress's row is IN_PROGRESS
    ) {}

    // TODO: add prune(Duration olderThan) for cleaning DONE/FAILED rows older than N days,
    //   plus a POST /api/av/screenshots/queue/prune route — out of scope for initial ship.
}
