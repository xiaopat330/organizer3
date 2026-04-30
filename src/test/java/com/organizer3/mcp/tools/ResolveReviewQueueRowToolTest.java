package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.organizer3.db.SchemaInitializer;
import com.organizer3.javdb.enrichment.EnrichmentReviewQueueRepository;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.*;

class ResolveReviewQueueRowToolTest {

    private static final ObjectMapper M = new ObjectMapper();

    private Connection connection;
    private Jdbi jdbi;
    private EnrichmentReviewQueueRepository repo;
    private ResolveReviewQueueRowTool tool;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        jdbi.useHandle(h ->
                h.execute("INSERT INTO titles(id, code, base_code, label, seq_num) VALUES (1, 'T-1', 'T', 'T', 1)"));
        repo = new EnrichmentReviewQueueRepository(jdbi);
        tool = new ResolveReviewQueueRowTool(repo);
    }

    @AfterEach
    void tearDown() throws Exception { connection.close(); }

    @Test
    void happyPath_openRow_resolvedSuccessfully() {
        repo.enqueue(1L, "slug1", "ambiguous", "sentinel_short_circuit");
        long id = rowId();

        var result = (ResolveReviewQueueRowTool.Result) tool.call(args(id, "accepted_gap"));

        assertTrue(result.ok());
        assertTrue(result.message().contains(String.valueOf(id)));
    }

    @Test
    void alreadyResolved_returnsFalse_withIndicativeMessage() {
        repo.enqueue(1L, "slug1", "ambiguous", "sentinel_short_circuit");
        long id = rowId();
        tool.call(args(id, "accepted_gap"));

        var result = (ResolveReviewQueueRowTool.Result) tool.call(args(id, "marked_resolved"));

        assertFalse(result.ok());
        assertTrue(result.message().contains("already resolved") || result.message().contains("not found"));
    }

    @Test
    void invalidResolution_throwsIllegalArgumentException() {
        repo.enqueue(1L, "slug1", "ambiguous", "sentinel_short_circuit");
        long id = rowId();

        assertThrows(IllegalArgumentException.class,
                () -> tool.call(args(id, "manual_slug")),
                "manual_slug is not in the MVP allowlist and must be rejected");
    }

    private long rowId() {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT id FROM enrichment_review_queue WHERE title_id = 1")
                        .mapTo(Long.class).one());
    }

    private ObjectNode args(long id, String resolution) {
        ObjectNode node = M.createObjectNode();
        node.put("id",         id);
        node.put("resolution", resolution);
        return node;
    }
}
