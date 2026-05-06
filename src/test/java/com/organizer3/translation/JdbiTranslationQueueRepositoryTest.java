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
                "麻美ゆま", strategyId, now, TranslationQueueRow.STATUS_PENDING, null, null);
        assertTrue(inserted);
        assertEquals(1, queueRepo.countByStatus(TranslationQueueRow.STATUS_PENDING));
    }

    @Test
    void enqueueIfAbsent_skipsWhenPendingRowExists() {
        queueRepo.enqueueIfAbsent("麻美ゆま", strategyId, now, TranslationQueueRow.STATUS_PENDING, null, null);
        boolean second = queueRepo.enqueueIfAbsent(
                "麻美ゆま", strategyId, now, TranslationQueueRow.STATUS_PENDING, null, null);
        assertFalse(second);
        assertEquals(1, queueRepo.countByStatus(TranslationQueueRow.STATUS_PENDING));
    }

    @Test
    void enqueueIfAbsent_skipsWhenInFlightRowExists() {
        queueRepo.enqueue("麻美ゆま", strategyId, now, TranslationQueueRow.STATUS_IN_FLIGHT, null, null);
        boolean inserted = queueRepo.enqueueIfAbsent(
                "麻美ゆま", strategyId, now, TranslationQueueRow.STATUS_PENDING, null, null);
        assertFalse(inserted);
        assertEquals(0, queueRepo.countByStatus(TranslationQueueRow.STATUS_PENDING));
    }

    @Test
    void enqueueIfAbsent_allowsInsertWhenOnlyDoneRowExists() {
        long id = queueRepo.enqueue("麻美ゆま", strategyId, now, TranslationQueueRow.STATUS_PENDING, null, null);
        queueRepo.claimNext();
        queueRepo.markDone(id, now);

        boolean inserted = queueRepo.enqueueIfAbsent(
                "麻美ゆま", strategyId, now, TranslationQueueRow.STATUS_PENDING, null, null);
        assertTrue(inserted);
        assertEquals(1, queueRepo.countByStatus(TranslationQueueRow.STATUS_PENDING));
    }

    @Test
    void enqueueIfAbsent_allowsInsertWhenOnlyFailedRowExists() {
        long id = queueRepo.enqueue("麻美ゆま", strategyId, now, TranslationQueueRow.STATUS_PENDING, null, null);
        queueRepo.claimNext();
        queueRepo.markFailed(id, "error", now);

        boolean inserted = queueRepo.enqueueIfAbsent(
                "麻美ゆま", strategyId, now, TranslationQueueRow.STATUS_PENDING, null, null);
        assertTrue(inserted);
        assertEquals(1, queueRepo.countByStatus(TranslationQueueRow.STATUS_PENDING));
    }
}
