package com.organizer3.translation;


/**
 * Row from the {@code translation_strategy} table.
 *
 * <p>A strategy binds a model ID to a prompt template. The {@code promptTemplate} contains a
 * {@code {jp}} placeholder that is replaced with the source text at call time.
 *
 * <p>Strategies are rarely modified. When a prompt changes, a new row is inserted and the old
 * one is deactivated ({@code isActive = false}), so old cache rows remain valid references.
 */
public record TranslationStrategy(
        long id,
        String name,
        String modelId,
        String promptTemplate,
        String optionsJson,
        boolean isActive
) {}
