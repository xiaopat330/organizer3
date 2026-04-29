package com.organizer3.javdb.enrichment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests against fixtures captured from javdb.com/actors/J9dd (Mana Sakura, ~266 titles, 7 pages).
 * Captured 2026-04-29 via the curl recipe in the slug-verification proposal.
 */
class JavdbFilmographyParserTest {

    private JavdbFilmographyParser parser;

    @BeforeEach
    void setUp() {
        parser = new JavdbFilmographyParser();
    }

    private String fixture(String name) throws IOException {
        return Files.readString(Path.of("src/test/resources/javdb/" + name));
    }

    @Test
    void parsesAllItemsOnFirstPage() throws Exception {
        FilmographyPage page = parser.parsePage(fixture("actress_filmography.html"));

        assertEquals(40, page.entries().size(), "page 1 should have 40 entries");
        assertTrue(page.hasNextPage(), "page 1 must signal next page exists");

        // Spot check the first entry against fixture content.
        FilmographyEntry first = page.entries().get(0);
        assertEquals("START-562", first.productCode(),
                "first entry's code should be the most recent title (top-of-page)");
        assertEquals("kKDYP0", first.titleSlug());
    }

    @Test
    void detectsLastPageByAbsenceOfRelNext() throws Exception {
        FilmographyPage last = parser.parsePage(fixture("actress_filmography_p7.html"));

        assertEquals(25, last.entries().size(), "last page has fewer than 40 entries");
        assertFalse(last.hasNextPage(), "last page must NOT signal next page");
    }

    @Test
    void middlePagesAlsoSignalNext() throws Exception {
        FilmographyPage p2 = parser.parsePage(fixture("actress_filmography_p2.html"));

        assertEquals(40, p2.entries().size());
        assertTrue(p2.hasNextPage(), "page 2 of 7 must signal next page");
    }

    @Test
    void allEntriesAcrossPagesAreUnique() throws Exception {
        // Sanity: stitching pages 1+2+7 should yield no duplicate slugs.
        var seen = new java.util.HashSet<String>();
        for (String f : new String[]{"actress_filmography.html",
                                     "actress_filmography_p2.html",
                                     "actress_filmography_p7.html"}) {
            for (FilmographyEntry e : parser.parsePage(fixture(f)).entries()) {
                assertTrue(seen.add(e.titleSlug()),
                        "duplicate slug across pages: " + e.titleSlug() + " (code " + e.productCode() + ")");
            }
        }
        assertEquals(105, seen.size(), "p1+p2+p7 = 40+40+25");
    }

    @Test
    void emptyHtmlReturnsEmptyPage() {
        FilmographyPage page = parser.parsePage("");
        assertEquals(0, page.entries().size());
        assertFalse(page.hasNextPage());
    }

    @Test
    void nullHtmlReturnsEmptyPage() {
        FilmographyPage page = parser.parsePage(null);
        assertEquals(0, page.entries().size());
        assertFalse(page.hasNextPage());
    }

    @Test
    void itemMissingProductCodeIsSilentlySkipped() {
        // Hand-crafted minimal markup: one valid entry + one entry missing the <strong> code element.
        String html = """
            <html><body>
              <div class="item">
                <a href="/v/aaa111" class="box">
                  <div class="video-title"><strong>VALID-001</strong> Title</div>
                </a>
              </div>
              <div class="item">
                <a href="/v/bbb222" class="box">
                  <div class="video-title">no strong code element</div>
                </a>
              </div>
              <nav class="pagination"></nav>
            </body></html>
            """;
        FilmographyPage page = parser.parsePage(html);
        assertEquals(1, page.entries().size(), "skip the malformed entry, keep the valid one");
        assertEquals("VALID-001", page.entries().get(0).productCode());
    }
}
