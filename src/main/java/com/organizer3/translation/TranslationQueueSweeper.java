package com.organizer3.translation;

import com.organizer3.translation.repository.TranslationQueueRepository;
import lombok.extern.slf4j.Slf4j;

/**
 * Periodic sweeper that resets stuck {@code in_flight} queue rows back to {@code pending}.
 *
 * <p>A row becomes stuck when the worker crashes (or is killed) while processing it, leaving it
 * in {@code in_flight} indefinitely. The sweeper runs on a schedule and resets any
 * {@code in_flight} rows whose {@code started_at} is older than the configured threshold.
 *
 * <p>Per §8: "Worker crashes mid-call → Queue row stays in {@code in_flight}. Sweeper job
 * (every N minutes) resets stuck {@code in_flight} rows older than threshold back to
 * {@code pending}."
 */
@Slf4j
public class TranslationQueueSweeper implements Runnable {

    private final TranslationQueueRepository queueRepo;
    private final int thresholdSeconds;

    public TranslationQueueSweeper(TranslationQueueRepository queueRepo,
                                    int thresholdSeconds) {
        this.queueRepo        = queueRepo;
        this.thresholdSeconds = thresholdSeconds;
    }

    @Override
    public void run() {
        try {
            int reset = queueRepo.resetStuckToRetry(thresholdSeconds);
            if (reset > 0) {
                log.info("TranslationQueueSweeper: reset {} stuck in_flight row(s) to pending (threshold={}s)",
                        reset, thresholdSeconds);
            } else {
                log.debug("TranslationQueueSweeper: no stuck in_flight rows found");
            }
        } catch (Exception e) {
            log.error("TranslationQueueSweeper: error during sweep", e);
        }
    }
}
