package com.organizer3.translation.ollama;

/**
 * Lightweight projection of a model entry from {@code /api/tags}.
 */
public record OllamaModel(
        String name,
        long sizeBytes
) {}
