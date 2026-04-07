package com.organizer3.ai;

import com.organizer3.model.Actress;
import com.organizer3.model.Title;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ClaudeActressNameLookupTest {

    // ── findJapaneseName ─────────────────────────────────────────────────────

    @Test
    void returnsJapaneseNameFromApiResponse() {
        var lookup = new ClaudeActressNameLookup((system, msg) -> "桜まな");
        Optional<String> result = lookup.findJapaneseName(actress("mana sakura"), List.of());
        assertEquals(Optional.of("桜まな"), result);
    }

    @Test
    void returnsEmptyWhenApiRespondsUnknown() {
        var lookup = new ClaudeActressNameLookup((system, msg) -> "unknown");
        Optional<String> result = lookup.findJapaneseName(actress("nobody"), List.of());
        assertTrue(result.isEmpty());
    }

    @Test
    void returnsEmptyWhenApiThrows() {
        var lookup = new ClaudeActressNameLookup((system, msg) -> { throw new RuntimeException("network error"); });
        Optional<String> result = lookup.findJapaneseName(actress("mana sakura"), List.of());
        assertTrue(result.isEmpty());
    }

    @Test
    void stripsWhitespaceFromApiResponse() {
        var lookup = new ClaudeActressNameLookup((system, msg) -> "  桜まな  ");
        Optional<String> result = lookup.findJapaneseName(actress("mana sakura"), List.of());
        assertEquals(Optional.of("桜まな"), result);
    }

    // ── buildMessage ─────────────────────────────────────────────────────────

    @Test
    void buildMessage_nameOnlyWhenNoTitles() {
        var lookup = new ClaudeActressNameLookup((system, msg) -> "");
        String msg = lookup.buildMessage("mana sakura", List.of());
        assertEquals("Actress: mana sakura", msg);
    }

    @Test
    void buildMessage_includesCodesAndLabels() {
        var lookup = new ClaudeActressNameLookup((system, msg) -> "");
        List<Title> titles = List.of(
                Title.builder().code("SNIS-001").label("SNIS").build(),
                Title.builder().code("SNIS-015").label("SNIS").build(),
                Title.builder().code("IPX-089").label("IPX").build()
        );

        String msg = lookup.buildMessage("mana sakura", titles);

        assertTrue(msg.contains("Actress: mana sakura"), "should contain actress name");
        assertTrue(msg.contains("SNIS-001"), "should contain first code");
        assertTrue(msg.contains("SNIS-015"), "should contain second code");
        assertTrue(msg.contains("IPX-089"), "should contain third code");
        assertTrue(msg.contains("SNIS"), "should contain SNIS label");
        assertTrue(msg.contains("IPX"), "should contain IPX label");
    }

    @Test
    void buildMessage_deduplicatesLabels() {
        var lookup = new ClaudeActressNameLookup((system, msg) -> "");
        List<Title> titles = List.of(
                Title.builder().code("SNIS-001").label("SNIS").build(),
                Title.builder().code("SNIS-002").label("SNIS").build(),
                Title.builder().code("SNIS-003").label("SNIS").build()
        );

        String msg = lookup.buildMessage("mana sakura", titles);

        int occurrences = 0;
        int idx = 0;
        while ((idx = msg.indexOf("SNIS", idx)) != -1) { occurrences++; idx += 4; }
        // Codes: 3 occurrences + Labels line: 1 occurrence = 4 total
        // (SNIS-001, SNIS-002, SNIS-003, and "Labels: SNIS")
        assertTrue(occurrences <= 4, "Label should not be duplicated in the Labels line");
        long labelLineCount = msg.lines()
                .filter(l -> l.startsWith("Labels:"))
                .count();
        assertEquals(1, labelLineCount);
        assertTrue(msg.lines().filter(l -> l.startsWith("Labels:")).findFirst().orElse("").split(",").length == 1,
                "SNIS should appear exactly once in the Labels line");
    }

    @Test
    void buildMessage_limitsToTenCodes() {
        var lookup = new ClaudeActressNameLookup((system, msg) -> "");
        List<Title> titles = java.util.stream.IntStream.rangeClosed(1, 15)
                .mapToObj(i -> Title.builder().code("SNIS-" + String.format("%03d", i)).label("SNIS").build())
                .toList();

        String msg = lookup.buildMessage("mana sakura", titles);

        assertFalse(msg.contains("SNIS-011"), "11th code should be excluded");
        assertFalse(msg.contains("SNIS-015"), "15th code should be excluded");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static Actress actress(String name) {
        return Actress.builder().canonicalName(name).build();
    }
}
