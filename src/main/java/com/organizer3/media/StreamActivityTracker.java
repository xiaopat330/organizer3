package com.organizer3.media;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Narrow playback-activity signal for the AV screenshot worker.
 *
 * <p>Bumped only from the two video-stream endpoints ({@code /api/stream/{id}} and
 * {@code /api/av/stream/{id}}) so the worker can pause between videos while a video is
 * actively playing. Deliberately separate from {@link UserActivityTracker}: the queue's
 * own 2s progress poll would keep UserActivityTracker permanently "active", preventing
 * the worker from ever running.
 */
public class StreamActivityTracker {

    private final AtomicLong lastStreamByteAt = new AtomicLong(0);

    /** Record that a stream byte was served. Called from the Javalin before-filter. */
    public void bump() {
        lastStreamByteAt.set(System.currentTimeMillis());
    }

    /** True if a stream byte was served within the last {@code withinMillis} milliseconds. */
    public boolean isPlaying(long withinMillis) {
        return System.currentTimeMillis() - lastStreamByteAt.get() < withinMillis;
    }
}
