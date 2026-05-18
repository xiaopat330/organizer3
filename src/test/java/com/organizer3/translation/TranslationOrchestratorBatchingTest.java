package com.organizer3.translation;

import com.organizer3.ollama.OllamaModelOrchestrator;
import com.organizer3.ollama.OrchestratorConfig;
import com.organizer3.translation.ollama.HttpOllamaAdapter;
import com.organizer3.translation.ollama.OllamaRequest;
import com.organizer3.translation.ollama.OllamaResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Phase 4 Track A exit-criterion test: two concurrent translation-style submits to the
 * same model load the model exactly once (≤ 1 model switch in orchestrator metrics).
 *
 * <p>Uses a real {@link OllamaModelOrchestrator} wrapping a mocked {@link HttpOllamaAdapter},
 * exactly the wiring that {@link TranslationWorker} / {@link Tier2BatchSweeper} /
 * {@link TranslationServiceImpl} now use in production. Each {@code generate} call sleeps
 * briefly so that both submissions are guaranteed to overlap in the queue before the scheduler
 * drains them — which is what would have caused thrash with the old direct-adapter path if
 * a separate AI-assist call to a different model had been racing in parallel.
 */
class TranslationOrchestratorBatchingTest {

    private HttpOllamaAdapter adapter;
    private OllamaModelOrchestrator orchestrator;

    @BeforeEach
    void setUp() throws Exception {
        adapter = mock(HttpOllamaAdapter.class);
        // Each generate call takes ~50ms so two concurrent submits genuinely overlap in the queue.
        when(adapter.generate(any(OllamaRequest.class))).thenAnswer(inv -> {
            Thread.sleep(50);
            return new OllamaResponse("English: ok", 0L, 0, 0, 0L);
        });
        orchestrator = new OllamaModelOrchestrator(adapter, OrchestratorConfig.defaults());
        orchestrator.start();
    }

    @AfterEach
    void tearDown() {
        if (orchestrator != null) orchestrator.stop();
    }

    @Test
    void twoConcurrentSubmits_sameModel_yieldZeroModelSwitches() throws Exception {
        String model = "gemma4:e4b";
        OllamaRequest req1 = new OllamaRequest(model, "p1", null, null, Duration.ofSeconds(5));
        OllamaRequest req2 = new OllamaRequest(model, "p2", null, null, Duration.ofSeconds(5));

        CountDownLatch both = new CountDownLatch(2);
        CompletableFuture<OllamaResponse> f1 = orchestrator.submit(model, req1)
                .whenComplete((r, t) -> both.countDown());
        CompletableFuture<OllamaResponse> f2 = orchestrator.submit(model, req2)
                .whenComplete((r, t) -> both.countDown());

        assertTrue(both.await(5, TimeUnit.SECONDS), "both submits should complete");
        assertNotNull(f1.get());
        assertNotNull(f2.get());

        // The whole point of routing translation through the orchestrator: same-model concurrent
        // work batches under a single active-model window, so VRAM never thrashes.
        OllamaModelOrchestrator.Metrics m = orchestrator.metrics();
        assertEquals(0L, m.modelSwitches(),
                "two concurrent same-model submits must not cause any model switch");
        assertEquals(2L, m.callsByModel().getOrDefault(model, 0L),
                "both calls should be recorded against the single active model");

        // Both calls really did hit the (mocked) adapter — confirms the orchestrator path was
        // exercised end-to-end rather than short-circuited.
        verify(adapter, times(2)).generate(any(OllamaRequest.class));
    }
}
