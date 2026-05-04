package com.organizer3.translation;

import com.organizer3.translation.ollama.OllamaAdapter;
import com.organizer3.translation.ollama.OllamaModel;
import com.organizer3.translation.repository.TranslationCacheRepository;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.List;

/**
 * Caches the result of an Ollama health check for ~30 seconds to avoid hammering
 * the daemon on every worker loop iteration.
 *
 * <p>Callers ask {@link #currentStatus()} to get the cached (or freshly computed) status.
 * The worker loop calls {@link #isHealthy()} for a simple boolean gate.
 */
@Slf4j
public class HealthGate {

    private static final int CACHE_TTL_SECONDS = 30;
    private static final int LATENCY_P95_SAMPLE_SIZE = 100;
    private static final long LATENCY_P95_MAX_MS = 90_000L; // 90 seconds

    private final OllamaAdapter ollamaAdapter;
    private final TranslationCacheRepository cacheRepo;
    private final TranslationConfig config;

    private volatile HealthStatus cached = null;
    private volatile Instant cacheExpiry = Instant.EPOCH;

    public HealthGate(OllamaAdapter ollamaAdapter,
                      TranslationCacheRepository cacheRepo,
                      TranslationConfig config) {
        this.ollamaAdapter = ollamaAdapter;
        this.cacheRepo     = cacheRepo;
        this.config        = config;
    }

    /** Returns true if the last health check (cached up to 30s) passed. */
    public boolean isHealthy() {
        return currentStatus().overall();
    }

    /**
     * Returns the current health status, recomputing if the cached value has expired.
     * Thread-safe (double-checked against expiry, worst case runs twice on contention).
     */
    public HealthStatus currentStatus() {
        if (Instant.now().isBefore(cacheExpiry) && cached != null) {
            return cached;
        }
        return refresh();
    }

    /** Force a fresh check, bypassing the TTL cache. */
    public synchronized HealthStatus refresh() {
        HealthStatus status = compute();
        cached = status;
        cacheExpiry = Instant.now().plusSeconds(CACHE_TTL_SECONDS);
        return status;
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    private HealthStatus compute() {
        // 1. Daemon reachable?
        boolean reachable;
        try {
            reachable = ollamaAdapter.isHealthy();
        } catch (Exception e) {
            log.debug("HealthGate: isHealthy() threw: {}", e.getMessage());
            reachable = false;
        }
        if (!reachable) {
            return HealthStatus.ollamaDown();
        }

        // 2. Tier-1 model present?
        String tier1Model = config.primaryModelOrDefault();
        boolean modelPresent = false;
        try {
            List<OllamaModel> models = ollamaAdapter.listModels();
            modelPresent = models.stream().anyMatch(m -> tier1Model.equals(m.name()));
        } catch (Exception e) {
            log.debug("HealthGate: listModels() threw: {}", e.getMessage());
        }
        if (!modelPresent) {
            return HealthStatus.modelMissing(tier1Model);
        }

        // 3. Latency p95 check (advisory only — won't block if no data)
        Long p95 = null;
        boolean latencyOk = true;
        try {
            p95 = cacheRepo.latencyP95(LATENCY_P95_SAMPLE_SIZE);
            if (p95 != null && p95 > LATENCY_P95_MAX_MS) {
                latencyOk = false;
            }
        } catch (Exception e) {
            log.debug("HealthGate: latencyP95 threw: {}", e.getMessage());
        }

        String message = latencyOk ? "OK"
                : String.format("p95 latency high: %dms (threshold %dms)", p95, LATENCY_P95_MAX_MS);
        // Latency being high is advisory — overall still true as long as daemon + model are present
        return new HealthStatus(true, true, latencyOk, p95, true, message);
    }
}
