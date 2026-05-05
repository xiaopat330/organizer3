package com.organizer3.translation;

import com.organizer3.db.SchemaInitializer;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link FailedTranslationRetrySweeper}, the sweeper that re-eligibilities
 * transient failures (Ollama unreachable, adapter errors) once their retry_after
 * window has elapsed.
 */
class FailedTranslationRetrySweeperTest {

    private static final DateTimeFormatter ISO_UTC =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private Connection connection;
    private Jdbi jdbi;
    private FailedTranslationRetrySweeper sweeper;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        sweeper = new FailedTranslationRetrySweeper(jdbi);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (connection != null) connection.close();
    }

    /** Insert a translation_strategy row, return its id. */
    private long seedStrategy(String name) {
        return jdbi.withHandle(h ->
                h.createUpdate("""
                        INSERT INTO translation_strategy (name, model_id, prompt_template, is_active)
                        VALUES (:name, 'gemma4:e4b', '{jp}', 1)
                        """)
                        .bind("name", name)
                        .executeAndReturnGeneratedKeys("id")
                        .mapTo(Long.class).one());
    }

    /** Insert a translation_cache row. */
    private long seedCacheFailure(long strategyId, String sourceText, String failureReason, String retryAfter) {
        return jdbi.withHandle(h ->
                h.createUpdate("""
                        INSERT INTO translation_cache (source_hash, source_text, strategy_id,
                            english_text, failure_reason, retry_after, cached_at)
                        VALUES (:hash, :src, :sid, NULL, :reason, :retryAfter, :now)
                        """)
                        .bind("hash", "h-" + sourceText)
                        .bind("src", sourceText)
                        .bind("sid", strategyId)
                        .bind("reason", failureReason)
                        .bind("retryAfter", retryAfter)
                        .bind("now", "2026-05-05T00:00:00.000Z")
                        .executeAndReturnGeneratedKeys("id")
                        .mapTo(Long.class).one());
    }

    /** Insert a translation_queue row. */
    private long seedQueue(String sourceText, long strategyId, String status) {
        return jdbi.withHandle(h ->
                h.createUpdate("""
                        INSERT INTO translation_queue (source_text, strategy_id, submitted_at,
                            status, callback_kind, callback_id)
                        VALUES (:src, :sid, :now, :status, 'title_javdb_enrichment.title_original_en', 1)
                        """)
                        .bind("src", sourceText)
                        .bind("sid", strategyId)
                        .bind("status", status)
                        .bind("now", "2026-05-05T00:00:00.000Z")
                        .executeAndReturnGeneratedKeys("id")
                        .mapTo(Long.class).one());
    }

    private boolean queueRowExists(long id) {
        return jdbi.withHandle(h -> h.createQuery("SELECT COUNT(*) FROM translation_queue WHERE id = :id")
                .bind("id", id)
                .mapTo(Integer.class).one()) > 0;
    }

    private String pastRetry() {
        return ISO_UTC.format(Instant.now().minusSeconds(60));
    }

    private String futureRetry() {
        return ISO_UTC.format(Instant.now().plusSeconds(600));
    }

    // ── Eligible cases ───────────────────────────────────────────────────────

    @Test
    void run_deletesFailedQueueRow_whenTransientAndRetryElapsed() {
        long sid = seedStrategy("label_basic");
        seedCacheFailure(sid, "src1", "unreachable", pastRetry());
        long qid = seedQueue("src1", sid, "failed");

        sweeper.run();

        assertFalse(queueRowExists(qid),
                "Failed queue row with elapsed retry_after should be deleted");
    }

    @Test
    void run_deletesAdapterErrorFailures() {
        long sid = seedStrategy("label_basic");
        seedCacheFailure(sid, "src1", "adapter_error", pastRetry());
        long qid = seedQueue("src1", sid, "failed");

        sweeper.run();

        assertFalse(queueRowExists(qid));
    }

    @Test
    void run_treatsNullRetryAfterAsElapsed() {
        long sid = seedStrategy("label_basic");
        seedCacheFailure(sid, "src1", "unreachable", null);
        long qid = seedQueue("src1", sid, "failed");

        sweeper.run();

        assertFalse(queueRowExists(qid),
                "Null retry_after should be treated as past — eligible immediately");
    }

    // ── Ineligible cases — defend the predicate ─────────────────────────────

    @Test
    void run_skipsRowsWithRetryStillInFuture() {
        long sid = seedStrategy("label_basic");
        seedCacheFailure(sid, "src1", "unreachable", futureRetry());
        long qid = seedQueue("src1", sid, "failed");

        sweeper.run();

        assertTrue(queueRowExists(qid),
                "Future retry_after must keep the row failed (rate-limit)");
    }

    @Test
    void run_skipsPermanentFailures() {
        long sid = seedStrategy("label_basic");
        seedCacheFailure(sid, "src1", "sanitized_both_tiers", pastRetry());
        long qid = seedQueue("src1", sid, "failed");

        sweeper.run();

        assertTrue(queueRowExists(qid),
                "Permanent failures (sanitized_both_tiers, refused) must not auto-retry");
    }

    @Test
    void run_skipsPendingQueueRows() {
        long sid = seedStrategy("label_basic");
        seedCacheFailure(sid, "src1", "unreachable", pastRetry());
        long qid = seedQueue("src1", sid, "pending");

        sweeper.run();

        assertTrue(queueRowExists(qid),
                "Pending rows are still in flight — not the sweeper's concern");
    }

    @Test
    void run_skipsDoneQueueRows() {
        long sid = seedStrategy("label_basic");
        seedCacheFailure(sid, "src1", "unreachable", pastRetry());
        long qid = seedQueue("src1", sid, "done");

        sweeper.run();

        assertTrue(queueRowExists(qid));
    }

    // ── Mixed-batch behavior ─────────────────────────────────────────────────

    @Test
    void run_handlesMixedBatchCorrectly() {
        long sid = seedStrategy("label_basic");

        // Eligible: transient + elapsed
        seedCacheFailure(sid, "src1", "unreachable", pastRetry());
        long qElig = seedQueue("src1", sid, "failed");
        // Skip: future retry
        seedCacheFailure(sid, "src2", "unreachable", futureRetry());
        long qFuture = seedQueue("src2", sid, "failed");
        // Skip: permanent
        seedCacheFailure(sid, "src3", "sanitized_both_tiers", pastRetry());
        long qPerm = seedQueue("src3", sid, "failed");

        sweeper.run();

        assertFalse(queueRowExists(qElig),  "eligible row deleted");
        assertTrue(queueRowExists(qFuture), "future-retry row preserved");
        assertTrue(queueRowExists(qPerm),   "permanent failure preserved");
    }
}
