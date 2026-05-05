package com.organizer3.translation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ExplicitTermSubstitutorTest {

    @Test
    void nullAndEmptyPassThrough() {
        assertNull(ExplicitTermSubstitutor.substitute(null));
        assertEquals("", ExplicitTermSubstitutor.substitute(""));
    }

    @Test
    void noExplicitTermsLeftUnchanged() {
        String benign = "放課後の図書室で勉強する女子高生";
        assertEquals(benign, ExplicitTermSubstitutor.substitute(benign));
    }

    @Test
    void hmn035_canonicalCase() {
        // The actual title that motivated this fix — see PR #67's dashboard
        // tile + the 2026-05-05 ollama experiments.
        String input  = "放課後の乳首開発ハーレム中出し倶楽部 松本いちか 白桃はな";
        String output = ExplicitTermSubstitutor.substitute(input);
        assertTrue(output.contains("creampie"),
                "expected 'creampie' substitution, got: " + output);
        assertFalse(output.contains("中出し"),
                "original kanji should be replaced, got: " + output);
        // Surrounding context is left intact for the LLM to translate
        assertTrue(output.contains("放課後"));
        assertTrue(output.contains("ハーレム"));
        assertTrue(output.contains("倶楽部"));
    }

    @Test
    void longestMatchWins_namaNakadashi() {
        // 生中出し must match before 中出し alone.
        String result = ExplicitTermSubstitutor.substitute("生中出しシリーズ");
        assertTrue(result.contains("raw creampie"), result);
        assertFalse(result.contains("creampie 出し"), result);
    }

    @Test
    void multipleTermsInOneInput() {
        String result = ExplicitTermSubstitutor.substitute("中出しと痴漢の輪姦");
        assertTrue(result.contains("creampie"));
        assertTrue(result.contains("molestation"));
        assertTrue(result.contains("gangbang"));
        assertFalse(result.contains("中出し"));
        assertFalse(result.contains("痴漢"));
        assertFalse(result.contains("輪姦"));
    }

    @Test
    void substitutionResultPassesSanitizationDetector() {
        // End-to-end check: after substitution, an LLM that simply echoes the
        // EN tokens through verbatim produces output that the detector accepts.
        String input  = "放課後の中出し倶楽部";
        String substituted = ExplicitTermSubstitutor.substitute(input);
        // Simulate a model that translated the rest and kept "creampie":
        String fakeOutput = "After-school creampie club";
        assertTrue(substituted.contains("creampie"));
        // SanitizationDetector inspects against the *original* source text.
        assertFalse(SanitizationDetector.isSanitized(input, fakeOutput),
                "detector should pass when output contains the EN explicit token");
    }

    @Test
    void censoredVariants() {
        assertTrue(ExplicitTermSubstitutor.substitute("レ×プ事件").contains("rape"));
        assertTrue(ExplicitTermSubstitutor.substitute("輪●動画").contains("gangbang"));
    }
}
