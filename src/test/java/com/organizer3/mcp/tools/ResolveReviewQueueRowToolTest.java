package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.organizer3.db.SchemaInitializer;
import com.organizer3.javdb.JavdbConfig;
import com.organizer3.javdb.enrichment.EnrichmentQueue;
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

    private static final JavdbConfig CONFIG = new JavdbConfig(true, 1.0, 3, new int[]{1, 5, 30}, 5, null, null, null, null, 3, null, null);

    private Connection connection;
    private Jdbi jdbi;
    private EnrichmentReviewQueueRepository repo;
    private EnrichmentQueue enrichmentQueue;
    private ResolveReviewQueueRowTool tool;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        jdbi.useHandle(h ->
                h.execute("INSERT INTO titles(id, code, base_code, label, seq_num) VALUES (1, 'T-1', 'T', 'T', 1)"));
        repo = new EnrichmentReviewQueueRepository(jdbi);
        enrichmentQueue = new EnrichmentQueue(jdbi, CONFIG);
        tool = new ResolveReviewQueueRowTool(jdbi, repo, enrichmentQueue);
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

    // ── discharge integration tests ────────────────────────────────────────────

    @Test
    void acceptedGap_discharges_failedWorkQueueRow() {
        seedFailedWorkQueueRow(1L);
        repo.enqueue(1L, "slug1", "ambiguous", "code_search_fallback");
        long id = rowId();

        tool.call(args(id, "accepted_gap"));

        assertWorkQueueDischarged("accepted_gap");
    }

    @Test
    void markedResolved_discharges_failedWorkQueueRow() {
        seedFailedWorkQueueRow(1L);
        repo.enqueue(1L, "slug1", "ambiguous", "code_search_fallback");
        long id = rowId();

        tool.call(args(id, "marked_resolved"));

        assertWorkQueueDischarged("marked_resolved");
    }

    @Test
    void dismissed_discharges_failedWorkQueueRow() {
        seedFailedWorkQueueRow(1L);
        repo.enqueue(1L, "slug1", "ambiguous", "code_search_fallback");
        long id = rowId();

        tool.call(args(id, "dismissed"));

        assertWorkQueueDischarged("dismissed");
    }

    @Test
    void markedMoved_does_NOT_discharge_workQueueRow() {
        seedFailedWorkQueueRow(1L);
        repo.enqueue(1L, "slug1", "ambiguous", "code_search_fallback");
        long id = rowId();

        tool.call(args(id, "marked_moved"));

        String status = jdbi.withHandle(h ->
                h.createQuery("SELECT status FROM javdb_enrichment_queue WHERE target_id = 1")
                        .mapTo(String.class).one());
        assertEquals("failed", status, "marked_moved must not discharge the work-queue row");
    }

    @Test
    void confirmedDelete_does_NOT_discharge_workQueueRow() {
        seedFailedWorkQueueRow(1L);
        repo.enqueue(1L, "slug1", "ambiguous", "code_search_fallback");
        long id = rowId();

        tool.call(args(id, "confirmed_delete"));

        String status = jdbi.withHandle(h ->
                h.createQuery("SELECT status FROM javdb_enrichment_queue WHERE target_id = 1")
                        .mapTo(String.class).one());
        assertEquals("failed", status, "confirmed_delete must not discharge the work-queue row");
    }

    private void seedFailedWorkQueueRow(long titleId) {
        jdbi.useHandle(h -> h.execute(
                "INSERT INTO javdb_enrichment_queue (job_type, target_id, actress_id, source, priority, status, attempts, next_attempt_at, created_at, updated_at, last_error) " +
                "VALUES ('fetch_title', ?, 0, 'actress', 'NORMAL', 'failed', 1, datetime('now'), datetime('now'), datetime('now'), 'ambiguous')",
                titleId));
    }

    private void assertWorkQueueDischarged(String expectedResolution) {
        var row = jdbi.withHandle(h ->
                h.createQuery("SELECT status, last_error FROM javdb_enrichment_queue WHERE target_id = 1")
                        .mapToMap().one());
        assertEquals("done", row.get("status"), "work-queue row must be discharged to done");
        assertTrue(row.get("last_error").toString().contains("[resolved: " + expectedResolution + "]"),
                "last_error must be annotated with resolution tag");
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
