package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.organizer3.db.SchemaInitializer;
import com.organizer3.javdb.enrichment.EnrichmentHistoryRepository;
import com.organizer3.javdb.enrichment.EnrichmentReviewQueueRepository;
import com.organizer3.repository.TitleRepository;
import com.organizer3.repository.jdbi.JdbiTitleLocationRepository;
import com.organizer3.repository.jdbi.JdbiTitleRepository;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.*;

class ConfirmOrphanDeleteToolTest {

    private static final ObjectMapper M = new ObjectMapper();

    private Connection connection;
    private Jdbi jdbi;
    private EnrichmentReviewQueueRepository reviewQueueRepo;
    private TitleRepository titleRepo;
    private ConfirmOrphanDeleteTool tool;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        reviewQueueRepo = new EnrichmentReviewQueueRepository(jdbi);
        EnrichmentHistoryRepository historyRepo = new EnrichmentHistoryRepository(jdbi, M);
        titleRepo = new JdbiTitleRepository(jdbi,
                new JdbiTitleLocationRepository(jdbi), historyRepo, reviewQueueRepo);
        tool = new ConfirmOrphanDeleteTool(titleRepo, reviewQueueRepo);
    }

    @AfterEach
    void tearDown() throws Exception { connection.close(); }

    private long insertTitle(String code) {
        return jdbi.withHandle(h -> {
            h.execute("INSERT INTO titles(code, base_code, label, seq_num) VALUES (?, ?, 'T', 1)", code, code);
            return h.createQuery("SELECT last_insert_rowid()").mapTo(Long.class).one();
        });
    }

    private long insertEnrichedOrphanQueueRow(long titleId) {
        jdbi.useHandle(h -> {
            h.execute("INSERT INTO title_javdb_enrichment (title_id, javdb_slug, fetched_at) VALUES (?, 't-001', '2026-01-01T00:00:00')", titleId);
            reviewQueueRepo.enqueueOrphanFlag(titleId, "t-001", null, h);
        });
        return jdbi.withHandle(h -> h.createQuery(
                "SELECT id FROM enrichment_review_queue WHERE title_id = ? AND reason = 'orphan_enriched'")
                .bind(0, titleId).mapTo(Long.class).one());
    }

    @Test
    void happyPath_deletesEnrichedTitleAndResolvesQueueRow() {
        long titleId   = insertTitle("T-001");
        long queueRowId = insertEnrichedOrphanQueueRow(titleId);

        ObjectNode args = M.createObjectNode().put("queue_row_id", queueRowId);
        var result = (ConfirmOrphanDeleteTool.Result) tool.call(args);

        assertTrue(result.ok());
        assertTrue(titleRepo.findById(titleId).isEmpty(), "title must be deleted");
        assertTrue(reviewQueueRepo.findOpenById(queueRowId).isEmpty(), "queue row must be resolved");
        // Verify queue row was resolved with 'confirmed_delete'
        String resolution = jdbi.withHandle(h -> h.createQuery(
                "SELECT resolution FROM enrichment_review_queue WHERE id = ?")
                .bind(0, queueRowId).mapTo(String.class).one());
        assertEquals("confirmed_delete", resolution);
    }

    @Test
    void alreadyResolved_throwsIllegalArgument() {
        long titleId   = insertTitle("T-002");
        long queueRowId = insertEnrichedOrphanQueueRow(titleId);
        reviewQueueRepo.resolveOne(queueRowId, "marked_moved");

        ObjectNode args = M.createObjectNode().put("queue_row_id", queueRowId);
        assertThrows(IllegalArgumentException.class, () -> tool.call(args));
    }

    @Test
    void wrongReason_throwsIllegalArgument() {
        long titleId = insertTitle("T-003");
        jdbi.useHandle(h -> reviewQueueRepo.enqueueWithDetail(
                titleId, "t-003", "cast_anomaly", "actress_filmography", null, h));
        long queueRowId = jdbi.withHandle(h -> h.createQuery(
                "SELECT id FROM enrichment_review_queue WHERE title_id = ? AND reason = 'cast_anomaly'")
                .bind(0, titleId).mapTo(Long.class).one());

        ObjectNode args = M.createObjectNode().put("queue_row_id", queueRowId);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> tool.call(args));
        assertTrue(ex.getMessage().contains("cast_anomaly"));
    }

    @Test
    void rowNotFound_throwsIllegalArgument() {
        ObjectNode args = M.createObjectNode().put("queue_row_id", 9999L);
        assertThrows(IllegalArgumentException.class, () -> tool.call(args));
    }
}
