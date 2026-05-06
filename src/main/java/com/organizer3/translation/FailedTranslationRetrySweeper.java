package com.organizer3.translation;

import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.Jdbi;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;

/**
 * Periodic sweeper that re-eligibilities translation rows that failed due to
 * transient errors (Ollama unreachable, adapter errors) once a minimum
 * cooldown window has elapsed since the last failure.
 *
 * <p>Mechanism: deletes {@code translation_queue} rows where (a) the queue
 * row's {@code status='failed'}, (b) the queue row's {@code last_error} is
 * one of the transient categories, and (c) the queue row's
 * {@code completed_at} is null or older than {@link #RETRY_COOLDOWN_SECONDS}.
 * Once the queue row is gone, {@link TitleTranslationSweeper}'s
 * {@code NOT EXISTS} predicate becomes true and the title is re-eligible for
 * translation on the next scheduled tick.
 *
 * <p>Uses {@code q.last_error} (not the cache's {@code failure_reason}) so
 * that tier-2 unreachable failures are also caught. When
 * {@link Tier2BatchSweeper} hits an OllamaException it marks the queue row
 * {@code failed} with {@code last_error='unreachable'} but under the tier-1
 * {@code strategy_id}; the tier-1 cache still shows {@code sanitized}. A
 * cache-join-based check would miss these rows.
 *
 * <p>The cache row itself is left in place. {@link TranslationServiceImpl#requestTranslation}
 * recognizes a cached-but-no-translation row as a fall-through and enqueues a
 * fresh pending queue row; the worker then re-attempts with no short-circuit
 * (since {@code bestTranslation()} is null on a pure-failure cache row).
 */
@Slf4j
public class FailedTranslationRetrySweeper implements Runnable {

    private static final DateTimeFormatter ISO_UTC =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    /** Failure reasons we treat as transient — eligible for automatic retry. */
    private static final Set<String> TRANSIENT_FAILURES =
            Set.of("unreachable", "adapter_error");

    /** Minimum seconds since last failure before a row becomes eligible for retry. */
    static final int RETRY_COOLDOWN_SECONDS = 300;

    private final Jdbi jdbi;

    public FailedTranslationRetrySweeper(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    @Override
    public void run() {
        try {
            String retryThreshold = ISO_UTC.format(Instant.now().minusSeconds(RETRY_COOLDOWN_SECONDS));
            int deleted = jdbi.withHandle(h -> h.createUpdate("""
                    DELETE FROM translation_queue
                    WHERE id IN (
                        SELECT q.id
                        FROM translation_queue q
                        WHERE q.status = 'failed'
                          AND q.last_error IN (<reasons>)
                          AND (q.completed_at IS NULL OR q.completed_at < :retryThreshold)
                    )
                    """)
                    .bindList("reasons", List.copyOf(TRANSIENT_FAILURES))
                    .bind("retryThreshold", retryThreshold)
                    .execute());

            if (deleted > 0) {
                log.info("FailedTranslationRetrySweeper: re-eligibilitied {} failed translation(s) "
                        + "for retry (transient failures past cooldown window)", deleted);
            } else {
                log.debug("FailedTranslationRetrySweeper: no eligible failures to retry");
            }
        } catch (Exception e) {
            log.error("FailedTranslationRetrySweeper: error during sweep", e);
        }
    }
}
