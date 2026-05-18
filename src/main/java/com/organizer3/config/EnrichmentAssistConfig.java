package com.organizer3.config;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Configuration for the AI Picker Assist feature.
 *
 * <p>Loaded from the {@code enrichment.assist} block of {@code organizer-config.yaml}.
 * When the block is missing, {@link #defaults()} is used (mode = {@code off}, safe).
 *
 * @param mode                              {@code off} | {@code shadow} | {@code suggest} | {@code auto}
 * @param primaryModel                      Ollama model name (e.g. {@code phi4})
 * @param secondaryModel                    Ollama model name (e.g. {@code gemma3:12b})
 * @param sweeperIntervalSeconds            poll interval between sweeper iterations
 * @param autoApplyDelaySeconds             grace period before {@code auto} mode applies an agreed suggestion
 * @param promptVersion                     tracked for telemetry / regression analysis
 * @param maxAutoApplyAttempts              after this many consecutive auto-apply failures the row is
 *                                          excluded from the sweeper's auto-apply queue (stays open for
 *                                          human picker resolution); defaults to 3
 * @param postProcessingEnabled             when true (default), the {@link com.organizer3.enrichment.ai.PostProcessingRules}
 *                                          layer runs pre/post the LLM ensemble. Set to false to disable
 *                                          the entire Java-side rules layer if it regresses anything.
 * @param backfillBatchSize                 Phase 5 Track A — chunk size used by the backfill task to
 *                                          batch ensemble calls by model (N rows of primary, then N
 *                                          rows of secondary). Defaults to 20.
 * @param parallelEnsemble                  Phase 5 Track B — when true, the per-row sweeper path
 *                                          dispatches phi4 + gemma3 concurrently (bypassing the
 *                                          orchestrator's serial scheduler) provided current loaded
 *                                          Ollama model bytes are within
 *                                          {@link #parallelEnsembleMemoryBudgetMb}. Defaults to false.
 * @param parallelEnsembleMemoryBudgetMb    Phase 5 Track B — memory headroom guard, in MB. When
 *                                          Ollama's currently loaded model bytes (sum of VRAM /
 *                                          system RAM per {@code /api/ps}) exceed this budget, the
 *                                          per-row path falls back to serial. Defaults to 22000 (22 GB).
 */
public record EnrichmentAssistConfig(
        @JsonProperty("mode")                            String mode,
        @JsonProperty("primaryModel")                    String primaryModel,
        @JsonProperty("secondaryModel")                  String secondaryModel,
        @JsonProperty("sweeperIntervalSeconds")          int sweeperIntervalSeconds,
        @JsonProperty("autoApplyDelaySeconds")           int autoApplyDelaySeconds,
        @JsonProperty("promptVersion")                   String promptVersion,
        @JsonProperty("maxAutoApplyAttempts")            int maxAutoApplyAttempts,
        @JsonProperty("postProcessingEnabled")           boolean postProcessingEnabled,
        @JsonProperty("backfillBatchSize")               int backfillBatchSize,
        @JsonProperty("parallelEnsemble")                boolean parallelEnsemble,
        @JsonProperty("parallelEnsembleMemoryBudgetMb")  int parallelEnsembleMemoryBudgetMb
) {
    public static EnrichmentAssistConfig defaults() {
        return new EnrichmentAssistConfig(
                "off", "phi4", "gemma3:12b", 60, 60, "v7-kanji-bridge", 3, true, 20, false, 22000);
    }
}
