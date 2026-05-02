package com.organizer3.javdb.draft;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link DraftGcScheduler} — primarily the initial-delay computation.
 */
class DraftGcSchedulerTest {

    private DraftGcService mockService() {
        return mock(DraftGcService.class);
    }

    private DraftGcScheduler schedulerAt(String isoInstant, int gcHourUtc) {
        Clock fixed = Clock.fixed(Instant.parse(isoInstant), ZoneOffset.UTC);
        return new DraftGcScheduler(mockService(), gcHourUtc, fixed);
    }

    // ── initial delay computation ──────────────────────────────────────────────

    @Test
    void initialDelay_whenBeforeTargetHour_isLessThan24h() {
        // 2026-01-01T01:00:00Z (1am) with gcHour=2 → ~1 hour until next 2am
        long delay = schedulerAt("2026-01-01T01:00:00Z", 2).computeInitialDelaySeconds();
        assertTrue(delay > 0, "delay must be positive");
        assertTrue(delay <= 3600 + 60, "delay from 1am to 2am should be ~1h");
    }

    @Test
    void initialDelay_whenAfterTargetHour_wrapsToNextDay() {
        // 2026-01-01T03:00:00Z (3am) with gcHour=2 → ~23 hours until next 2am
        long delay = schedulerAt("2026-01-01T03:00:00Z", 2).computeInitialDelaySeconds();
        assertTrue(delay > 0, "delay must be positive");
        // Should be roughly 23 hours.
        assertTrue(delay > 22 * 3600L && delay < 24 * 3600L,
                "delay from 3am to next 2am should be ~23h, got " + delay);
    }

    @Test
    void initialDelay_whenExactlyAtTargetHour_wrapsToNextDay() {
        // 2026-01-01T02:00:00Z exactly at 2am → schedules next day
        long delay = schedulerAt("2026-01-01T02:00:00Z", 2).computeInitialDelaySeconds();
        assertTrue(delay >= 1, "delay must be at least 1 second");
        // Should be ~24h (next day's 2am)
        assertTrue(delay >= 23 * 3600L && delay <= 24 * 3600L,
                "should schedule for next day's 2am, got " + delay);
    }

    @Test
    void initialDelay_isAtLeastOneSecond() {
        // Even edge cases should never return 0 or negative.
        for (String iso : new String[]{
                "2026-01-01T00:00:00Z",
                "2026-01-01T02:00:00Z",
                "2026-01-01T23:59:59Z"
        }) {
            long delay = schedulerAt(iso, 2).computeInitialDelaySeconds();
            assertTrue(delay >= 1, "delay must be >= 1 for " + iso + ", got " + delay);
        }
    }
}
