package com.organizer3.enrichment.ai;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssistPromptBuilderTest {

    private static AssistPromptBuilder.Input sampleRow() {
        AssistPromptBuilder.Input.Candidate c1 = new AssistPromptBuilder.Input.Candidate(
                "abc-123",
                "田野憂のなんとか",
                "2023-05-01",
                "S1",
                List.of("田野憂"),
                120,
                4.32,
                89
        );
        AssistPromptBuilder.Input.Candidate c2 = new AssistPromptBuilder.Input.Candidate(
                "abc-456",
                "別の作品",
                "2022-11-12",
                "Madonna",
                List.of("東実果"),
                95,
                null,
                null
        );
        return new AssistPromptBuilder.Input(
                "ABC-123",
                "ABC",
                "//pandora/jav_T/T/Tano Yu/ABC-123 something",
                List.of("Yu Tano"),
                List.of("yu-tano"),
                List.of(c1, c2)
        );
    }

    @Test
    void system_includesKanjiBridgeRule() {
        assertNotNull(AssistPromptBuilder.SYSTEM);
        assertTrue(AssistPromptBuilder.SYSTEM.contains("Romaji"),
                "system prompt should mention romaji bridging");
        assertTrue(AssistPromptBuilder.SYSTEM.contains("田野憂"),
                "system prompt should retain kanji example for the bridge rule");
        assertTrue(AssistPromptBuilder.SYSTEM.contains("JSON"),
                "system prompt should enforce JSON-only output");
    }

    @Test
    void buildUserPrompt_includesCode_labelAndActress() {
        String p = AssistPromptBuilder.buildUserPrompt(sampleRow());

        assertTrue(p.contains("Product code: ABC-123"), "code missing");
        assertTrue(p.contains("Label: ABC"), "label missing");
        assertTrue(p.contains("Filed under actress(es): Yu Tano"), "actress missing");
        assertTrue(p.contains("Linked javdb actress slug(s): yu-tano"), "linked slugs missing");
    }

    @Test
    void buildUserPrompt_showsOnlyFolderName_notFullPath() {
        String p = AssistPromptBuilder.buildUserPrompt(sampleRow());
        assertTrue(p.contains("Folder name: ABC-123 something"), "folder name missing");
        assertTrue(!p.contains("//pandora/jav_T"), "must not leak volume path");
    }

    @Test
    void buildUserPrompt_numbersCandidatesFromOne() {
        String p = AssistPromptBuilder.buildUserPrompt(sampleRow());
        assertTrue(p.contains("1. slug=abc-123"), "candidate 1 missing");
        assertTrue(p.contains("2. slug=abc-456"), "candidate 2 missing");
    }

    @Test
    void buildUserPrompt_includesAllCandidateFields() {
        String p = AssistPromptBuilder.buildUserPrompt(sampleRow());
        assertTrue(p.contains("title: 田野憂のなんとか"), "title missing");
        assertTrue(p.contains("release_date: 2023-05-01"), "release date missing");
        assertTrue(p.contains("maker: S1"), "maker missing");
        assertTrue(p.contains("cast: 田野憂"), "cast missing");
        assertTrue(p.contains("duration: 120 min"), "duration missing");
        assertTrue(p.contains("rating: 4.32 (89 reviews)"), "rating missing");
    }

    @Test
    void buildUserPrompt_handlesEmptyCast() {
        AssistPromptBuilder.Input.Candidate noCast = new AssistPromptBuilder.Input.Candidate(
                "xyz", "t", null, null, List.of(), null, null, null);
        AssistPromptBuilder.Input row = new AssistPromptBuilder.Input(
                "X-1", null, null, List.of(), List.of(), List.of(noCast));
        String p = AssistPromptBuilder.buildUserPrompt(row);
        assertTrue(p.contains("cast: (none listed)"), "empty cast hint missing");
    }

    @Test
    void buildUserPrompt_endsWithJsonInstruction() {
        String p = AssistPromptBuilder.buildUserPrompt(sampleRow());
        assertTrue(p.contains("Reply ONLY with JSON"), "JSON instruction missing");
    }
}
