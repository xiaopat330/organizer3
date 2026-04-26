package com.organizer3.web;

import com.organizer3.db.SchemaInitializer;
import com.organizer3.javdb.enrichment.EnrichmentRunner;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JavdbDiscoveryServiceTest {

    private Connection connection;
    private Jdbi jdbi;
    private JavdbDiscoveryService service;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        EnrichmentRunner mockRunner = Mockito.mock(EnrichmentRunner.class);
        Mockito.when(mockRunner.isPaused()).thenReturn(false);
        service = new JavdbDiscoveryService(jdbi, mockRunner);
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private long insertActress(String canonicalName, String stageName) {
        return insertActressWithFlags(canonicalName, stageName, false, false);
    }

    private long insertActressWithFlags(String canonicalName, String stageName, boolean favorite, boolean bookmark) {
        return jdbi.withHandle(h ->
                h.createUpdate("INSERT INTO actresses (canonical_name, stage_name, tier, first_seen_at, favorite, bookmark) " +
                               "VALUES (:cn, :sn, 'LIBRARY', '2024-01-01', :fav, :bkm)")
                        .bind("cn", canonicalName)
                        .bind("sn", stageName)
                        .bind("fav", favorite ? 1 : 0)
                        .bind("bkm", bookmark ? 1 : 0)
                        .executeAndReturnGeneratedKeys("id").mapTo(Long.class).one());
    }

    private long insertTitle(String code) {
        return jdbi.withHandle(h ->
                h.createUpdate("INSERT INTO titles (code, base_code, label, seq_num) VALUES (:c, :c, 'TST', 1)")
                        .bind("c", code)
                        .executeAndReturnGeneratedKeys("id").mapTo(Long.class).one());
    }

    private void linkActressTitle(long actressId, long titleId) {
        jdbi.useHandle(h ->
                h.execute("INSERT INTO title_actresses (actress_id, title_id) VALUES (?, ?)", actressId, titleId));
    }

    private void insertTitleStaging(long titleId, String slug, String status) {
        jdbi.useHandle(h ->
                h.execute("INSERT INTO javdb_title_staging (title_id, javdb_slug, status) VALUES (?, ?, ?)",
                        titleId, slug, status));
    }

    private void insertActressStaging(long actressId, String slug, String status) {
        jdbi.useHandle(h ->
                h.execute("INSERT INTO javdb_actress_staging (actress_id, javdb_slug, status) VALUES (?, ?, ?)",
                        actressId, slug, status));
    }

    private void insertQueueRow(long actressId, String status) {
        jdbi.useHandle(h ->
                h.execute("INSERT INTO javdb_enrichment_queue " +
                          "(job_type, target_id, actress_id, status, attempts, next_attempt_at, created_at, updated_at) " +
                          "VALUES ('actress', ?, ?, ?, 0, '2024-01-01T00:00:00Z', '2024-01-01T00:00:00Z', '2024-01-01T00:00:00Z')",
                        actressId, actressId, status));
    }

    private void insertTitleQueueRow(long titleId, long actressId, String status) {
        jdbi.useHandle(h ->
                h.execute("INSERT INTO javdb_enrichment_queue " +
                          "(job_type, target_id, actress_id, status, attempts, next_attempt_at, created_at, updated_at) " +
                          "VALUES ('fetch_title', ?, ?, ?, 0, '2024-01-01T00:00:00Z', '2024-01-01T00:00:00Z', '2024-01-01T00:00:00Z')",
                        titleId, actressId, status));
    }

    // ── listActresses ──────────────────────────────────────────────────────

    @Test
    void listActresses_returnsActressesWithTitlesOrderedByName() {
        long a1 = insertActress("Alpha", "Alpha Stage");
        long a2 = insertActress("Beta",  "Beta Stage");
        long t1 = insertTitle("AAA-001");
        long t2 = insertTitle("AAA-002");
        linkActressTitle(a1, t1);
        linkActressTitle(a1, t2);
        linkActressTitle(a2, t1);

        List<JavdbDiscoveryService.ActressRow> rows = service.listActresses();

        assertEquals(2, rows.size());
        assertEquals("Alpha", rows.get(0).canonicalName());
        assertEquals(2, rows.get(0).totalTitles());
        assertEquals("Beta", rows.get(1).canonicalName());
        assertEquals(1, rows.get(1).totalTitles());
    }

    @Test
    void listActresses_excludesActressesWithNoTitles() {
        insertActress("NoTitles", "NT");
        long a2 = insertActress("HasTitle", "HT");
        long t1 = insertTitle("BBB-001");
        linkActressTitle(a2, t1);

        List<JavdbDiscoveryService.ActressRow> rows = service.listActresses();

        assertEquals(1, rows.size());
        assertEquals("HasTitle", rows.get(0).canonicalName());
    }

    @Test
    void listActresses_countsOnlyFetchedTitlesAsEnriched() {
        long a = insertActress("Tester", "Tester");
        long t1 = insertTitle("C-001");
        long t2 = insertTitle("C-002");
        long t3 = insertTitle("C-003");
        linkActressTitle(a, t1);
        linkActressTitle(a, t2);
        linkActressTitle(a, t3);
        insertTitleStaging(t1, "slug1", "fetched");
        insertTitleStaging(t2, "slug2", "slug_only");
        // t3 has no staging row

        JavdbDiscoveryService.ActressRow row = service.listActresses().get(0);

        assertEquals(3, row.totalTitles());
        assertEquals(1, row.enrichedTitles());
    }

    @Test
    void listActresses_reportsActressStatus() {
        long a = insertActress("WithProfile", "WP");
        long t = insertTitle("D-001");
        linkActressTitle(a, t);
        insertActressStaging(a, "wp-slug", "fetched");

        JavdbDiscoveryService.ActressRow row = service.listActresses().get(0);

        assertEquals("fetched", row.actressStatus());
    }

    @Test
    void listActresses_nullActressStatusWhenNoStagingRow() {
        long a = insertActress("NoProfile", "NP");
        long t = insertTitle("E-001");
        linkActressTitle(a, t);

        JavdbDiscoveryService.ActressRow row = service.listActresses().get(0);

        assertNull(row.actressStatus());
    }

    @Test
    void listActresses_returnsFavoriteAndBookmarkFlags() {
        long a1 = insertActressWithFlags("Favorite One", "F1", true, false);
        long a2 = insertActressWithFlags("Bookmarked One", "B1", false, true);
        long t1 = insertTitle("FAV-001");
        long t2 = insertTitle("BKM-001");
        linkActressTitle(a1, t1);
        linkActressTitle(a2, t2);

        List<JavdbDiscoveryService.ActressRow> rows = service.listActresses();
        JavdbDiscoveryService.ActressRow fav = rows.stream().filter(r -> r.id() == a1).findFirst().orElseThrow();
        JavdbDiscoveryService.ActressRow bkm = rows.stream().filter(r -> r.id() == a2).findFirst().orElseThrow();

        assertTrue(fav.favorite());
        assertFalse(fav.bookmark());
        assertFalse(bkm.favorite());
        assertTrue(bkm.bookmark());
    }

    @Test
    void listActresses_countsActiveTitleQueueJobsOnly() {
        long a = insertActress("Queued", "Q");
        long t1 = insertTitle("QQ-001");
        long t2 = insertTitle("QQ-002");
        long t3 = insertTitle("QQ-003");
        linkActressTitle(a, t1);
        linkActressTitle(a, t2);
        linkActressTitle(a, t3);
        insertTitleQueueRow(t1, a, "pending");
        insertTitleQueueRow(t2, a, "in_flight");
        insertTitleQueueRow(t3, a, "done");       // done should not count

        JavdbDiscoveryService.ActressRow row = service.listActresses().get(0);

        assertEquals(2, row.activeJobs());
    }

    @Test
    void listActresses_activeJobsZeroWhenNoneQueued() {
        long a = insertActress("NoQueue", "NQ");
        long t = insertTitle("NQ-001");
        linkActressTitle(a, t);

        assertEquals(0, service.listActresses().get(0).activeJobs());
    }

    // ── getActressTitles ───────────────────────────────────────────────────

    @Test
    void getActressTitles_returnsOnlyTitlesForThatActress() {
        long a1 = insertActress("A1", "A1");
        long a2 = insertActress("A2", "A2");
        long t1 = insertTitle("F-001");
        long t2 = insertTitle("F-002");
        linkActressTitle(a1, t1);
        linkActressTitle(a2, t2);

        List<JavdbDiscoveryService.TitleRow> rows = service.getActressTitles(a1);

        assertEquals(1, rows.size());
        assertEquals("F-001", rows.get(0).code());
    }

    @Test
    void getActressTitles_nullStagingFieldsWhenNoRow() {
        long a = insertActress("A", "A");
        long t = insertTitle("G-001");
        linkActressTitle(a, t);

        JavdbDiscoveryService.TitleRow row = service.getActressTitles(a).get(0);

        assertNull(row.status());
        assertNull(row.javdbSlug());
        assertNull(row.titleOriginal());
        assertNull(row.queueStatus());
    }

    @Test
    void getActressTitles_prioritisesInFlightOverPending() {
        long a = insertActress("A", "A");
        long t = insertTitle("QS-001");
        linkActressTitle(a, t);
        insertTitleQueueRow(t, a, "pending");
        insertTitleQueueRow(t, a, "in_flight");

        assertEquals("in_flight", service.getActressTitles(a).get(0).queueStatus());
    }

    @Test
    void getActressTitles_showsPendingQueueStatus() {
        long a = insertActress("A", "A");
        long t = insertTitle("QS-002");
        linkActressTitle(a, t);
        insertTitleQueueRow(t, a, "pending");

        assertEquals("pending", service.getActressTitles(a).get(0).queueStatus());
    }

    @Test
    void getActressTitles_showsDoneQueueStatusWhenOnlyDone() {
        long a = insertActress("A", "A");
        long t = insertTitle("QS-003");
        linkActressTitle(a, t);
        insertTitleQueueRow(t, a, "done");

        assertEquals("done", service.getActressTitles(a).get(0).queueStatus());
    }

    @Test
    void getActressTitles_populatesStagingFieldsWhenRowExists() {
        long a = insertActress("A", "A");
        long t = insertTitle("H-001");
        linkActressTitle(a, t);
        jdbi.useHandle(h -> h.execute(
                "INSERT INTO javdb_title_staging (title_id, javdb_slug, status, title_original, release_date, maker, publisher) " +
                "VALUES (?, 'h-slug', 'fetched', 'タイトル', '2020-01-01', 'マーカー', 'パブリッシャー')", t));

        JavdbDiscoveryService.TitleRow row = service.getActressTitles(a).get(0);

        assertEquals("fetched",      row.status());
        assertEquals("h-slug",       row.javdbSlug());
        assertEquals("タイトル",     row.titleOriginal());
        assertEquals("2020-01-01",   row.releaseDate());
        assertEquals("マーカー",     row.maker());
        assertEquals("パブリッシャー", row.publisher());
    }

    @Test
    void getActressTitles_returnsEmptyForUnknownActress() {
        assertTrue(service.getActressTitles(9999L).isEmpty());
    }

    // ── getActressProfile ──────────────────────────────────────────────────

    @Test
    void getActressProfile_returnsNullWhenNoStagingRow() {
        long a = insertActress("X", "X");
        assertNull(service.getActressProfile(a));
    }

    @Test
    void getActressProfile_returnsProfileWhenStagingExists() {
        long a = insertActress("Profiled", "P");
        jdbi.useHandle(h -> h.execute(
                "INSERT INTO javdb_actress_staging (actress_id, javdb_slug, status, avatar_url, twitter_handle, title_count) " +
                "VALUES (?, 'p-slug', 'fetched', 'https://img.example.com/p.jpg', 'ptwitter', 42)", a));

        JavdbDiscoveryService.ProfileRow row = service.getActressProfile(a);

        assertNotNull(row);
        assertEquals("p-slug",                      row.javdbSlug());
        assertEquals("fetched",                     row.status());
        assertEquals("https://img.example.com/p.jpg", row.avatarUrl());
        assertEquals("ptwitter",                    row.twitterHandle());
        assertEquals(42,                            row.titleCount());
    }

    @Test
    void getActressProfile_nullTitleCountWhenNotSet() {
        long a = insertActress("NoCount", "NC");
        jdbi.useHandle(h -> h.execute(
                "INSERT INTO javdb_actress_staging (actress_id, javdb_slug, status) VALUES (?, 'nc-slug', 'slug_only')", a));

        JavdbDiscoveryService.ProfileRow row = service.getActressProfile(a);

        assertNotNull(row);
        assertNull(row.titleCount());
    }

    // ── getQueueStatus ─────────────────────────────────────────────────────

    @Test
    void getQueueStatus_returnsZerosWhenQueueEmpty() {
        JavdbDiscoveryService.QueueStatus s = service.getQueueStatus();
        assertEquals(0, s.pending());
        assertEquals(0, s.inFlight());
        assertEquals(0, s.failed());
        assertFalse(s.paused());
    }

    @Test
    void getQueueStatus_countsEachStatusSeparately() {
        long a1 = insertActress("Q1", "Q1");
        long a2 = insertActress("Q2", "Q2");
        long a3 = insertActress("Q3", "Q3");
        long a4 = insertActress("Q4", "Q4");
        insertQueueRow(a1, "pending");
        insertQueueRow(a2, "pending");
        insertQueueRow(a3, "in_flight");
        insertQueueRow(a4, "failed");

        JavdbDiscoveryService.QueueStatus s = service.getQueueStatus();

        assertEquals(2, s.pending());
        assertEquals(1, s.inFlight());
        assertEquals(1, s.failed());
    }

    @Test
    void getQueueStatus_doesNotCountDoneOrCancelled() {
        long a1 = insertActress("D1", "D1");
        long a2 = insertActress("C1", "C1");
        insertQueueRow(a1, "done");
        insertQueueRow(a2, "cancelled");

        JavdbDiscoveryService.QueueStatus s = service.getQueueStatus();

        assertEquals(0, s.pending());
        assertEquals(0, s.inFlight());
        assertEquals(0, s.failed());
    }
}
