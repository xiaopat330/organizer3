package com.organizer3.javdb.enrichment;

import com.organizer3.db.SchemaInitializer;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

class JavdbActressFilmographyRepositoryTest {

    private Connection connection;
    private JdbiJavdbActressFilmographyRepository repo;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        Jdbi jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        repo = new JdbiJavdbActressFilmographyRepository(jdbi);
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static FetchResult sampleResult(String fetchedAt, int pageCount, String... codeSlugPairs) {
        List<FilmographyEntry> entries = new java.util.ArrayList<>();
        for (int i = 0; i < codeSlugPairs.length; i += 2) {
            entries.add(new FilmographyEntry(codeSlugPairs[i], codeSlugPairs[i + 1]));
        }
        return new FetchResult(fetchedAt, pageCount, null, "http", entries);
    }

    private static Clock fixedClock(String instant) {
        return Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
    }

    // ── tests ─────────────────────────────────────────────────────────────────

    @Test
    void upsertAndFindMeta_roundTrip() {
        repo.upsertFilmography("J9dd", sampleResult("2026-01-01T00:00:00Z", 3,
                "STAR-334", "slug1", "STAR-358", "slug2"));

        Optional<FilmographyMeta> meta = repo.findMeta("J9dd");
        assertTrue(meta.isPresent());
        assertEquals("J9dd", meta.get().actressSlug());
        assertEquals("2026-01-01T00:00:00Z", meta.get().fetchedAt());
        assertEquals(3, meta.get().pageCount());
        assertNull(meta.get().lastReleaseDate());
        assertEquals("http", meta.get().source());
    }

    @Test
    void getCodeToSlug_returnsAllEntries() {
        repo.upsertFilmography("J9dd", sampleResult("2026-01-01T00:00:00Z", 1,
                "STAR-334", "slug1", "STAR-358", "slug2", "STAR-999", "slug3"));

        Map<String, String> map = repo.getCodeToSlug("J9dd");
        assertEquals(3, map.size());
        assertEquals("slug1", map.get("STAR-334"));
        assertEquals("slug2", map.get("STAR-358"));
        assertEquals("slug3", map.get("STAR-999"));
    }

    @Test
    void getCodeToSlug_returnsEmptyMapForUnknownActress() {
        Map<String, String> map = repo.getCodeToSlug("nobody");
        assertTrue(map.isEmpty());
    }

    @Test
    void findTitleSlug_returnsPresentForKnownPair() {
        repo.upsertFilmography("J9dd", sampleResult("2026-01-01T00:00:00Z", 1,
                "STAR-334", "correctSlug"));

        assertEquals(Optional.of("correctSlug"), repo.findTitleSlug("J9dd", "STAR-334"));
    }

    @Test
    void findTitleSlug_returnsEmptyForUnknownActress() {
        assertEquals(Optional.empty(), repo.findTitleSlug("nobody", "STAR-334"));
    }

    @Test
    void findTitleSlug_returnsEmptyForUnknownCode() {
        repo.upsertFilmography("J9dd", sampleResult("2026-01-01T00:00:00Z", 1,
                "STAR-334", "slug1"));

        assertEquals(Optional.empty(), repo.findTitleSlug("J9dd", "STAR-999"));
    }

    @Test
    void upsert_replacesExistingDataAtomically() {
        // First fetch: 3 entries
        repo.upsertFilmography("J9dd", sampleResult("2026-01-01T00:00:00Z", 2,
                "STAR-1", "s1", "STAR-2", "s2", "STAR-OLD", "old"));

        // Second fetch (re-upsert): 2 entries, different set
        repo.upsertFilmography("J9dd", sampleResult("2026-06-01T00:00:00Z", 1,
                "STAR-1", "s1-new", "STAR-2", "s2"));

        Map<String, String> map = repo.getCodeToSlug("J9dd");
        assertEquals(2, map.size(), "old entries from previous fetch must not survive upsert");
        assertEquals("s1-new", map.get("STAR-1"), "updated slug must reflect new fetch");
        assertFalse(map.containsKey("STAR-OLD"), "entry absent from new fetch must be gone");

        // Metadata also updated
        Optional<FilmographyMeta> meta = repo.findMeta("J9dd");
        assertTrue(meta.isPresent());
        assertEquals("2026-06-01T00:00:00Z", meta.get().fetchedAt());
        assertEquals(1, meta.get().pageCount());
    }

