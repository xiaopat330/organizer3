package com.organizer3.javdb.enrichment;

import com.organizer3.db.SchemaInitializer;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.*;

class EnrichmentReviewQueueRepositoryTest {

    private Jdbi jdbi;
    private Connection connection;
    private EnrichmentReviewQueueRepository repo;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        repo = new EnrichmentReviewQueueRepository(jdbi);
        jdbi.useHandle(h -> h.execute(
                "INSERT INTO titles(id, code, base_code, label, seq_num) VALUES (1, 'T-1', 'T', 'T', 1)"));
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    @Test
    void enqueue_insertsRow() {
        repo.enqueue(1L, "slug1", "cast_anomaly", "actress_filmography");

        assertTrue(repo.hasOpen(1L, "cast_anomaly"));
        assertEquals(1, repo.countOpen("cast_anomaly"));
    }

    @Test
    void enqueue_isIdempotent_forSameTitleAndReason() {
        repo.enqueue(1L, "slug1", "cast_anomaly", "actress_filmography");
        repo.enqueue(1L, "slug1", "cast_anomaly", "actress_filmography");

        assertEquals(1, repo.countOpen("cast_anomaly"), "duplicate enqueue must be a no-op");
    }

    @Test
    void enqueue_allowsDifferentReasons_forSameTitle() {
        repo.enqueue(1L, "slug1", "cast_anomaly",  "actress_filmography");
        repo.enqueue(1L, "slug1", "fetch_failed",  "code_search_fallback");

        assertEquals(1, repo.countOpen("cast_anomaly"));
        assertEquals(1, repo.countOpen("fetch_failed"));
        assertEquals(2, repo.countTotal("cast_anomaly") + repo.countTotal("fetch_failed"));
    }

    @Test
    void countOpen_excludesResolvedRows() {
        repo.enqueue(1L, "slug1", "ambiguous", "sentinel_short_circuit");
        jdbi.useHandle(h -> h.execute(
                "UPDATE enrichment_review_queue SET resolved_at = '2026-04-29T00:00:00Z' WHERE title_id = 1"));

        assertEquals(0, repo.countOpen("ambiguous"), "resolved row should not count as open");
        assertEquals(1, repo.countTotal("ambiguous"), "countTotal includes resolved rows");
    }

    @Test
    void hasOpen_returnsFalse_whenNoneOpen() {
        assertFalse(repo.hasOpen(1L, "cast_anomaly"));
    }

    @Test
    void hasOpen_returnsTrue_whenOpenRowExists() {
        repo.enqueue(1L, "slug1", "cast_anomaly", "actress_filmography");
        assertTrue(repo.hasOpen(1L, "cast_anomaly"));
    }

    @Test
    void enqueue_allowsReenqueue_afterResolution() {
        repo.enqueue(1L, "slug1", "cast_anomaly", "actress_filmography");
        jdbi.useHandle(h -> h.execute(
                "UPDATE enrichment_review_queue SET resolved_at = '2026-04-29T00:00:00Z'"));

        // A new open entry is allowed once the old one is resolved.
        repo.enqueue(1L, "slug1-v2", "cast_anomaly", "actress_filmography");

        assertEquals(1, repo.countOpen("cast_anomaly"));
        assertEquals(2, repo.countTotal("cast_anomaly"));
    }
}
