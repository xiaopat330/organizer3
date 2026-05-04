package com.organizer3.translation.ollama;

/**
 * Response from a single Ollama {@code /api/generate} call.
 *
 * <p>Field names mirror Ollama's JSON response schema. All timing/token fields are optional
 * (Ollama may omit them on some backends); callers should treat zero as "not reported".
 */
public record OllamaResponse(
        /** The generated text. Empty string on error. */
        String responseText,
        /** Total wall-clock duration of the request in nanoseconds (Ollama's {@code total_duration}). */
        long totalDurationNs,
        /** Number of tokens in the prompt (Ollama's {@code prompt_eval_count}). */
        int promptEvalCount,
        /** Number of tokens generated (Ollama's {@code eval_count}). */
        int evalCount,
        /** Time spent on token generation in nanoseconds (Ollama's {@code eval_duration}). */
        long evalDurationNs
) {}
