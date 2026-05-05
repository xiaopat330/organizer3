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
 * transient errors (Ollama unreachable, adapter errors) once their
 * {@code retry_after} window has elapsed.
 *
 * <p>Mechanism: deletes {@code translation_queue} rows where (a) the queue
 * row's {@code status='failed'}, (b) the linked cache row's
 * {@code failure_reason} is one of the transient categories, and (c) the
 * cache row's {@code retry_after} is null or in the past. Once the queue
 * row is gone, {@link com.organizer3.translation.TitleTranslationSweeper}'s
 * {@code NOT EXISTS} predicate becomes true and the title is re-eligibility
 * for translation on the next scheduled tick.
 *
 * <p>The cache row itself is left in place. {@link TranslationServiceImpl#requestTranslation}
 * recognizes a cached-but-no-translation row as a fall-through and enqueues a
 * fresh pending queue row; the worker then re-attempts with no short-circuit
 * (since {@code bestTranslation()} is null on a pure-failure cache row).
 *
 * <p>No attempt cap: rate-limited naturally by the worker re-stamping
 * {@code retry_after = now + 5 min} on each failure. If a row genuinely
 * cannot be translated, this sweeper retries it ~12 times per hour at most.
 * If runaway loops are observed in practice, add a max-attempts gate.
 */
@Slf4j
public class FailedTranslationRetrySweeper implements Runnable {

    private static final DateTimeFormatter ISO_UTC =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    /** Failure reasons we treat as transient — eligible for automatic retry. */
    private static final Set<String> TRANSIENT_FAILURES =
            Set.of("unreachable", "adapter_error");

    private final Jdbi jdbi;

    public FailedTranslationRetrySweeper(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    @Override
    public void run() {
        try {
            String now = ISO_UTC.format(Instant.now());
            int deleted = jdbi.withHandle(h -> h.createUpdate("""
                    DELETE FROM translation_queue
                    WHERE id IN (
                        SELECT q.id
                        FROM translation_queue q
                        JOIN translation_cache c
                          ON c.source_text = q.source_text
                         AND c.strategy_id = q.strategy_id
                        WHERE q.status = 'failed'
                          AND c.failure_reason IN (<reasons>)
                          AND (c.retry_after IS NULL OR c.retry_after < :now)
                    )
                    """)
                    .bindList("reasons", List.copyOf(TRANSIENT_FAILURES))
                    .bind("now", now)
                    .execute());

            if (deleted > 0) {
                log.info("FailedTranslationRetrySweeper: re-eligibilitied {} failed translation(s) "
                        + "for retry (transient failures with elapsed retry_after)", deleted);
            } else {
                log.debug("FailedTranslationRetrySweeper: no eligible failures to retry");
            }
        } catch (Exception e) {
            log.error("FailedTranslationRetrySweeper: error during sweep", e);
        }
    }
}
