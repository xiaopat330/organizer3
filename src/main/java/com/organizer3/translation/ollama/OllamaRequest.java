package com.organizer3.translation.ollama;


import java.time.Duration;
import java.util.Map;

/**
 * Transport record for a single request to the Ollama {@code /api/generate} endpoint.
 *
 * <p>This is a pure transport object — no domain semantics, no notion of translation
 * strategies, prompts, or catalog entities.
 *
 * <p>The {@code think} field corresponds to the top-level {@code think} key in the Ollama
 * JSON payload (NOT inside {@code options}). Setting it to {@code false} prevents qwen3-family
 * models from emitting chain-of-thought output before the actual response.
 */
public record OllamaRequest(
        String modelId,
        String prompt,
        String systemMessage,
        /** Maps directly to Ollama's {@code options} object (temperature, num_predict, etc.). */
        Map<String, Object> options,
        Duration timeout,
        /**
         * When {@code true}, sets top-level {@code "format": "json"} in the request body so the
         * model is constrained to emit valid JSON. Default {@code false} for translation pipeline
         * back-compat.
         */
        boolean formatJson
) {
    /** Back-compat constructor: defaults {@code formatJson} to {@code false}. */
    public OllamaRequest(String modelId, String prompt, String systemMessage,
                         Map<String, Object> options, Duration timeout) {
        this(modelId, prompt, systemMessage, options, timeout, false);
    }

    /**
     * Whether to suppress model thinking/reasoning output.
     * Always {@code false} for translation requests — set at the top level of the JSON body.
     */
    public boolean think() {
        return false;
    }
}
