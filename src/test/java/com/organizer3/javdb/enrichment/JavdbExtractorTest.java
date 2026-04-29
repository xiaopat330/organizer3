package com.organizer3.javdb.enrichment;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JavdbExtractorTest {

    private final JavdbExtractor extractor = new JavdbExtractor();

    private static String loadFixture(String name) throws IOException {
        try (InputStream in = JavdbExtractorTest.class.getClassLoader()
                .getResourceAsStream("javdb/" + name)) {
            assertNotNull(in, "Fixture not found: " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    // --- title extraction ---

    @Test
    void extractTitle_parsesAllFields() throws IOException {
        String html = loadFixture("title_detail.html");
        TitleExtract result = extractor.extractTitle(html, "DV-948", "deD0v");

        assertEquals("DV-948", result.code());
        assertEquals("deD0v", result.javdbSlug());
        assertEquals("年下の男の子 麻美ゆま", result.titleOriginal());
        assertEquals("2008-09-12", result.releaseDate());
        assertEquals(180, result.durationMinutes());
        assertEquals("アリスJAPAN", result.maker());
        assertEquals("デジタルヴィデオ", result.publisher());
        assertEquals("Test Series", result.series());
        assertEquals(4.5, result.ratingAvg(), 0.01);
        assertEquals(29, result.ratingCount());
    }

    @Test
    void extractTitle_parsesTags() throws IOException {
        String html = loadFixture("title_detail.html");
        TitleExtract result = extractor.extractTitle(html, "DV-948", "deD0v");

        List<String> tags = result.tags();
        assertEquals(3, tags.size());
        assertTrue(tags.contains("Solowork"));
        assertTrue(tags.contains("Big Tits"));
        assertTrue(tags.contains("Cowgirl"));
    }

    @Test
    void extractTitle_parsesCast() throws IOException {
        String html = loadFixture("title_detail.html");
        TitleExtract result = extractor.extractTitle(html, "DV-948", "deD0v");

        List<TitleExtract.CastEntry> cast = result.cast();
        assertEquals(1, cast.size());
        assertEquals("ex3z", cast.get(0).slug());
        assertEquals("麻美ゆま", cast.get(0).name());
        assertEquals("F", cast.get(0).gender());
    }

    @Test
    void extractTitle_parsesCoverAndThumbnails() throws IOException {
        String html = loadFixture("title_detail.html");
        TitleExtract result = extractor.extractTitle(html, "DV-948", "deD0v");

        assertEquals("https://c0.jdbstatic.com/covers/de/deD0v.jpg", result.coverUrl());
        assertEquals(3, result.thumbnailUrls().size());
        assertTrue(result.thumbnailUrls().get(0).contains("jdbstatic.com/thumbs"));
    }

    @Test
    void extractTitle_nullInput_returnsEmptyExtract() {
        TitleExtract result = extractor.extractTitle(null, "ABC-001", "slug1");
        assertEquals("ABC-001", result.code());
        assertNull(result.titleOriginal());
        assertNull(result.releaseDate());
        assertTrue(result.cast().isEmpty());
    }

    @Test
    void extractTitle_missingOptionalFields_areNull() {
        String html = """
                <html><body>
                  <strong class="current-title">タイトル</strong>
                  <nav class="panel movie-panel-info">
                    <div class="panel-block">
                      <strong>Released Date:</strong>
                      <span class="value">2020-01-15</span>
                    </div>
                  </nav>
                </body></html>
                """;
        TitleExtract result = extractor.extractTitle(html, "TST-001", "s1");
        assertEquals("タイトル", result.titleOriginal());
        assertEquals("2020-01-15", result.releaseDate());
        assertNull(result.series());
        assertNull(result.ratingAvg());
        assertTrue(result.tags().isEmpty());
    }

    // --- actress extraction ---

    @Test
    void extractActress_parsesAllFields() throws IOException {
        String html = loadFixture("actress_detail.html");
        ActressExtract result = extractor.extractActress(html, "ex3z");

        assertEquals("ex3z", result.slug());
        assertEquals(List.of("麻美由真", "麻美ゆま"), result.nameVariants());
        assertEquals("https://c0.jdbstatic.com/avatars/ex/ex3z.jpg", result.avatarUrl());
        assertEquals(870, result.titleCount());
        assertEquals("yuma_asami_xoxo", result.twitterHandle());
        assertEquals("yuma_asami_xoxo", result.instagramHandle());
    }

    @Test
    void extractActress_avatarFromLiveMarkup() {
        String html = """
                <html><body>
                  <section class="section actor-section">
                    <div class="column actor-avatar">
                      <div class="image">
                        <span class="avatar" style="background-image: url(https://c0.jdbstatic.com/avatars/mq/MqQQ.jpg)"></span>
                      </div>
                    </div>
                  </section>
                </body></html>
                """;
        ActressExtract result = extractor.extractActress(html, "MqQQ");
        assertEquals("https://c0.jdbstatic.com/avatars/mq/MqQQ.jpg", result.avatarUrl());
    }

    @Test
    void extractActress_nullInput_returnsEmptyExtract() {
        ActressExtract result = extractor.extractActress(null, "ex3z");
        assertEquals("ex3z", result.slug());
        assertTrue(result.nameVariants().isEmpty());
        assertNull(result.avatarUrl());
        assertNull(result.titleCount());
    }

    // ─── 1E: castEmpty / castParseFailed ──────────────────────────────────────

    @Test
    void extractTitle_emptyCast_setsCastEmptyTrue() {
        String html = """
                <html><body><div class="movie-panel-info">
                  <div class="panel-block"><strong>Actors:</strong><span class="value"></span></div>
                </div></body></html>
                """;
        TitleExtract result = extractor.extractTitle(html, "T-1", "t1");
        assertTrue(result.castEmpty(), "castEmpty should be true when Actor block has no entries");
        assertFalse(result.castParseFailed());
        assertTrue(result.cast().isEmpty());
    }

    @Test
    void extractTitle_nonEmptyCast_setsCastEmptyFalse() {
        String html = """
                <html><body><div class="movie-panel-info">
                  <div class="panel-block"><strong>Actors:</strong><span class="value">
                    <a href="/actors/ab1">女優A</a>
                  </span></div>
                </div></body></html>
                """;
        TitleExtract result = extractor.extractTitle(html, "T-1", "t1");
        assertFalse(result.castEmpty(), "castEmpty should be false when cast has entries");
        assertFalse(result.castParseFailed());
        assertEquals(1, result.cast().size());
    }

    @Test
    void extractTitle_nullInput_castEmptyAndParseFailed_areFalse() {
        TitleExtract result = extractor.extractTitle(null, "T-1", "t1");
        assertFalse(result.castEmpty());
        assertFalse(result.castParseFailed());
    }

    @Test
    void extractTitle_noActorBlock_castEmptyFalse() {
        // No Actor block at all — not the same as genuine empty cast
        String html = "<html><body><div class=\"movie-panel-info\"></div></body></html>";
        TitleExtract result = extractor.extractTitle(html, "T-1", "t1");
        assertFalse(result.castEmpty(), "castEmpty is false when there is no Actor block");
        assertFalse(result.castParseFailed());
    }
}
