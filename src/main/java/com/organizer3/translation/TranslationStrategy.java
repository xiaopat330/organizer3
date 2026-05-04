package com.organizer3.translation;


/**
 * Row from the {@code translation_strategy} table.
 *
 * <p>A strategy binds a model ID to a prompt template. The {@code promptTemplate} contains a
 * {@code {jp}} placeholder that is replaced with the source text at call time.
 *
 * <p>Strategies are rarely modified. When a prompt changes, a new row is inserted and the old
 * one is deactivated ({@code isActive = false}), so old cache rows remain valid references.
 *
 * <p>{@code tier2StrategyId} is nullable. When non-null, it points to the tier-2 fallback
 * strategy to use when this (tier-1) strategy refuses or produces a sanitized translation.
 * Tier-2 strategies have {@code tier2StrategyId = null}.
 */
public record TranslationStrategy(
        long id,
        String name,
        String modelId,
        String promptTemplate,
        String optionsJson,
        boolean isActive,
        Long tier2StrategyId
) {}
