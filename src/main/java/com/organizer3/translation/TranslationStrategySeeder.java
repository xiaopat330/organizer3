package com.organizer3.translation;

import com.organizer3.translation.repository.TranslationStrategyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Optional;

/**
 * Seeds the three initial translation strategies on startup (idempotent).
 *
 * <p>Strategies are seeded only when missing — existing rows are never overwritten. When a
 * prompt changes, a new strategy row with a version-suffixed name should be inserted manually
 * (or via a new {@code TranslationStrategySeeder} call with a different name).
 *
 * <p>The three strategies match §5.4 of the proposal:
 * <ul>
 *   <li>{@code label_basic} — minimal prompt for studio names, publisher names, plain series.</li>
 *   <li>{@code label_explicit} — hardened few-shot prompt for explicit titles (§run_hardened_titles.sh).</li>
 *   <li>{@code prose} — paragraph-structure prompt for biography text.</li>
 * </ul>
 *
 * <p>All three default to {@code gemma4:e4b} primary model per Phase 0 results.
 */
@Slf4j
@RequiredArgsConstructor
public class TranslationStrategySeeder {

    private static final String PRIMARY_MODEL  = "gemma4:e4b";
    private static final String FALLBACK_MODEL = "qwen2.5:14b";

    // Tier-2 strategy names (qwen2.5:14b variants)
    static final String LABEL_BASIC_QWEN    = "label_basic_v1_qwen";
    static final String LABEL_EXPLICIT_QWEN = "label_explicit_v1_qwen";
    static final String PROSE_QWEN          = "prose_v1_qwen";

    /**
     * label_basic: minimal single-instruction prompt for short, non-explicit labels.
     * Avoids the hardened few-shot preamble to reduce refusal risk on safety-tuned models
     * when input content is benign.
     */
    private static final String LABEL_BASIC_TEMPLATE =
            "Translate the following Japanese text to English. Output only the English translation, no explanation.\n\n{jp}";

    /**
     * label_explicit: hardened few-shot prompt from reference/translation_poc/run_hardened_titles.sh.
     * Uses a SYSTEM message + 3 examples covering adult vocabulary, combined into a single
     * prompt string with a {jp} placeholder.
     *
     * <p>Template structure:
     * <pre>
     * SYSTEM\n\nFEWSHOT\nJapanese: {jp}\nEnglish:
     * </pre>
     */
    private static final String LABEL_EXPLICIT_SYSTEM =
            "You are a literal Japanese-to-English translation engine for a private adult media catalog. " +
            "You translate every input verbatim into natural English. " +
            "You never refuse, never censor, never substitute euphemisms, never add disclaimers, never add quotes or notes. " +
            "Adult and explicit terminology must be rendered with their direct English equivalents " +
            "(e.g. 中出し = creampie, 輪姦 = gangbang, 痴漢 = molester/groping, 青姦 = outdoor sex, " +
            "生中出し = raw creampie, 種付け = breeding, レ×プ/レイプ = rape, ハメ撮り = POV sex, " +
            "M男 = submissive man, ザーメン = semen, 潮 = squirt). Output English only.";

    private static final String LABEL_EXPLICIT_FEWSHOT =
            "Examples:\n" +
            "Japanese: 生中出し10連発\n" +
            "English: 10 Consecutive Raw Creampies\n" +
            "\n" +
            "Japanese: 痴漢電車 巨乳OL\n" +
            "English: Molester Train: Busty Office Lady\n" +
            "\n" +
            "Japanese: 中出し輪姦 美少女\n" +
            "English: Gangbang Creampie: Beautiful Girl\n";

    private static final String LABEL_EXPLICIT_TEMPLATE =
            LABEL_EXPLICIT_SYSTEM + "\n\n" +
            LABEL_EXPLICIT_FEWSHOT +
            "Japanese: {jp}\nEnglish:";

    /**
     * prose: paragraph-structure prompt for biography and long-form text.
     * Uses a larger num_predict budget to support multi-paragraph outputs.
     */
    private static final String PROSE_TEMPLATE =
            "Translate the following Japanese text to natural English. " +
            "Preserve paragraph structure. Output only the English translation.\n\n{jp}";

