package com.organizer3.javdb.enrichment;

import com.organizer3.db.SchemaInitializer;
import com.organizer3.javdb.JavdbClient;
import com.organizer3.javdb.JavdbFetchException;
import com.organizer3.javdb.JavdbNotFoundException;
import com.organizer3.javdb.JavdbSearchParser;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class JavdbSlugResolverTest {

    private FakeJavdbClient client;
    private JavdbSlugResolver resolver;

    @BeforeEach
    void setUp() {
        client = new FakeJavdbClient();
        resolver = new JavdbSlugResolver(client);
    }

    @Test
    void actressAnchoredPathReturnsCorrectSlugFromFilmography() {
        // Mana Sakura's filmography has STAR-334 → "correctSlug" (her actual title).
        client.actressPage("J9dd", 1, html(true,
                entry("STAR-334", "correctSlug"),
                entry("STAR-358", "anotherSlug")));

        // Code search would return the WRONG slug (a different studio's STAR-334).
        client.searchResult("STAR-334", "wrongSlugFromCodeSearch");

        var result = resolver.resolve("STAR-334", "J9dd");

        assertInstanceOf(JavdbSlugResolver.Success.class, result);
        var success = (JavdbSlugResolver.Success) result;
        assertEquals("correctSlug", success.slug());
        assertEquals(JavdbSlugResolver.Source.ACTRESS_FILMOGRAPHY, success.source());
        assertEquals(0, client.searchByCodeCalls, "must not fall back to code search when filmography matches");
    }

    @Test
    void actressAnchoredPathReturnsNoMatchWhenCodeAbsentFromFilmography() {
        client.actressPage("J9dd", 1, html(false,
                entry("STAR-358", "slugA"),
                entry("STAR-359", "slugB")));

        var result = resolver.resolve("STAR-334", "J9dd");

        assertInstanceOf(JavdbSlugResolver.NoMatchInFilmography.class, result);
        assertEquals("J9dd", ((JavdbSlugResolver.NoMatchInFilmography) result).actressSlug());
        assertEquals(0, client.searchByCodeCalls,
                "must NOT fall back to code search — no_match is the correct outcome");
    }

    @Test
    void filmographyPaginationStitchesPagesUntilNoNext() {
        // 3-page filmography: code on page 3 should still resolve.
        client.actressPage("J9dd", 1, html(true, entry("STAR-1", "s1"), entry("STAR-2", "s2")));
        client.actressPage("J9dd", 2, html(true, entry("STAR-3", "s3")));
        client.actressPage("J9dd", 3, html(false, entry("STAR-4", "s4-found")));

        var result = resolver.resolve("STAR-4", "J9dd");

        assertInstanceOf(JavdbSlugResolver.Success.class, result);
        assertEquals("s4-found", ((JavdbSlugResolver.Success) result).slug());
        assertEquals(3, client.actressPageCalls.size(), "should have fetched all 3 pages");
    }

    @Test
    void filmographyIsCachedPerActressAcrossResolveCalls() {
        // 2-page filmography. Two resolves for two of her titles must only fetch the pages once.
        client.actressPage("J9dd", 1, html(true, entry("STAR-1", "s1")));
        client.actressPage("J9dd", 2, html(false, entry("STAR-2", "s2")));

        var r1 = resolver.resolve("STAR-1", "J9dd");
        var r2 = resolver.resolve("STAR-2", "J9dd");

        assertInstanceOf(JavdbSlugResolver.Success.class, r1);
        assertInstanceOf(JavdbSlugResolver.Success.class, r2);
        assertEquals(2, client.actressPageCalls.size(),
                "filmography pages must be fetched once total — second resolve uses the cache");
    }

    @Test
    void clearFilmographyCacheForcesRefetch() {
        client.actressPage("J9dd", 1, html(false, entry("STAR-1", "s1")));

        resolver.resolve("STAR-1", "J9dd");
        resolver.clearFilmographyCache();
        resolver.resolve("STAR-1", "J9dd");

        assertEquals(2, client.actressPageCalls.size(), "post-clear resolve must re-fetch");
    }

    @Test
    void codeSearchFallbackUsedWhenNoActressSlug() {
        client.searchResult("XYZ-001", "fallbackSlug");

        var result = resolver.resolve("XYZ-001", null);

        assertInstanceOf(JavdbSlugResolver.Success.class, result);
        var success = (JavdbSlugResolver.Success) result;
        assertEquals("fallbackSlug", success.slug());
        assertEquals(JavdbSlugResolver.Source.CODE_SEARCH_FALLBACK, success.source());
        assertEquals(0, client.actressPageCalls.size(), "must not fetch a filmography when no actress slug given");
    }

    @Test
    void codeSearchFallbackUsedWhenActressSlugBlank() {
        client.searchResult("XYZ-001", "fallbackSlug");

        var result = resolver.resolve("XYZ-001", "  ");

        assertInstanceOf(JavdbSlugResolver.Success.class, result);
        assertEquals(JavdbSlugResolver.Source.CODE_SEARCH_FALLBACK,
                ((JavdbSlugResolver.Success) result).source());
    }

    @Test
    void codeSearchReturnsNotFoundWhenSearchHasNoResults() {
        // No search result registered → empty html → parser returns empty → CodeNotFound.
        var result = resolver.resolve("DOES-NOT-EXIST", null);

        assertInstanceOf(JavdbSlugResolver.CodeNotFound.class, result);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static String html(boolean hasNextPage, String... entriesHtml) {
        StringBuilder sb = new StringBuilder("<html><body>");
        for (String e : entriesHtml) sb.append(e);
        sb.append("<nav class=\"pagination\">");
        if (hasNextPage) {
            sb.append("<a rel=\"next\" class=\"pagination-next\" href=\"#\">Next</a>");
        }
        sb.append("</nav>");
        sb.append("</body></html>");
        return sb.toString();
    }

    private static String entry(String code, String slug) {
        return """
            <div class="item">
              <a href="/v/%s" class="box">
                <div class="video-title"><strong>%s</strong></div>
              </a>
            </div>
            """.formatted(slug, code);
    }

    // ── L2 integration tests ─────────────────────────────────────────────────

    /**
     * Tests that require a real in-memory SQLite database to verify the two-level
     * (L1 in-memory + L2 database) caching behaviour of {@link JavdbSlugResolver}.
     */
    @Nested
    class L2CacheIntegrationTest {

        private Connection connection;
        private JdbiJavdbActressFilmographyRepository filmographyRepo;
        private FakeJavdbClient l2Client;

        @BeforeEach
        void setUp() throws Exception {
            connection = DriverManager.getConnection("jdbc:sqlite::memory:");
            Jdbi jdbi = Jdbi.create(connection);
            new SchemaInitializer(jdbi).initialize();
            filmographyRepo = new JdbiJavdbActressFilmographyRepository(jdbi);
            l2Client = new FakeJavdbClient();
        }

        @AfterEach
        void tearDown() throws Exception {
            connection.close();
        }

        private JavdbSlugResolver resolverWithClock(Clock clock) {
            return new JavdbSlugResolver(l2Client, new JavdbFilmographyParser(),
                    new JavdbSearchParser(), filmographyRepo, null, 90, 50, clock);
        }

        private static Clock fixedClock(String instant) {
            return Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
        }

        @Test
        void firstResolveHitsHttpAndPersistsToDb() {
            l2Client.actressPage("J9dd", 1, html(false, entry("STAR-334", "correctSlug")));
            JavdbSlugResolver r = resolverWithClock(fixedClock("2026-04-29T10:00:00Z"));

            var result = r.resolve("STAR-334", "J9dd");

            assertInstanceOf(JavdbSlugResolver.Success.class, result);
            assertEquals("correctSlug", ((JavdbSlugResolver.Success) result).slug());
            assertEquals(1, l2Client.actressPageCalls.size(), "should have fetched from HTTP");

            // L2 must have the data now
            assertEquals(Optional.of("correctSlug"), filmographyRepo.findTitleSlug("J9dd", "STAR-334"));
        }

        @Test
        void secondResolveInSameJvmHitsL1NotDb() {
            l2Client.actressPage("J9dd", 1, html(false, entry("STAR-334", "slug1")));
            JavdbSlugResolver r = resolverWithClock(fixedClock("2026-04-29T10:00:00Z"));

            r.resolve("STAR-334", "J9dd");
            // Second resolve — L1 is populated, no DB or HTTP should be touched.
            var result = r.resolve("STAR-334", "J9dd");

            assertInstanceOf(JavdbSlugResolver.Success.class, result);
            assertEquals(1, l2Client.actressPageCalls.size(), "L1 hit must not re-fetch HTTP");
        }

        @Test
        void resolverWithPrePopulatedDbButEmptyL1LoadsFromDbWithoutHttp() {
            // Pre-populate L2 with a fresh entry (TTL not exceeded)
            String fetchedAt = "2026-04-29T09:00:00Z";
            filmographyRepo.upsertFilmography("J9dd", new FetchResult(
                    fetchedAt, 1, null, "http",
                    List.of(new FilmographyEntry("STAR-334", "slugFromDb"))));

            // Resolver starts with empty L1 — should load from L2.
            JavdbSlugResolver r = resolverWithClock(fixedClock("2026-04-29T10:00:00Z"));
            var result = r.resolve("STAR-334", "J9dd");

            assertInstanceOf(JavdbSlugResolver.Success.class, result);
            assertEquals("slugFromDb", ((JavdbSlugResolver.Success) result).slug());
            assertEquals(0, l2Client.actressPageCalls.size(), "must not hit HTTP when L2 is fresh");
        }

        @Test
        void concurrentFilmographyFetchCoalescesOneHttpRequest() throws Exception {
            l2Client.actressPage("J9dd", 1, html(false, entry("STAR-334", "slug1")));
            JavdbSlugResolver r = resolverWithClock(fixedClock("2026-04-29T10:00:00Z"));

            // Start gate: release both threads simultaneously to maximize concurrency.
            CountDownLatch startGate = new CountDownLatch(1);
            AtomicInteger fetchCount = new AtomicInteger();

            // Wrap the client to count actual HTTP calls.
            JavdbClient countingClient = new FakeJavdbClient() {
                {
                    actressPage("J9dd", 1, html(false, entry("STAR-334", "slug1")));
                }

                @Override
                public String fetchActressPage(String slug, int page) {
                    fetchCount.incrementAndGet();
                    return super.fetchActressPage(slug, page);
                }
            };
            JavdbSlugResolver r2 = new JavdbSlugResolver(countingClient, new JavdbFilmographyParser(),
                    new JavdbSearchParser(), filmographyRepo, null, 90, 50, fixedClock("2026-04-29T10:00:00Z"));

            CompletableFuture<Map<String, String>> t1 = CompletableFuture.supplyAsync(() -> {
                try { startGate.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return r2.filmographyOf("J9dd");
            });
            CompletableFuture<Map<String, String>> t2 = CompletableFuture.supplyAsync(() -> {
                try { startGate.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return r2.filmographyOf("J9dd");
            });

            startGate.countDown(); // release both threads
            Map<String, String> result1 = t1.join();
            Map<String, String> result2 = t2.join();

            assertEquals(1, fetchCount.get(), "per-actress mutex must coalesce concurrent fetches to one HTTP call");
            assertEquals(result1, result2, "both threads must see the same map");
            assertEquals("slug1", result1.get("STAR-334"));
        }

        @Test
        void midPaginationFailureLeavesDatabaseClean() {
            // Page 1 succeeds, page 2 throws — must not write anything to DB.
            l2Client.actressPage("J9dd", 1, html(true, entry("STAR-334", "slug1")));
            l2Client.failOnPage = 2;

            JavdbSlugResolver r = resolverWithClock(fixedClock("2026-04-29T10:00:00Z"));
            assertThrows(Exception.class, () -> r.filmographyOf("J9dd"),
                    "mid-pagination failure must propagate");

            // L2 must have no data — no partial state
            assertTrue(filmographyRepo.findMeta("J9dd").isEmpty(),
                    "no metadata row must be written after partial fetch");
            assertTrue(filmographyRepo.getCodeToSlug("J9dd").isEmpty(),
                    "no entry rows must be written after partial fetch");
        }

        @Test
        void actress404WithPriorCache_returnsStaleMapAndPinsMetaAsNotFound() {
            // Pre-populate L2 with 2 entries from a successful fetch
            filmographyRepo.upsertFilmography("J9dd", new FetchResult(
                    "2025-10-11T00:00:00Z", 1, null, "http",
                    List.of(new FilmographyEntry("STAR-334", "slug1"),
                            new FilmographyEntry("STAR-358", "slug2"))));

            // Mock client now throws 404 on any actress page fetch
            l2Client.throwNotFoundException = true;

            // "now" is past TTL so L2 is stale and HTTP fetch is attempted
            JavdbSlugResolver r = resolverWithClock(fixedClock("2026-04-29T00:00:00Z"));
            Map<String, String> result = r.filmographyOf("J9dd");

            // Resolver must still return the (now-stale) cached map
            assertEquals(2, result.size());
            assertEquals("slug1", result.get("STAR-334"));
            assertEquals("slug2", result.get("STAR-358"));

            // Metadata must reflect not_found
            Optional<FilmographyMeta> meta = filmographyRepo.findMeta("J9dd");
            assertTrue(meta.isPresent());
            assertEquals("not_found", meta.get().lastFetchStatus());

            // Entries still present but stale
            assertEquals(2, filmographyRepo.getCodeToSlug("J9dd").size());
        }

        @Test
        void staleL2EntryTriggesHttpRefetch() {
            // L2 has an entry fetched 200 days ago — past the 90-day TTL.
            filmographyRepo.upsertFilmography("J9dd", new FetchResult(
                    "2025-10-11T00:00:00Z", 1, null, "http",
                    List.of(new FilmographyEntry("STAR-334", "oldSlug"))));

            l2Client.actressPage("J9dd", 1, html(false, entry("STAR-334", "freshSlug")));
            // "now" is 2026-04-29 — 200 days after the 2025-10-11 fetch, > 90-day TTL
            JavdbSlugResolver r = resolverWithClock(fixedClock("2026-04-29T00:00:00Z"));

            var result = r.resolve("STAR-334", "J9dd");

            assertInstanceOf(JavdbSlugResolver.Success.class, result);
            assertEquals("freshSlug", ((JavdbSlugResolver.Success) result).slug());
            assertEquals(1, l2Client.actressPageCalls.size(), "stale L2 must trigger HTTP re-fetch");
        }
    }

    /** Stub client that returns canned HTML for predetermined inputs. */
    static class FakeJavdbClient implements JavdbClient {
        private final Map<String, String> codeSearchResults = new HashMap<>();
        private final Map<String, String> actressPageResults = new HashMap<>();
        int searchByCodeCalls = 0;
        List<String> actressPageCalls = new ArrayList<>();
        boolean throwNotFoundException = false;
        int failOnPage = -1; // -1 = never fail

        void searchResult(String code, String slugInResult) {
            codeSearchResults.put(code, "<html><body><a href=\"/v/" + slugInResult + "\"></a></body></html>");
        }

        void actressPage(String actressSlug, int page, String html) {
            actressPageResults.put(actressSlug + "?page=" + page, html);
        }

        @Override public String searchByCode(String code) {
            searchByCodeCalls++;
            return codeSearchResults.getOrDefault(code, "<html><body></body></html>");
        }

        @Override public String fetchTitlePage(String slug) {
            throw new UnsupportedOperationException("not used by the resolver");
        }

        @Override public String fetchActressPage(String slug) {
            return fetchActressPage(slug, 1);
        }

        @Override public String fetchActressPage(String slug, int page) {
            actressPageCalls.add(slug + "?page=" + page);
            if (throwNotFoundException) throw new JavdbNotFoundException("/actors/" + slug);
            if (page == failOnPage) throw new JavdbFetchException("simulated failure on page " + page);
            return actressPageResults.getOrDefault(slug + "?page=" + page, "<html><body></body></html>");
        }
    }
}
