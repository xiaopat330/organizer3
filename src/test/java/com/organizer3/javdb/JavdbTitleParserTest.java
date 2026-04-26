package com.organizer3.javdb;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JavdbTitleParserTest {

    private final JavdbTitleParser parser = new JavdbTitleParser();

    // Mirrors real javdb HTML: English label, gender symbol sibling after each name link
    private static final String SOLO_HTML = """
            <html><body>
              <nav class="panel movie-panel-info">
                <div class="panel-block">
                  <strong>Released Date:</strong>
                  &nbsp;<span class="value">2013-06-01</span>
                </div>
                <div class="panel-block">
                  <strong>Actor(s):</strong>
                  &nbsp;<span class="value">
                    <a href="/actors/B2g5">波多野結衣</a><strong class="symbol female">♀</strong>&nbsp;
                  </span>
                </div>
              </nav>
            </body></html>
            """;

    private static final String MIXED_HTML = """
            <html><body>
              <nav class="panel movie-panel-info">
                <div class="panel-block">
                  <strong>Actor(s):</strong>
                  &nbsp;<span class="value">
                    <a href="/actors/B2g5">波多野結衣</a><strong class="symbol female">♀</strong>&nbsp;
                    <a href="/actors/x7wn">田淵正浩</a><strong class="symbol male">♂</strong>&nbsp;
                    <a href="/actors/Xyz9">小島みなみ</a><strong class="symbol female">♀</strong>&nbsp;
                  </span>
                </div>
              </nav>
            </body></html>
            """;

    @Test
    void parsesSoloActress() {
        List<JavdbActress> actresses = parser.parseActresses(SOLO_HTML);

        assertEquals(1, actresses.size());
        assertEquals("波多野結衣", actresses.get(0).kanjiName());
        assertEquals("B2g5", actresses.get(0).actressSlug());
    }

    @Test
    void filtersMaleActorsReturnsOnlyFemale() {
        List<JavdbActress> actresses = parser.parseActresses(MIXED_HTML);

        assertEquals(2, actresses.size());
        assertEquals("波多野結衣", actresses.get(0).kanjiName());
        assertEquals("小島みなみ", actresses.get(1).kanjiName());
    }

    @Test
    void returnsEmptyWhenNoActorPanel() {
        String html = "<html><body><div class='panel-block'><strong>Duration:</strong></div></body></html>";
        assertTrue(parser.parseActresses(html).isEmpty());
    }

    @Test
    void returnsEmptyForNullInput() {
        assertTrue(parser.parseActresses(null).isEmpty());
    }
}
