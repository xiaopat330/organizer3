package com.organizer3.javdb.enrichment;

import com.organizer3.db.SchemaInitializer;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RevalidationPendingRepositoryTest {

    private Connection connection;
    private Jdbi jdbi;
    private RevalidationPendingRepository repo;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        repo = new RevalidationPendingRepository(jdbi);
        // Seed a title row so FK constraint is satisfied
        jdbi.useHandle(h -> h.execute(
                "INSERT INTO titles(id, code, base_code, label, seq_num) VALUES (1,'TST-1','TST-1','TST',1)"));
    }

    @AfterEach
    void tearDown() throws Exception { connection.close(); }

    @Test
    void enqueue_and_count() {
        assertEquals(0, repo.countPending());
        repo.enqueue(1L, "drift");
        assertEquals(1, repo.countPending());
    }

    @Test
    void enqueue_isIdempotent() {
        repo.enqueue(1L, "drift");
        repo.enqueue(1L, "drift"); // same title_id — PK collapse
        assertEquals(1, repo.countPending());
    }

    @Test
    void drainBatch_returnsAndDeletesRows() {
        repo.enqueue(1L, "drift");

        List<RevalidationPendingRepository.Pending> batch = repo.drainBatch(10);
        assertEquals(1, batch.size());
        assertEquals(1L, batch.get(0).titleId());
        assertEquals("drift", batch.get(0).reason());
        assertNotNull(batch.get(0).enqueuedAt());

        assertEquals(0, repo.countPending(), "rows must be deleted after drain");
    }

    @Test
    void drainBatch_respectsLimit() {
        // Need additional title rows
        jdbi.useHandle(h -> {
            h.execute("INSERT INTO titles(id, code, base_code, label, seq_num) VALUES (2,'TST-2','TST-2','TST',2)");
            h.execute("INSERT INTO titles(id, code, base_code, label, seq_num) VALUES (3,'TST-3','TST-3','TST',3)");
        });
        repo.enqueue(1L, "drift");
        repo.enqueue(2L, "drift");
        repo.enqueue(3L, "drift");

        List<RevalidationPendingRepository.Pending> batch = repo.drainBatch(2);
        assertEquals(2, batch.size());
        assertEquals(1, repo.countPending(), "one row should remain after partial drain");
    }

    @Test
    void drainBatch_emptyQueueReturnsEmptyList() {
        List<RevalidationPendingRepository.Pending> batch = repo.drainBatch(10);
        assertTrue(batch.isEmpty());
        assertEquals(0, repo.countPending());
    }

    @Test
    void drainBatch_fifoOrder() throws Exception {
        jdbi.useHandle(h ->
                h.execute("INSERT INTO titles(id, code, base_code, label, seq_num) VALUES (2,'TST-2','TST-2','TST',2)"));
        // Insert with explicit timestamps to guarantee order
        jdbi.useHandle(h -> h.execute(
                "INSERT INTO revalidation_pending (title_id, reason, enqueued_at) VALUES (1, 'drift', '2024-01-01T00:00:00.000Z')"));
        jdbi.useHandle(h -> h.execute(
                "INSERT OR IGNORE INTO revalidation_pending (title_id, reason, enqueued_at) VALUES (2, 'drift', '2024-01-02T00:00:00.000Z')"));

        List<RevalidationPendingRepository.Pending> batch = repo.drainBatch(10);
        assertEquals(2, batch.size());
        assertEquals(1L, batch.get(0).titleId(), "oldest enqueued_at must come first");
        assertEquals(2L, batch.get(1).titleId());
    }
}
