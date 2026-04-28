package com.organizer3.javdb.enrichment;

import com.organizer3.javdb.JavdbClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    /** Stub client that returns canned HTML for predetermined inputs. */
    private static class FakeJavdbClient implements JavdbClient {
        private final Map<String, String> codeSearchResults = new HashMap<>();
        private final Map<String, String> actressPageResults = new HashMap<>();
        int searchByCodeCalls = 0;
        List<String> actressPageCalls = new ArrayList<>();

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
            return actressPageResults.getOrDefault(slug + "?page=" + page, "<html><body></body></html>");
        }
    }
}
