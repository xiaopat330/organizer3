package com.organizer3.javdb.draft;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Periodically runs {@link PromotionFolderRenameReconciler#reconcile(int)} so that promoted
 * titles whose post-commit folder rename hard-failed eventually get normalized.
 *
 * <p>Mirrors {@code DraftGcScheduler}: a single daemon-thread {@link ScheduledExecutorService}
 * (daemon so the JVM can exit without waiting on a pending tick). The first tick is delayed
 * ~2 minutes so the reconciler also catches anything stranded before this boot, then repeats
 * every {@link #intervalSeconds}.
 *
 * <p>Call {@link #start()} after construction and {@link #stop()} in the shutdown sequence.
 */
@Slf4j
public class PromotionRenameReconcileScheduler {

    /** Default interval between reconcile passes (10 minutes). */
    public static final int DEFAULT_INTERVAL_SECONDS = 600;

    /**
     * Default per-pass candidate-SCAN cap. The query returns ALL promoted-on-unsorted titles
     * (already-correct + needs-rename); already-correct rows are cheap Java-only no-ops, only
     * needs-rename rows do SMB work. Set well above the realistic promoted-on-unsorted count so
     * the needs-rename tail is never silently truncated by the LIMIT.
     */
    public static final int DEFAULT_BATCH_LIMIT = 5000;

    /** Initial delay before the first pass — catches pre-boot strandings without racing startup. */
    private static final int INITIAL_DELAY_SECONDS = 120;

    private final PromotionFolderRenameReconciler reconciler;
    private final int intervalSeconds;
    private final int batchLimit;

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "promotion-rename-reconciler");
        t.setDaemon(true);
        return t;
    });

    public PromotionRenameReconcileScheduler(PromotionFolderRenameReconciler reconciler,
                                             int intervalSeconds, int batchLimit) {
        this.reconciler = reconciler;
        this.intervalSeconds = intervalSeconds;
        this.batchLimit = batchLimit;
    }

    /** Schedule the recurring reconcile pass. */
    public void start() {
        log.info("Promotion rename reconciler starting — first pass in {}s, then every {}s (batchLimit={})",
                INITIAL_DELAY_SECONDS, intervalSeconds, batchLimit);
        executor.scheduleAtFixedRate(this::runOnce,
                INITIAL_DELAY_SECONDS, intervalSeconds, TimeUnit.SECONDS);
    }

    /** Stop the scheduler, waiting up to 5 seconds for an in-flight pass to finish. */
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

    /** A single pass — a sweep must never throw out of the executor. */
    private void runOnce() {
        try {
            reconciler.reconcile(batchLimit);
        } catch (Exception e) {
            log.error("Promotion rename reconcile pass failed", e);
        }
    }
}
