package com.organizer3.avstars.iafd;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class IafdSearchParserTest {

    private final IafdSearchParser parser = new IafdSearchParser();

    private String loadFixture(String name) throws IOException, URISyntaxException {
        Path path = Path.of(getClass().getClassLoader().getResource("iafd/" + name).toURI());
        return Files.readString(path);
    }

    // ── two results ────────────────────────────────────────────────────────────

    @Test
    void twoResultsParsed() throws Exception {
        List<IafdSearchResult> results = parser.parse(loadFixture("search_anissa_kate.html"));
        assertEquals(2, results.size());
    }

    @Test
    void firstResultUuid() throws Exception {
        List<IafdSearchResult> results = parser.parse(loadFixture("search_anissa_kate.html"));
        assertEquals("53696199-bf71-4219-b58a-bd1e2fae9f1e", results.get(0).uuid());
    }

    @Test
    void firstResultName() throws Exception {
        List<IafdSearchResult> results = parser.parse(loadFixture("search_anissa_kate.html"));
        assertEquals("Anissa Kate", results.get(0).name());
    }

    @Test
    void firstResultAkas() throws Exception {
        List<IafdSearchResult> results = parser.parse(loadFixture("search_anissa_kate.html"));
        assertEquals(List.of("Alissa Kate", "Anissa Gate", "Annissa Kate"), results.get(0).akas());
    }

    @Test
    void firstResultActiveYears() throws Exception {
        List<IafdSearchResult> results = parser.parse(loadFixture("search_anissa_kate.html"));
        assertEquals(2010, results.get(0).activeFrom());
        assertEquals(2024, results.get(0).activeTo());
    }

    @Test
    void firstResultTitleCount() throws Exception {
        List<IafdSearchResult> results = parser.parse(loadFixture("search_anissa_kate.html"));
        assertEquals(352, results.get(0).titleCount());
    }

    @Test
    void firstResultHeadshotUrl() throws Exception {
        List<IafdSearchResult> results = parser.parse(loadFixture("search_anissa_kate.html"));
        assertTrue(results.get(0).headshotUrl().contains("anissakate_f_1"));
    }

    @Test
    void secondResultNoAkas() throws Exception {
        List<IafdSearchResult> results = parser.parse(loadFixture("search_anissa_kate.html"));
        assertTrue(results.get(1).akas().isEmpty());
    }

    // ── no results ─────────────────────────────────────────────────────────────

    @Test
    void emptyTableReturnsEmptyList() throws Exception {
        List<IafdSearchResult> results = parser.parse(loadFixture("search_no_results.html"));
        assertTrue(results.isEmpty());
    }

    @Test
    void nullInputReturnsEmptyList() {
        assertTrue(parser.parse(null).isEmpty());
    }

    @Test
    void blankInputReturnsEmptyList() {
        assertTrue(parser.parse("  ").isEmpty());
    }
}
