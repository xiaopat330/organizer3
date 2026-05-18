package com.organizer3.config;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Configuration for the AI Picker Assist feature.
 *
 * <p>Loaded from the {@code enrichment.assist} block of {@code organizer-config.yaml}.
 * When the block is missing, {@link #defaults()} is used (mode = {@code off}, safe).
 *
 * @param mode                    {@code off} | {@code shadow} | {@code suggest} | {@code auto}
 * @param primaryModel            Ollama model name (e.g. {@code phi4})
 * @param secondaryModel          Ollama model name (e.g. {@code gemma3:12b})
 * @param sweeperIntervalSeconds  poll interval between sweeper iterations
 * @param autoApplyDelaySeconds   grace period before {@code auto} mode applies an agreed suggestion
 * @param promptVersion           tracked for telemetry / regression analysis
 * @param maxAutoApplyAttempts    after this many consecutive auto-apply failures the row is
 *                                excluded from the sweeper's auto-apply queue (stays open for
 *                                human picker resolution); defaults to 3
 */
public record EnrichmentAssistConfig(
        @JsonProperty("mode")                    String mode,
        @JsonProperty("primaryModel")            String primaryModel,
        @JsonProperty("secondaryModel")          String secondaryModel,
        @JsonProperty("sweeperIntervalSeconds")  int sweeperIntervalSeconds,
        @JsonProperty("autoApplyDelaySeconds")   int autoApplyDelaySeconds,
        @JsonProperty("promptVersion")           String promptVersion,
        @JsonProperty("maxAutoApplyAttempts")    int maxAutoApplyAttempts
) {
    public static EnrichmentAssistConfig defaults() {
        return new EnrichmentAssistConfig("off", "phi4", "gemma3:12b", 60, 60, "v7-kanji-bridge", 3);
    }
}
