package com.organizer3.translation;


import com.organizer3.translation.repository.TranslationStrategyRepository;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Strategy selection logic for translation requests.
 *
 * <p>Tier-1 selection ({@link #pick}) is a pure function — no database access, testable without mocks.
 * Tier-2 fallback lookup ({@link #pickFallback}) requires a repository reference for the DB lookup.
 *
 * <p>Selection rules per §5.4:
 * <ol>
 *   <li>Caller-provided {@code contextHint} wins if it matches a known strategy name.</li>
 *   <li>Input contains an explicit JP token <em>or</em> length &gt; 50 chars → {@code label_explicit}.</li>
 *   <li>Otherwise → {@code label_basic}.</li>
 * </ol>
 */
public final class StrategySelector {

    /** Strategy names — constants for safe cross-module references. */
    public static final String LABEL_BASIC    = "label_basic";
    public static final String LABEL_EXPLICIT = "label_explicit";
    public static final String PROSE          = "prose";

    /**
     * Explicit JP tokens that indicate adult / explicit content.
     * When any of these appear in the source text, the hardened few-shot prompt is warranted.
     */
    private static final Pattern EXPLICIT_JP_TOKENS = Pattern.compile(
            "中出し|輪姦|輪●|痴漢|青姦|生中出し|種付け|レイプ|レ[×x×]プ|ハメ撮り|ザーメン|淫|変態|キメセク|M男"
    );

    private StrategySelector() {}

    /**
     * Pick a strategy name for the given inputs.
     *
     * @param sourceText  the Japanese text to translate
     * @param contextHint caller-provided hint ({@code "prose"}, {@code "label_basic"},
     *                    {@code "label_explicit"}), or {@code null}
     * @param attempt     1 for first attempt; &gt;1 for retries (tier-2, Phase 3+)
     * @return one of {@link #LABEL_BASIC}, {@link #LABEL_EXPLICIT}, or {@link #PROSE}
     */
    public static String pick(String sourceText, String contextHint, int attempt) {
        // Rule 1: caller hint wins
        if (contextHint != null) {
            String hint = contextHint.trim().toLowerCase();
            if (hint.equals(PROSE) || hint.equals(LABEL_BASIC) || hint.equals(LABEL_EXPLICIT)) {
                return hint;
            }
        }

        // Rule 2: heuristic from source text
        if (sourceText != null) {
            if (EXPLICIT_JP_TOKENS.matcher(sourceText).find() || sourceText.length() > 50) {
                return LABEL_EXPLICIT;
            }
        }

        // Rule 3: default
        return LABEL_BASIC;
    }

    /**
     * Look up the tier-2 fallback strategy for a given tier-1 strategy id.
     *
     * <p>The pairing is stored in the {@code tier2_strategy_id} column of the
     * {@code translation_strategy} table, populated at startup by {@link TranslationStrategySeeder}.
     *
     * @param strategyId  the tier-1 strategy id
     * @param strategyRepo the repository to look up the strategy
     * @return the tier-2 fallback strategy, or empty if none is configured
     */
    public static Optional<TranslationStrategy> pickFallback(long strategyId,
                                                              TranslationStrategyRepository strategyRepo) {
        return strategyRepo.findById(strategyId)
                .flatMap(s -> {
                    if (s.tier2StrategyId() == null) {
                        return Optional.empty();
                    }
                    return strategyRepo.findById(s.tier2StrategyId());
                });
    }
}
