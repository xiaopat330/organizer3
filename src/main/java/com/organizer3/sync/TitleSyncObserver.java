package com.organizer3.sync;

/**
 * Observer notified when sync writes to an existing title row.
 *
 * <p>Implementations may, for example, mark an active draft as having an upstream
 * change (Phase 5 — Draft Mode). The observer is called <em>after</em>
 * {@code titleRepo.findOrCreateByCode} returns a title that was already present in
 * the database — i.e., a rediscovery, not an INSERT. New titles never trigger the hook.
 *
 * <p>Implementations must be <em>best-effort</em>: any exception thrown from
 * {@link #onTitleSyncWrite(long)} is caught by the caller and logged; it must
 * never propagate and block the sync pipeline.
 *
 * <p>See spec/PROPOSAL_DRAFT_MODE.md §9.1.
 */
public interface TitleSyncObserver {

    /** Fired when sync rediscovers an existing title (UPDATE path, not INSERT). */
    void onTitleSyncWrite(long titleId);

    /** No-op singleton for callers that don't need the hook (tests, legacy paths). */
    TitleSyncObserver NO_OP = titleId -> { /* intentionally empty */ };
}
