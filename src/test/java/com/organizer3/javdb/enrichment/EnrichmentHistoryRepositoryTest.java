package com.organizer3.javdb.enrichment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.organizer3.db.SchemaInitializer;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EnrichmentHistoryRepositoryTest {

    private Connection connection;
    private Jdbi jdbi;
    private EnrichmentHistoryRepository historyRepo;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        historyRepo = new EnrichmentHistoryRepository(jdbi, new ObjectMapper());

        jdbi.useHandle(h -> {
            h.execute("INSERT INTO titles(id, code, base_code, label, seq_num) VALUES (10, 'ABC-010', 'ABC-010', 'ABC', 10)");
            h.execute("INSERT INTO titles(id, code, base_code, label, seq_num) VALUES (11, 'ABC-011', 'ABC-011', 'ABC', 11)");
        });
    }

    @AfterEach
    void tearDown() throws Exception { connection.close(); }

    @Test
    void appendIfExists_isNoOpWhenNoEnrichmentRow() {
        jdbi.useHandle(h -> historyRepo.appendIfExists(10L, "enrichment_runner", h));
        assertEquals(0, historyRepo.countForTitle(10L),
                "no enrichment row exists yet — nothing to snapshot");
    }

    @Test
    void appendIfExists_snapshotsAllColumnsIntoPayload() throws Exception {
        jdbi.useHandle(h -> h.execute("""
                INSERT INTO title_javdb_enrichment
                    (title_id, javdb_slug, fetched_at, release_date, rating_avg, rating_count,
                     maker, publisher, series, title_original, duration_minutes,
                     cover_url, thumbnail_urls_json, cast_json, raw_path,
                     resolver_source, confidence, cast_validated)
                VALUES
                    (10, 'abc-slug', '2024-01-01T00:00:00Z', '2024-01-15', 4.2, 88,
                     'S1 NO.1 STYLE', NULL, 'Test Series', '原題', 120,
                     'https://example/cover.jpg', '["t1.jpg"]', '[{"code":"a1"}]', 'raw/abc.json',
                     'actress_filmography', 'HIGH', 1)
                """));

        jdbi.useHandle(h -> historyRepo.appendIfExists(10L, "cleanup", h));

        assertEquals(1, historyRepo.countForTitle(10L));
        var rows = historyRepo.recentForTitle(10L, 5);
        assertEquals(1, rows.size());

        EnrichmentHistoryRepository.HistoryRow row = rows.get(0);
        assertEquals(10L,      row.titleId());
        assertEquals("ABC-010", row.titleCode());
        assertEquals("cleanup", row.reason());
        assertEquals("abc-slug", row.priorSlug());
        assertNotNull(row.priorPayload());

        // Verify the payload is parseable JSON containing key columns
        var node = new ObjectMapper().readTree(row.priorPayload());
        assertEquals("abc-slug",           node.path("javdb_slug").asText());
        assertEquals("S1 NO.1 STYLE",      node.path("maker").asText());
        assertEquals("HIGH",               node.path("confidence").asText());
        assertEquals(1,                    node.path("cast_validated").asInt());
        assertEquals("2024-01-15",         node.path("release_date").asText());
        assertEquals(4.2,                  node.path("rating_avg").asDouble(), 0.001);
    }

    @Test
    void appendIfExists_populatesTitleCodeFromJoin() {
        jdbi.useHandle(h -> {
            h.execute("INSERT INTO title_javdb_enrichment (title_id, javdb_slug, fetched_at) VALUES (11, 'xyz-slug', '2024-06-01T00:00:00Z')");
            historyRepo.appendIfExists(11L, "enrichment_runner", h);
        });

        var rows = historyRepo.recentForTitle(11L, 5);
        assertEquals(1, rows.size());
        assertEquals("ABC-011", rows.get(0).titleCode());
    }

    @Test
    void multipleAppends_accumulateRows() {
        jdbi.useHandle(h -> {
            h.execute("INSERT INTO title_javdb_enrichment (title_id, javdb_slug, fetched_at) VALUES (10, 'slug-v1', '2024-01-01T00:00:00Z')");
            historyRepo.appendIfExists(10L, "enrichment_runner", h);
            h.execute("UPDATE title_javdb_enrichment SET javdb_slug = 'slug-v2' WHERE title_id = 10");
            historyRepo.appendIfExists(10L, "enrichment_runner", h);
        });

        assertEquals(2, historyRepo.countForTitle(10L),
                "each appendIfExists call should produce a separate row");
    }

    @Test
    void recentForTitle_returnsRowsInDescendingOrder() throws Exception {
        // Insert two rows with explicit changed_at to guarantee ordering.
        jdbi.useHandle(h -> {
            h.execute("INSERT INTO title_javdb_enrichment (title_id, javdb_slug, fetched_at) VALUES (10, 'slug-a', '2024-01-01T00:00:00Z')");
            historyRepo.appendIfExists(10L, "cleanup", h);
        });
        Thread.sleep(5); // ensure changed_at differs
        jdbi.useHandle(h -> {
            h.execute("UPDATE title_javdb_enrichment SET javdb_slug = 'slug-b' WHERE title_id = 10");
            historyRepo.appendIfExists(10L, "enrichment_runner", h);
        });

        List<EnrichmentHistoryRepository.HistoryRow> rows = historyRepo.recentForTitle(10L, 10);
        assertEquals(2, rows.size());
        // Most recent first
        assertEquals("enrichment_runner", rows.get(0).reason());
        assertEquals("cleanup",           rows.get(1).reason());
    }
}
