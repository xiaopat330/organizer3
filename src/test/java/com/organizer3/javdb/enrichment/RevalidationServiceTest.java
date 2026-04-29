package com.organizer3.javdb.enrichment;

import com.organizer3.db.SchemaInitializer;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.*;

class RevalidationServiceTest {

    private Connection connection;
    private Jdbi jdbi;
    private RevalidationService service;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        service = new RevalidationService(jdbi);

        // Base fixture: one actress (id=10, not sentinel), one title (id=1)
        jdbi.useHandle(h -> {
            h.execute("INSERT INTO actresses(id, canonical_name, tier, first_seen_at, is_sentinel) VALUES (10, 'Test Actress', 'LIBRARY', '2024-01-01', 0)");
            h.execute("INSERT INTO titles(id, code, base_code, label, seq_num) VALUES (1, 'TST-001', 'TST-001', 'TST', 1)");
            h.execute("INSERT INTO title_actresses(title_id, actress_id) VALUES (1, 10)");
            // Actress staging row
            h.execute("INSERT INTO javdb_actress_staging(actress_id, javdb_slug, status) VALUES (10, 'actress-slug', 'fetched')");
            // Filmography metadata row (required by FK on javdb_actress_filmography_entry)
            h.execute("INSERT INTO javdb_actress_filmography(actress_slug, fetched_at, page_count, source) VALUES ('actress-slug', '2024-01-01T00:00:00Z', 1, 'http')");
        });
    }

    @AfterEach
    void tearDown() throws Exception { connection.close(); }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void insertEnrichment(String javdbSlug, String confidence) {
        jdbi.useHandle(h -> h.execute(
                "INSERT INTO title_javdb_enrichment(title_id, javdb_slug, fetched_at, confidence) VALUES (1, ?, '2024-01-01T00:00:00Z', ?)",
                javdbSlug, confidence));
    }

    private void insertFilmographyEntry(String actressSlug, String productCode, String titleSlug) {
        jdbi.useHandle(h -> h.execute(
                "INSERT OR REPLACE INTO javdb_actress_filmography_entry(actress_slug, product_code, title_slug, stale) VALUES (?, ?, ?, 0)",
                actressSlug, productCode, titleSlug));
    }

    private String confidence() {
        return jdbi.withHandle(h -> h.createQuery(
                "SELECT confidence FROM title_javdb_enrichment WHERE title_id = 1")
                .mapTo(String.class).one());
    }

    private boolean lastRevalidatedAtSet() {
        String val = jdbi.withHandle(h -> h.createQuery(
                "SELECT last_revalidated_at FROM title_javdb_enrichment WHERE title_id = 1")
                .mapTo(String.class).one());
        return val != null && !val.isBlank();
    }

    private int reviewQueueOpenCount(String reason) {
        return jdbi.withHandle(h -> h.createQuery(
                "SELECT COUNT(*) FROM enrichment_review_queue WHERE reason = :r AND resolved_at IS NULL")
                .bind("r", reason)
                .mapTo(Integer.class).one());
    }

    // ── CONFIRMED tests ──────────────────────────────────────────────────────

    @Test
    void confirmed_setsHighAndStampsTimestamp() {
        insertEnrichment("slug1", "UNKNOWN");
        insertFilmographyEntry("actress-slug", "TST-001", "slug1"); // slug matches!

        var s = service.revalidateOne(1L);

        assertEquals(1, s.confirmed());
        assertEquals(0, s.rejected());
        assertEquals("HIGH", confidence());
        assertTrue(lastRevalidatedAtSet());
    }

    // ── REJECTED tests ────────────────────────────────────────────────────────

    @Test
    void rejected_unknown_setsLowNoReviewQueue() {
        insertEnrichment("slug1", "UNKNOWN");
        insertFilmographyEntry("actress-slug", "TST-001", "different-slug"); // mismatch

        var s = service.revalidateOne(1L);

        assertEquals(1, s.rejected());
        assertEquals(0, s.downgradedToLow(), "UNKNOWN→LOW is not a downgrade from HIGH");
        assertEquals("LOW", confidence());
        assertEquals(0, reviewQueueOpenCount("no_match"), "only HIGH→LOW opens review queue");
        assertTrue(lastRevalidatedAtSet());
    }

    @Test
    void rejected_high_toLow_opensReviewQueue() {
        insertEnrichment("slug1", "HIGH");
        insertFilmographyEntry("actress-slug", "TST-001", "different-slug"); // mismatch

        var s = service.revalidateOne(1L);

        assertEquals(1, s.rejected());
        assertEquals(1, s.downgradedToLow());
        assertEquals("LOW", confidence());
        assertEquals(1, reviewQueueOpenCount("no_match"));
    }

    // ── NO_SIGNAL tests ───────────────────────────────────────────────────────

    @Test
    void noSignal_confidenceUnchanged_timestampStamped() {
        insertEnrichment("slug1", "MEDIUM");
        // No filmography entry → no signal

        var s = service.revalidateOne(1L);

        assertEquals(1, s.noSignal());
        assertEquals("MEDIUM", confidence(), "NO_SIGNAL must not change confidence");
        assertTrue(lastRevalidatedAtSet(), "last_revalidated_at must be stamped even on NO_SIGNAL");
    }

    @Test
    void noSignal_sentinelOnlyLinks() {
        // Replace the actress link with a sentinel actress
        jdbi.useHandle(h -> {
            h.execute("UPDATE actresses SET is_sentinel = 1 WHERE id = 10");
        });
        insertEnrichment("slug1", "UNKNOWN");
        insertFilmographyEntry("actress-slug", "TST-001", "slug1"); // would confirm, but skipped

        var s = service.revalidateOne(1L);

        assertEquals(1, s.noSignal(), "sentinel-only links must yield NO_SIGNAL");
    }

    @Test
    void noSignal_actressNotInStaging() {
        // Remove the actress staging row
        jdbi.useHandle(h -> h.execute("DELETE FROM javdb_actress_staging WHERE actress_id = 10"));
        insertEnrichment("slug1", "UNKNOWN");

        var s = service.revalidateOne(1L);

        assertEquals(1, s.noSignal());
    }

    // ── multi-actress ─────────────────────────────────────────────────────────

    @Test
    void multiActress_confirmedWins() {
        // Second actress rejects, first confirms — CONFIRMED should win
        jdbi.useHandle(h -> {
            h.execute("INSERT INTO actresses(id, canonical_name, tier, first_seen_at, is_sentinel) VALUES (20, 'Actress B', 'LIBRARY', '2024-01-01', 0)");
            h.execute("INSERT INTO title_actresses(title_id, actress_id) VALUES (1, 20)");
            h.execute("INSERT INTO javdb_actress_staging(actress_id, javdb_slug, status) VALUES (20, 'actress-b-slug', 'fetched')");
            h.execute("INSERT OR IGNORE INTO javdb_actress_filmography(actress_slug, fetched_at, page_count, source) VALUES ('actress-b-slug', '2024-01-01T00:00:00Z', 1, 'http')");
        });
        insertEnrichment("slug1", "UNKNOWN");
        insertFilmographyEntry("actress-slug",   "TST-001", "slug1");           // CONFIRMS
        insertFilmographyEntry("actress-b-slug", "TST-001", "different-slug");  // would REJECT

        var s = service.revalidateOne(1L);

        assertEquals(1, s.confirmed(), "CONFIRMED must win over REJECTED in multi-actress case");
        assertEquals("HIGH", confidence());
    }

    // ── skipped ───────────────────────────────────────────────────────────────

    @Test
    void skipped_noEnrichmentRow() {
        // No enrichment row for title 1
        var s = service.revalidateOne(1L);
        assertEquals(1, s.skipped());
    }

    // ── safety net ────────────────────────────────────────────────────────────

    @Test
    void safetyNetSlice_picksUnknownRows() {
        insertEnrichment("slug1", "UNKNOWN");
        insertFilmographyEntry("actress-slug", "TST-001", "slug1");

        var s = service.revalidateSafetyNetSlice(10);

        assertEquals(1, s.confirmed());
        assertEquals("HIGH", confidence());
    }

    @Test
    void safetyNetSlice_skipsHighConfidenceRecentlyRevalidated() {
        insertEnrichment("slug1", "HIGH");
        // Stamp last_revalidated_at as very recent
        jdbi.useHandle(h -> h.execute(
                "UPDATE title_javdb_enrichment SET last_revalidated_at = strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE title_id = 1"));

        var s = service.revalidateSafetyNetSlice(10);

        assertEquals(0, s.confirmed() + s.rejected() + s.noSignal(),
                "recently revalidated HIGH row must not appear in the safety-net slice");
    }
}
