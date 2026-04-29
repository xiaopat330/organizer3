package com.organizer3.javdb.enrichment;

import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Periodic scheduler for enrichment re-validation.
 *
 * <p>Each tick runs two phases:
 * <ol>
 *   <li><b>Drain phase</b> — processes all rows in {@code revalidation_pending} in
 *       batch-sized chunks until the queue is empty.</li>
 *   <li><b>Safety-net phase</b> — runs one slice of the safety-net predicate
 *       (UNKNOWN confidence, null/stale {@code last_revalidated_at}).</li>
 * </ol>
 *
 * <p>The first tick fires after {@code intervalHours} (NOT at startup) to avoid
 * a burst immediately after every app restart.
 */
@Slf4j
public class RevalidationCronScheduler {

    private final RevalidationService revalidationService;
    private final RevalidationPendingRepository pendingRepo;
    private final int drainBatchSize;
    private final int safetyNetBatchSize;

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "revalidation-cron");
        t.setDaemon(true);
        return t;
    });

    public RevalidationCronScheduler(RevalidationService revalidationService,
                                      RevalidationPendingRepository pendingRepo,
                                      int drainBatchSize,
                                      int safetyNetBatchSize) {
        this.revalidationService = revalidationService;
        this.pendingRepo = pendingRepo;
        this.drainBatchSize = drainBatchSize;
        this.safetyNetBatchSize = safetyNetBatchSize;
    }

    /**
     * Starts the scheduler. The first tick fires after {@code intervalHours},
     * then repeats every {@code intervalHours} hours.
     */
    public void start(long intervalHours) {
        log.info("Revalidation cron scheduler starting — interval={}h drainBatch={} safetyNetBatch={}",
                intervalHours, drainBatchSize, safetyNetBatchSize);
        executor.scheduleAtFixedRate(this::tick, intervalHours, intervalHours, TimeUnit.HOURS);
    }

    /** Stops the scheduler, waiting up to {@code timeoutSeconds} for an in-flight tick to finish. */
    public void stop(int timeoutSeconds) {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(timeoutSeconds, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    void tick() {
        try {
            // Phase 1: drain dirty queue
            int totalDrained = 0;
            while (true) {
                List<RevalidationPendingRepository.Pending> batch = pendingRepo.drainBatch(drainBatchSize);
                if (batch.isEmpty()) break;
                List<Long> ids = batch.stream().map(RevalidationPendingRepository.Pending::titleId).toList();
                RevalidationService.RevalidationSummary summary = revalidationService.revalidateBatch(ids);
                totalDrained += batch.size();
                log.debug("revalidation-cron: drain batch={} {}", batch.size(), summary.describe());
                if (batch.size() < drainBatchSize) break;
            }

            // Phase 2: safety-net sweep
            RevalidationService.RevalidationSummary safetyNet =
                    revalidationService.revalidateSafetyNetSlice(safetyNetBatchSize);
            log.info("revalidation-cron: tick complete — drained={} safety-net: {}",
                    totalDrained, safetyNet.describe());
        } catch (Exception e) {
            log.error("revalidation-cron: tick failed: {}", e.getMessage(), e);
        }
    }
}
