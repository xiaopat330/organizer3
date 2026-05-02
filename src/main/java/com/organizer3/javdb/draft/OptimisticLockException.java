package com.organizer3.javdb.draft;

/**
 * Thrown when an optimistic-lock update fails because the row was modified
 * concurrently since the caller last read it.
 *
 * <p>Callers should reload the resource and retry (or surface a 409 CONFLICT
 * to the UI, as per spec §4.4).
 */
public class OptimisticLockException extends RuntimeException {

    public OptimisticLockException(String message) {
        super(message);
    }
}
