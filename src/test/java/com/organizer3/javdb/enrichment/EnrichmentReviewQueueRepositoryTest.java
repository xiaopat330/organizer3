package com.organizer3.javdb.enrichment;

import com.organizer3.db.SchemaInitializer;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;

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

    // ── listOpen ──────────────────────────────────────────────────────────────

    @Test
    void listOpen_returnsOnlyUnresolvedRows_orderedByCreatedAtDesc() {
        repo.enqueue(1L, "slug1", "ambiguous",   "sentinel_short_circuit");
        jdbi.useHandle(h -> h.execute(
                "INSERT INTO titles(id, code, base_code, label, seq_num) VALUES (2, 'T-2', 'T', 'T', 2)"));
        repo.enqueue(2L, "slug2", "cast_anomaly", "actress_filmography");
        // Resolve the ambiguous row
        jdbi.useHandle(h -> h.execute(
                "UPDATE enrichment_review_queue SET resolved_at = '2026-04-29T00:00:00Z' WHERE reason = 'ambiguous'"));

        List<EnrichmentReviewQueueRepository.OpenRow> rows = repo.listOpen(null, 100, 0);

        assertEquals(1, rows.size(), "only unresolved rows should be returned");
        assertEquals("cast_anomaly", rows.get(0).reason());
        assertEquals("T-2", rows.get(0).titleCode());
    }

    @Test
    void listOpen_filterByReason_returnsOnlyMatchingRows() {
        repo.enqueue(1L, "slug1", "ambiguous",   "sentinel_short_circuit");
        jdbi.useHandle(h -> h.execute(
                "INSERT INTO titles(id, code, base_code, label, seq_num) VALUES (2, 'T-2', 'T', 'T', 2)"));
        repo.enqueue(2L, "slug2", "cast_anomaly", "actress_filmography");

        List<EnrichmentReviewQueueRepository.OpenRow> filtered = repo.listOpen("ambiguous", 100, 0);

        assertEquals(1, filtered.size());
        assertEquals("ambiguous", filtered.get(0).reason());
        assertEquals("T-1", filtered.get(0).titleCode());
    }

    // ── countOpenByReason ─────────────────────────────────────────────────────

    @Test
    void countOpenByReason_returnsCorrectMap_excludesResolved() {
        repo.enqueue(1L, "slug1", "ambiguous", "sentinel_short_circuit");
        jdbi.useHandle(h -> {
            h.execute("INSERT INTO titles(id, code, base_code, label, seq_num) VALUES (2, 'T-2', 'T', 'T', 2)");
            h.execute("INSERT INTO titles(id, code, base_code, label, seq_num) VALUES (3, 'T-3', 'T', 'T', 3)");
        });
        repo.enqueue(2L, "slug2", "cast_anomaly", "actress_filmography");
        repo.enqueue(3L, "slug3", "ambiguous",    "code_search_fallback");
        // Resolve one ambiguous row
        jdbi.useHandle(h -> h.execute(
                "UPDATE enrichment_review_queue SET resolved_at = '2026-04-29T00:00:00Z' WHERE title_id = 3"));

        Map<String, Integer> counts = repo.countOpenByReason();

        assertEquals(1, counts.get("ambiguous"),   "one resolved row must be excluded");
        assertEquals(1, counts.get("cast_anomaly"));
        assertNull(counts.get("fetch_failed"), "absent reason must not appear");
    }

    // ── resolveOne ────────────────────────────────────────────────────────────

    @Test
    void resolveOne_marksRowResolved_andExcludesItFromListOpen() {
        repo.enqueue(1L, "slug1", "ambiguous", "sentinel_short_circuit");
        long id = jdbi.withHandle(h ->
                h.createQuery("SELECT id FROM enrichment_review_queue WHERE title_id = 1")
                        .mapTo(Long.class).one());

        boolean ok = repo.resolveOne(id, "accepted_gap");

        assertTrue(ok, "resolveOne should return true for an open row");

        String resolvedAt = jdbi.withHandle(h ->
                h.createQuery("SELECT resolved_at FROM enrichment_review_queue WHERE id = :id")
                        .bind("id", id).mapTo(String.class).one());
        assertNotNull(resolvedAt, "resolved_at must be set");

        String resolution = jdbi.withHandle(h ->
                h.createQuery("SELECT resolution FROM enrichment_review_queue WHERE id = :id")
                        .bind("id", id).mapTo(String.class).one());
        assertEquals("accepted_gap", resolution);

        // listOpen should no longer return the row
        List<EnrichmentReviewQueueRepository.OpenRow> open = repo.listOpen(null, 100, 0);
        assertTrue(open.stream().noneMatch(r -> r.id() == id), "resolved row must not appear in listOpen");
    }

    @Test
    void resolveOne_alreadyResolved_returnsFalse_doesNotTouchRow() {
        repo.enqueue(1L, "slug1", "ambiguous", "sentinel_short_circuit");
        long id = jdbi.withHandle(h ->
                h.createQuery("SELECT id FROM enrichment_review_queue WHERE title_id = 1")
                        .mapTo(Long.class).one());
        repo.resolveOne(id, "accepted_gap");

        // Second call on same row
        boolean ok = repo.resolveOne(id, "marked_resolved");

        assertFalse(ok, "resolveOne on already-resolved row must return false");

        // resolution must remain the original value
        String resolution = jdbi.withHandle(h ->
                h.createQuery("SELECT resolution FROM enrichment_review_queue WHERE id = :id")
                        .bind("id", id).mapTo(String.class).one());
        assertEquals("accepted_gap", resolution, "already-resolved row must not be modified");
    }
}
