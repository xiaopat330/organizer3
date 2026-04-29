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
