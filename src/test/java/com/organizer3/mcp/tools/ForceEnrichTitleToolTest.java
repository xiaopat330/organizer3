package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.organizer3.db.SchemaInitializer;
import com.organizer3.db.TitleEffectiveTagsService;
import com.organizer3.javdb.JavdbClient;
import com.organizer3.javdb.JavdbConfig;
import com.organizer3.javdb.JavdbNotFoundException;
import com.organizer3.javdb.enrichment.EnrichmentHistoryRepository;
import com.organizer3.javdb.enrichment.EnrichmentQueue;
import com.organizer3.javdb.enrichment.EnrichmentReviewQueueRepository;
import com.organizer3.javdb.enrichment.JavdbEnrichmentRepository;
import com.organizer3.javdb.enrichment.JavdbExtractor;
import com.organizer3.javdb.enrichment.JavdbStagingRepository;
import com.organizer3.javdb.enrichment.RevalidationPendingRepository;
import com.organizer3.javdb.enrichment.TitleExtract;
import com.organizer3.repository.jdbi.JdbiTitleLocationRepository;
import com.organizer3.repository.jdbi.JdbiTitleRepository;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ForceEnrichTitleToolTest {

    private static final ObjectMapper M = new ObjectMapper();

    private Connection connection;
    private Jdbi jdbi;
    private JdbiTitleRepository titleRepo;
    private JavdbEnrichmentRepository enrichmentRepo;
    private EnrichmentReviewQueueRepository reviewQueueRepo;
    private RevalidationPendingRepository revalidationPendingRepo;
    private EnrichmentHistoryRepository historyRepo;

    private static final JavdbConfig CONFIG = new JavdbConfig(true, 1.0, 3, new int[]{1, 5, 30}, 5, null, null, null, null, 3, null, null);

    private JavdbClient mockClient;
    private JavdbExtractor mockExtractor;
    private JavdbStagingRepository mockStagingRepo;
    private EnrichmentQueue enrichmentQueue;

    private ForceEnrichTitleTool tool;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        jdbi.useHandle(h -> {
            h.execute("INSERT INTO volumes (id, structure_type) VALUES ('vol1', 'conventional')");
            h.execute("INSERT INTO titles (id, code, base_code, label, seq_num) VALUES (1, 'TEST-001', 'TEST', 'TEST', 1)");
        });

        var titleLocationRepo = new JdbiTitleLocationRepository(jdbi);
        titleRepo              = new JdbiTitleRepository(jdbi, titleLocationRepo);
        historyRepo            = new EnrichmentHistoryRepository(jdbi, M);
        enrichmentRepo         = new JavdbEnrichmentRepository(jdbi, M, new TitleEffectiveTagsService(jdbi), historyRepo);
        reviewQueueRepo        = new EnrichmentReviewQueueRepository(jdbi);
        revalidationPendingRepo = new RevalidationPendingRepository(jdbi);

        mockClient      = Mockito.mock(JavdbClient.class);
        mockExtractor   = Mockito.mock(JavdbExtractor.class);
        mockStagingRepo = Mockito.mock(JavdbStagingRepository.class);
        enrichmentQueue = new EnrichmentQueue(jdbi, CONFIG);

        when(mockStagingRepo.saveTitleRaw(any(), any())).thenReturn("javdb_raw/title/AbCd12.json");

        tool = new ForceEnrichTitleTool(jdbi, titleRepo, mockClient, mockExtractor,
                mockStagingRepo, enrichmentRepo, reviewQueueRepo, revalidationPendingRepo, enrichmentQueue);
    }

    @AfterEach
    void tearDown() throws Exception { connection.close(); }

    // ── happy path ────────────────────────────────────────────────────────────

    @Test
    void happyPath_writesEnrichment_snapshotsHistory_resolvesQueueRows() {
        // Seed a prior enrichment row so appendIfExists has something to snapshot.
        enrichmentRepo.upsertEnrichment(1L, "OldSlug", null, makeExtract("OldSlug"),
                "actress_filmography", "MEDIUM", true);

        // Pre-existing queue rows for the title
        reviewQueueRepo.enqueue(1L, "AbCd12", "cast_anomaly",  "actress_filmography");
        reviewQueueRepo.enqueue(1L, "AbCd12", "ambiguous",     "code_search_fallback");

        TitleExtract extract = makeExtract("AbCd12");
        when(mockClient.fetchTitlePage("AbCd12")).thenReturn("<html/>");
        when(mockExtractor.extractTitle(eq("<html/>"), eq("TEST-001"), eq("AbCd12")))
                .thenReturn(extract);

        var result = (ForceEnrichTitleTool.Result) tool.call(args(1L, "AbCd12", false));

        assertTrue(result.ok());
        assertNull(result.error());

        // Enrichment row written with manual/HIGH/castValidated=false
        var row = jdbi.withHandle(h ->
                h.createQuery("SELECT resolver_source, confidence, cast_validated FROM title_javdb_enrichment WHERE title_id = 1")
                        .map((rs, ctx) -> new Object[]{
                                rs.getString("resolver_source"),
                                rs.getString("confidence"),
                                rs.getInt("cast_validated")})
                        .one());
        assertEquals("manual",  row[0]);
        assertEquals("HIGH",    row[1]);
        assertEquals(0,         row[2]);

        // History entry present with reason manual_override (not enrichment_runner)
        int historyCount = jdbi.withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM title_javdb_enrichment_history WHERE title_id = 1 AND reason = 'manual_override'")
                        .mapTo(Integer.class).one());
        assertEquals(1, historyCount, "history snapshot must use reason=manual_override");

        // All open queue rows resolved
        assertEquals(0, reviewQueueRepo.countOpen("cast_anomaly"), "cast_anomaly must be resolved");
        assertEquals(0, reviewQueueRepo.countOpen("ambiguous"),    "ambiguous must be resolved");

        // Revalidation enqueued
        assertEquals(1, revalidationPendingRepo.countPending(), "revalidation must be enqueued");
    }

    @Test
    void happyPath_dischargesFailedWorkQueueRow() {
        // Seed a failed work-queue row for title 1
        jdbi.useHandle(h -> h.execute(
                "INSERT INTO javdb_enrichment_queue (job_type, target_id, actress_id, source, priority, status, attempts, next_attempt_at, created_at, updated_at, last_error) " +
                "VALUES ('fetch_title', 1, 0, 'actress', 'NORMAL', 'failed', 1, datetime('now'), datetime('now'), datetime('now'), 'ambiguous')"));

        TitleExtract extract = makeExtract("AbCd12");
        when(mockClient.fetchTitlePage("AbCd12")).thenReturn("<html/>");
        when(mockExtractor.extractTitle(eq("<html/>"), eq("TEST-001"), eq("AbCd12"))).thenReturn(extract);

        var result = (ForceEnrichTitleTool.Result) tool.call(args(1L, "AbCd12", false));
        assertTrue(result.ok());

        var workRow = jdbi.withHandle(h ->
                h.createQuery("SELECT status, last_error FROM javdb_enrichment_queue WHERE target_id = 1")
                        .mapToMap().one());
        assertEquals("done", workRow.get("status"), "failed work-queue row must be discharged to done");
        assertTrue(workRow.get("last_error").toString().contains("[resolved: manual_override]"),
                "last_error must be annotated with resolution tag");
    }

    // ── 404 path ─────────────────────────────────────────────────────────────

    @Test
    void slugNotFound_returnsOkFalse_error_slug_not_found() {
        when(mockClient.fetchTitlePage("ZzZz99"))
                .thenThrow(new JavdbNotFoundException("https://javdb.com/v/ZzZz99"));

        var result = (ForceEnrichTitleTool.Result) tool.call(args(1L, "ZzZz99", false));

        assertFalse(result.ok());
        assertEquals("slug_not_found", result.error());
        verify(mockStagingRepo, never()).saveTitleRaw(any(), any());
    }

    // ── dry_run path ──────────────────────────────────────────────────────────

    @Test
    void dryRun_returnsExtract_doesNotWrite() {
        reviewQueueRepo.enqueue(1L, "AbCd12", "cast_anomaly", "actress_filmography");

        TitleExtract extract = makeExtract("AbCd12");
        when(mockClient.fetchTitlePage("AbCd12")).thenReturn("<html/>");
        when(mockExtractor.extractTitle(eq("<html/>"), eq("TEST-001"), eq("AbCd12")))
                .thenReturn(extract);

        var result = (ForceEnrichTitleTool.Result) tool.call(args(1L, "AbCd12", true));

        assertTrue(result.ok());
        assertNotNull(result.extract(), "dry_run must return the parsed extract");
        assertNotNull(result.message());
        assertTrue(result.message().contains("[dry_run]"));

        // No writes
        verify(mockStagingRepo, never()).saveTitleRaw(any(), any());
        int enrichCount = jdbi.withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM title_javdb_enrichment").mapTo(Integer.class).one());
        assertEquals(0, enrichCount, "dry_run must not write enrichment row");
        assertEquals(1, reviewQueueRepo.countOpen("cast_anomaly"), "dry_run must not resolve queue rows");
        assertEquals(0, revalidationPendingRepo.countPending(), "dry_run must not enqueue revalidation");
    }

    // ── dry_run default (no dry_run arg) ─────────────────────────────────────

    @Test
    void dryRun_isDefaultWhenArgAbsent() {
        TitleExtract extract = makeExtract("AbCd12");
        when(mockClient.fetchTitlePage("AbCd12")).thenReturn("<html/>");
        when(mockExtractor.extractTitle(any(), any(), any())).thenReturn(extract);

        ObjectNode a = M.createObjectNode();
        a.put("title_id", 1L);
        a.put("slug",     "AbCd12");
        // dry_run not set → should default to true

        var result = (ForceEnrichTitleTool.Result) tool.call(a);

        assertTrue(result.ok());
        assertTrue(result.message().contains("[dry_run]"), "absent dry_run must default to true");
        verify(mockStagingRepo, never()).saveTitleRaw(any(), any());
    }

    // ── invalid slug regex ────────────────────────────────────────────────────

    @Test
    void invalidSlug_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class,
                () -> tool.call(args(1L, "Ab-Cd!12", false)),
                "slug with non-alphanumeric characters must be rejected");
    }

    // ── missing title ────────────────────────────────────────────────────────

    @Test
    void missingTitle_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class,
                () -> tool.call(args(9999L, "AbCd12", false)),
                "unknown title_id must throw");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private ObjectNode args(long titleId, String slug, boolean dryRun) {
        ObjectNode node = M.createObjectNode();
        node.put("title_id", titleId);
        node.put("slug",     slug);
        node.put("dry_run",  dryRun);
        return node;
    }

    private TitleExtract makeExtract(String slug) {
        return new TitleExtract(
                "TEST-001", slug, "Test Title", "2024-01-01",
                90, "Test Maker", null, null,
                4.5, 100,
                List.of("tag1", "tag2"),
                List.of(),
                "https://cdn.javdb.com/cover.jpg",
                List.of(),
                "2024-01-01T00:00:00Z",
                false, false);
    }
}
