package com.organizer3.translation;

import com.organizer3.db.SchemaInitializer;
import com.organizer3.translation.repository.jdbi.JdbiTranslationStrategyRepository;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link StrategySelector}.
 *
 * <p>The {@link StrategySelector#pick} tests are pure-function (no DB). The
 * {@link StrategySelector#pickFallback} tests use real in-memory SQLite.
 */
class StrategySelectorTest {

    // DB-backed state for pickFallback tests
    private Connection connection;
    private JdbiTranslationStrategyRepository strategyRepo;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        Jdbi jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        strategyRepo = new JdbiTranslationStrategyRepository(jdbi);
        // Seed all 6 strategies + pairings
        new TranslationStrategySeeder(strategyRepo).seedIfEmpty();
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

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

    // -------------------------------------------------------------------------
    // pickFallback — requires real DB (seeded by setUp)
    // -------------------------------------------------------------------------

    @Test
    void pickFallback_labelBasic_returnsQwenStrategy() {
        TranslationStrategy tier1 = strategyRepo.findByName(StrategySelector.LABEL_BASIC).orElseThrow();
        Optional<TranslationStrategy> fallback = StrategySelector.pickFallback(tier1.id(), strategyRepo);

        assertTrue(fallback.isPresent(), "label_basic should have a tier-2 fallback");
        assertEquals(TranslationStrategySeeder.LABEL_BASIC_QWEN, fallback.get().name());
        assertEquals("qwen2.5:14b", fallback.get().modelId());
    }

    @Test
    void pickFallback_labelExplicit_returnsQwenStrategy() {
        TranslationStrategy tier1 = strategyRepo.findByName(StrategySelector.LABEL_EXPLICIT).orElseThrow();
        Optional<TranslationStrategy> fallback = StrategySelector.pickFallback(tier1.id(), strategyRepo);

        assertTrue(fallback.isPresent(), "label_explicit should have a tier-2 fallback");
        assertEquals(TranslationStrategySeeder.LABEL_EXPLICIT_QWEN, fallback.get().name());
        assertEquals("qwen2.5:14b", fallback.get().modelId());
    }

    @Test
    void pickFallback_prose_returnsQwenStrategy() {
        TranslationStrategy tier1 = strategyRepo.findByName(StrategySelector.PROSE).orElseThrow();
        Optional<TranslationStrategy> fallback = StrategySelector.pickFallback(tier1.id(), strategyRepo);

        assertTrue(fallback.isPresent(), "prose should have a tier-2 fallback");
        assertEquals(TranslationStrategySeeder.PROSE_QWEN, fallback.get().name());
        assertEquals("qwen2.5:14b", fallback.get().modelId());
    }

    @Test
    void pickFallback_tier2Strategy_returnsEmpty() {
        // Tier-2 strategies have no further fallback (tier2_strategy_id is null)
        TranslationStrategy qwenStrategy = strategyRepo.findByName(TranslationStrategySeeder.LABEL_BASIC_QWEN)
                .orElseThrow();
        Optional<TranslationStrategy> fallback = StrategySelector.pickFallback(qwenStrategy.id(), strategyRepo);
        assertTrue(fallback.isEmpty(), "Tier-2 strategies should have no further fallback");
    }

    @Test
    void pickFallback_unknownId_returnsEmpty() {
        // Non-existent strategy id
        Optional<TranslationStrategy> fallback = StrategySelector.pickFallback(99999L, strategyRepo);
        assertTrue(fallback.isEmpty());
    }

    @Test
    void seeder_isIdempotent_secondCallDoesNotDuplicate() {
        // Running seeder twice should not create extra strategies
        new TranslationStrategySeeder(strategyRepo).seedIfEmpty();
        assertEquals(6, strategyRepo.findAllActive().size(), "Should have exactly 6 strategies after 2 seeder runs");
    }
}
