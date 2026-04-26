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

    /**
     * Seeds an enrichment record for a title. The {@code status} parameter is interpreted:
     * {@code "fetched"} writes the row (visible in queries); anything else is a no-op so
     * existing tests using "not_found" / "fetch_error" / null still pass through correctly.
     */
    private void insertTitleStaging(long titleId, String slug, String status) {
        if (!"fetched".equals(status)) return;
        jdbi.useHandle(h ->
                h.execute("INSERT INTO title_javdb_enrichment (title_id, javdb_slug, fetched_at) " +
                          "VALUES (?, ?, '2026-04-25T00:00:00Z')",
                        titleId, slug));
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
                "INSERT INTO title_javdb_enrichment (title_id, javdb_slug, fetched_at, title_original, release_date, maker, publisher) " +
                "VALUES (?, 'h-slug', '2026-04-25T00:00:00Z', 'タイトル', '2020-01-01', 'マーカー', 'パブリッシャー')", t));

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

    // ── filtered getActressTitles + tag facets (Phase 3 surfacing) ────────────

    /** Seeds an enrichment row + tag assignments. Refreshes title_count denorm. */
    private void seedEnrichment(long titleId, String slug, Double ratingAvg, Integer ratingCount, String... tags) {
        jdbi.useHandle(h -> {
            h.createUpdate("""
                    INSERT INTO title_javdb_enrichment (title_id, javdb_slug, fetched_at, rating_avg, rating_count)
                    VALUES (:id, :slug, '2026-04-25T00:00:00Z', :avg, :cnt)
                    """)
                    .bind("id", titleId).bind("slug", slug)
                    .bind("avg", ratingAvg).bind("cnt", ratingCount)
                    .execute();
            for (String tag : tags) {
                h.createUpdate("INSERT OR IGNORE INTO enrichment_tag_definitions(name) VALUES(:n)").bind("n", tag).execute();
                h.createUpdate("""
                        INSERT INTO title_enrichment_tags(title_id, tag_id)
                        SELECT :id, id FROM enrichment_tag_definitions WHERE name = :n
                        """).bind("id", titleId).bind("n", tag).execute();
            }
            h.execute("""
                    UPDATE enrichment_tag_definitions SET title_count = (
                        SELECT COUNT(*) FROM title_enrichment_tags WHERE tag_id = enrichment_tag_definitions.id)
                    """);
        });
    }

    @Test
    void filter_tagsAnd_requiresAllTagsPresent() {
        long a = insertActress("A", "A");
        long t1 = insertTitle("F-1"); linkActressTitle(a, t1); seedEnrichment(t1, "s1", 4.5, 100, "Big Tits", "Cowgirl");
        long t2 = insertTitle("F-2"); linkActressTitle(a, t2); seedEnrichment(t2, "s2", 4.0, 50, "Big Tits");
        long t3 = insertTitle("F-3"); linkActressTitle(a, t3); seedEnrichment(t3, "s3", 4.2, 80, "Cowgirl");

        var both = service.getActressTitles(a, new JavdbDiscoveryService.TitleFilter(
                List.of("Big Tits", "Cowgirl"), null, null));
        assertEquals(1, both.size(), "AND of two tags should match only the title carrying both");
        assertEquals("F-1", both.get(0).code());
    }

    @Test
    void filter_minRatingAvg_excludesLowRated() {
        long a = insertActress("A", "A");
        long t1 = insertTitle("F-1"); linkActressTitle(a, t1); seedEnrichment(t1, "s1", 4.5, 100, "X");
        long t2 = insertTitle("F-2"); linkActressTitle(a, t2); seedEnrichment(t2, "s2", 3.5, 100, "X");

        var rows = service.getActressTitles(a, new JavdbDiscoveryService.TitleFilter(
                null, 4.0, null));
        assertEquals(1, rows.size());
        assertEquals("F-1", rows.get(0).code());
    }

    @Test
    void filter_minRatingCount_excludesLowSampleSize() {
        long a = insertActress("A", "A");
        long t1 = insertTitle("F-1"); linkActressTitle(a, t1); seedEnrichment(t1, "s1", 4.9, 5,   "X");
        long t2 = insertTitle("F-2"); linkActressTitle(a, t2); seedEnrichment(t2, "s2", 4.5, 100, "X");

        var rows = service.getActressTitles(a, new JavdbDiscoveryService.TitleFilter(
                null, null, 50));
        assertEquals(1, rows.size());
        assertEquals("F-2", rows.get(0).code());
    }

    @Test
    void filter_combined_appliesAllPredicates() {
        long a = insertActress("A", "A");
        long t1 = insertTitle("F-1"); linkActressTitle(a, t1); seedEnrichment(t1, "s1", 4.5, 100, "Big Tits", "POV");
        long t2 = insertTitle("F-2"); linkActressTitle(a, t2); seedEnrichment(t2, "s2", 4.5, 100, "Big Tits");
        long t3 = insertTitle("F-3"); linkActressTitle(a, t3); seedEnrichment(t3, "s3", 3.0, 100, "Big Tits", "POV");

        var rows = service.getActressTitles(a, new JavdbDiscoveryService.TitleFilter(
                List.of("Big Tits", "POV"), 4.0, 50));
        assertEquals(1, rows.size());
        assertEquals("F-1", rows.get(0).code());
    }

    @Test
    void filter_emptyExcludesUnenriched_butNoFilterIncludesThem() {
        long a = insertActress("A", "A");
        long t1 = insertTitle("F-1"); linkActressTitle(a, t1); seedEnrichment(t1, "s1", 4.5, 100, "X");
        long t2 = insertTitle("F-2"); linkActressTitle(a, t2);   // un-enriched
        long t3 = insertTitle("F-3"); linkActressTitle(a, t3);   // un-enriched

        // No filter → all titles surface (current behavior)
        assertEquals(3, service.getActressTitles(a, JavdbDiscoveryService.TitleFilter.none()).size());

        // Any active filter → un-enriched are dropped
        assertEquals(1, service.getActressTitles(a, new JavdbDiscoveryService.TitleFilter(
                List.of("X"), null, null)).size());
    }

    @Test
    void tagFacets_countsAcrossMatchingTitlesOnly() {
        long a = insertActress("A", "A");
        long t1 = insertTitle("F-1"); linkActressTitle(a, t1); seedEnrichment(t1, "s1", 4.5, 100, "Big Tits", "POV");
        long t2 = insertTitle("F-2"); linkActressTitle(a, t2); seedEnrichment(t2, "s2", 4.5, 100, "Big Tits", "Cowgirl");
        long t3 = insertTitle("F-3"); linkActressTitle(a, t3); seedEnrichment(t3, "s3", 3.0, 100, "Big Tits", "POV");

        // No filter — both POV and Cowgirl present, Big Tits in all 3
        var unfiltered = service.getActressTagFacets(a, JavdbDiscoveryService.TitleFilter.none());
        var unfilteredMap = unfiltered.stream().collect(java.util.stream.Collectors.toMap(
                JavdbDiscoveryService.TagFacet::name, JavdbDiscoveryService.TagFacet::count));
        assertEquals(3, unfilteredMap.get("Big Tits"));
        assertEquals(2, unfilteredMap.get("POV"));
        assertEquals(1, unfilteredMap.get("Cowgirl"));

        // Filter: rating >= 4.0 → only t1 and t2 match. Cowgirl now 1, POV now 1, Big Tits 2.
        var filtered = service.getActressTagFacets(a, new JavdbDiscoveryService.TitleFilter(
                null, 4.0, null));
        var filteredMap = filtered.stream().collect(java.util.stream.Collectors.toMap(
                JavdbDiscoveryService.TagFacet::name, JavdbDiscoveryService.TagFacet::count));
        assertEquals(2, filteredMap.get("Big Tits"));
        assertEquals(1, filteredMap.get("POV"));
        assertEquals(1, filteredMap.get("Cowgirl"));

        // Filter: tag conjunction Big Tits + POV → only t1 and t3, but Cowgirl wasn't on either
        var both = service.getActressTagFacets(a, new JavdbDiscoveryService.TitleFilter(
                List.of("Big Tits", "POV"), null, null));
        var bothMap = both.stream().collect(java.util.stream.Collectors.toMap(
                JavdbDiscoveryService.TagFacet::name, JavdbDiscoveryService.TagFacet::count));
        assertEquals(2, bothMap.get("Big Tits"));
        assertEquals(2, bothMap.get("POV"));
        assertNull(bothMap.get("Cowgirl"), "tags not on any matching title should be absent from facets");
    }

    @Test
    void tagFacets_emptyWhenNoMatches() {
        long a = insertActress("A", "A");
        long t = insertTitle("F-1"); linkActressTitle(a, t); seedEnrichment(t, "s", 3.0, 10, "Big Tits");
        var facets = service.getActressTagFacets(a, new JavdbDiscoveryService.TitleFilter(
                null, 4.5, null));
        assertTrue(facets.isEmpty());
    }

    // ── getTitleEnrichmentDetail ───────────────────────────────────────────

    @Test
    void enrichmentDetail_returnsNullForMissingTitle() {
        assertNull(service.getTitleEnrichmentDetail(999L));
    }

    @Test
    void enrichmentDetail_returnsAllFields() {
        long a = insertActress("Test Actress", "Test");
        long titleId = insertTitle("ABC-001");
        linkActressTitle(a, titleId);
        jdbi.useHandle(h -> h.createUpdate("""
                INSERT INTO title_javdb_enrichment
                  (title_id, javdb_slug, fetched_at, title_original, release_date,
                   duration_minutes, maker, publisher, series, rating_avg, rating_count,
                   cast_json)
                VALUES (?, 'AbCd01', '2026-04-25T10:00:00Z', 'テストタイトル', '2024-03-15',
                        120, 'Test Maker', 'Test Publisher', 'Test Series', 4.25, 182,
                        '[{"slug":"s1","name":"Alice","gender":"f"}]')
                """).bind(0, titleId).execute());
        jdbi.useHandle(h -> {
            h.createUpdate("INSERT OR IGNORE INTO enrichment_tag_definitions(name) VALUES('Big Tits')").execute();
            h.createUpdate("INSERT OR IGNORE INTO enrichment_tag_definitions(name) VALUES('POV')").execute();
            h.execute("INSERT INTO title_enrichment_tags(title_id, tag_id) SELECT ?, id FROM enrichment_tag_definitions WHERE name='Big Tits'", titleId);
            h.execute("INSERT INTO title_enrichment_tags(title_id, tag_id) SELECT ?, id FROM enrichment_tag_definitions WHERE name='POV'", titleId);
        });

        var d = service.getTitleEnrichmentDetail(titleId);

        assertNotNull(d);
        assertEquals("ABC-001",         d.code());
        assertEquals("AbCd01",          d.javdbSlug());
        assertEquals("テストタイトル",  d.titleOriginal());
        assertEquals("2024-03-15",      d.releaseDate());
        assertEquals(120,               d.durationMinutes());
        assertEquals("Test Maker",      d.maker());
        assertEquals("Test Publisher",  d.publisher());
        assertEquals("Test Series",     d.series());
        assertEquals(4.25,              d.ratingAvg(), 0.001);
        assertEquals(182,               d.ratingCount());
        assertNotNull(d.castJson());
        assertTrue(d.castJson().contains("Alice"));
        assertEquals(2, d.tags().size());
        assertTrue(d.tags().containsAll(List.of("Big Tits", "POV")));
        assertEquals("2026-04-25T10:00:00Z", d.fetchedAt());
    }

    @Test
    void enrichmentDetail_returnsEmptyTagsWhenNone() {
        long titleId = insertTitle("DEF-001");
        jdbi.useHandle(h -> h.execute(
                "INSERT INTO title_javdb_enrichment (title_id, javdb_slug, fetched_at) VALUES (?, 'xyz', '2026-01-01T00:00:00Z')",
                titleId));

        var d = service.getTitleEnrichmentDetail(titleId);

        assertNotNull(d);
        assertTrue(d.tags().isEmpty());
    }
}
