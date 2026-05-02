package com.organizer3.javdb.draft;

/**
 * Thrown when a requested draft does not exist.
 *
 * <p>Maps to HTTP 404 in route handlers.
 *
 * <p>See spec/PROPOSAL_DRAFT_MODE.md §4.1.
 */
public class DraftNotFoundException extends RuntimeException {

    private final long draftId;

    public DraftNotFoundException(long draftId) {
        super("Draft not found: id=" + draftId);
        this.draftId = draftId;
    }

    public long getDraftId() {
        return draftId;
    }
}
