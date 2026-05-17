package com.organizer3.ollama;

import com.organizer3.translation.ollama.HttpOllamaAdapter;
import com.organizer3.translation.ollama.OllamaRequest;
import com.organizer3.translation.ollama.OllamaResponse;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingDeque;
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
    private volatile boolean stopped = false;
    private volatile boolean accepting = true;

    private Thread schedulerThread;

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
                        log.debug("ollama-orchestrator: switch {} -> {}", activeModel, pick);
                    }
                    activeModel = pick;
                    activeModelStartedAt = clock.instant();
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
