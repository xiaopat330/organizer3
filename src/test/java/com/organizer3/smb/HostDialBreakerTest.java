package com.organizer3.smb;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure, time-source-driven tests for {@link HostDialBreaker} — no SMB, no sleeping.
 * The fake clock is advanced explicitly so cooldown/window transitions are deterministic.
 */
class HostDialBreakerTest {

    private static final String HOST = "pandora.local";

    private final AtomicLong clock = new AtomicLong(1_000L);

    /** threshold=3, window=60s, cooldown=30s (in millis). */
    private HostDialBreaker newBreaker() {
        return new HostDialBreaker(clock::get, 3, 60_000L, 30_000L);
    }

    private void advance(long ms) { clock.addAndGet(ms); }

    // ── Opening ──────────────────────────────────────────────────────────────

    @Test
    void kFailuresWithinWindow_opensBreaker() throws IOException {
        HostDialBreaker b = newBreaker();
        b.checkOpenOrThrow(HOST);              // CLOSED — allowed
        b.recordFailure(HOST);
        assertFalse(b.isOpen(HOST), "1 failure must not open");
        b.recordFailure(HOST);
        assertFalse(b.isOpen(HOST), "2 failures must not open");
        b.recordFailure(HOST);
        assertTrue(b.isOpen(HOST), "3rd failure (== threshold) must open");
    }

    @Test
    void failuresSpreadBeyondWindow_doNotOpen() throws IOException {
        HostDialBreaker b = newBreaker();
        b.recordFailure(HOST);
        advance(40_000L);
        b.recordFailure(HOST);
        advance(40_000L);   // first failure is now 80s old — pruned (> 60s window)
        b.recordFailure(HOST);
        assertFalse(b.isOpen(HOST),
                "only 2 failures fall within any 60s window — breaker must stay closed");
    }

    // ── Fast-fail while open ─────────────────────────────────────────────────

    @Test
    void whileOpen_checkFastFails() throws IOException {
        HostDialBreaker b = newBreaker();
        openBreaker(b);
        IOException ex = assertThrows(IOException.class, () -> b.checkOpenOrThrow(HOST));
        assertTrue(ex.getMessage().contains("dial-backoff"), "message: " + ex.getMessage());
        assertTrue(ex.getMessage().contains(HOST));
    }

    // ── Half-open probe single-flight ────────────────────────────────────────

    @Test
    void afterCooldown_oneCallerGrantedProbe_othersFastFail() throws IOException {
        HostDialBreaker b = newBreaker();
        openBreaker(b);
        advance(30_000L);  // cooldown elapsed
        // First caller is granted the single probe (returns normally).
        assertDoesNotThrow(() -> b.checkOpenOrThrow(HOST));
        assertEquals(HostDialBreaker.Status.HALF_OPEN, b.statusOf(HOST));
        // Concurrent/subsequent callers fast-fail while the probe is outstanding.
        assertThrows(IOException.class, () -> b.checkOpenOrThrow(HOST));
        assertThrows(IOException.class, () -> b.checkOpenOrThrow(HOST));
    }

    @Test
    void probeSuccess_closesBreaker_subsequentDialsAllowed() throws IOException {
        HostDialBreaker b = newBreaker();
        openBreaker(b);
        advance(30_000L);
        b.checkOpenOrThrow(HOST);              // granted probe
        b.recordSuccess(HOST);                 // probe succeeded
        assertFalse(b.isOpen(HOST));
        assertEquals(HostDialBreaker.Status.CLOSED, b.statusOf(HOST));
        assertDoesNotThrow(() -> b.checkOpenOrThrow(HOST), "closed breaker allows dials");
    }

    @Test
    void probeFailure_reopensWithLongerCooldown() throws IOException {
        HostDialBreaker b = newBreaker();
        openBreaker(b);
        assertEquals(30_000L, b.openUntilMillis(HOST) - clock.get(), "first cooldown is the base 30s");

        advance(30_000L);
        b.checkOpenOrThrow(HOST);              // granted probe
        long probeNow = clock.get();
        b.recordFailure(HOST);                 // probe failed → re-open, longer cooldown
        assertTrue(b.isOpen(HOST));
        assertEquals(60_000L, b.openUntilMillis(HOST) - probeNow,
                "second cooldown must be 2x base (exponential backoff)");
    }

    @Test
    void exponentialBackoff_isCappedAt8x() throws IOException {
        HostDialBreaker b = newBreaker();
        // Open #1 (base), then fail probes repeatedly and assert the cooldown ladder: 30,60,120,240,240.
        openBreaker(b);
        assertCooldown(b, 30_000L);
        assertCooldown(b, 60_000L, /*reopen*/ true);
        assertCooldown(b, 120_000L, true);
        assertCooldown(b, 240_000L, true);
        assertCooldown(b, 240_000L, true);   // capped at 8x = 240s
    }

    @Test
    void recordSuccess_resetsConsecutiveOpens() throws IOException {
        HostDialBreaker b = newBreaker();
        // Escalate to a longer cooldown, then succeed, then re-open — cooldown must be base again.
        openBreaker(b);                        // #1: 30s
        advance(30_000L);
        b.checkOpenOrThrow(HOST);
        b.recordFailure(HOST);                 // #2: 60s
        assertEquals(60_000L, b.openUntilMillis(HOST) - clock.get());

        advance(60_000L);
        b.checkOpenOrThrow(HOST);
        b.recordSuccess(HOST);                 // success resets consecutiveOpens
        assertEquals(HostDialBreaker.Status.CLOSED, b.statusOf(HOST));

        openBreaker(b);                        // re-open from scratch → base 30s again
        assertEquals(30_000L, b.openUntilMillis(HOST) - clock.get(),
                "after a success the backoff must reset to the base cooldown");
    }

    // ── Per-host isolation ───────────────────────────────────────────────────

    @Test
    void perHostIsolation_hostAOpenDoesNotAffectHostB() throws IOException {
        HostDialBreaker b = newBreaker();
        openBreaker(b);                        // opens HOST (pandora)
        String other = "qnap2.local";
        assertTrue(b.isOpen(HOST));
        assertFalse(b.isOpen(other), "a different host must be unaffected");
        assertDoesNotThrow(() -> b.checkOpenOrThrow(other), "host B still allows dials");
        b.recordFailure(other);
        assertFalse(b.isOpen(other), "one failure on host B must not open it");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Drives HOST from CLOSED to OPEN via `threshold` failures at the current clock. */
    private void openBreaker(HostDialBreaker b) throws IOException {
        for (int i = 0; i < 3; i++) b.recordFailure(HOST);
        assertTrue(b.isOpen(HOST));
    }

    /** Assumes OPEN; advances past the current cooldown, probes, fails, and asserts the new cooldown. */
    private void assertCooldown(HostDialBreaker b, long expectedCooldownMs) {
        assertEquals(expectedCooldownMs, b.openUntilMillis(HOST) - clock.get());
    }

    private void assertCooldown(HostDialBreaker b, long expectedCooldownMs, boolean reopen) throws IOException {
        if (reopen) {
            long cur = b.openUntilMillis(HOST) - clock.get();
            advance(cur);                      // reach cooldown expiry
            b.checkOpenOrThrow(HOST);          // granted probe
            b.recordFailure(HOST);             // fail → re-open
        }
        assertEquals(expectedCooldownMs, b.openUntilMillis(HOST) - clock.get());
    }
}
