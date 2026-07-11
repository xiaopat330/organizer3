package com.organizer3.smb;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Per-<em>host</em> circuit breaker for SMB dial attempts (Wave 2 of the connection-driver
 * hardening — see {@code spec/PLAN_SMB_CONNECTION_DRIVER.md}).
 *
 * <p>A VPN switch severs every TCP connection to a NAS host at once, so a wedged host would
 * otherwise make {@link SmbConnectionFactory#acquire} burn the full dial timeout for
 * <em>each</em> of the ~20 volumes across only ~3 hosts, on every reconciler pass — the audited
 * thrash. Keying on <strong>host</strong> (not volume) means the breaker fast-fails every volume
 * on a downed host after a handful of dial failures, and a single half-open probe re-closes it
 * for all of them.
 *
 * <p>State machine per host, guarded by that host's own {@link State} monitor:
 * <ul>
 *   <li><b>CLOSED</b> — dials allowed. Each {@link #recordFailure} appends a timestamp and prunes
 *       any older than the window; when {@code threshold} failures fall inside the window the
 *       breaker opens.</li>
 *   <li><b>OPEN</b> — {@link #checkOpenOrThrow} fast-fails until {@code openUntil}; once the
 *       cooldown elapses the next check transitions to HALF_OPEN.</li>
 *   <li><b>HALF_OPEN</b> — exactly one caller is granted a probe (the per-host
 *       {@code probeInProgress} flag, flipped under the host lock); all others fast-fail.
 *       {@link #recordSuccess} closes the breaker; {@link #recordFailure} re-opens it with a
 *       capped exponential cooldown.</li>
 * </ul>
 *
 * <p>All timing reads the injected {@code nowMillis} supplier — never {@code System.currentTimeMillis()}
 * directly — so tests advance a fake clock instead of sleeping.
 *
 * <p><b>Concurrency:</b> {@link #states} is a {@link ConcurrentHashMap}; each host's mutable
 * {@link State} is only ever touched inside {@code synchronized (state)}, so the read-decide-mutate
 * sequences (open/half-open transitions, the single-flight probe grant) are atomic per host and
 * fully isolated across hosts.
 */
final class HostDialBreaker {

    enum Status { CLOSED, OPEN, HALF_OPEN }

    private static final class State {
        Status status = Status.CLOSED;
        /** Failure timestamps within the sliding window (CLOSED-state accounting). */
        final Deque<Long> failures = new ArrayDeque<>();
        /** Wall-clock (ms) at which the OPEN cooldown expires. */
        long openUntil = 0L;
        /** Number of consecutive opens without an intervening success — drives exponential backoff. */
        int consecutiveOpens = 0;
        /** True while a single HALF_OPEN probe is outstanding; single-flights the probe per host. */
        boolean probeInProgress = false;
    }

    /** Exponential backoff is capped at 8x the base cooldown (shift of 3). Internal, no setting. */
    private static final int MAX_BACKOFF_SHIFT = 3;

    private final LongSupplier nowMillis;
    private final int threshold;
    private final long windowMillis;
    private final long cooldownMillis;
    private final ConcurrentHashMap<String, State> states = new ConcurrentHashMap<>();

    HostDialBreaker(LongSupplier nowMillis, int threshold, long windowMillis, long cooldownMillis) {
        this.nowMillis = nowMillis;
        this.threshold = threshold;
        this.windowMillis = windowMillis;
        this.cooldownMillis = cooldownMillis;
    }

    private State stateFor(String host) {
        return states.computeIfAbsent(host, h -> new State());
    }

    /**
     * Gate a dial for {@code host}. Returns normally when a dial is permitted; throws
     * {@link IOException} when the breaker is open (or a HALF_OPEN probe is already outstanding),
     * so the caller fast-fails instead of burning the dial timeout.
     *
     * <p>When the cooldown has elapsed this atomically grants the single HALF_OPEN probe to the
     * calling thread; the caller MUST subsequently report the outcome via exactly one of
     * {@link #recordSuccess}/{@link #recordFailure} so the probe flag is cleared.
     */
    void checkOpenOrThrow(String host) throws IOException {
        State s = stateFor(host);
        synchronized (s) {
            long now = nowMillis.getAsLong();
            if (s.status == Status.OPEN) {
                if (now < s.openUntil) {
                    throw backoff(host, s.openUntil);
                }
                // Cooldown elapsed → allow a single half-open probe.
                s.status = Status.HALF_OPEN;
                s.probeInProgress = false;
            }
            if (s.status == Status.HALF_OPEN) {
                if (s.probeInProgress) {
                    // Another caller already holds the probe; everyone else fast-fails.
                    throw backoff(host, s.openUntil);
                }
                s.probeInProgress = true;
                return;
            }
            // CLOSED — dial allowed.
        }
    }

    /**
     * Clears all per-host breaker state, returning every host to a fresh CLOSED breaker. Called by
     * {@link SmbConnectionFactory#invalidateAll()} when the network has re-settled: a settled network
     * must not stay stuck in a pre-switch backoff. Any dial already in flight that later records into
     * its (now-orphaned) {@link State} simply has no effect — the next {@link #stateFor} lookup builds
     * a fresh state.
     */
    void resetAll() {
        states.clear();
    }

    /** Records a successful dial: closes the breaker and clears all failure/backoff state. */
    void recordSuccess(String host) {
        State s = stateFor(host);
        synchronized (s) {
            s.status = Status.CLOSED;
            s.failures.clear();
            s.consecutiveOpens = 0;
            s.openUntil = 0L;
            s.probeInProgress = false;
        }
    }

    /**
     * Records a failed dial. A failure during a HALF_OPEN probe re-opens the breaker with a longer
     * (capped exponential) cooldown; failures in CLOSED accumulate within the sliding window and
     * open the breaker once {@code threshold} is reached. A failure while already OPEN (a straggler
     * dial that was already in flight when the breaker opened) is ignored.
     */
    void recordFailure(String host) {
        State s = stateFor(host);
        synchronized (s) {
            long now = nowMillis.getAsLong();
            if (s.status == Status.HALF_OPEN) {
                // Probe failed → back to OPEN with a longer cooldown.
                s.consecutiveOpens++;
                s.openUntil = now + effectiveCooldown(s.consecutiveOpens);
                s.status = Status.OPEN;
                s.probeInProgress = false;
                return;
            }
            if (s.status == Status.OPEN) {
                // Already open; leave the existing cooldown as-is.
                return;
            }
            // CLOSED: record the failure and prune anything older than the window.
            s.failures.addLast(now);
            long cutoff = now - windowMillis;
            while (!s.failures.isEmpty() && s.failures.peekFirst() < cutoff) {
                s.failures.pollFirst();
            }
            if (s.failures.size() >= threshold) {
                s.consecutiveOpens++;
                s.openUntil = now + effectiveCooldown(s.consecutiveOpens);
                s.status = Status.OPEN;
                s.failures.clear();
                s.probeInProgress = false;
            }
        }
    }

    /**
     * Capped exponential backoff: {@code min(cooldown << (consecutiveOpens - 1), cooldown * 8)}.
     * The shift is clamped to {@link #MAX_BACKOFF_SHIFT} to avoid overflow and to enforce the 8x cap.
     */
    private long effectiveCooldown(int consecutiveOpens) {
        int shift = Math.min(Math.max(consecutiveOpens - 1, 0), MAX_BACKOFF_SHIFT);
        return Math.min(cooldownMillis << shift, cooldownMillis * 8);
    }

    // ── Visible for testing ──────────────────────────────────────────────────

    /** Visible for testing — raw status (OPEN even if the cooldown has since elapsed, until a check probes). */
    boolean isOpen(String host) {
        State s = stateFor(host);
        synchronized (s) {
            return s.status == Status.OPEN;
        }
    }

    /** Visible for testing — wall-clock (ms) at which the current OPEN cooldown expires. */
    long openUntilMillis(String host) {
        State s = stateFor(host);
        synchronized (s) {
            return s.openUntil;
        }
    }

    /** Visible for testing — current status. */
    Status statusOf(String host) {
        State s = stateFor(host);
        synchronized (s) {
            return s.status;
        }
    }

    private static IOException backoff(String host, long openUntil) {
        return new IOException("SMB host " + host + " in dial-backoff until " + openUntil + "; fast-failing");
    }
}
