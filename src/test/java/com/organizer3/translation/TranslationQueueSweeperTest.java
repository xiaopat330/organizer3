package com.organizer3.translation;

import com.organizer3.db.SchemaInitializer;
import com.organizer3.translation.repository.jdbi.JdbiTranslationQueueRepository;
import com.organizer3.translation.repository.jdbi.JdbiTranslationStrategyRepository;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for {@link TranslationQueueSweeper}.
 *
 * <p>Verifies that a row marked {@code in_flight} with a {@code started_at} older than the
 * threshold is reset to {@code pending}, and that recently-started rows are left alone.
 */
class TranslationQueueSweeperTest {

    private static final DateTimeFormatter ISO_UTC =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private JdbiTranslationQueueRepository queueRepo;
    private TranslationQueueSweeper sweeper;
    private long strategyId;
    private Connection connection;
    private Jdbi jdbi;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();

        JdbiTranslationStrategyRepository strategyRepo = new JdbiTranslationStrategyRepository(jdbi);
        strategyId = strategyRepo.insert(new TranslationStrategy(
                0, "label_basic", "gemma4:e4b", "Translate: {jp}", null, true, null));

        queueRepo = new JdbiTranslationQueueRepository(jdbi);
        // Threshold of 10 minutes = 600 seconds
        sweeper = new TranslationQueueSweeper(queueRepo, 600);
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    @Test
    void sweep_resetsStuckInFlightRowOlderThanThreshold() {
        // Enqueue as pending, claim it (sets started_at to now), then backdate started_at to 11 min ago
        String now = ISO_UTC.format(Instant.now());
        long id = queueRepo.enqueue("テスト", strategyId, now,
                TranslationQueueRow.STATUS_PENDING, null, null);
        queueRepo.claimNext(); // → in_flight, started_at = now

        // Backdate started_at to 11 minutes ago to simulate a stuck row
        String staleStartedAt = ISO_UTC.format(Instant.now().minusSeconds(660));
        jdbi.useHandle(h ->
                h.createUpdate("UPDATE translation_queue SET started_at = :t WHERE id = :id")
                        .bind("t", staleStartedAt)
                        .bind("id", id)
                        .execute());

        sweeper.run();

        assertEquals(1, queueRepo.countByStatus(TranslationQueueRow.STATUS_PENDING),
                "Stuck in_flight row should be reset to pending");
        assertEquals(0, queueRepo.countByStatus(TranslationQueueRow.STATUS_IN_FLIGHT));
    }

    @Test
    void sweep_leavesRecentInFlightRowAlone() {
        String now = ISO_UTC.format(Instant.now());
        queueRepo.enqueue("テスト", strategyId, now, TranslationQueueRow.STATUS_PENDING, null, null);
        queueRepo.claimNext(); // → in_flight, started_at = now

        // Do NOT backdate — started_at is very recent
        sweeper.run();

        // Row should still be in_flight
        assertEquals(1, queueRepo.countByStatus(TranslationQueueRow.STATUS_IN_FLIGHT));
        assertEquals(0, queueRepo.countByStatus(TranslationQueueRow.STATUS_PENDING));
    }

    @Test
    void sweep_noRowsToReset_doesNotThrow() {
        // No in_flight rows at all
        sweeper.run(); // should complete without error
        assertEquals(0, queueRepo.countByStatus(TranslationQueueRow.STATUS_PENDING));
    }
}
