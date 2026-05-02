package com.organizer3.javdb.draft;

import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Schedules a daily {@link DraftGcService#sweep()} at a configurable UTC hour
 * (default 2am UTC).
 *
 * <p>The implementation uses a single-daemon-thread {@link ScheduledExecutorService}
 * — the same pattern as {@code BackupScheduler}. The executor is daemon so the JVM
 * can exit without waiting for a pending tick.
 *
 * <p>The initial delay is computed to align the first tick with the next occurrence
 * of {@code gcHourUtc}:00:00 UTC, then repeats every 24 hours.
 *
 * <p>Call {@link #start()} after the service is constructed and {@link #stop()} in
 * the application's shutdown sequence.
 *
 * <p>See spec/PROPOSAL_DRAFT_MODE.md §9.2.
 */
@Slf4j
public class DraftGcScheduler {

    /** Default UTC hour at which the sweep fires. */
    public static final int DEFAULT_GC_HOUR_UTC = 2;

    private final DraftGcService gcService;
    private final int gcHourUtc;
    private final Clock clock;

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "draft-gc-scheduler");
        t.setDaemon(true);
        return t;
    });

    public DraftGcScheduler(DraftGcService gcService, int gcHourUtc) {
        this(gcService, gcHourUtc, Clock.systemUTC());
    }

    /** Package-private constructor for tests — allows injecting a fake clock. */
    DraftGcScheduler(DraftGcService gcService, int gcHourUtc, Clock clock) {
        this.gcService = gcService;
        this.gcHourUtc = gcHourUtc;
        this.clock = clock;
    }

    /** Schedule the sweep. The first tick fires at the next {@code gcHourUtc}:00 UTC. */
    public void start() {
        long initialDelaySeconds = computeInitialDelaySeconds();
        log.info("Draft GC scheduler starting — next sweep in {}s (at {}:00 UTC, every 24h)",
                initialDelaySeconds, gcHourUtc);
        executor.scheduleAtFixedRate(this::runSweep,
                initialDelaySeconds, TimeUnit.DAYS.toSeconds(1), TimeUnit.SECONDS);
    }

    /** Stop the scheduler, waiting up to 5 seconds for any in-flight sweep to finish. */
    public void stop() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private void runSweep() {
        try {
            int total = gcService.sweep();
            log.debug("Draft GC sweep complete — total_reaped={}", total);
        } catch (Exception e) {
            log.error("Draft GC sweep failed", e);
        }
    }

    /**
     * Computes how many seconds from now until the next {@code gcHourUtc}:00:00 UTC.
     * Returns at least 1 second to avoid firing immediately at startup.
     */
    long computeInitialDelaySeconds() {
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(ZoneOffset.UTC));
        ZonedDateTime next = now.toLocalDate().atStartOfDay(ZoneOffset.UTC)
                .plusHours(gcHourUtc);
        if (!next.isAfter(now)) {
            next = next.plusDays(1);
        }
        long delay = Duration.between(now, next).getSeconds();
        return Math.max(delay, 1L);
    }
}
