package com.organizer3.media;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks the wall-clock time of the most recent user-facing request.
 *
 * <p>The web layer bumps this on every {@code /api/**} request via a Javalin
 * {@code before} hook; the background thumbnail worker reads it to decide
 * whether the system is quiet enough to run generation.
 */
public class UserActivityTracker {

    private final AtomicLong lastActivityMillis = new AtomicLong(System.currentTimeMillis());

    /** Record that the user just did something. Called from a web request handler. */
    public void bump() {
        lastActivityMillis.set(System.currentTimeMillis());
    }

    /** Milliseconds since the last bump. */
    public long millisSinceLast() {
        return System.currentTimeMillis() - lastActivityMillis.get();
    }

    /** True if at least {@code quietMillis} have passed since the last bump. */
    public boolean isQuiet(long quietMillis) {
        return millisSinceLast() >= quietMillis;
    }

    /** Wall-clock millis of the last recorded activity. For logging/status. */
    public long lastActivityMillis() {
        return lastActivityMillis.get();
    }
}
