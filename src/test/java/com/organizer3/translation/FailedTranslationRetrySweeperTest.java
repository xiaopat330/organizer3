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
 * transient failures (Ollama unreachable, adapter errors) once the cooldown window elapses.
 *
 * <p>The sweeper uses {@code q.last_error} (not the cache's {@code failure_reason}) and
 * {@code q.completed_at} as the retry-eligibility signals. No cache row is required.
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

    /** Insert a failed queue row with the given last_error and completed_at. */
    private long seedFailedQueue(String sourceText, long strategyId,
                                  String lastError, String completedAt) {
        return jdbi.withHandle(h ->
                h.createUpdate("""
                        INSERT INTO translation_queue (source_text, strategy_id, submitted_at,
                            status, last_error, completed_at, callback_kind, callback_id)
                        VALUES (:src, :sid, '2026-05-05T00:00:00.000Z', 'failed',
                                :lastError, :completedAt,
                                'title_javdb_enrichment.title_original_en', 1)
                        """)
                        .bind("src", sourceText)
                        .bind("sid", strategyId)
                        .bind("lastError", lastError)
                        .bind("completedAt", completedAt)
                        .executeAndReturnGeneratedKeys("id")
                        .mapTo(Long.class).one());
    }

    private boolean queueRowExists(long id) {
        return jdbi.withHandle(h -> h.createQuery("SELECT COUNT(*) FROM translation_queue WHERE id = :id")
                .bind("id", id)
                .mapTo(Integer.class).one()) > 0;
    }

    /** A completed_at well past the cooldown window. */
    private String pastCompletedAt() {
        return ISO_UTC.format(Instant.now().minusSeconds(
                FailedTranslationRetrySweeper.RETRY_COOLDOWN_SECONDS + 60));
    }

    /** A completed_at within the cooldown window (too recent to retry). */
    private String recentCompletedAt() {
        return ISO_UTC.format(Instant.now().minusSeconds(30));
    }

    // ── Eligible cases ───────────────────────────────────────────────────────

    @Test
    void run_deletesFailedQueueRow_whenUnreachableAndCooldownElapsed() {
        long sid = seedStrategy("label_basic");
        long qid = seedFailedQueue("src1", sid, "unreachable", pastCompletedAt());

        sweeper.run();

        assertFalse(queueRowExists(qid),
                "Failed queue row with elapsed cooldown should be deleted");
    }

    @Test
    void run_deletesAdapterErrorFailures() {
        long sid = seedStrategy("label_basic");
        long qid = seedFailedQueue("src1", sid, "adapter_error", pastCompletedAt());

        sweeper.run();

        assertFalse(queueRowExists(qid));
    }

    @Test
    void run_treatsNullCompletedAtAsElapsed() {
        long sid = seedStrategy("label_basic");
        long qid = seedFailedQueue("src1", sid, "unreachable", null);

        sweeper.run();

        assertFalse(queueRowExists(qid),
                "Null completed_at should be treated as past — eligible immediately");
    }

    /**
     * Tier-2 unreachable failures: queue row has tier-1 strategy_id, tier-1 cache
     * shows 'sanitized', tier-2 cache shows 'unreachable'. The old cache-join approach
     * missed these; the new q.last_error approach must catch them.
     */
    @Test
    void run_catchesTier2UnreachableFailures_withoutCacheRow() {
        long sid = seedStrategy("label_basic");
        // No cache row seeded — simulates the tier-1 cache still saying 'sanitized'
        long qid = seedFailedQueue("src1", sid, "unreachable", pastCompletedAt());

        sweeper.run();

        assertFalse(queueRowExists(qid),
                "Tier-2 unreachable failure (no cache join) should be deleted by q.last_error check");
    }

    // ── Ineligible cases ─────────────────────────────────────────────────────

    @Test
    void run_skipsRowsWithinCooldownWindow() {
        long sid = seedStrategy("label_basic");
        long qid = seedFailedQueue("src1", sid, "unreachable", recentCompletedAt());

        sweeper.run();

        assertTrue(queueRowExists(qid),
                "Recently failed row must not be retried until cooldown elapses");
    }

    @Test
    void run_skipsPermanentFailures() {
        long sid = seedStrategy("label_basic");
        long qid = seedFailedQueue("src1", sid, "sanitized_both_tiers", pastCompletedAt());

        sweeper.run();

        assertTrue(queueRowExists(qid),
                "Permanent failures must not auto-retry");
    }

    @Test
    void run_skipsPendingQueueRows() {
        long sid = seedStrategy("label_basic");
        jdbi.useHandle(h -> h.createUpdate("""
                        INSERT INTO translation_queue (source_text, strategy_id, submitted_at,
                            status, last_error, callback_kind, callback_id)
                        VALUES ('src1', :sid, '2026-05-05T00:00:00.000Z', 'pending',
                                'unreachable', 'title_javdb_enrichment.title_original_en', 1)
                        """).bind("sid", sid).execute());
        long qid = jdbi.withHandle(h -> h.createQuery(
                "SELECT id FROM translation_queue WHERE source_text='src1'")
                .mapTo(Long.class).one());

        sweeper.run();

        assertTrue(queueRowExists(qid),
                "Pending rows are not the sweeper's concern");
    }

    @Test
    void run_skipsDoneQueueRows() {
        long sid = seedStrategy("label_basic");
        jdbi.useHandle(h -> h.createUpdate("""
                        INSERT INTO translation_queue (source_text, strategy_id, submitted_at,
                            status, last_error, completed_at, callback_kind, callback_id)
                        VALUES ('src1', :sid, '2026-05-05T00:00:00.000Z', 'done',
                                'unreachable', :past, 'title_javdb_enrichment.title_original_en', 1)
                        """).bind("sid", sid).bind("past", pastCompletedAt()).execute());
        long qid = jdbi.withHandle(h -> h.createQuery(
                "SELECT id FROM translation_queue WHERE source_text='src1'")
                .mapTo(Long.class).one());

        sweeper.run();

        assertTrue(queueRowExists(qid));
    }

    // ── Mixed-batch behavior ─────────────────────────────────────────────────

    @Test
    void run_handlesMixedBatchCorrectly() {
        long sid = seedStrategy("label_basic");

        // Eligible: transient + elapsed cooldown
        long qElig = seedFailedQueue("src1", sid, "unreachable", pastCompletedAt());
        // Skip: within cooldown window
        long qRecent = seedFailedQueue("src2", sid, "unreachable", recentCompletedAt());
        // Skip: permanent failure
        long qPerm = seedFailedQueue("src3", sid, "sanitized_both_tiers", pastCompletedAt());

        sweeper.run();

        assertFalse(queueRowExists(qElig),  "eligible row deleted");
        assertTrue(queueRowExists(qRecent), "within-cooldown row preserved");
        assertTrue(queueRowExists(qPerm),   "permanent failure preserved");
    }
}
