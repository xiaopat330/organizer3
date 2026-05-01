package com.organizer3.media;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StreamActivityTrackerTest {

    @Test
    void isPlayingReturnsFalseWithNoActivity() {
        StreamActivityTracker tracker = new StreamActivityTracker();
        assertFalse(tracker.isPlaying(30_000));
    }

    @Test
    void isPlayingReturnsTrueImmediatelyAfterBump() {
        StreamActivityTracker tracker = new StreamActivityTracker();
        tracker.bump();
        assertTrue(tracker.isPlaying(30_000));
    }

    @Test
    void isPlayingReturnsFalseWhenWindowExpired() throws InterruptedException {
        StreamActivityTracker tracker = new StreamActivityTracker();
        tracker.bump();
        Thread.sleep(10);
        // Window of 5ms should be expired after 10ms sleep
        assertFalse(tracker.isPlaying(5));
    }

    @Test
    void isPlayingReturnsTrueWhenWithinWindow() throws InterruptedException {
        StreamActivityTracker tracker = new StreamActivityTracker();
        tracker.bump();
        Thread.sleep(5);
        // Window of 5000ms — still active
        assertTrue(tracker.isPlaying(5_000));
    }

    @Test
    void bumpUpdatesLastActivityTime() throws InterruptedException {
        StreamActivityTracker tracker = new StreamActivityTracker();
        tracker.bump();
        Thread.sleep(50);
        assertFalse(tracker.isPlaying(20)); // expired

        tracker.bump(); // re-bump
        assertTrue(tracker.isPlaying(5_000)); // active again
    }
}
