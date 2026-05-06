package com.organizer3.translation.repository;

import com.organizer3.db.SchemaInitializer;
import com.organizer3.translation.repository.jdbi.JdbiTranslationQueueRepository;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link JdbiTranslationQueueRepository#hasPending(String, String)}.
 */
class JdbiTranslationQueueRepositoryHasPendingTest {

    private Connection connection;
    private Jdbi jdbi;
    private JdbiTranslationQueueRepository repo;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        repo = new JdbiTranslationQueueRepository(jdbi);

        // Seed a translation_strategy row for "label_basic"
        jdbi.useHandle(h -> h.createUpdate("""
                INSERT OR IGNORE INTO translation_strategy
                    (id, name, model_id, prompt_template)
                VALUES (1, 'label_basic', 'test-model', 'translate: {{text}}')
                """).execute());
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    @Test
    void hasPending_trueWhenPendingRowExists() {
        jdbi.useHandle(h -> h.execute("""
                INSERT INTO translation_queue
                    (source_text, strategy_id, submitted_at, status)
                VALUES ('夏目彩春', 1, '2024-01-01T00:00:00.000Z', 'pending')
                """));

        assertTrue(repo.hasPending("label_basic", "夏目彩春"));
    }

    @Test
    void hasPending_trueWhenInFlightRowExists() {
        jdbi.useHandle(h -> h.execute("""
                INSERT INTO translation_queue
                    (source_text, strategy_id, submitted_at, status)
                VALUES ('夏目彩春', 1, '2024-01-01T00:00:00.000Z', 'in_flight')
                """));

        assertTrue(repo.hasPending("label_basic", "夏目彩春"));
    }

    @Test
    void hasPending_falseWhenOnlyDoneRowExists() {
        jdbi.useHandle(h -> h.execute("""
                INSERT INTO translation_queue
                    (source_text, strategy_id, submitted_at, status)
                VALUES ('夏目彩春', 1, '2024-01-01T00:00:00.000Z', 'done')
                """));

        assertFalse(repo.hasPending("label_basic", "夏目彩春"));
    }

    @Test
    void hasPending_falseWhenOnlyTier2PendingRowExists() {
        jdbi.useHandle(h -> h.execute("""
                INSERT INTO translation_queue
                    (source_text, strategy_id, submitted_at, status)
                VALUES ('夏目彩春', 1, '2024-01-01T00:00:00.000Z', 'tier_2_pending')
                """));

        assertFalse(repo.hasPending("label_basic", "夏目彩春"));
    }

    @Test
    void hasPending_falseWhenNoRowsExist() {
        assertFalse(repo.hasPending("label_basic", "存在しない"));
    }

    @Test
    void hasPending_falseWhenStrategyNameDoesNotMatch() {
        jdbi.useHandle(h -> h.execute("""
                INSERT INTO translation_queue
                    (source_text, strategy_id, submitted_at, status)
                VALUES ('夏目彩春', 1, '2024-01-01T00:00:00.000Z', 'pending')
                """));

        assertFalse(repo.hasPending("other_strategy", "夏目彩春"));
    }

    @Test
    void hasPending_queued_statusAllowsStageNameStatusEndpointToReturnQueued() {
        // Integration-style: stage-name-status endpoint returns "queued" on a kanji
        // that has a translation_queue row but no stage_name_suggestion yet.
        jdbi.useHandle(h -> h.execute("""
                INSERT INTO translation_queue
                    (source_text, strategy_id, submitted_at, status)
                VALUES ('麻美ゆま', 1, '2024-01-01T00:00:00.000Z', 'pending')
                """));

        assertTrue(repo.hasPending("label_basic", "麻美ゆま"),
                "hasPending must return true so status endpoint returns 'queued', not 'missing'");
    }
}
