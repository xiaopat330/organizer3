package com.organizer3.config.volume;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class VolumeConfigTest {

    @Test
    void noLetters_coversAnyName() {
        VolumeConfig v = new VolumeConfig("x", "//h/x", "conventional", "h", null, null);
        assertTrue(v.coversName("Alice"));
        assertTrue(v.coversName("Zygote"));
    }

    @Test
    void emptyLetters_coversAnyName() {
        VolumeConfig v = new VolumeConfig("x", "//h/x", "conventional", "h", null, List.of());
        assertTrue(v.coversName("Alice"));
    }

    @Test
    void singleLetter_matchesFirstCharCaseInsensitive() {
        VolumeConfig v = new VolumeConfig("a", "//h/a", "conventional", "h", null, List.of("A"));
        assertTrue(v.coversName("Ai Haneda"));
        assertTrue(v.coversName("ai haneda"));   // lowercase
        assertFalse(v.coversName("Bob"));
    }

    @Test
    void multipleSingleLetters_matchesAnyListed() {
        VolumeConfig v = new VolumeConfig("bg", "//h/bg", "conventional", "h", null,
                List.of("B", "C", "D", "E", "F", "G"));
        assertTrue(v.coversName("Chinatsu Hashimoto"));
        assertTrue(v.coversName("Dana Dearmond"));
        assertTrue(v.coversName("Fumi Kimura"));
        assertFalse(v.coversName("Hana Suzuki"));
    }

    @Test
    void twoCharPrefix_matchesOnlyTwoCharStart() {
        VolumeConfig v = new VolumeConfig("ma", "//h/ma", "conventional", "h", null, List.of("Ma"));
        assertTrue(v.coversName("Mako Kono"));
        assertTrue(v.coversName("Mary Smith"));
        assertTrue(v.coversName("mako kono"));   // lowercase
        assertFalse(v.coversName("Mio Kayama"));  // M but not Ma
        assertFalse(v.coversName("Natsuki"));
    }

    @Test
    void singleCharPrefixAlsoMatchesDoubleCharName_m_vs_ma() {
        // Plain "M" still matches Mako — sort just flags LETTER mismatches,
        // not "sub-optimal volume placement". This is documented in YAML comments.
        VolumeConfig m = new VolumeConfig("m", "//h/m", "conventional", "h", null, List.of("M"));
        assertTrue(m.coversName("Mio Kayama"));
        assertTrue(m.coversName("Mako Kono"));    // also true — that's by design
    }

    @Test
    void nullOrEmptyName_rejected() {
        VolumeConfig v = new VolumeConfig("a", "//h/a", "conventional", "h", null, List.of("A"));
        assertFalse(v.coversName(null));
        assertFalse(v.coversName(""));
    }

    @Test
    void nullEntriesInLetters_ignored() {
        VolumeConfig v = new VolumeConfig("x", "//h/x", "conventional", "h", null,
                java.util.Arrays.asList("A", null, ""));
        assertTrue(v.coversName("Alice"));
        assertFalse(v.coversName("Bob"));
    }
}
