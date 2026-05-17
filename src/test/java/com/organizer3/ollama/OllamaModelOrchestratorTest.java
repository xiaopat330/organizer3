package com.organizer3.ollama;

import com.organizer3.translation.ollama.HttpOllamaAdapter;
import com.organizer3.translation.ollama.OllamaRequest;
import com.organizer3.translation.ollama.OllamaResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OllamaModelOrchestratorTest {

    private HttpOllamaAdapter adapter;
    private OllamaModelOrchestrator orch;

    @BeforeEach
    void setUp() {
        adapter = mock(HttpOllamaAdapter.class);
        when(adapter.generate(any(OllamaRequest.class)))
                .thenReturn(new OllamaResponse("ok", 0L, 0, 0, 0L));
    }

    @AfterEach
    void tearDown() {
        if (orch != null) orch.stop();
    }

    private OllamaRequest req(String model) {
        return new OllamaRequest(model, "p", null, null, Duration.ofSeconds(5));
    }

    @Test
    void batching_singleModel_completesAllWithZeroSwitches() throws Exception {
        orch = new OllamaModelOrchestrator(adapter, OrchestratorConfig.defaults());
        orch.start();

        List<CompletableFuture<OllamaResponse>> futures = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            futures.add(orch.submit("modelA", req("modelA")));
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .get(10, TimeUnit.SECONDS);

        for (CompletableFuture<OllamaResponse> f : futures) {
            assertEquals("ok", f.get().responseText());
        }
        assertEquals(0L, orch.metrics().modelSwitches(),
                "0 switches expected: a single model never transitions to a different model");
        assertEquals(50L, orch.metrics().callsByModel().get("modelA"));
    }

    @Test
    void affinity_interleavedSubmissions_batchByModel() throws Exception {
        // Slow down adapter slightly so submissions can pile up before scheduler drains.
        when(adapter.generate(any(OllamaRequest.class))).thenAnswer(inv -> {
            Thread.sleep(2);
            return new OllamaResponse("ok", 0L, 0, 0, 0L);
        });
        orch = new OllamaModelOrchestrator(adapter, OrchestratorConfig.defaults());
        orch.start();

        ExecutorService submitters = Executors.newFixedThreadPool(2);
        List<Future<CompletableFuture<OllamaResponse>>> submitFutures = new ArrayList<>();
        int perThread = 50;
        for (int i = 0; i < perThread; i++) {
            submitFutures.add(submitters.submit(() -> orch.submit("modelA", req("modelA"))));
            submitFutures.add(submitters.submit(() -> orch.submit("modelB", req("modelB"))));
        }
        List<CompletableFuture<OllamaResponse>> futures = new ArrayList<>();
        for (Future<CompletableFuture<OllamaResponse>> sf : submitFutures) futures.add(sf.get());
        submitters.shutdown();

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .get(30, TimeUnit.SECONDS);

        long switches = orch.metrics().modelSwitches();
        long total = futures.size();
        assertTrue(switches < total / 2,
                "Expected affinity batching: switches=" + switches + " should be < " + (total / 2));
        assertEquals((long) perThread, orch.metrics().callsByModel().get("modelA"));
        assertEquals((long) perThread, orch.metrics().callsByModel().get("modelB"));
    }

    @Test
    void fairness_capForcesSwitch() throws Exception {
        // Use a MutableClock so we can advance time past the fairness cap deterministically.
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        // Add per-call latency so the clock advances between items and the fairness check trips.
        AtomicInteger callCount = new AtomicInteger();
        when(adapter.generate(any(OllamaRequest.class))).thenAnswer(inv -> {
            callCount.incrementAndGet();
            // Advance the clock by 1s on each call so after maxModelDurationSeconds=2 calls,
            // the orchestrator should switch.
            clock.advance(Duration.ofSeconds(1));
            return new OllamaResponse("ok", 0L, 0, 0, 0L);
        });

        orch = new OllamaModelOrchestrator(adapter, new OrchestratorConfig(2, "15m"), clock);
        orch.start();

        // Submit a flood to A, then a single B item. B must complete within a bounded
        // number of A items, not after all of them.
        List<CompletableFuture<OllamaResponse>> aFutures = new ArrayList<>();
        for (int i = 0; i < 1000; i++) aFutures.add(orch.submit("modelA", req("modelA")));
        // Snapshot the total call count at the moment B's future completes, before the
        // scheduler can race ahead on A again.
        java.util.concurrent.atomic.AtomicInteger countWhenBDone = new java.util.concurrent.atomic.AtomicInteger(-1);
        CompletableFuture<OllamaResponse> bFuture = orch.submit("modelB", req("modelB"))
                .whenComplete((r, t) -> countWhenBDone.set(callCount.get()));

        OllamaResponse bResp = bFuture.get(10, TimeUnit.SECONDS);
        assertEquals("ok", bResp.responseText());

        int bDone = countWhenBDone.get();
        // With cap=2s and 1s/call advance, ~2 A items fit before the cap fires, then B runs.
        // The whenComplete sets the snapshot synchronously on the scheduler thread so no
        // additional A items have been processed yet.
        assertTrue(bDone > 0 && bDone < 100,
                "B should be reached within ~3 adapter calls; observed total calls when B done=" + bDone);
        assertTrue(orch.metrics().modelSwitches() >= 1, "expected at least one switch A->B");
    }

    @Test
    void exception_inOneCall_doesNotPoisonScheduler() throws Exception {
        AtomicInteger n = new AtomicInteger();
        when(adapter.generate(any(OllamaRequest.class))).thenAnswer(inv -> {
            int i = n.incrementAndGet();
            if (i == 1) throw new RuntimeException("boom");
            return new OllamaResponse("ok", 0L, 0, 0, 0L);
        });

        orch = new OllamaModelOrchestrator(adapter, OrchestratorConfig.defaults());
        orch.start();

        CompletableFuture<OllamaResponse> bad = orch.submit("modelA", req("modelA"));
        CompletableFuture<OllamaResponse> good1 = orch.submit("modelA", req("modelA"));
        CompletableFuture<OllamaResponse> good2 = orch.submit("modelA", req("modelA"));

        ExecutionException ex = assertThrows(ExecutionException.class,
                () -> bad.get(5, TimeUnit.SECONDS));
        assertTrue(ex.getCause() instanceof RuntimeException);
        assertEquals("ok", good1.get(5, TimeUnit.SECONDS).responseText());
        assertEquals("ok", good2.get(5, TimeUnit.SECONDS).responseText());
    }

    @Test
    void stop_refusesNewSubmissionsAndDrains() throws Exception {
        orch = new OllamaModelOrchestrator(adapter, OrchestratorConfig.defaults());
        orch.start();

        // One in-flight should drain.
        CompletableFuture<OllamaResponse> first = orch.submit("modelA", req("modelA"));
        first.get(5, TimeUnit.SECONDS);

        orch.stop();

        CompletableFuture<OllamaResponse> rejected = orch.submit("modelA", req("modelA"));
        ExecutionException ex = assertThrows(ExecutionException.class,
                () -> rejected.get(2, TimeUnit.SECONDS));
        assertTrue(ex.getCause() instanceof IllegalStateException);
    }

    @Test
    void keepAlive_isAppliedFromConfigWhenRequestHasNone() throws Exception {
        List<OllamaRequest> seen = Collections.synchronizedList(new ArrayList<>());
        when(adapter.generate(any(OllamaRequest.class))).thenAnswer(inv -> {
            seen.add(inv.getArgument(0));
            return new OllamaResponse("ok", 0L, 0, 0, 0L);
        });
        orch = new OllamaModelOrchestrator(adapter, new OrchestratorConfig(600, "15m"));
        orch.start();
        orch.submit("modelA", req("modelA")).get(5, TimeUnit.SECONDS);
        assertEquals(1, seen.size());
        assertEquals("15m", seen.get(0).keepAlive());
    }

    /** Minimal mutable clock for fairness testing. */
    private static final class MutableClock extends Clock {
        private volatile Instant now;

        MutableClock(Instant start) { this.now = start; }
        void advance(Duration d) { this.now = this.now.plus(d); }
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
