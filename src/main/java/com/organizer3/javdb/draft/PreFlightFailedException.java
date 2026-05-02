package com.organizer3.javdb.draft;

import java.util.List;

/**
 * Thrown when a draft's pre-flight check fails.
 *
 * <p>Carries the structured list of error codes returned by
 * {@link DraftPromotionService#preflight}. Distinct from
 * {@link OptimisticLockException} (concurrent modification conflict)
 * and {@link PromotionException} (transaction-level failure).
 *
 * <p>See spec/PROPOSAL_DRAFT_MODE.md §4.1.
 */
public class PreFlightFailedException extends RuntimeException {

    private final List<String> errors;

    public PreFlightFailedException(List<String> errors) {
        super("Pre-flight failed: " + errors);
        this.errors = List.copyOf(errors);
    }

    /** The structured error codes from the pre-flight check. */
    public List<String> getErrors() {
        return errors;
    }
}
