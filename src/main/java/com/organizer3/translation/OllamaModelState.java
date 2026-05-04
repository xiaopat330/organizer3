package com.organizer3.translation;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Lightweight in-memory tracking of the most recently used Ollama model.
 *
 * <p>Per Appendix E.4: the service must track "which model is currently loaded" as live state,
 * queryable from the worker loop before scheduling work. This class provides a simple atomic
 * holder for that state without polling {@code ollama ps} (a Phase 4 enhancement).
 *
 * <p>The worker dispatcher updates the state after each successful Ollama call. The
 * {@link Tier2BatchSweeper} checks this state to decide whether a tier-2 batch can run
 * immediately (qwen2.5 already loaded) vs. must wait for the next scheduled interval.
 */
public final class OllamaModelState {

    private final AtomicReference<String> currentModelId = new AtomicReference<>(null);

    /** Returns the model id most recently used, or {@code null} if no calls have been made. */
    public String getCurrentModelId() {
        return currentModelId.get();
    }

    /** Update the tracked model id after a successful Ollama call. */
    public void setCurrentModelId(String modelId) {
        currentModelId.set(modelId);
    }

    /**
     * Returns {@code true} if the given model id matches the currently loaded model.
     * Returns {@code false} if state is unknown (null).
     */
    public boolean isCurrentlyLoaded(String modelId) {
        if (modelId == null) return false;
        return modelId.equals(currentModelId.get());
    }
}
