package com.organizer3.translation.ollama;

import java.util.List;
import java.util.function.Consumer;

/**
 * Low-level adapter for the Ollama HTTP API.
 *
 * <p>Responsibilities: translate Java request/response types to/from Ollama's JSON wire format,
 * handle connection concerns (timeouts, health checks, model presence). No knowledge of catalog
 * entities, translation strategies, or queues.
 *
 * <p>The {@link OllamaRequest#think()} field is always serialised as {@code "think": false} at
 * the top level of the JSON body — NOT inside {@code options}. This is required to suppress
 * chain-of-thought output from qwen3-family models.
 */
public interface OllamaAdapter {

    /**
     * Submit a single generate request and block until the response arrives.
     * The timeout in {@link OllamaRequest#timeout()} is applied per-call.
     *
     * @throws OllamaException on HTTP errors, parse failures, or timeout
     */
    OllamaResponse generate(OllamaRequest req);

    /**
     * Streaming variant — placeholder for future use (e.g. long bio translations with progress).
     * Not yet implemented in Phase 1.
     *
     * @throws UnsupportedOperationException always, until implemented
     */
    void generateStreaming(OllamaRequest req, Consumer<String> onToken);

    /**
     * List models currently installed in Ollama (from {@code /api/tags}).
     *
     * @throws OllamaException on HTTP/parse errors
     */
    List<OllamaModel> listModels();

    /**
     * Ensure a model is installed, pulling it if missing. Blocks until pull completes.
     *
     * @param modelId  the model identifier, e.g. {@code "gemma4:e4b"}
     * @param progress called with progress status lines as they arrive (may be null)
     * @throws OllamaException on HTTP/network errors
     */
    void ensureModel(String modelId, Consumer<String> progress);

    /**
     * Returns {@code true} if the Ollama daemon is reachable and responding to requests.
     * Does not check model availability.
     */
    boolean isHealthy();
}
