package com.organizer3.web;

import com.organizer3.config.volume.OrganizerConfig;
import com.organizer3.config.volume.VolumeConfig;
import com.organizer3.db.SchemaInitializer;
import com.organizer3.javdb.JavdbConfig;
import com.organizer3.javdb.enrichment.EnrichmentJob;
import com.organizer3.javdb.enrichment.EnrichmentQueue;
import com.organizer3.javdb.enrichment.ProfileChainGate;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TitleDiscoveryServiceTest {

    /** profileChainMinTitles=2 keeps fixtures small while still exercising the threshold. */
    private static final JavdbConfig CONFIG_MIN2 =
            new JavdbConfig(true, 1.0, 3, new int[]{1, 5, 30}, 5, null, null, null, null, 2);

    private Connection connection;
    private Jdbi jdbi;
    private TitleDiscoveryService service;
    private EnrichmentQueue queue;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();

        seedVolumes("vol-recent", "conventional");
        seedVolumes("pool-jav",   "sort_pool");
        seedVolumes("pool-av",    "sort_pool");
        seedVolumes("unsorted",   "queue");

        OrganizerConfig config = mock(OrganizerConfig.class);
        when(config.volumes()).thenReturn(List.of(
                volumeCfg("pool-jav", "sort_pool"),
                volumeCfg("pool-av",  "sort_pool"),
                volumeCfg("vol-recent", "library")
        ));

        ProfileChainGate gate = new ProfileChainGate(jdbi, CONFIG_MIN2);
        queue = new EnrichmentQueue(jdbi, CONFIG_MIN2);
        service = new TitleDiscoveryService(jdbi, config, gate, queue);
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    // ── listRecent: filter, sort, pagination, status mapping ───────────────

    @Test
    void listRecent_excludesEnrichedTitlesNotStagingFetched() {
        // The authoritative "enriched" signal is presence of a row in title_javdb_enrichment,
        // not staging.status='fetched'. Staging may be NULL even after a successful enrichment
        // (the staging row can be cleaned up post-promotion or never written for that path).
        long t1 = insertTitle("CODE-001");
        long t2 = insertTitle("CODE-002");
        long t3 = insertTitle("CODE-003");
        insertLocation(t1, "vol-recent", "/a", "2026-04-25");
        insertLocation(t2, "vol-recent", "/b", "2026-04-26");
        insertLocation(t3, "vol-recent", "/c", "2026-04-27");

        // t1: no enrichment row → unenriched (visible).
        // t2: staging='slug_only' but no enrichment row → unenriched (visible, staging badge).
        insertStaging(t2, "slug_only");
        // t3: enriched (in title_javdb_enrichment), staging is NULL → must be hidden.
        insertEnrichment(t3, "abc123");

        var page = service.listRecent(0, 50);

        var codes = page.rows().stream().map(TitleDiscoveryService.TitleRow::code).toList();
        assertEquals(List.of("CODE-002", "CODE-001"), codes,
                "enriched titles must be excluded; sort is added_date desc");
        assertFalse(page.hasMore());
    }

    @Test
    void listRecent_excludesEnrichedEvenWhenStagingIsNull() {
        // Real-world repro: title is fully enriched (title_javdb_enrichment present) but the
        // staging row is absent. The earlier filter only checked staging and surfaced the row.
        long t = insertTitle("ENRICHED-001");
        insertLocation(t, "vol-recent", "/e", "2026-04-27");
        insertEnrichment(t, "slug-x");
        // No insertStaging — javdb_title_staging.title_id IS NULL for this row.

        assertEquals(0, service.listRecent(0, 50).rows().size(),
                "enriched title with NULL staging must be hidden from Titles tab");
    }

    @Test
    void listRecent_excludesMultiActressTitles() {
        long aSolo = insertActress("Solo");
        long aA = insertActress("Alice");
        long aB = insertActress("Bob");
        long t1 = insertTitle("SOLO-001");
        long t2 = insertTitle("DUO-001");
        long t3 = insertTitle("ORPHAN-001"); // no actress credit
        insertCredit(t1, aSolo);
        insertCredit(t2, aA);
        insertCredit(t2, aB);
        insertLocation(t1, "vol-recent", "/x", "2026-04-25");
        insertLocation(t2, "vol-recent", "/y", "2026-04-26");
        insertLocation(t3, "vol-recent", "/z", "2026-04-27");

        var page = service.listRecent(0, 50);

        var codes = page.rows().stream().map(TitleDiscoveryService.TitleRow::code).toList();
        assertTrue(codes.contains("SOLO-001"));
        assertTrue(codes.contains("ORPHAN-001"), "no-credit titles still appear in Titles tab");
        assertFalse(codes.contains("DUO-001"),   "multi-actress titles belong to Collections tab");
    }

    @Test
    void listRecent_excludesQueueStructureTypeVolumes() {
        // Titles whose latest location is on a queue-type volume (e.g. 'unsorted')
        // are not yet ready for enrichment — they need curation first.
        long t1 = insertTitle("LIB-001");
        long t2 = insertTitle("UNS-001");
        insertLocation(t1, "vol-recent", "/lib", "2026-04-26");
        insertLocation(t2, "unsorted",   "/uns", "2026-04-27");

        var page = service.listRecent(0, 50);

        var codes = page.rows().stream().map(TitleDiscoveryService.TitleRow::code).toList();
        assertTrue(codes.contains("LIB-001"));
        assertFalse(codes.contains("UNS-001"),
                "queue-type volume titles must be excluded from All recent");
    }

    @Test
    void listRecent_excludesUnderscorePrefixedCodes() {
        // System folders (_sandbox, _trash, _JAV_xxx, etc.) are tracked as titles
        // but should never appear in the enrichment surface.
        long real     = insertTitle("REAL-001");
        long sandbox  = insertTitle("_sandbox");
        long trash    = insertTitle("_trash");
        long jav      = insertTitle("_JAV_Whoever");
        insertLocation(real,    "vol-recent", "/r",  "2026-04-27");
        insertLocation(sandbox, "vol-recent", "/s",  "2026-04-27");
        insertLocation(trash,   "vol-recent", "/tr", "2026-04-27");
        insertLocation(jav,     "vol-recent", "/j",  "2026-04-27");

        var codes = service.listRecent(0, 50).rows().stream()
                .map(TitleDiscoveryService.TitleRow::code).toList();
        assertEquals(List.of("REAL-001"), codes);
    }

    @Test
    void listPool_excludesUnderscorePrefixedCodes() {
        long real    = insertTitle("ABC-001");
        long sandbox = insertTitle("_sandbox");
        insertLocation(real,    "pool-jav", "/A", "2026-04-25");
        insertLocation(sandbox, "pool-jav", "/B", "2026-04-26");

        var codes = service.listPool("pool-jav", 0, 50).rows().stream()
                .map(TitleDiscoveryService.TitleRow::code).toList();
        assertEquals(List.of("ABC-001"), codes);
    }

    @Test
    void listPools_countExcludesUnderscorePrefixedCodes() {
        long real    = insertTitle("ABC-001");
        long sandbox = insertTitle("_sandbox");
        insertLocation(real,    "pool-jav", "/A", "2026-04-25");
        insertLocation(sandbox, "pool-jav", "/B", "2026-04-26");

        var chip = service.listPools().stream()
                .filter(c -> c.volumeId().equals("pool-jav"))
                .findFirst().orElseThrow();
        assertEquals(1, chip.unenrichedCount(),
                "_sandbox must not be counted in the pool chip badge");
    }

    @Test
    void listRecent_paginatesAndReportsHasMore() {
        for (int i = 0; i < 5; i++) {
            long t = insertTitle("PAGE-" + i);
            insertLocation(t, "vol-recent", "/p" + i, "2026-04-2" + i);
        }
        var p0 = service.listRecent(0, 2);
        assertEquals(2, p0.rows().size());
        assertTrue(p0.hasMore());
        // 5 rows / pageSize 2 → 3 total pages.
        assertEquals(3, p0.totalPages());
        var p2 = service.listRecent(2, 2);
        assertEquals(1, p2.rows().size());
        assertFalse(p2.hasMore(), "last page must report hasMore=false");
        assertEquals(3, p2.totalPages(), "totalPages stays consistent across page indices");
    }

    @Test
    void listRecent_totalPagesIsZeroWhenEmpty() {
        var p = service.listRecent(0, 50);
        assertTrue(p.rows().isEmpty());
        assertEquals(0, p.totalPages());
    }

    @Test
    void listRecent_mapsQueueStatusFromActiveJobs() {
        long t = insertTitle("QUEUE-001");
        insertLocation(t, "vol-recent", "/q", "2026-04-27");
        // Seed a pending fetch_title job for this title.
        jdbi.useHandle(h -> h.execute(
                "INSERT INTO javdb_enrichment_queue(job_type, target_id, source, status, attempts, " +
                "next_attempt_at, created_at, updated_at) " +
                "VALUES ('fetch_title', " + t + ", 'recent', 'pending', 0, '2026-04-27', '2026-04-27', '2026-04-27')"));

        var rows = service.listRecent(0, 50).rows();
        assertEquals(1, rows.size());
        assertEquals("pending", rows.get(0).queueStatus());
    }

    @Test
    void listRecent_queueStatusPrefersInFlightOverPending() {
        // When a title has multiple queue rows, in_flight wins over pending. This pins
        // the CASE-branch precedence in the queue_status subquery.
        long t = insertTitle("QSORT-001");
        insertLocation(t, "vol-recent", "/qs", "2026-04-27");
        jdbi.useHandle(h -> {
            h.execute("INSERT INTO javdb_enrichment_queue(job_type, target_id, source, status, attempts, " +
                    "next_attempt_at, created_at, updated_at) " +
                    "VALUES ('fetch_title', " + t + ", 'recent', 'pending', 0, '2026-04-27', '2026-04-27', '2026-04-27')");
            h.execute("INSERT INTO javdb_enrichment_queue(job_type, target_id, source, status, attempts, " +
                    "next_attempt_at, created_at, updated_at) " +
                    "VALUES ('fetch_title', " + t + ", 'recent', 'in_flight', 0, '2026-04-27', '2026-04-27', '2026-04-27')");
        });

        var rows = service.listRecent(0, 50).rows();
        assertEquals(1, rows.size());
        assertEquals("in_flight", rows.get(0).queueStatus(),
                "in_flight must win over pending when both rows exist for the same title");
    }

    // ── listRecent: actress credit + eligibility ────────────────────────────

    @Test
    void listRecent_marksActressEligibleWhenAboveThreshold() {
        long alice = insertActress("Alice");
        // Alice gets two titles → meets threshold (min=2).
        long t1 = insertTitle("AL-001");
        long t2 = insertTitle("AL-002");
        insertCredit(t1, alice);
        insertCredit(t2, alice);
        insertLocation(t1, "vol-recent", "/a1", "2026-04-26");
        insertLocation(t2, "vol-recent", "/a2", "2026-04-27");

        var rows = service.listRecent(0, 50).rows();
        assertEquals(2, rows.size());
        for (var row : rows) {
            assertNotNull(row.actress());
            assertEquals("Alice", row.actress().name());
            assertEquals("eligible", row.actress().eligibility(),
                    "Alice has 2 titles ≥ threshold 2 → eligible");
        }
    }

    @Test
    void listRecent_marksActressIneligibleWhenSentinel() {
        long various = insertSentinelActress("Various");
        long t = insertTitle("V-001");
        insertCredit(t, various);
        insertLocation(t, "vol-recent", "/v", "2026-04-27");

        var rows = service.listRecent(0, 50).rows();
        assertEquals(1, rows.size());
        assertNotNull(rows.get(0).actress());
        assertEquals("sentinel", rows.get(0).actress().eligibility(),
                "sentinel actress is its own eligibility code (red badge)");
    }

    @Test
    void listRecent_returnsNullActressWhenNoCredit() {
        long t = insertTitle("NO-001");
        insertLocation(t, "vol-recent", "/n", "2026-04-27");

        var rows = service.listRecent(0, 50).rows();
        assertEquals(1, rows.size());
        assertNull(rows.get(0).actress());
    }

    // ── listPool: scoping + sort ───────────────────────────────────────────

    @Test
    void listPool_scopesToOneVolumeAndSortsByCode() {
        long tA = insertTitle("ABC-002");
        long tB = insertTitle("ABC-001");
        long tC = insertTitle("XYZ-001");  // in a different pool
        insertLocation(tA, "pool-jav", "/A", "2026-04-25");
        insertLocation(tB, "pool-jav", "/B", "2026-04-26");
        insertLocation(tC, "pool-av",  "/C", "2026-04-27");

        var page = service.listPool("pool-jav", 0, 50);

        assertEquals(2, page.rows().size());
        assertEquals("ABC-001", page.rows().get(0).code(), "pool view sorts by code asc");
        assertEquals("ABC-002", page.rows().get(1).code());
        assertEquals("pool-jav", page.rows().get(0).volumeId());
    }

    // ── listPools: chip counts include zero-count pools ────────────────────

    @Test
    void listPools_countsPerSortPoolVolumeIncludingZero() {
        long t = insertTitle("ABC-001");
        insertLocation(t, "pool-jav", "/A", "2026-04-25");
        // pool-av has no titles → still appears with count 0.

        List<TitleDiscoveryService.PoolChip> chips = service.listPools();

        assertEquals(2, chips.size());
        assertEquals("pool-jav", chips.get(0).volumeId());
        assertEquals(1, chips.get(0).unenrichedCount());
        assertEquals("pool-av", chips.get(1).volumeId());
        assertEquals(0, chips.get(1).unenrichedCount(),
                "empty pools must still appear so they can be greyed out in the UI");
    }

    // ── code-prefix filter (case-insensitive) ──────────────────────────────

    @Test
    void listRecent_filtersByCaseInsensitiveCodePrefix() {
        long abc1 = insertTitle("ABC-001");
        long abc2 = insertTitle("ABC-002");
        long xyz  = insertTitle("XYZ-001");
        insertLocation(abc1, "vol-recent", "/1", "2026-04-25");
        insertLocation(abc2, "vol-recent", "/2", "2026-04-26");
        insertLocation(xyz,  "vol-recent", "/3", "2026-04-27");

        // Lowercase input — must match uppercase codes via COLLATE NOCASE.
        var page = service.listRecent("abc", 0, 50);
        var codes = page.rows().stream().map(TitleDiscoveryService.TitleRow::code).toList();
        assertEquals(2, codes.size());
        assertTrue(codes.contains("ABC-001"));
        assertTrue(codes.contains("ABC-002"));
        assertFalse(codes.contains("XYZ-001"));
        assertEquals(1, page.totalPages(), "totalPages reflects the filtered count, not the total");
    }

    @Test
    void listRecent_emptyOrBlankFilter_returnsEverything() {
        long t1 = insertTitle("ABC-001");
        long t2 = insertTitle("XYZ-001");
        insertLocation(t1, "vol-recent", "/1", "2026-04-26");
        insertLocation(t2, "vol-recent", "/2", "2026-04-27");

        var unfiltered = service.listRecent(null, 0, 50);
        var blank      = service.listRecent("   ", 0, 50);
        assertEquals(2, unfiltered.rows().size());
        assertEquals(2, blank.rows().size(),
                "blank filter must behave the same as no filter");
    }

    @Test
    void listCollections_filtersByCaseInsensitiveCodePrefix() {
        long alice = insertActress("Alice");
        long bob   = insertActress("Bob");
        long abc = insertTitle("ABC-001");
        long xyz = insertTitle("XYZ-001");
        for (long t : new long[] {abc, xyz}) {
            insertCredit(t, alice);
            insertCredit(t, bob);
            insertLocation(t, "vol-recent", "/" + t, "2026-04-26");
        }

        var codes = service.listCollections("abc", 0, 50).rows().stream()
                .map(TitleDiscoveryService.CollectionRow::code).toList();
        assertEquals(List.of("ABC-001"), codes);
    }

    // ── listCollections: filter, cast eligibility, ordering ────────────────

    @Test
    void listCollections_includesOnlyMultiActressTitles() {
        long alice = insertActress("Alice");
        long bob   = insertActress("Bob");
        long solo  = insertTitle("SOLO-001");
        long duo   = insertTitle("DUO-001");
        insertCredit(solo, alice);
        insertCredit(duo,  alice);
        insertCredit(duo,  bob);
        insertLocation(solo, "vol-recent", "/s", "2026-04-25");
        insertLocation(duo,  "vol-recent", "/d", "2026-04-26");

        var page = service.listCollections(0, 50);

        assertEquals(1, page.rows().size(), "single-actress titles must be excluded");
        assertEquals("DUO-001", page.rows().get(0).code());
    }

    @Test
    void listCollections_visibleAcrossAllStructureTypes_optionB() {
        // Option B from the design discussion: multi-actress titles surface here regardless
        // of the volume's structure_type. The volume column tells the user where the title is.
        long a1 = insertActress("Alice");
        long a2 = insertActress("Bob");
        long onColl = insertTitle("COL-001");
        long onConv = insertTitle("CONV-001");
        long onPool = insertTitle("POOL-001");
        for (long t : new long[] {onColl, onConv, onPool}) {
            insertCredit(t, a1);
            insertCredit(t, a2);
        }
        insertLocation(onColl, "pool-jav", "/c", "2026-04-25");      // sort_pool here for variety
        insertLocation(onConv, "vol-recent", "/v", "2026-04-26");    // conventional
        insertLocation(onPool, "pool-av", "/p", "2026-04-27");

        var codes = service.listCollections(0, 50).rows().stream()
                .map(TitleDiscoveryService.CollectionRow::code).toList();
        assertEquals(3, codes.size(), "multi-actress titles surface from all structure types");
        // Sort: code asc.
        assertEquals(List.of("COL-001", "CONV-001", "POOL-001"), codes);
    }

    @Test
    void listCollections_castShowsThreeStateEligibility() {
        long realEligible    = insertActress("Eligible");        // 2 titles → eligible
        long realIneligible  = insertActress("Ineligible");      // 1 title → below_threshold
        long sentinel        = insertSentinelActress("Various"); // sentinel
        long t = insertTitle("MIX-001");
        long padding = insertTitle("PAD-001");
        insertCredit(t, realEligible);
        insertCredit(t, realIneligible);
        insertCredit(t, sentinel);
        // Give realEligible a second credit so she meets the threshold of 2.
        insertCredit(padding, realEligible);
        insertLocation(t,       "vol-recent", "/m", "2026-04-27");
        insertLocation(padding, "vol-recent", "/p", "2026-04-26");

        var rows = service.listCollections(0, 50).rows();
        var mix = rows.stream().filter(r -> r.code().equals("MIX-001")).findFirst().orElseThrow();
        assertEquals(3, mix.cast().size());
        var byName = mix.cast().stream().collect(java.util.stream.Collectors.toMap(
                TitleDiscoveryService.ActressCredit::name, c -> c));
        assertEquals("eligible",        byName.get("Eligible").eligibility());
        assertEquals("below_threshold", byName.get("Ineligible").eligibility());
        assertEquals("sentinel",        byName.get("Various").eligibility());
    }

    @Test
    void listCollections_excludesEnrichedAndUnderscoreCodes() {
        long a1 = insertActress("A");
        long a2 = insertActress("B");
        long enriched  = insertTitle("ENR-001");
        long sandbox   = insertTitle("_sandbox");
        long shown     = insertTitle("SHOW-001");
        for (long t : new long[] {enriched, sandbox, shown}) {
            insertCredit(t, a1);
            insertCredit(t, a2);
            insertLocation(t, "vol-recent", "/x" + t, "2026-04-26");
        }
        insertEnrichment(enriched, "slug-x");

        var codes = service.listCollections(0, 50).rows().stream()
                .map(TitleDiscoveryService.CollectionRow::code).toList();
        assertEquals(List.of("SHOW-001"), codes);
    }

    // ── enqueue: source validation, cap, actress lookup ────────────────────

    @Test
    void enqueue_rejectsInvalidSource() {
        assertThrows(IllegalArgumentException.class,
                () -> service.enqueue("actress", List.of(1L)));
        assertThrows(IllegalArgumentException.class,
                () -> service.enqueue("nonsense", List.of(1L)));
    }

    @Test
    void enqueue_collection_passesNullActressId() {
        long alice = insertActress("Alice");
        long bob   = insertActress("Bob");
        long t     = insertTitle("COL-001");
        insertCredit(t, alice);
        insertCredit(t, bob);
        insertLocation(t, "vol-recent", "/c", "2026-04-27");

        int n = service.enqueue("collection", List.of(t));
        assertEquals(1, n);

        Long actressId = jdbi.withHandle(h -> h.createQuery(
                "SELECT actress_id FROM javdb_enrichment_queue WHERE target_id = :t")
                .bind("t", t).mapTo(Long.class).findOne().orElse(null));
        assertNull(actressId,
                "collection-source enqueues never bind a single actress; chain decision is per-cast at runtime");

        String src = jdbi.withHandle(h -> h.createQuery(
                "SELECT source FROM javdb_enrichment_queue WHERE target_id = :t")
                .bind("t", t).mapTo(String.class).one());
        assertEquals(EnrichmentJob.SOURCE_COLLECTION, src);
    }

    @Test
    void enqueue_acceptsRecentAndPool() {
        long t = insertTitle("ENQ-001");
        insertLocation(t, "vol-recent", "/q", "2026-04-27");

        int n = service.enqueue("recent", List.of(t));

        assertEquals(1, n);
        String src = jdbi.withHandle(h -> h.createQuery(
                "SELECT source FROM javdb_enrichment_queue WHERE target_id = :t AND status = 'pending'")
                .bind("t", t).mapTo(String.class).one());
        assertEquals(EnrichmentJob.SOURCE_RECENT, src);
    }

    @Test
    void enqueue_capsAtHundred() {
        List<Long> ids = new java.util.ArrayList<>();
        for (int i = 0; i < 150; i++) {
            long id = insertTitle("CAP-" + i);
            insertLocation(id, "vol-recent", "/" + i, "2026-04-27");
            ids.add(id);
        }
        int n = service.enqueue("recent", ids);
        assertEquals(TitleDiscoveryService.ENQUEUE_CAP, n);

        int actual = jdbi.withHandle(h -> h.createQuery(
                "SELECT COUNT(*) FROM javdb_enrichment_queue WHERE source = 'recent'")
                .mapTo(Integer.class).one());
        assertEquals(100, actual);
    }

    @Test
    void enqueue_passesActressIdWhenSingleCredit_nullWhenMulti() {
        long alice = insertActress("Alice");
        long bob   = insertActress("Bob");
        long solo  = insertTitle("SOLO-001");
        long duo   = insertTitle("DUO-001");
        insertCredit(solo, alice);
        insertCredit(duo,  alice);
        insertCredit(duo,  bob);

        service.enqueue("recent", List.of(solo, duo));

        Long soloAct = jdbi.withHandle(h -> h.createQuery(
                "SELECT actress_id FROM javdb_enrichment_queue WHERE target_id = :t")
                .bind("t", solo).mapTo(Long.class).one());
        Long duoAct = jdbi.withHandle(h -> h.createQuery(
                "SELECT actress_id FROM javdb_enrichment_queue WHERE target_id = :t")
                .bind("t", duo).mapTo(Long.class).findOne().orElse(null));
        assertEquals(alice, soloAct);
        assertNull(duoAct, "multi-actress title enqueues with null actress_id");
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private long insertActress(String name) {
        return jdbi.withHandle(h -> h.createUpdate(
                "INSERT INTO actresses(canonical_name, tier, first_seen_at, is_sentinel) " +
                "VALUES (:n, 'LIBRARY', '2024-01-01', 0)")
                .bind("n", name)
                .executeAndReturnGeneratedKeys("id").mapTo(Long.class).one());
    }

    private long insertSentinelActress(String name) {
        return jdbi.withHandle(h -> h.createUpdate(
                "INSERT INTO actresses(canonical_name, tier, first_seen_at, is_sentinel) " +
                "VALUES (:n, 'LIBRARY', '2024-01-01', 1)")
                .bind("n", name)
                .executeAndReturnGeneratedKeys("id").mapTo(Long.class).one());
    }

    private long insertTitle(String code) {
        return jdbi.withHandle(h -> h.createUpdate(
                "INSERT INTO titles(code) VALUES (:c)")
                .bind("c", code)
                .executeAndReturnGeneratedKeys("id").mapTo(Long.class).one());
    }

    private void insertCredit(long titleId, long actressId) {
        jdbi.useHandle(h -> h.createUpdate(
                "INSERT INTO title_actresses(title_id, actress_id) VALUES (:t, :a)")
                .bind("t", titleId).bind("a", actressId).execute());
    }

    private void insertLocation(long titleId, String volumeId, String path, String addedDate) {
        jdbi.useHandle(h -> h.createUpdate(
                "INSERT INTO title_locations(title_id, volume_id, partition_id, path, last_seen_at, added_date) " +
                "VALUES (:t, :v, 'p1', :p, :d, :d)")
                .bind("t", titleId).bind("v", volumeId).bind("p", path).bind("d", addedDate)
                .execute());
    }

    private void insertEnrichment(long titleId, String slug) {
        jdbi.useHandle(h -> h.createUpdate(
                "INSERT INTO title_javdb_enrichment(title_id, javdb_slug, fetched_at) " +
                "VALUES (:t, :s, :now)")
                .bind("t", titleId).bind("s", slug).bind("now", "2026-04-27T00:00:00Z")
                .execute());
    }

    private void insertStaging(long titleId, String status) {
        jdbi.useHandle(h -> h.createUpdate(
                "INSERT INTO javdb_title_staging(title_id, status) VALUES (:t, :s)")
                .bind("t", titleId).bind("s", status).execute());
    }

    private void seedVolumes(String id, String structureType) {
        jdbi.useHandle(h -> h.createUpdate(
                "INSERT INTO volumes(id, structure_type) VALUES (:i, :s)")
                .bind("i", id).bind("s", structureType).execute());
    }

    private VolumeConfig volumeCfg(String id, String structureType) {
        return new VolumeConfig(id, "//host/share", structureType, "server", "group");
    }
}
