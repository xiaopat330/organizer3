package com.organizer3.ollama;

/**
 * Tunables for {@link OllamaModelOrchestrator}.
 *
 * @param maxModelDurationSeconds fairness cap — once the active model has been active for longer
 *                                than this, the scheduler will switch to the next non-empty queue
 *                                (preferring a different model) even if the active queue still has
 *                                work. Default 600s (10 min).
 * @param keepAliveString         {@code keep_alive} value passed through to Ollama on every call
 *                                (e.g. {@code "15m"}) so the model stays loaded between calls.
 *                                Applied only if the caller's {@link com.organizer3.translation.ollama.OllamaRequest}
 *                                has a {@code null} keepAlive; an explicit caller value wins.
 */
public record OrchestratorConfig(
        int maxModelDurationSeconds,
        String keepAliveString
) {
    public static OrchestratorConfig defaults() {
        return new OrchestratorConfig(600, "15m");
    }
}
