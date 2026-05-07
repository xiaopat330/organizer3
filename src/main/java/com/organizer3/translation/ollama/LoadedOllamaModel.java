package com.organizer3.translation.ollama;

/**
 * A model currently loaded into Ollama's runtime memory, as reported by {@code /api/ps}.
 *
 * @param name        e.g. {@code "gemma4:e4b"}
 * @param sizeBytes   total memory the model occupies (system RAM)
 * @param vramBytes   portion of {@code sizeBytes} that's in VRAM (0 on CPU-only or
 *                    Apple Silicon unified memory hosts where Ollama doesn't separate)
 * @param expiresAtIso when Ollama plans to unload the model, ISO-8601 UTC; null if never
 */
public record LoadedOllamaModel(
        String name,
        long sizeBytes,
        long vramBytes,
        String expiresAtIso
) {}