    @Test
    void evict_dropsBothMetaAndEntries() {
        repo.upsertFilmography("J9dd", sampleResult("2026-01-01T00:00:00Z", 1,
                "STAR-334", "slug1"));

        repo.evict("J9dd");

        assertTrue(repo.findMeta("J9dd").isEmpty());
        assertTrue(repo.getCodeToSlug("J9dd").isEmpty());
    }

    @Test
    void evict_isIdempotentForUnknownActress() {
        assertDoesNotThrow(() -> repo.evict("nobody"));
    }

    @Test
    void isStale_returnsTrueWhenNoMetaExists() {
        Clock clock = fixedClock("2026-04-29T00:00:00Z");
        assertTrue(repo.isStale("nobody", 90, clock));
    }

    @Test
    void isStale_returnsFalseWhenWithinTtl() {
        repo.upsertFilmography("J9dd", sampleResult("2026-03-01T00:00:00Z", 1));
        // 59 days after fetch — within 90-day TTL
        Clock clock = fixedClock("2026-04-29T00:00:00Z");
        assertFalse(repo.isStale("J9dd", 90, clock));
    }

    @Test
    void isStale_returnsTrueWhenPastTtlAndActiveActress() {
        // lastReleaseDate is null (no info) → treat as active → stale after TTL
        repo.upsertFilmography("J9dd", sampleResult("2025-01-01T00:00:00Z", 1));
        // 483 days after fetch — well past 90-day TTL; no lastReleaseDate → active
        Clock clock = fixedClock("2026-04-29T00:00:00Z");
        assertTrue(repo.isStale("J9dd", 90, clock));
    }

    @Test
    void isStale_returnsFalseWhenPastTtlButLastReleaseDateOverTwoYearsOld() {
        // lastReleaseDate more than 2 years before "now" → settled catalog, skip TTL
        FetchResult result = new FetchResult(
                "2025-01-01T00:00:00Z", 1, "2022-01-01", "http", List.of());
        repo.upsertFilmography("J9dd", result);

        // Now is 2026-04-29; fetchedAt was 2025-01-01 (483 days ago, past 90-day TTL).
        // lastReleaseDate 2022-01-01 is >2 years before now → exemption applies.
        Clock clock = fixedClock("2026-04-29T00:00:00Z");
        assertFalse(repo.isStale("J9dd", 90, clock));
    }

    // ── drift detection tests ─────────────────────────────────────────────────

    @Test
    void drift_slugChangedForExistingCode_updatesRowAndBumpsDriftCount() {
        repo.upsertFilmography("J9dd", sampleResult("2026-01-01T00:00:00Z", 1,
                "STAR-334", "old-slug"));

        repo.upsertFilmography("J9dd", sampleResult("2026-06-01T00:00:00Z", 1,
                "STAR-334", "new-slug"));

        assertEquals(Optional.of("new-slug"), repo.findTitleSlug("J9dd", "STAR-334"));
        Optional<FilmographyMeta> meta = repo.findMeta("J9dd");
        assertTrue(meta.isPresent());
        assertEquals(1, meta.get().lastDriftCount(), "slug change must increment drift count");
    }

    @Test
    void drift_vanishedUnreferencedEntry_deleted() {
        repo.upsertFilmography("J9dd", sampleResult("2026-01-01T00:00:00Z", 1,
                "STAR-334", "slug1", "STAR-999", "slugVanished"));

        // Second fetch: STAR-999 is gone and no title_javdb_enrichment row references slugVanished
        repo.upsertFilmography("J9dd", sampleResult("2026-06-01T00:00:00Z", 1,
                "STAR-334", "slug1"));

        Map<String, String> map = repo.getCodeToSlug("J9dd");
        assertFalse(map.containsKey("STAR-999"), "unreferenced vanished entry must be deleted");
        assertEquals(0, repo.findMeta("J9dd").get().lastDriftCount(), "unreferenced delete is not drift");
    }

