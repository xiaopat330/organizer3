package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.organizer3.db.SchemaInitializer;
import com.organizer3.javdb.enrichment.DisambiguationSnapshotter;
import com.organizer3.javdb.enrichment.EnrichmentReviewQueueRepository;
import com.organizer3.repository.TitleRepository;
import com.organizer3.repository.jdbi.JdbiTitleLocationRepository;
import com.organizer3.repository.jdbi.JdbiTitleRepository;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.sql.Connection;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RefreshReviewCandidatesToolTest {

    private static final ObjectMapper M = new ObjectMapper();

    private Connection connection;
    private Jdbi jdbi;
    private EnrichmentReviewQueueRepository reviewQueueRepo;
    private TitleRepository titleRepo;
    private DisambiguationSnapshotter mockSnapshotter;
    private RefreshReviewCandidatesTool tool;

    private long titleId = 1L;
    private long queueRowId;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        jdbi.useHandle(h -> {
            h.execute("INSERT INTO volumes (id, structure_type) VALUES ('vol1', 'conventional')");
            h.execute("INSERT INTO titles (id, code, base_code, label, seq_num) VALUES (1, 'STAR-334', 'STAR', 'STAR', 334)");
        });

        reviewQueueRepo = new EnrichmentReviewQueueRepository(jdbi);
        titleRepo       = new JdbiTitleRepository(jdbi, new JdbiTitleLocationRepository(jdbi));
        mockSnapshotter = Mockito.mock(DisambiguationSnapshotter.class);

        tool = new RefreshReviewCandidatesTool(reviewQueueRepo, titleRepo, mockSnapshotter);

        // Insert an open ambiguous row (null detail — simulates legacy rows)
        reviewQueueRepo.enqueue(titleId, null, "ambiguous", "sentinel_short_circuit");
        queueRowId = jdbi.withHandle(h ->
                h.createQuery("SELECT id FROM enrichment_review_queue WHERE title_id = :id")
                        .bind("id", titleId)
                        .mapTo(Long.class).one());
    }

    @AfterEach
    void tearDown() throws Exception { connection.close(); }

    @Test
    void happyPath_buildsSnapshotAndPersistsDetail() throws Exception {
        String freshDetail = "{\"code\":\"STAR-334\",\"candidates\":[{\"slug\":\"AbCd12\"}]}";
        when(mockSnapshotter.buildSnapshot(eq(titleId), eq("STAR-334"), isNull(), isNull()))
                .thenReturn(freshDetail);

        ObjectNode args = M.createObjectNode();
        args.put("queue_row_id", queueRowId);
        var result = (RefreshReviewCandidatesTool.Result) tool.call(args);

        assertTrue(result.ok(), "should succeed");
        assertEquals(freshDetail, result.detailJson());

        // Detail persisted in DB
        var row = reviewQueueRepo.findOpenById(queueRowId);
        assertTrue(row.isPresent());
        assertEquals(freshDetail, row.get().detail());

        verify(mockSnapshotter).buildSnapshot(eq(titleId), eq("STAR-334"), isNull(), isNull());
    }

    @Test
    void rowNotFound_returnsError() throws Exception {
        ObjectNode args = M.createObjectNode();
        args.put("queue_row_id", 9999L);
        var result = (RefreshReviewCandidatesTool.Result) tool.call(args);

        assertFalse(result.ok());
        assertEquals("row_not_found", result.error());
        verifyNoInteractions(mockSnapshotter);
    }

    @Test
    void titleNotFound_returnsError() throws Exception {
        // Insert a queue row with an orphan title_id (no corresponding titles row).
        // findOpenById uses LEFT JOIN so it returns the row; titleRepo.findById fails.
        jdbi.useHandle(h -> h.execute(
                "INSERT INTO enrichment_review_queue (title_id, slug, reason, resolver_source) " +
                "VALUES (999, null, 'ambiguous', 'sentinel_short_circuit')"));
        long orphanRowId = jdbi.withHandle(h ->
                h.createQuery("SELECT id FROM enrichment_review_queue WHERE title_id = 999")
                        .mapTo(Long.class).one());

        ObjectNode args = M.createObjectNode();
        args.put("queue_row_id", orphanRowId);
        var result = (RefreshReviewCandidatesTool.Result) tool.call(args);

        assertFalse(result.ok());
        assertEquals("title_not_found", result.error());
        verifyNoInteractions(mockSnapshotter);
    }

    @Test
    void snapshotBuildFails_returnsError() throws Exception {
        when(mockSnapshotter.buildSnapshot(anyLong(), anyString(), any(), any()))
                .thenReturn(null);

        ObjectNode args = M.createObjectNode();
        args.put("queue_row_id", queueRowId);
        var result = (RefreshReviewCandidatesTool.Result) tool.call(args);

        assertFalse(result.ok());
        assertEquals("snapshot_build_failed", result.error());
    }
}
