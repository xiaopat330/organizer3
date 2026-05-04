package com.organizer3.translation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link StrategySelector} — pure function, no mocks or DB needed.
 */
class StrategySelectorTest {

    @Test
    void shortNonExplicit_defaultsToLabelBasic() {
        assertEquals(StrategySelector.LABEL_BASIC, StrategySelector.pick("テスト", null, 1));
    }

    @Test
    void explicitJpToken_nakadasiSelectsLabelExplicit() {
        // 中出し
        assertEquals(StrategySelector.LABEL_EXPLICIT, StrategySelector.pick("生中出し花野真衣", null, 1));
    }

    @Test
    void explicitJpToken_waisetsu_selectsLabelExplicit() {
        // 淫 is in the explicit list
        assertEquals(StrategySelector.LABEL_EXPLICIT, StrategySelector.pick("淫乱妻", null, 1));
    }

    @Test
    void explicitJpToken_chikan_selectsLabelExplicit() {
        // 痴漢
        assertEquals(StrategySelector.LABEL_EXPLICIT, StrategySelector.pick("痴漢電車", null, 1));
    }

    @Test
    void longInput_over50chars_selectsLabelExplicit() {
        String longText = "あ".repeat(51);
        assertEquals(StrategySelector.LABEL_EXPLICIT, StrategySelector.pick(longText, null, 1));
    }

    @Test
    void exactlyAtBoundary_50chars_selectsLabelBasic() {
        String exactly50 = "あ".repeat(50);
        assertEquals(StrategySelector.LABEL_BASIC, StrategySelector.pick(exactly50, null, 1));
    }

    @Test
    void contextHint_prose_returnsProse() {
        assertEquals(StrategySelector.PROSE, StrategySelector.pick("テスト", "prose", 1));
    }

    @Test
    void contextHint_labelBasic_returnsLabelBasic() {
        assertEquals(StrategySelector.LABEL_BASIC, StrategySelector.pick("中出し", "label_basic", 1));
    }

    @Test
    void contextHint_labelExplicit_returnsLabelExplicit() {
        assertEquals(StrategySelector.LABEL_EXPLICIT, StrategySelector.pick("テスト", "label_explicit", 1));
    }

    @Test
    void contextHint_winsOverLongInput() {
        String longText = "あ".repeat(51);
        assertEquals(StrategySelector.PROSE, StrategySelector.pick(longText, "prose", 1));
    }

    @Test
    void contextHint_winsOverExplicitTokens() {
        // Would normally pick label_explicit, but prose hint wins
        assertEquals(StrategySelector.PROSE, StrategySelector.pick("中出し花野", "prose", 1));
    }

    @Test
    void nullSourceText_defaultsToLabelBasic() {
        assertEquals(StrategySelector.LABEL_BASIC, StrategySelector.pick(null, null, 1));
    }

    @Test
    void unknownHint_fallsBackToHeuristic() {
        // Unknown hint is ignored; falls back to heuristic
        assertEquals(StrategySelector.LABEL_BASIC, StrategySelector.pick("短い", "unknown_hint", 1));
    }

    @Test
    void attempt2_sameAsTier1ForPhase1() {
        // Phase 1 has no tier-2 routing; attempt>1 behaves same as attempt=1
        assertEquals(StrategySelector.LABEL_EXPLICIT, StrategySelector.pick("中出し", null, 2));
    }

    @Test
    void rapeTokenXAlt_selectsExplicit() {
        // レ×プ variant (as seen in test set)
        assertEquals(StrategySelector.LABEL_EXPLICIT, StrategySelector.pick("フラれた女に執着襲撃レ×プ", null, 1));
    }
}