    @Test
    void drift_vanishedReferencedEntry_pinnedStale() throws Exception {
        // Seed filmography
        repo.upsertFilmography("J9dd", sampleResult("2026-01-01T00:00:00Z", 1,
                "STAR-334", "slug1", "STAR-999", "slugVanished"));

        // Insert a title_javdb_enrichment row referencing slugVanished (FK enforcement is off)
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("INSERT INTO title_javdb_enrichment (title_id, javdb_slug, fetched_at) " +
                         "VALUES (42, 'slugVanished', '2026-01-01T00:00:00Z')");
        }

        // Second fetch: STAR-999 is absent
        repo.upsertFilmography("J9dd", sampleResult("2026-06-01T00:00:00Z", 1,
                "STAR-334", "slug1"));

        // slugVanished is still referenced → entry pinned stale, not deleted
        Map<String, String> map = repo.getCodeToSlug("J9dd");
        assertTrue(map.containsKey("STAR-999"), "referenced vanished entry must be retained");

        // Verify stale=1 via direct DB query
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT stale FROM javdb_actress_filmography_entry " +
                     "WHERE actress_slug='J9dd' AND product_code='STAR-999'")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt("stale"), "vanished+referenced entry must be marked stale=1");
        }

        assertEquals(1, repo.findMeta("J9dd").get().lastDriftCount(),
                "vanished+referenced counts as drift");
    }

    @Test
    void drift_staleEntryReappearsInFetch_restoredStale0() throws Exception {
        // First fetch: STAR-334 is normal
        repo.upsertFilmography("J9dd", sampleResult("2026-01-01T00:00:00Z", 1,
                "STAR-334", "slug1"));

        // Manually mark it stale (simulate a prior 404 or vanish cycle)
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("UPDATE javdb_actress_filmography_entry SET stale=1 " +
                         "WHERE actress_slug='J9dd' AND product_code='STAR-334'");
        }

        // Re-fetch includes STAR-334 again → should restore stale=0
        repo.upsertFilmography("J9dd", sampleResult("2026-06-01T00:00:00Z", 1,
                "STAR-334", "slug1"));

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT stale FROM javdb_actress_filmography_entry " +
                     "WHERE actress_slug='J9dd' AND product_code='STAR-334'")) {
            assertTrue(rs.next());
            assertEquals(0, rs.getInt("stale"), "reappearing entry must have stale reset to 0");
        }
    }

    @Test
    void markNotFound_updatesStatusAndMarksEntriesStale() {
        repo.upsertFilmography("J9dd", sampleResult("2026-01-01T00:00:00Z", 1,
                "STAR-334", "slug1", "STAR-358", "slug2"));

        int count = repo.markNotFound("J9dd", "2026-06-01T00:00:00Z");

        assertEquals(2, count, "both entries should be marked stale");

        Optional<FilmographyMeta> meta = repo.findMeta("J9dd");
        assertTrue(meta.isPresent());
        assertEquals("not_found", meta.get().lastFetchStatus());
        assertEquals("2026-06-01T00:00:00Z", meta.get().fetchedAt());

        // Entries are still retrievable (not deleted)
        assertEquals(2, repo.getCodeToSlug("J9dd").size());
    }

    @Test
    void markNotFound_withNoPriorCache_createsMetaRow() {
        // No prior data — markNotFound should upsert a fresh metadata row
        int count = repo.markNotFound("newActress", "2026-06-01T00:00:00Z");

        assertEquals(0, count, "no entries to mark stale for unknown actress");
        Optional<FilmographyMeta> meta = repo.findMeta("newActress");
        assertTrue(meta.isPresent());
        assertEquals("not_found", meta.get().lastFetchStatus());
    }

    @Test
    void isStale_returnsTrueWhenPastTtlAndLastReleaseDateRecentEnough() {
        // lastReleaseDate 1 year ago — actress still active, TTL applies
        FetchResult result = new FetchResult(
                "2025-01-01T00:00:00Z", 1, "2025-06-01", "http", List.of());
        repo.upsertFilmography("J9dd", result);

        Clock clock = fixedClock("2026-04-29T00:00:00Z");
        assertTrue(repo.isStale("J9dd", 90, clock));
    }
}
