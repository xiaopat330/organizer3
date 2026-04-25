package com.organizer3.javdb;

import org.junit.jupiter.api.Test;

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
}
