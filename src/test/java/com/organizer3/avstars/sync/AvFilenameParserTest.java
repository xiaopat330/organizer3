package com.organizer3.avstars.sync;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AvFilenameParserTest {

    private final AvFilenameParser parser = new AvFilenameParser();

    // ── studio extraction ─────────────────────────────────────────────────────

    @Test
    void bracketStudioExtracted() {
        var r = parser.parse("[Brazzers] (Anissa Kate) Fucked In Front Of Class XXX (2019) (1080p HEVC) [GhostFreakXX].mp4");
        assertEquals("Brazzers", r.studio());
    }

    @Test
    void bangStudioExtracted() {
        var r = parser.parse("!DorcelClub - 2015.12.30 Gets A Hard DP Action 2160p-h265.mkv");
        assertEquals("DorcelClub", r.studio());
    }

    @Test
    void dotFormStudioExtracted() {
        var r = parser.parse("1111Customs.22.08.23.Alex.Coal.Anissa.Kate.Some.Title.2160p.mp4");
        assertEquals("1111Customs", r.studio());
    }

    @Test
    void bracketStudioWithUrlDotExtracted() {
        var r = parser.parse("[AdrianaChechik.com] - 2014.09.19 - Hands Mouth Full [Gangbang, Anal, DP].mkv");
        assertEquals("AdrianaChechik.com", r.studio());
    }

    @Test
    void nonLeadingBracketNotStudio() {
        // Mid-filename bracket should not be captured as studio
        var r = parser.parse("2008.07 [Vouyer Media] Asa Akira (Control Freaks Scene.1) (1080p).mp4");
        // No leading bracket; studio not detectable
        assertNull(r.studio());
    }

    @Test
    void noStudioReturnsNull() {
        var r = parser.parse("RandomTitle.2019.1080p.mp4");
        assertNull(r.studio());
    }

    // ── release date extraction ───────────────────────────────────────────────

    @Test
    void fullIsoDateExtracted() {
        var r = parser.parse("[AdrianaChechik.com] - 2014.09.19 - Hands Mouth Full [Gangbang, Anal, DP].mkv");
        assertEquals("2014-09-19", r.releaseDate());
    }

    @Test
    void fullIsoDashDateExtracted() {
        var r = parser.parse("SomeStudio - 2021-03-15 - Some Title 1080p.mp4");
        assertEquals("2021-03-15", r.releaseDate());
    }

    @Test
    void bangStudioFullDateExtracted() {
        var r = parser.parse("!DorcelClub - 2015.12.30 Gets A Hard DP Action 2160p-h265.mkv");
        assertEquals("2015-12-30", r.releaseDate());
    }

    @Test
    void twoDigitYearDateExtracted() {
        var r = parser.parse("1111Customs.22.08.23.Alex.Coal.Anissa.Kate.Some.Title.2160p.mp4");
        assertEquals("2022-08-23", r.releaseDate());
    }

    @Test
    void twoDigitYear24Extracted() {
        var r = parser.parse("SomeStudio.24.03.15.First.Last.Title.Words.XXX.1080p.mp4");
        assertEquals("2024-03-15", r.releaseDate());
    }

    @Test
    void yearOnlyInParensExtracted() {
        var r = parser.parse("[Brazzers] (Anissa Kate) Fucked In Front Of Class XXX (2019) (1080p HEVC) [GhostFreakXX].mp4");
        assertEquals("2019", r.releaseDate());
    }

    @Test
    void yearOnlyBareExtracted() {
        var r = parser.parse("SomeTitle 2020 1080p.mp4");
        assertEquals("2020", r.releaseDate());
    }

    @Test
    void noDateReturnsNull() {
        var r = parser.parse("[Brazzers] Some Title 1080p.mp4");
        assertNull(r.releaseDate());
    }

    // ── resolution extraction ─────────────────────────────────────────────────

    @Test
    void resolution1080pExtracted() {
        var r = parser.parse("[Brazzers] (Anissa Kate) Fucked In Front Of Class XXX (2019) (1080p HEVC) [GhostFreakXX].mp4");
        assertEquals("1080p", r.resolution());
    }

    @Test
    void resolution2160pExtracted() {
        var r = parser.parse("!DorcelClub - 2015.12.30 Gets A Hard DP Action 2160p-h265.mkv");
        assertEquals("2160p", r.resolution());
    }

    @Test
    void resolution4kNormalisedTo2160p() {
        var r = parser.parse("SomeTitle 4K 2020.mp4");
        assertEquals("2160p", r.resolution());
    }

    @Test
    void resolution4kLowercaseNormalisedTo2160p() {
        var r = parser.parse("SomeTitle 4k.mp4");
        assertEquals("2160p", r.resolution());
    }

    @Test
    void resolution720pExtracted() {
        var r = parser.parse("SomeTitle 720p 2021.mkv");
        assertEquals("720p", r.resolution());
    }

    @Test
    void resolutionFromDotFormFilename() {
        var r = parser.parse("1111Customs.22.08.23.Alex.Coal.Anissa.Kate.Some.Title.2160p.mp4");
        assertEquals("2160p", r.resolution());
    }

    @Test
    void noResolutionReturnsNull() {
        var r = parser.parse("[Brazzers] Some Title 2020.mp4");
        assertNull(r.resolution());
    }

    // ── codec extraction ──────────────────────────────────────────────────────

    @Test
    void hevcCodecNormalisedToH265() {
        var r = parser.parse("[Brazzers] (Anissa Kate) Fucked In Front Of Class XXX (2019) (1080p HEVC) [GhostFreakXX].mp4");
        assertEquals("h265", r.codec());
    }

    @Test
    void h265DashNormalisedToH265() {
        var r = parser.parse("!DorcelClub - 2015.12.30 Gets A Hard DP Action 2160p-h265.mkv");
        assertEquals("h265", r.codec());
    }

    @Test
    void x265NormalisedToH265() {
        var r = parser.parse("SomeTitle 1080p x265.mkv");
        assertEquals("h265", r.codec());
    }

    @Test
    void h264NormalisedToH264() {
        var r = parser.parse("SomeTitle 1080p H264 2020.mp4");
        assertEquals("h264", r.codec());
    }

    @Test
    void x264NormalisedToH264() {
        var r = parser.parse("SomeTitle 720p x264.mp4");
        assertEquals("h264", r.codec());
    }

    @Test
    void avcNormalisedToH264() {
        var r = parser.parse("SomeTitle 1080p AVC.mp4");
        assertEquals("h264", r.codec());
    }

    @Test
    void noCodecReturnsNull() {
        var r = parser.parse("[Brazzers] Some Title 1080p 2020.mp4");
        assertNull(r.codec());
    }

    // ── tag extraction ────────────────────────────────────────────────────────

    @Test
    void trailingTagBlockExtracted() {
        var r = parser.parse("[AdrianaChechik.com] - 2014.09.19 - Hands Mouth Full [Gangbang, Anal, DP].mkv");
        assertEquals(List.of("Gangbang", "Anal", "DP"), r.tags());
    }

    @Test
    void singleTokenBracketNotTags() {
        // [GhostFreakXX] has no commas — should not be extracted as tags
        var r = parser.parse("[Brazzers] (Anissa Kate) Fucked In Front Of Class XXX (2019) (1080p HEVC) [GhostFreakXX].mp4");
        assertTrue(r.tags().isEmpty());
    }

    @Test
    void noTagBlockReturnsEmptyList() {
        var r = parser.parse("1111Customs.22.08.23.Alex.Coal.Anissa.Kate.Some.Title.2160p.mp4");
        assertTrue(r.tags().isEmpty());
    }

    @Test
    void tagsAreTrimmed() {
        var r = parser.parse("SomeTitle 1080p [ Anal ,  DP , Creampie ].mkv");
        assertEquals(List.of("Anal", "DP", "Creampie"), r.tags());
    }

    // ── full-filename integration ─────────────────────────────────────────────

    @Test
    void dorcelClubFilenameFullParse() {
        var r = parser.parse("!DorcelClub - 2015.12.30 Gets A Hard DP Action 2160p-h265.mkv");
        assertEquals("DorcelClub", r.studio());
        assertEquals("2015-12-30", r.releaseDate());
        assertEquals("2160p", r.resolution());
        assertEquals("h265", r.codec());
        assertTrue(r.tags().isEmpty());
    }

    @Test
    void customsDotFormFullParse() {
        var r = parser.parse("1111Customs.22.08.23.Alex.Coal.Anissa.Kate.Some.Title.2160p.mp4");
        assertEquals("1111Customs", r.studio());
        assertEquals("2022-08-23", r.releaseDate());
        assertEquals("2160p", r.resolution());
        assertNull(r.codec());
        assertTrue(r.tags().isEmpty());
    }

    @Test
    void adrianaChechikFullParse() {
        var r = parser.parse("[AdrianaChechik.com] - 2014.09.19 - Hands Mouth Full [Gangbang, Anal, DP].mkv");
        assertEquals("AdrianaChechik.com", r.studio());
        assertEquals("2014-09-19", r.releaseDate());
        assertNull(r.resolution());
        assertNull(r.codec());
        assertEquals(List.of("Gangbang", "Anal", "DP"), r.tags());
    }

    @Test
    void brazzersFullParse() {
        var r = parser.parse("[Brazzers] (Anissa Kate) Fucked In Front Of Class XXX (2019) (1080p HEVC) [GhostFreakXX].mp4");
        assertEquals("Brazzers", r.studio());
        assertEquals("2019", r.releaseDate());
        assertEquals("1080p", r.resolution());
        assertEquals("h265", r.codec());
        assertTrue(r.tags().isEmpty());
    }

    // ── edge cases ────────────────────────────────────────────────────────────

    @Test
    void nullInputReturnsEmptyResult() {
        var r = parser.parse(null);
        assertNull(r.studio());
        assertNull(r.releaseDate());
        assertNull(r.resolution());
        assertNull(r.codec());
        assertTrue(r.tags().isEmpty());
    }

    @Test
    void blankInputReturnsEmptyResult() {
        var r = parser.parse("   ");
        assertNull(r.studio());
        assertNull(r.releaseDate());
        assertNull(r.resolution());
        assertNull(r.codec());
        assertTrue(r.tags().isEmpty());
    }

    @Test
    void extensionStrippedBeforeParsing() {
        // Same content with and without extension should yield same studio
        var withExt    = parser.parse("[Brazzers] Some Title 1080p.mp4");
        var withoutExt = parser.parse("[Brazzers] Some Title 1080p");
        assertEquals(withExt.studio(), withoutExt.studio());
        assertEquals(withExt.resolution(), withoutExt.resolution());
    }

    @Test
    void leadingSortCharsStripped() {
        var r = parser.parse("!DorcelClub - 2015.12.30 Gets A Hard DP Action 2160p.mkv");
        // Bang is sort trick; studio should still be DorcelClub
        assertEquals("DorcelClub", r.studio());
    }
}