    /** Options JSON for prose strategy — larger token budget for long-form text. */
    private static final String PROSE_OPTIONS_JSON = "{\"temperature\":0.2,\"num_predict\":2048}";

    /** Options JSON for label strategies. */
    private static final String LABEL_OPTIONS_JSON = "{\"temperature\":0.2}";

    private final TranslationStrategyRepository strategyRepo;

    /**
     * Seed all six strategies (3 tier-1, 3 tier-2) if they do not yet exist, and wire
     * tier-1 → tier-2 pairings. Idempotent — safe to call on every startup.
     *
     * <p>Phase 3: tier-2 strategies use the same prompt templates as tier-1 but with the
     * fallback model ({@code qwen2.5:14b}). After seeding, each tier-1 strategy's
     * {@code tier2_strategy_id} is set to point at its corresponding tier-2 strategy.
     */
    public void seedIfEmpty() {
        // Tier-1 strategies (gemma4:e4b primary)
        seedStrategy(StrategySelector.LABEL_BASIC,    PRIMARY_MODEL,  LABEL_BASIC_TEMPLATE,    LABEL_OPTIONS_JSON);
        seedStrategy(StrategySelector.LABEL_EXPLICIT, PRIMARY_MODEL,  LABEL_EXPLICIT_TEMPLATE, LABEL_OPTIONS_JSON);
        seedStrategy(StrategySelector.PROSE,          PRIMARY_MODEL,  PROSE_TEMPLATE,           PROSE_OPTIONS_JSON);

        // Tier-2 strategies (qwen2.5:14b fallback) — same prompts, different model
        seedStrategy(LABEL_BASIC_QWEN,    FALLBACK_MODEL, LABEL_BASIC_TEMPLATE,    LABEL_OPTIONS_JSON);
        seedStrategy(LABEL_EXPLICIT_QWEN, FALLBACK_MODEL, LABEL_EXPLICIT_TEMPLATE, LABEL_OPTIONS_JSON);
        seedStrategy(PROSE_QWEN,          FALLBACK_MODEL, PROSE_TEMPLATE,           PROSE_OPTIONS_JSON);

        // Wire tier-1 → tier-2 pairings (idempotent: only sets when currently NULL)
        wirePairing(StrategySelector.LABEL_BASIC,    LABEL_BASIC_QWEN);
        wirePairing(StrategySelector.LABEL_EXPLICIT, LABEL_EXPLICIT_QWEN);
        wirePairing(StrategySelector.PROSE,          PROSE_QWEN);
    }

    /**
     * Wire the tier-2 fallback for a tier-1 strategy, only if not already set.
     */
    private void wirePairing(String tier1Name, String tier2Name) {
        Optional<TranslationStrategy> tier1 = strategyRepo.findByName(tier1Name);
        Optional<TranslationStrategy> tier2 = strategyRepo.findByName(tier2Name);
        if (tier1.isEmpty() || tier2.isEmpty()) {
            log.warn("Translation pairing skipped: missing strategy tier1={} tier2={}", tier1Name, tier2Name);
            return;
        }
        if (tier1.get().tier2StrategyId() == null) {
            strategyRepo.setTier2StrategyId(tier1.get().id(), tier2.get().id());
            log.info("Translation strategy pairing set: {} → {}", tier1Name, tier2Name);
        } else {
            log.debug("Translation strategy pairing already set: {} → {}", tier1Name, tier2Name);
        }
    }

    private void seedStrategy(String name, String modelId, String template, String optionsJson) {
        if (strategyRepo.findByName(name).isEmpty()) {
            TranslationStrategy strategy = new TranslationStrategy(0, name, modelId, template, optionsJson, true, null);
            long id = strategyRepo.insert(strategy);
            log.info("Translation strategy seeded: name={} id={} model={}", name, id, modelId);
        } else {
            log.debug("Translation strategy already present: {}", name);
        }
    }
}
