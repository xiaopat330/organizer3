package com.organizer3.ollama;

import com.organizer3.translation.ollama.HttpOllamaAdapter;
import com.organizer3.translation.ollama.LoadedOllamaModel;
import com.organizer3.translation.ollama.OllamaRequest;
import com.organizer3.translation.ollama.OllamaResponse;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Coordinates calls to {@link HttpOllamaAdapter} across multiple Ollama models, batching work by
 * model to avoid expensive cold loads when models swap in and out of VRAM.
 *
 * <p><b>Scheduling policy</b>
 *
 * <p>The orchestrator keeps a per-model FIFO queue of work items. A single scheduler thread loops:
 * <ol>
 *   <li>If the {@code activeModel} still has queued work AND its run time since first item of the
 *       current batch is under {@code maxModelDurationSeconds}, take the next item from its queue.
 *   <li>Otherwise, pick the non-empty queue with the most items. If multiple tie, prefer one that
 *       is NOT the current active model (fairness).
 *   <li>If all queues are empty, park on a condition variable until {@link #submit} wakes us.
 * </ol>
 *
 * <p><b>What counts as a "model switch"</b>: the {@code modelSwitches} metric increments only on a
 * <em>transition</em> of {@code activeModel} from one non-null model to a <em>different</em>
 * non-null model. The very first time a model becomes active (from null) is NOT a switch — so
 * submitting 50 items to one model yields 0 switches. This matches the spec's "1 model load"
 * intent: a switch corresponds to an expected VRAM swap.
 *
 * <p><b>Exception isolation</b>: any throwable from {@link HttpOllamaAdapter#generate} is caught,
 * completes the caller's future exceptionally, and the scheduler proceeds to the next item.
 *
 * <p><b>keep_alive</b>: every outbound request has its {@code keep_alive} field set to the
 * configured {@code keepAliveString} unless the caller's {@link OllamaRequest} already has a
 * non-null value — explicit caller intent wins.
 */
@Slf4j
public class OllamaModelOrchestrator {

    private record WorkItem(OllamaRequest request, CompletableFuture<OllamaResponse> future) {}

    private final HttpOllamaAdapter adapter;
    private final OrchestratorConfig config;
    private final Clock clock;

    // Guards queuesByModel, activeModel, activeModelStartedAt, stopped, accepting.
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition workAvailable = lock.newCondition();
    private final Map<String, LinkedBlockingDeque<WorkItem>> queuesByModel = new LinkedHashMap<>();

    private volatile String activeModel = null;
    private Instant activeModelStartedAt = null;
    // Items processed on activeModel since it became active. Reset on every switch.
    private long itemsOnActiveModel = 0L;
    private volatile boolean stopped = false;
    private volatile boolean accepting = true;

    private Thread schedulerThread;

    // Phase 5 Track B — parallel-ensemble fast path. Lazily initialised; bounded to 2
    // workers so one phi4 + one gemma3 call can run concurrently against Ollama while
    // larger workloads still flow through the per-model serial queues above.
    private volatile ExecutorService parallelExecutor;
    private final Object parallelExecutorLock = new Object();

    // Phase 5 Track B — 30-second cache of /api/ps total VRAM (falling back to system RAM
    // when VRAM is 0, e.g. Apple Silicon unified memory). Benign race on the volatiles is
    // fine; worst case is one redundant /api/ps call right around the TTL boundary.
    private static final long PS_CACHE_TTL_MS = 30_000L;
    private volatile long psCacheFetchedAtMs = 0L;
    private volatile int  psCacheValueMb     = 0;
    private volatile boolean psWarnedOnce    = false;

    // Metrics
    private final AtomicLong modelSwitches = new AtomicLong();
    private final Map<String, AtomicLong> callsByModel = new HashMap<>();
    private final Map<String, AtomicLong> totalNanosByModel = new HashMap<>();

    public OllamaModelOrchestrator(HttpOllamaAdapter adapter, OrchestratorConfig config) {
        this(adapter, config, Clock.systemUTC());
    }

    /** Test-friendly constructor: inject a Clock for deterministic fairness testing. */
    public OllamaModelOrchestrator(HttpOllamaAdapter adapter, OrchestratorConfig config, Clock clock) {
        this.adapter = adapter;
        this.config = config;
        this.clock = clock;
    }

    public synchronized void start() {
        if (schedulerThread != null) return;
        accepting = true;
        stopped = false;
        schedulerThread = new Thread(this::runLoop, "ollama-orchestrator");
        schedulerThread.setDaemon(true);
        schedulerThread.start();
    }

    /** Gracefully drain queued work and stop the scheduler thread. */
    public synchronized void stop() {
        lock.lock();
        try {
            accepting = false;
            stopped = true;
            workAvailable.signalAll();
        } finally {
            lock.unlock();
        }
        if (schedulerThread != null) {
            try {
                schedulerThread.join(Duration.ofSeconds(30).toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            schedulerThread = null;
        }
        // Fail any items still queued after stop.
        lock.lock();
        try {
            for (LinkedBlockingDeque<WorkItem> q : queuesByModel.values()) {
                WorkItem item;
                while ((item = q.pollFirst()) != null) {
                    item.future.completeExceptionally(
                            new IllegalStateException("OllamaModelOrchestrator stopped"));
                }
            }
        } finally {
            lock.unlock();
        }
        // Phase 5 Track B — tear down the parallel-ensemble executor if it was ever used.
        ExecutorService pe = parallelExecutor;
        if (pe != null) {
            pe.shutdown();
            try {
                if (!pe.awaitTermination(5, TimeUnit.SECONDS)) {
                    pe.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                pe.shutdownNow();
            }
            parallelExecutor = null;
        }
    }

    /** Submit a request for the given model. Returns a future that completes with the response. */
    public CompletableFuture<OllamaResponse> submit(String model, OllamaRequest request) {
        if (model == null || model.isEmpty()) {
            CompletableFuture<OllamaResponse> f = new CompletableFuture<>();
            f.completeExceptionally(new IllegalArgumentException("model must be non-empty"));
            return f;
        }
        CompletableFuture<OllamaResponse> future = new CompletableFuture<>();
        lock.lock();
        try {
            if (!accepting) {
                future.completeExceptionally(
                        new IllegalStateException("OllamaModelOrchestrator not accepting submissions"));
                return future;
            }
            queuesByModel.computeIfAbsent(model, k -> new LinkedBlockingDeque<>())
                    .add(new WorkItem(applyKeepAlive(request), future));
            workAvailable.signalAll();
        } finally {
            lock.unlock();
        }
        return future;
    }

    private OllamaRequest applyKeepAlive(OllamaRequest r) {
        if (r.keepAlive() != null && !r.keepAlive().isEmpty()) return r;
        if (config.keepAliveString() == null || config.keepAliveString().isEmpty()) return r;
        return new OllamaRequest(r.modelId(), r.prompt(), r.systemMessage(), r.options(),
                r.timeout(), r.formatJson(), config.keepAliveString());
    }

    private void runLoop() {
        while (true) {
            WorkItem next;
            lock.lock();
            try {
                // Pick next model to run.
                String pick = pickModelLocked();
                while (pick == null) {
                    if (stopped) return;
                    try {
                        workAvailable.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    pick = pickModelLocked();
                }
                if (!pick.equals(activeModel)) {
                    if (activeModel != null) {
                        modelSwitches.incrementAndGet();
                        log.info("[ai-assist] orchestrator switched: {} -> {} (after {} items)",
                                activeModel, pick, itemsOnActiveModel);
                    }
                    activeModel = pick;
                    activeModelStartedAt = clock.instant();
                    itemsOnActiveModel = 0L;
                }
                next = queuesByModel.get(pick).pollFirst();
            } finally {
                lock.unlock();
            }
            if (next == null) continue;

            long startNanos = System.nanoTime();
            try {
                OllamaResponse resp = adapter.generate(next.request);
                next.future.complete(resp);
            } catch (Throwable t) {
                next.future.completeExceptionally(t);
            } finally {
                long elapsed = System.nanoTime() - startNanos;
                callsByModel.computeIfAbsent(activeModel, k -> new AtomicLong()).incrementAndGet();
                totalNanosByModel.computeIfAbsent(activeModel, k -> new AtomicLong()).addAndGet(elapsed);
                itemsOnActiveModel++;
            }
        }
    }

    /** Must be called with lock held. Returns the model whose queue we should process next, or null. */
    private String pickModelLocked() {
        boolean capExceeded = false;
        if (activeModel != null) {
            LinkedBlockingDeque<WorkItem> activeQ = queuesByModel.get(activeModel);
            boolean activeHasWork = activeQ != null && !activeQ.isEmpty();
            capExceeded = activeModelStartedAt != null
                    && Duration.between(activeModelStartedAt, clock.instant()).getSeconds()
                            >= config.maxModelDurationSeconds();
            // Stay on active if non-empty AND under fairness cap.
            if (activeHasWork && !capExceeded) {
                return activeModel;
            }
        }
        // Otherwise pick from queues. When fairness cap fired, EXCLUDE the active model from the
        // first pass so we genuinely yield, falling back only if no other queue has work.
        String best = pickBestLocked(capExceeded);
        if (best == null && capExceeded) {
            // No other queue had work — fall back to staying on active model.
            best = pickBestLocked(false);
        }
        return best;
    }

    /** Picks the non-empty queue with the most items. Tiebreak: prefer != activeModel. */
    private String pickBestLocked(boolean excludeActive) {
        String best = null;
        int bestSize = 0;
        boolean bestIsActive = true;
        for (Map.Entry<String, LinkedBlockingDeque<WorkItem>> e : queuesByModel.entrySet()) {
            int sz = e.getValue().size();
            if (sz == 0) continue;
            boolean isActive = e.getKey().equals(activeModel);
            if (excludeActive && isActive) continue;
            if (best == null
                    || sz > bestSize
                    || (sz == bestSize && bestIsActive && !isActive)) {
                best = e.getKey();
                bestSize = sz;
                bestIsActive = isActive;
            }
        }
        return best;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Phase 5 Track B — parallel ensemble support
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Phase 5 Track B — current total bytes used by Ollama's resident models, in MB.
     *
     * <p>Queries {@code /api/ps} via the underlying adapter and sums {@code vramBytes}
     * across all loaded models (falling back to {@code sizeBytes} on hosts where Ollama
     * reports VRAM as 0 — e.g. Apple Silicon unified memory).
     *
     * <p>Result is cached for {@value #PS_CACHE_TTL_MS}ms to avoid hammering Ollama when
     * the sweeper makes many memory-gate decisions in quick succession.
     *
     * <p>On HTTP / parse / unreachable errors: returns {@link Integer#MAX_VALUE}. This is
     * pessimistic by design — callers treat it as "memory pressure, fall back to serial".
     * A WARN is logged once per process lifetime (subsequent failures are silent).
     */
    public int currentLoadedModelMb() {
        long now = System.currentTimeMillis();
        if (now - psCacheFetchedAtMs < PS_CACHE_TTL_MS && psCacheFetchedAtMs != 0L) {
            return psCacheValueMb;
        }
        try {
            List<LoadedOllamaModel> loaded = adapter.psModels();
            long totalBytes = 0L;
            for (LoadedOllamaModel m : loaded) {
                long bytes = m.vramBytes() > 0 ? m.vramBytes() : m.sizeBytes();
                totalBytes += bytes;
            }
            int mb = (int) Math.min((long) Integer.MAX_VALUE, totalBytes / 1_048_576L);
            psCacheValueMb = mb;
            psCacheFetchedAtMs = now;
            return mb;
        } catch (Throwable t) {
            if (!psWarnedOnce) {
                psWarnedOnce = true;
                log.warn("[ai-assist] /api/ps unreachable; parallel-ensemble guard will fall back to serial: {}",
                        t.getMessage());
            }
            // Cache the failure briefly too — avoids hammering on a long Ollama outage.
            psCacheFetchedAtMs = now;
            psCacheValueMb = Integer.MAX_VALUE;
            return Integer.MAX_VALUE;
        }
    }

    /**
     * Phase 5 Track B — bypass the per-model serial scheduler and dispatch the request
     * directly to the underlying {@link HttpOllamaAdapter} on a bounded 2-thread pool.
     *
     * <p><b>Why not {@link #submit}</b>: the standard {@code submit} path serialises
     * work through a single scheduler thread that processes one model's queue at a time
     * — perfect for batched throughput, useless for halving per-row latency. The parallel
     * path is gated by the {@code parallelEnsemble} flag and a memory-budget guard
     * ({@link #currentLoadedModelMb}); callers should only land here when both models
     * are expected to fit warm-resident in Ollama.
     *
     * <p>{@code keep_alive} is applied identically to the serial path so model residency
     * decisions are consistent across both code paths.
     */
    public CompletableFuture<OllamaResponse> submitParallel(String model, OllamaRequest request) {
        if (model == null || model.isEmpty()) {
            CompletableFuture<OllamaResponse> f = new CompletableFuture<>();
            f.completeExceptionally(new IllegalArgumentException("model must be non-empty"));
            return f;
        }
        ExecutorService exec = parallelExecutor;
        if (exec == null) {
            synchronized (parallelExecutorLock) {
                if (parallelExecutor == null) {
                    parallelExecutor = Executors.newFixedThreadPool(2, r -> {
                        Thread t = new Thread(r, "ollama-parallel-ensemble");
                        t.setDaemon(true);
                        return t;
                    });
                }
                exec = parallelExecutor;
            }
        }
        final OllamaRequest effective = applyKeepAlive(request);
        return CompletableFuture.supplyAsync(() -> adapter.generate(effective), exec);
    }

    public Metrics metrics() {
        Map<String, Long> calls = new HashMap<>();
        Map<String, Long> nanos = new HashMap<>();
        callsByModel.forEach((k, v) -> calls.put(k, v.get()));
        totalNanosByModel.forEach((k, v) -> nanos.put(k, v.get()));
        return new Metrics(modelSwitches.get(), calls, nanos);
    }

    public record Metrics(long modelSwitches, Map<String, Long> callsByModel,
                          Map<String, Long> totalNanosByModel) {}
}
