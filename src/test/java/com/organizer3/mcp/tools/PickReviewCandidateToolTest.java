package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.organizer3.db.SchemaInitializer;
import com.organizer3.db.TitleEffectiveTagsService;
import com.organizer3.javdb.JavdbConfig;
import com.organizer3.javdb.enrichment.EnrichmentHistoryRepository;
import com.organizer3.javdb.enrichment.EnrichmentQueue;
import com.organizer3.javdb.enrichment.EnrichmentReviewQueueRepository;
import com.organizer3.javdb.enrichment.JavdbEnrichmentRepository;
import com.organizer3.javdb.enrichment.JavdbStagingRepository;
import com.organizer3.javdb.enrichment.RevalidationPendingRepository;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.sql.Connection;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PickReviewCandidateToolTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static final String DETAIL_JSON = """
            {
              "code": "TEST-001",
              "linked_slugs": [],
              "candidates": [
                {
                  "slug": "AbCd12",
                  "title_original": "Test Title",
                  "release_date": "2024-01-01",
                  "maker": "Test Studio",
                  "publisher": null,
                  "series": null,
                  "cover_url": "https://cdn.example.com/cover.jpg",
                  "cast": [{"slug": "actor1", "name": "Actor One", "gender": "F"}],
                  "tags": ["tag1"],
                  "thumbnail_urls": [],
                  "fetched_at": "2024-01-01T00:00:00Z",
                  "cast_empty": false
                },
                {
                  "slug": "XyZz99",
                  "title_original": "Other Title",
                  "release_date": "2019-06-15",
                  "maker": "Other Studio",
                  "publisher": null,
                  "series": null,
                  "cover_url": "https://cdn.example.com/other.jpg",
                  "cast": [],
                  "tags": [],
                  "thumbnail_urls": [],
                  "fetched_at": "2024-01-01T00:00:00Z",
                  "cast_empty": true
                }
              ],
              "fetched_at": "2024-01-01T00:00:00Z"
            }
            """;

    private static final JavdbConfig CONFIG = new JavdbConfig(true, 1.0, 3, new int[]{1, 5, 30}, 5, null, null, null, null, 3, null, null);

    private Connection connection;
    private Jdbi jdbi;
    private EnrichmentReviewQueueRepository reviewQueueRepo;
    private JavdbEnrichmentRepository enrichmentRepo;
    private RevalidationPendingRepository revalidationPendingRepo;
    private EnrichmentQueue enrichmentQueue;
    private JavdbStagingRepository mockStagingRepo;
    private PickReviewCandidateTool tool;

    private long queueRowId;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        jdbi.useHandle(h -> {
            h.execute("INSERT INTO volumes (id, structure_type) VALUES ('vol1', 'conventional')");
            h.execute("INSERT INTO titles (id, code, base_code, label, seq_num) VALUES (1, 'TEST-001', 'TEST', 'TEST', 1)");
        });

        reviewQueueRepo        = new EnrichmentReviewQueueRepository(jdbi);
        var historyRepo        = new EnrichmentHistoryRepository(jdbi, M);
        enrichmentRepo         = new JavdbEnrichmentRepository(jdbi, M, new TitleEffectiveTagsService(jdbi), historyRepo);
        revalidationPendingRepo = new RevalidationPendingRepository(jdbi);
        enrichmentQueue        = new EnrichmentQueue(jdbi, CONFIG);
        mockStagingRepo        = Mockito.mock(JavdbStagingRepository.class);

        when(mockStagingRepo.saveTitleRaw(any(), any())).thenReturn("javdb_raw/title/AbCd12.json");

        tool = new PickReviewCandidateTool(jdbi, reviewQueueRepo, enrichmentRepo,
                mockStagingRepo, revalidationPendingRepo, enrichmentQueue);

        // Insert an ambiguous row with a snapshot
        reviewQueueRepo.enqueueWithDetail(1L, "AbCd12", "ambiguous", "code_search_fallback", DETAIL_JSON);
        queueRowId = jdbi.withHandle(h ->
                h.createQuery("SELECT id FROM enrichment_review_queue WHERE title_id = 1")
                        .mapTo(Long.class).one());
    }

    @AfterEach
    void tearDown() throws Exception { connection.close(); }

    @Test
    void happyPath_picksCandidate_writesEnrichment_resolvesRow() throws Exception {
        ObjectNode args = M.createObjectNode();
        args.put("queue_row_id", queueRowId);
        args.put("slug", "AbCd12");

        var result = (PickReviewCandidateTool.Result) tool.call(args);

        assertTrue(result.ok(), "should succeed");
        assertNull(result.error());

        // Enrichment row written
        var enrichRow = jdbi.withHandle(h ->
                h.createQuery("SELECT javdb_slug, resolver_source, confidence FROM title_javdb_enrichment WHERE title_id = 1")
                        .mapToMap().findOne());
        assertTrue(enrichRow.isPresent(), "enrichment row must be written");
        assertEquals("AbCd12", enrichRow.get().get("javdb_slug"));
        assertEquals("manual_picker", enrichRow.get().get("resolver_source"));
        assertEquals("HIGH", enrichRow.get().get("confidence"));

        // Queue row resolved
        assertFalse(reviewQueueRepo.hasOpen(1L, "ambiguous"), "queue row must be resolved");

        // Revalidation enqueued
        var revalRow = jdbi.withHandle(h ->
                h.createQuery("SELECT reason FROM revalidation_pending WHERE title_id = 1")
                        .mapTo(String.class).findOne());
        assertTrue(revalRow.isPresent(), "revalidation must be enqueued");
        assertEquals("manual_picker", revalRow.get());

        verify(mockStagingRepo).saveTitleRaw(eq("AbCd12"), any());
    }

    @Test
    void happyPath_dischargesFailedWorkQueueRow() throws Exception {
        // Seed a failed fetch_title row in the work queue for title 1
        jdbi.useHandle(h -> h.execute(
                "INSERT INTO javdb_enrichment_queue (job_type, target_id, actress_id, source, priority, status, attempts, next_attempt_at, created_at, updated_at, last_error) " +
                "VALUES ('fetch_title', 1, 0, 'actress', 'NORMAL', 'failed', 1, datetime('now'), datetime('now'), datetime('now'), 'ambiguous')"));

        ObjectNode args = M.createObjectNode();
        args.put("queue_row_id", queueRowId);
        args.put("slug", "AbCd12");

        var result = (PickReviewCandidateTool.Result) tool.call(args);
        assertTrue(result.ok());

        // Work-queue row must now be done with annotation
        var workRow = jdbi.withHandle(h ->
                h.createQuery("SELECT status, last_error FROM javdb_enrichment_queue WHERE target_id = 1")
                        .mapToMap().one());
        assertEquals("done", workRow.get("status"), "failed work-queue row must be discharged to done");
        assertTrue(workRow.get("last_error").toString().contains("[resolved: manual_picker]"),
                "last_error must be annotated with resolution tag");
    }

    @Test
    void snapshotMissing_returnsError() throws Exception {
        // Insert a row without detail
        jdbi.useHandle(h -> h.execute(
                "INSERT INTO titles (id, code, base_code, label, seq_num) VALUES (2, 'T-2', 'T', 'T', 2)"));
        reviewQueueRepo.enqueue(2L, "slug2", "ambiguous", "code_search_fallback");
        long rowId = jdbi.withHandle(h ->
                h.createQuery("SELECT id FROM enrichment_review_queue WHERE title_id = 2")
                        .mapTo(Long.class).one());

        ObjectNode args = M.createObjectNode();
        args.put("queue_row_id", rowId);
        args.put("slug", "slug2");

        var result = (PickReviewCandidateTool.Result) tool.call(args);

        assertFalse(result.ok());
        assertEquals("snapshot_missing", result.error());
    }

    @Test
    void slugNotInCandidates_returnsError() throws Exception {
        ObjectNode args = M.createObjectNode();
        args.put("queue_row_id", queueRowId);
        args.put("slug", "UnknownSlug");

        var result = (PickReviewCandidateTool.Result) tool.call(args);

        assertFalse(result.ok());
        assertEquals("slug_not_in_candidates", result.error());
        assertTrue(reviewQueueRepo.hasOpen(1L, "ambiguous"),
                "row must remain open when slug not found");
    }

    @Test
    void alreadyResolvedRow_returnsError() throws Exception {
        reviewQueueRepo.resolveOne(queueRowId, "accepted_gap");

        ObjectNode args = M.createObjectNode();
        args.put("queue_row_id", queueRowId);
        args.put("slug", "AbCd12");

        var result = (PickReviewCandidateTool.Result) tool.call(args);

        assertFalse(result.ok());
        assertEquals("row_not_found", result.error());
    }

    @Test
    void wrongReason_castAnomaly_returnsError() throws Exception {
        jdbi.useHandle(h -> h.execute(
                "INSERT INTO titles (id, code, base_code, label, seq_num) VALUES (3, 'T-3', 'T', 'T', 3)"));
        reviewQueueRepo.enqueue(3L, "slug3", "cast_anomaly", "actress_filmography");
        long rowId = jdbi.withHandle(h ->
                h.createQuery("SELECT id FROM enrichment_review_queue WHERE title_id = 3")
                        .mapTo(Long.class).one());

        ObjectNode args = M.createObjectNode();
        args.put("queue_row_id", rowId);
        args.put("slug", "slug3");

        var result = (PickReviewCandidateTool.Result) tool.call(args);

        assertFalse(result.ok());
        assertEquals("wrong_reason", result.error());
    }
}
