package com.organizer3.javdb;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class JavdbSearchParserTest {

    private final JavdbSearchParser parser = new JavdbSearchParser();

    @Test
    void extractsFirstTitleSlug() {
        String html = """
                <html><body>
                  <div class="movie-list">
                    <div class="item">
                      <a href="/v/AbXy12"><div class="cover"></div></a>
                    </div>
                    <div class="item">
                      <a href="/v/Zz9900"><div class="cover"></div></a>
                    </div>
                  </div>
                </body></html>
                """;

        Optional<String> slug = parser.parseFirstSlug(html);

        assertTrue(slug.isPresent());
        assertEquals("AbXy12", slug.get());
    }

    @Test
    void returnsEmptyWhenNoResults() {
        String html = "<html><body><p>No results</p></body></html>";
        assertTrue(parser.parseFirstSlug(html).isEmpty());
    }

    @Test
    void returnsEmptyForNullInput() {
        assertTrue(parser.parseFirstSlug(null).isEmpty());
    }

    // ── parseResults: (code, slug) pairs, used by the code-search fallback validation ──

    @Test
    void parseResultsExtractsCodeAndSlugPairsInOrder() {
        // Mirrors the real search-results markup (same movie-card shape as the filmography page).
        String html = """
                <html><body>
                  <div class="movie-list">
                    <div class="item">
                      <a href="/v/kKDYP0" class="box">
                        <div class="video-title"><strong>START-562</strong> some title text</div>
                      </a>
                    </div>
                    <div class="item">
                      <a href="/v/YwG1Xe" class="box">
                        <div class="video-title"><strong>START-541</strong> another title</div>
                      </a>
                    </div>
                  </div>
                </body></html>
                """;

        List<JavdbSearchParser.Result> results = parser.parseResults(html);

        assertEquals(2, results.size());
        assertEquals(new JavdbSearchParser.Result("START-562", "kKDYP0"), results.get(0));
        assertEquals(new JavdbSearchParser.Result("START-541", "YwG1Xe"), results.get(1));
    }

    @Test
    void parseResultsSkipsItemsMissingCodeOrSlug() {
        String html = """
                <html><body>
                  <div class="item">
                    <a href="/v/hasBoth" class="box">
                      <div class="video-title"><strong>ABC-001</strong> ok</div>
                    </a>
                  </div>
                  <div class="item">
                    <a href="/v/noCode" class="box">
                      <div class="video-title">title with no strong code</div>
                    </a>
                  </div>
                  <div class="item">
                    <div class="video-title"><strong>DEF-002</strong> no link</div>
                  </div>
                </body></html>
                """;

        List<JavdbSearchParser.Result> results = parser.parseResults(html);

        assertEquals(1, results.size());
        assertEquals(new JavdbSearchParser.Result("ABC-001", "hasBoth"), results.get(0));
    }

    @Test
    void parseResultsReturnsEmptyForNullOrBlank() {
        assertTrue(parser.parseResults(null).isEmpty());
        assertTrue(parser.parseResults("   ").isEmpty());
    }
}
