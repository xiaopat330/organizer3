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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ListReviewQueueToolTest {

    private static final ObjectMapper M = new ObjectMapper();

    private Connection connection;
    private EnrichmentReviewQueueRepository repo;
    private ListReviewQueueTool tool;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        Jdbi jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        jdbi.useHandle(h -> {
            h.execute("INSERT INTO titles(id, code, base_code, label, seq_num) VALUES (1, 'T-1', 'T', 'T', 1)");
            h.execute("INSERT INTO titles(id, code, base_code, label, seq_num) VALUES (2, 'T-2', 'T', 'T', 2)");
            h.execute("INSERT INTO titles(id, code, base_code, label, seq_num) VALUES (3, 'T-3', 'T', 'T', 3)");
        });
        repo = new EnrichmentReviewQueueRepository(jdbi);
        tool = new ListReviewQueueTool(repo);
    }

    @AfterEach
    void tearDown() throws Exception { connection.close(); }

    @Test
    void emptyState_returnsEmptyRowsAndEmptyCounts() {
        var result = (ListReviewQueueTool.Result) tool.call(M.createObjectNode());

        assertTrue(result.rows().isEmpty());
        assertTrue(result.counts().isEmpty());
    }

    @Test
    void mixedState_countsMapShowsAllOpenReasons() {
        repo.enqueue(1L, "slug1", "ambiguous",   "sentinel_short_circuit");
        repo.enqueue(2L, "slug2", "cast_anomaly", "actress_filmography");
        repo.enqueue(3L, "slug3", "ambiguous",    "code_search_fallback");

        var result = (ListReviewQueueTool.Result) tool.call(M.createObjectNode());

        Map<String, Integer> counts = result.counts();
        assertEquals(2, counts.get("ambiguous"),    "two ambiguous rows");
        assertEquals(1, counts.get("cast_anomaly"), "one cast_anomaly row");
        assertEquals(3, result.rows().size(),       "all three open rows returned");
    }

    @Test
    void filterByReason_returnsOnlyMatchingRows() {
        repo.enqueue(1L, "slug1", "ambiguous",    "sentinel_short_circuit");
        repo.enqueue(2L, "slug2", "cast_anomaly", "actress_filmography");

        ObjectNode args = M.createObjectNode();
        args.put("reason", "ambiguous");
        var result = (ListReviewQueueTool.Result) tool.call(args);

        assertEquals(1, result.rows().size());
        assertEquals("ambiguous", result.rows().get(0).reason());
        // counts are always full (unfiltered)
        assertEquals(1, result.counts().get("cast_anomaly"));
    }
}
