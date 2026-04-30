package com.organizer3.utilities.health.checks;

import com.organizer3.db.SchemaInitializer;
import com.organizer3.utilities.health.LibraryHealthCheck;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.*;

class StaleFilmographyCacheCheckTest {

    private Connection connection;
    private Jdbi jdbi;
    private StaleFilmographyCacheCheck check;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        check = new StaleFilmographyCacheCheck(jdbi);
    }

    @AfterEach
    void tearDown() throws Exception { connection.close(); }

    private void insertFilmography(String slug, String status, String fetchedAt) {
        jdbi.useHandle(h -> h.execute(
                "INSERT INTO javdb_actress_filmography " +
                "(actress_slug, fetched_at, page_count, source, last_fetch_status) " +
                "VALUES (?, ?, 1, 'javdb', ?)",
                slug, fetchedAt, status));
    }

    private void insertEntry(String actressSlug, String code, String titleSlug) {
        jdbi.useHandle(h -> h.execute(
                "INSERT INTO javdb_actress_filmography_entry (actress_slug, product_code, title_slug) VALUES (?, ?, ?)",
                actressSlug, code, titleSlug));
    }

    @Test
    void reportsZeroWhenCacheIsEmpty() {
        LibraryHealthCheck.CheckResult result = check.run();
        assertEquals(0, result.total());
        assertTrue(result.rows().isEmpty());
    }

    @Test
    void reportsZeroForFreshOkCache() {
        insertFilmography("alice", "ok", "2099-01-01T00:00:00Z");

        LibraryHealthCheck.CheckResult result = check.run();
        assertEquals(0, result.total(), "recent ok cache must not be flagged");
    }

    @Test
    void detectsNotFoundStatus() {
        insertFilmography("alice", "not_found", "2024-01-01T00:00:00Z");

        LibraryHealthCheck.CheckResult result = check.run();
        assertEquals(1, result.total());
        assertEquals(1, result.rows().size());
        LibraryHealthCheck.Finding f = result.rows().get(0);
        assertEquals("alice", f.label());
        assertTrue(f.detail().contains("status=not_found"));
    }

    @Test
    void detectsFetchFailedStatus() {
        insertFilmography("bob", "fetch_failed", "2024-01-01T00:00:00Z");

        LibraryHealthCheck.CheckResult result = check.run();
        assertEquals(1, result.total());
        assertEquals("bob", result.rows().get(0).label());
    }

    @Test
    void detectsAgedOkCache() {
        insertFilmography("carol", "ok", "2020-01-01T00:00:00Z");

        LibraryHealthCheck.CheckResult result = check.run();
        assertEquals(1, result.total());
        assertTrue(result.rows().get(0).detail().contains("status=ok"));
    }

    @Test
    void detailIncludesEntryCount() {
        insertFilmography("alice", "not_found", "2024-01-01T00:00:00Z");
        insertEntry("alice", "ABP-001", "abp-001-slug");
        insertEntry("alice", "ABP-002", "abp-002-slug");

        LibraryHealthCheck.CheckResult result = check.run();
        assertTrue(result.rows().get(0).detail().contains("entries=2"));
    }

    @Test
    void detailShowsZeroEntriesWhenCacheIsEmpty() {
        insertFilmography("ghost", "fetch_failed", "2024-01-01T00:00:00Z");

        LibraryHealthCheck.CheckResult result = check.run();
        assertTrue(result.rows().get(0).detail().contains("entries=0"));
    }

    /**
     * Rule 3 false-positive guard: a fresh ok cache must not be flagged even when
     * another actress's cache is stale.
     */
    @Test
    void freshOkCacheNotFlaggedWhenStaleExists() {
        insertFilmography("fresh", "ok", "2099-01-01T00:00:00Z");
        insertFilmography("stale", "ok", "2020-01-01T00:00:00Z");

        LibraryHealthCheck.CheckResult result = check.run();
        assertEquals(1, result.total(), "only the stale row should be flagged");
        assertEquals("stale", result.rows().get(0).label());
    }

    @Test
    void capsAtFiftyRows() {
        for (int i = 1; i <= 100; i++) {
            insertFilmography("actress-" + i, "not_found", "2024-01-01T00:00:00Z");
        }

        LibraryHealthCheck.CheckResult result = check.run();
        assertEquals(100, result.total());
        assertEquals(50, result.rows().size());
    }
}
