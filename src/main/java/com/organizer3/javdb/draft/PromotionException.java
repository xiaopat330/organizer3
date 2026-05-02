package com.organizer3.javdb.draft;

/**
 * Thrown when a draft promotion fails at the transaction level.
 *
 * <p>Distinct from {@link PreFlightFailedException} (pre-flight check failures)
 * and {@link OptimisticLockException} (concurrent modification). This exception
 * represents unexpected errors during the promotion transaction itself — e.g.,
 * a filesystem failure during cover copy, or a database error mid-transaction.
 *
 * <p>See spec/PROPOSAL_DRAFT_MODE.md §4.2 and §4.3.
 */
public class PromotionException extends RuntimeException {

    private final String code;

    public PromotionException(String code, String message) {
        super(message);
        this.code = code;
    }

    public PromotionException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    /** Structured error code for the client (e.g. {@code "cover_copy_failed"}). */
    public String getCode() {
        return code;
    }
}
