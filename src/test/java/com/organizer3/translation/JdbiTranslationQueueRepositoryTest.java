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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link JdbiTranslationQueueRepository#enqueueIfAbsent} using real in-memory SQLite.
 */
class JdbiTranslationQueueRepositoryTest {

    private static final DateTimeFormatter ISO_UTC =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private JdbiTranslationQueueRepository queueRepo;
    private long strategyId;
    private String now;
    private Connection connection;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        Jdbi jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();

        JdbiTranslationStrategyRepository strategyRepo = new JdbiTranslationStrategyRepository(jdbi);
        strategyId = strategyRepo.insert(new TranslationStrategy(
                0, "label_basic", "gemma4:e4b", "Translate: {jp}", null, true, null));

        queueRepo = new JdbiTranslationQueueRepository(jdbi);
        now = ISO_UTC.format(Instant.now());
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    @Test
    void enqueueIfAbsent_insertsWhenNoRowExists() {
        boolean inserted = queueRepo.enqueueIfAbsent(
                "麻美ゆま", strategyId, now, TranslationQueueRow.STATUS_PENDING, null, null, 0);
        assertTrue(inserted);
        assertEquals(1, queueRepo.countByStatus(TranslationQueueRow.STATUS_PENDING));
    }

    @Test
    void enqueueIfAbsent_skipsWhenPendingRowExists() {
        queueRepo.enqueueIfAbsent("麻美ゆま", strategyId, now, TranslationQueueRow.STATUS_PENDING, null, null, 0);
        boolean second = queueRepo.enqueueIfAbsent(
                "麻美ゆま", strategyId, now, TranslationQueueRow.STATUS_PENDING, null, null, 0);
        assertFalse(second);
        assertEquals(1, queueRepo.countByStatus(TranslationQueueRow.STATUS_PENDING));
    }

    @Test
    void enqueueIfAbsent_skipsWhenInFlightRowExists() {
        queueRepo.enqueue("麻美ゆま", strategyId, now, TranslationQueueRow.STATUS_IN_FLIGHT, null, null);
        boolean inserted = queueRepo.enqueueIfAbsent(
                "麻美ゆま", strategyId, now, TranslationQueueRow.STATUS_PENDING, null, null, 0);
        assertFalse(inserted);
        assertEquals(0, queueRepo.countByStatus(TranslationQueueRow.STATUS_PENDING));
    }

    @Test
    void enqueueIfAbsent_allowsInsertWhenOnlyDoneRowExists() {
        long id = queueRepo.enqueue("麻美ゆま", strategyId, now, TranslationQueueRow.STATUS_PENDING, null, null);
        queueRepo.claimNext();
        queueRepo.markDone(id, now);

        boolean inserted = queueRepo.enqueueIfAbsent(
                "麻美ゆま", strategyId, now, TranslationQueueRow.STATUS_PENDING, null, null, 0);
        assertTrue(inserted);
        assertEquals(1, queueRepo.countByStatus(TranslationQueueRow.STATUS_PENDING));
    }

    @Test
    void enqueueIfAbsent_allowsInsertWhenOnlyFailedRowExists() {
        long id = queueRepo.enqueue("麻美ゆま", strategyId, now, TranslationQueueRow.STATUS_PENDING, null, null);
        queueRepo.claimNext();
        queueRepo.markFailed(id, "error", now);

        boolean inserted = queueRepo.enqueueIfAbsent(
                "麻美ゆま", strategyId, now, TranslationQueueRow.STATUS_PENDING, null, null, 0);
        assertTrue(inserted);
        assertEquals(1, queueRepo.countByStatus(TranslationQueueRow.STATUS_PENDING));
    }

    @Test
    void enqueueIfAbsent_bumpsPriorityWhenExistingRowHasLowerPriority() {
        // Insert a low-priority row
        queueRepo.enqueueIfAbsent("麻美ゆま", strategyId, now, TranslationQueueRow.STATUS_PENDING, null, null, 0);

        // Attempt to bump to high priority — returns false (no new row), but bumps priority
        boolean inserted = queueRepo.enqueueIfAbsent(
                "麻美ゆま", strategyId, now, TranslationQueueRow.STATUS_PENDING, null, null, 10);
        assertFalse(inserted, "should return false when row already exists");
        assertEquals(1, queueRepo.countByStatus(TranslationQueueRow.STATUS_PENDING));

        // Claim and verify the priority was bumped
        var claimed = queueRepo.claimNext();
        assertTrue(claimed.isPresent());
        assertEquals(10, claimed.get().priority(), "priority should have been bumped to 10");
    }

    @Test
    void enqueueIfAbsent_doesNotLowerPriorityWhenExistingRowHasHigherPriority() {
        // Insert a high-priority row
        queueRepo.enqueueIfAbsent("麻美ゆま", strategyId, now, TranslationQueueRow.STATUS_PENDING, null, null, 10);

        // Try to lower priority — should be a no-op (monotonic)
        queueRepo.enqueueIfAbsent("麻美ゆま", strategyId, now, TranslationQueueRow.STATUS_PENDING, null, null, 0);

        var claimed = queueRepo.claimNext();
        assertTrue(claimed.isPresent());
        assertEquals(10, claimed.get().priority(), "priority should remain at 10 (not lowered)");
    }

    @Test
    void claimNext_returnsHigherPriorityRowFirst() {
        // Enqueue low-priority first, high-priority second (reversed submission order)
        queueRepo.enqueueIfAbsent("低優先", strategyId, now, TranslationQueueRow.STATUS_PENDING, null, null, 0);
        queueRepo.enqueueIfAbsent("高優先", strategyId, now, TranslationQueueRow.STATUS_PENDING, null, null, 10);

        var claimed = queueRepo.claimNext();
        assertTrue(claimed.isPresent());
        assertEquals("高優先", claimed.get().sourceText(), "higher priority row must be claimed first");
    }

    @Test
    void claimNext_breaksTiesByFifo() {
        // Two rows with same priority — older one should be claimed first
        String older = "2024-01-01T00:00:00.000Z";
        String newer = "2024-06-01T00:00:00.000Z";
        queueRepo.enqueue("新しい", strategyId, newer, TranslationQueueRow.STATUS_PENDING, null, null);
        queueRepo.enqueue("古い", strategyId, older, TranslationQueueRow.STATUS_PENDING, null, null);

        var claimed = queueRepo.claimNext();
        assertTrue(claimed.isPresent());
        assertEquals("古い", claimed.get().sourceText(), "older submitted_at row must be claimed first on tie");
    }

    @Test
    void deletePendingForSource_deletesOnlyPendingRows() {
        long pendingId = queueRepo.enqueue("麻美ゆま", strategyId, now, TranslationQueueRow.STATUS_PENDING, null, null);
        queueRepo.enqueue("麻美ゆま", strategyId, now, TranslationQueueRow.STATUS_IN_FLIGHT, null, null);

        int deleted = queueRepo.deletePendingForSource(strategyId, "麻美ゆま");

        assertEquals(1, deleted, "only the pending row should be deleted");
        assertEquals(0, queueRepo.countByStatus(TranslationQueueRow.STATUS_PENDING));
        assertEquals(1, queueRepo.countByStatus(TranslationQueueRow.STATUS_IN_FLIGHT));
    }

    @Test
    void deletePendingForSource_returnsZeroWhenNoPendingRows() {
        int deleted = queueRepo.deletePendingForSource(strategyId, "存在しない");
        assertEquals(0, deleted);
    }
}
