package com.organizer3.repository;

/**
 * Thrown when a destructive repository operation detects that it is about to delete an
 * implausibly large number of rows — almost certainly the symptom of a bug in a predicate
 * elsewhere rather than a legitimate cleanup. Callers should log loudly and refuse to
 * proceed rather than silently applying the cascade.
 *
 * <p>Introduced after the 2026-04-23 incident where a SQL type-mismatch bug wiped every
 * {@code title_locations} row, which would then cascade through {@code deleteOrphaned()}
 * on the next sync and drop every title and its covers. A guard on the orphan count stops
 * the amplifier before data is lost.
 */
public final class CatastrophicDeleteException extends RuntimeException {

    private final int wouldDelete;
    private final int total;
    private final int threshold;

    public CatastrophicDeleteException(String operation, int wouldDelete, int total, int threshold) {
        super("Refusing " + operation + ": would delete " + wouldDelete + " of " + total
                + " rows (threshold=" + threshold + "). This usually indicates a bug in the"
                + " predicate that selected rows for deletion, not a real cleanup."
                + " Investigate before re-running.");
        this.wouldDelete = wouldDelete;
        this.total = total;
        this.threshold = threshold;
    }

    public int wouldDelete() { return wouldDelete; }
    public int total() { return total; }
    public int threshold() { return threshold; }
}
