package com.organizer3.media;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class UserActivityTrackerTest {

    @Test
    void freshTrackerReportsRecentActivity() {
        UserActivityTracker t = new UserActivityTracker();
        assertTrue(t.millisSinceLast() < 1000,
                "a tracker just constructed should report ~0ms since last activity");
    }

    @Test
    void bumpResetsTheClock() throws InterruptedException {
        UserActivityTracker t = new UserActivityTracker();
        Thread.sleep(50);
        assertTrue(t.millisSinceLast() >= 50);
        t.bump();
        assertTrue(t.millisSinceLast() < 50, "bump should reset the clock");
    }

    @Test
    void isQuietThresholdBehavior() throws InterruptedException {
        UserActivityTracker t = new UserActivityTracker();
        assertFalse(t.isQuiet(10_000), "fresh tracker is not quiet on a 10s threshold");
        Thread.sleep(60);
        assertTrue(t.isQuiet(50));
    }
}
