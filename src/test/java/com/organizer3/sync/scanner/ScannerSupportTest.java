package com.organizer3.sync.scanner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class ScannerSupportTest {

    // --- hasParenthesizedTitleCode ---

    @ParameterizedTest
    @ValueSource(strings = {
            "Eimi Fukada (MIAA-085)",
            "Kirara Asuka - Demosaiced (SNIS-052)",
            "(BLK-162)",
            "(GDQN-001)",
            "Yua Mikami (SSIS-509_4K)",
            "Yua Mikami - Demosaiced (SNIS-986-AI)",
            "Ai Hoshina, Eimi Fukada - Demosaiced (PRED-159)",
            "Eimi Fukada (SCUTE-713)",
            "(CWP-103)",
    })
    void recognizesTitleFolders(String name) {
        assertTrue(ScannerSupport.hasParenthesizedTitleCode(name),
                "Should recognize as title: " + name);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "favorites",
            "_favorites",
            "meh",
            "ok",
            "processed",
            "covers",
            "cover",
            "_covers",
            "temp",
            "#Rio_Hamasaki, part 1",
            "a12738",
            "xxx-av.com-21090-FHD",
            "xxx-av.com-21091-FHD",
            "AIKA [face]",
            "av9898-961 by arsenal-fan",
    })
    void rejectsNonTitleFolders(String name) {
        assertFalse(ScannerSupport.hasParenthesizedTitleCode(name),
                "Should NOT recognize as title: " + name);
    }

    // --- extractActressName ---

    @Test
    void extractsSimpleActressName() {
        assertEquals("Eimi Fukada", ScannerSupport.extractActressName("Eimi Fukada (MIAA-085)"));
    }

    @Test
    void extractsActressNameWithDemosaicedSuffix() {
        assertEquals("Kirara Asuka", ScannerSupport.extractActressName("Kirara Asuka - Demosaiced (SNIS-052)"));
    }

    @Test
    void extractsFirstActressFromMultiActress() {
        assertEquals("Ai Hoshina", ScannerSupport.extractActressName("Ai Hoshina, Eimi Fukada - Demosaiced (PRED-159)"));
    }

    @Test
    void returnsNullForParenCodeOnly() {
        assertNull(ScannerSupport.extractActressName("(BLK-162)"));
    }

    @Test
    void returnsNullForNoParens() {
        assertNull(ScannerSupport.extractActressName("favorites"));
    }
}
