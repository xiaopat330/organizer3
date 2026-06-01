package com.organizer3.translation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link StageNameRomajiParser}.
 *
 * <p>Covers: clean JSON, fenced JSON, JSON+trailing prose, empty surname (mononym → given only),
 * lowercase input (titlecasing), all-caps mononym (JULIA → unchanged), unparseable 2-token
 * (surname-first flip), unparseable 1-token (passthrough), null/blank guard.
 */
class StageNameRomajiParserTest {

    // ──────────────────────────────────────────────────────────────────────────
    // Clean JSON
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    void cleanJson_givenAndSurname_composesWesternOrder() {
        // 兒玉七海 → Nanami Kodama (given=Nanami, surname=Kodama → Western order)
        String result = StageNameRomajiParser.parseAndCompose("{\"given\":\"Nanami\",\"surname\":\"Kodama\"}");
        assertEquals("Nanami Kodama", result);
    }

    @Test
    void cleanJson_mikamiYua() {
        String result = StageNameRomajiParser.parseAndCompose("{\"given\":\"Yua\",\"surname\":\"Mikami\"}");
        assertEquals("Yua Mikami", result);
    }

    @Test
    void cleanJson_fukadaEimi() {
        String result = StageNameRomajiParser.parseAndCompose("{\"given\":\"Eimi\",\"surname\":\"Fukada\"}");
        assertEquals("Eimi Fukada", result);
    }

    @Test
    void cleanJson_nagisaAiri() {
        String result = StageNameRomajiParser.parseAndCompose("{\"given\":\"Airi\",\"surname\":\"Nagisa\"}");
        assertEquals("Airi Nagisa", result);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Mononym (empty surname)
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    void cleanJson_mononymEmptySurname_returnsGivenOnly() {
        String result = StageNameRomajiParser.parseAndCompose("{\"given\":\"Airi\",\"surname\":\"\"}");
        assertEquals("Airi", result);
    }

    @Test
    void cleanJson_mononymNullSurname_returnsGivenOnly() {
        String result = StageNameRomajiParser.parseAndCompose("{\"given\":\"Airi\",\"surname\":null}");
        assertEquals("Airi", result);
    }

    @Test
    void cleanJson_mononymMissingSurnameField_returnsGivenOnly() {
        String result = StageNameRomajiParser.parseAndCompose("{\"given\":\"JULIA\"}");
        assertEquals("JULIA", result);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // All-caps mononym preservation
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    void allCapsMononymJULIA_preserved() {
        // JULIA is all-caps — titlecase must not lowercase it
        String result = StageNameRomajiParser.parseAndCompose("{\"given\":\"JULIA\",\"surname\":\"\"}");
        assertEquals("JULIA", result);
    }

    @Test
    void allCapsMononymRINA_preserved() {
        String result = StageNameRomajiParser.parseAndCompose("{\"given\":\"RINA\",\"surname\":\"\"}");
        assertEquals("RINA", result);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Lowercase input (titlecasing)
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    void lowercase_givenAndSurname_titlecased() {
        // model returns all-lowercase
        String result = StageNameRomajiParser.parseAndCompose("{\"given\":\"rie\",\"surname\":\"miyagi\"}");
        assertEquals("Rie Miyagi", result);
    }

    @Test
    void lowercase_mononym_titlecased() {
        String result = StageNameRomajiParser.parseAndCompose("{\"given\":\"airi\",\"surname\":\"\"}");
        assertEquals("Airi", result);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Fenced JSON (```json ... ```)
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    void fencedJson_extractedAndParsed() {
        String input = "```json\n{\"given\":\"Nanami\",\"surname\":\"Kodama\"}\n```";
        String result = StageNameRomajiParser.parseAndCompose(input);
        assertEquals("Nanami Kodama", result);
    }

    @Test
    void fencedJsonNoLanguageTag_extractedAndParsed() {
        String input = "```\n{\"given\":\"Yua\",\"surname\":\"Mikami\"}\n```";
        String result = StageNameRomajiParser.parseAndCompose(input);
        assertEquals("Yua Mikami", result);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // JSON with trailing prose
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    void jsonWithTrailingExplanation_parsedCorrectly() {
        String input = "{\"given\":\"Eimi\",\"surname\":\"Fukada\"} (Note: surname-first in Japanese)";
        String result = StageNameRomajiParser.parseAndCompose(input);
        assertEquals("Eimi Fukada", result);
    }

    @Test
    void jsonWithLeadingAndTrailingProse_extractedAndParsed() {
        String input = "Here is the romanization: {\"given\":\"Airi\",\"surname\":\"Nagisa\"} — done.";
        String result = StageNameRomajiParser.parseAndCompose(input);
        assertEquals("Airi Nagisa", result);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Fallback: unparseable → plain-text surname-first flip
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    void fallback_twoTokenSurnameFirst_flipped() {
        // Model ignores JSON format and outputs "Kodama Nanami" (surname-first)
        String result = StageNameRomajiParser.parseAndCompose("Kodama Nanami");
        assertEquals("Nanami Kodama", result);
    }

    @Test
    void fallback_twoTokenAlreadyCased_flipped() {
        String result = StageNameRomajiParser.parseAndCompose("Mikami Yua");
        assertEquals("Yua Mikami", result);
    }

    @Test
    void fallback_oneToken_passthrough() {
        // Single mononym — no flip
        String result = StageNameRomajiParser.parseAndCompose("JULIA");
        assertEquals("JULIA", result);
    }

    @Test
    void fallback_threeTokens_passthroughUntouched() {
        // 3-token output — surname-first flip is ambiguous; return as-is
        String result = StageNameRomajiParser.parseAndCompose("Foo Bar Baz");
        assertEquals("Foo Bar Baz", result);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Null / blank guard
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    void nullInput_returnsNull() {
        assertNull(StageNameRomajiParser.parseAndCompose(null));
    }

    @Test
    void blankInput_returnsNull() {
        assertNull(StageNameRomajiParser.parseAndCompose("   "));
    }

    @Test
    void emptyStringInput_returnsNull() {
        assertNull(StageNameRomajiParser.parseAndCompose(""));
    }

    // ──────────────────────────────────────────────────────────────────────────
    // titlecase helper (package-private, tested directly)
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    void titlecase_lowercase_uppercasesFirst() {
        assertEquals("Nanami", StageNameRomajiParser.titlecase("nanami"));
    }

    @Test
    void titlecase_allCaps_preserved() {
        assertEquals("JULIA", StageNameRomajiParser.titlecase("JULIA"));
    }

    @Test
    void titlecase_alreadyTitleCase_unchanged() {
        assertEquals("Kodama", StageNameRomajiParser.titlecase("Kodama"));
    }

    @Test
    void titlecase_emptyString_returnsEmpty() {
        assertEquals("", StageNameRomajiParser.titlecase(""));
    }

    // ──────────────────────────────────────────────────────────────────────────
    // fallback helper (package-private, tested directly)
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    void fallback_twoTokens_flipsSurnameFirst() {
        assertEquals("Nanami Kodama", StageNameRomajiParser.fallback("Kodama Nanami"));
    }

    @Test
    void fallback_oneToken_unchanged() {
        assertEquals("JULIA", StageNameRomajiParser.fallback("JULIA"));
    }

    @Test
    void fallback_threeTokens_unchanged() {
        assertEquals("Foo Bar Baz", StageNameRomajiParser.fallback("Foo Bar Baz"));
    }
}
