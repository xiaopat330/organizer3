package com.organizer3.translation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.organizer3.translation.ollama.OllamaAdapter;
import com.organizer3.translation.ollama.OllamaException;
import com.organizer3.translation.ollama.OllamaRequest;
import com.organizer3.translation.ollama.OllamaResponse;
import com.organizer3.translation.repository.TranslationCacheRepository;
import com.organizer3.translation.repository.TranslationQueueRepository;
import com.organizer3.translation.repository.TranslationStrategyRepository;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Optional;

/**
 * Single-threaded worker that consumes {@code translation_queue} rows.
 *
 * <p>The worker loop:
 * <ol>
 *   <li>Claim one {@code pending} row ({@link #processOne} returns false if none).</li>
 *   <li>Pre-flight: check the cache. If already cached, skip Ollama and dispatch callback.</li>
 *   <li>Call {@link OllamaAdapter#generate} synchronously.</li>
 *   <li>On success: write to cache, dispatch callback, mark queue row {@code done}.</li>
 *   <li>On failure: increment attempt count. If under {@code maxAttempts}, re-queue (pending).
 *       Else mark {@code failed}.</li>
 * </ol>
 *
 * <p>Tests call {@link #processOne()} directly without starting the background loop.
 * Production code calls {@link #run()}, which loops until interrupted.
 */
@Slf4j
public class TranslationWorker implements Runnable {

    private static final DateTimeFormatter ISO_UTC =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private final OllamaAdapter ollamaAdapter;
    private final TranslationStrategyRepository strategyRepo;
    private final TranslationCacheRepository cacheRepo;
    private final TranslationQueueRepository queueRepo;
    private final CallbackDispatcher callbackDispatcher;
    private final TranslationConfig config;
    private final ObjectMapper json;

    public TranslationWorker(OllamaAdapter ollamaAdapter,
                              TranslationStrategyRepository strategyRepo,
                              TranslationCacheRepository cacheRepo,
                              TranslationQueueRepository queueRepo,
                              CallbackDispatcher callbackDispatcher,
                              TranslationConfig config,
                              ObjectMapper json) {
        this.ollamaAdapter      = ollamaAdapter;
        this.strategyRepo       = strategyRepo;
        this.cacheRepo          = cacheRepo;
        this.queueRepo          = queueRepo;
        this.callbackDispatcher = callbackDispatcher;
        this.config             = config;
        this.json               = json;
    }

    /**
     * Process one pending queue row end-to-end.
     *
     * @return {@code true} if a row was claimed and processed (success or failure);
     *         {@code false} if no pending rows were available.
     */
    public boolean processOne() {
        Optional<TranslationQueueRow> claimed = queueRepo.claimNext();
        if (claimed.isEmpty()) {
            return false;
        }

        TranslationQueueRow row = claimed.get();
        log.debug("TranslationWorker: claimed queue row={} strategy={}", row.id(), row.strategyId());

        TranslationStrategy strategy = strategyRepo.findById(row.strategyId()).orElse(null);
        if (strategy == null) {
            log.warn("TranslationWorker: strategy id={} not found — marking failed", row.strategyId());
            queueRepo.markFailed(row.id(), "strategy not found: id=" + row.strategyId(),
                    ISO_UTC.format(Instant.now()));
            return true;
        }

        // Pre-flight: re-check cache (covers race where two identical requests were queued)
        String hash = TranslationNormalization.hashOf(row.sourceText());
        Optional<TranslationCacheRow> cached = cacheRepo.findByHashAndStrategy(hash, strategy.id());
        if (cached.isPresent() && cached.get().bestTranslation() != null) {
            log.debug("TranslationWorker: cache pre-flight HIT for row={} — dispatching callback", row.id());
            callbackDispatcher.dispatch(row.callbackKind(), row.callbackId(),
                    cached.get().bestTranslation());
            queueRepo.markDone(row.id(), ISO_UTC.format(Instant.now()));
            return true;
        }

        // Execute translation
        executeAndRecord(row, strategy, hash, cached.map(TranslationCacheRow::id).orElse(null));
        return true;
    }

    /**
     * Worker loop — runs until the thread is interrupted. Sleeps between polls when idle.
     */
    @Override
    public void run() {
        log.info("TranslationWorker: started");
        int pollMs = config.workerPollIntervalSecondsOrDefault() * 1000;
        while (!Thread.currentThread().isInterrupted()) {
            try {
                boolean didWork = processOne();
                if (!didWork) {
                    Thread.sleep(pollMs);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("TranslationWorker: unexpected error in worker loop", e);
                // Don't crash the loop on unexpected errors — just continue
            }
        }
        log.info("TranslationWorker: stopped");
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    private void executeAndRecord(TranslationQueueRow row,
                                   TranslationStrategy strategy,
                                   String hash,
                                   Long existingCacheRowId) {
        String prompt = strategy.promptTemplate().replace("{jp}", row.sourceText());

        Map<String, Object> options = null;
        if (strategy.optionsJson() != null) {
            try {
                options = json.readValue(strategy.optionsJson(), new TypeReference<>() {});
            } catch (Exception e) {
                log.warn("TranslationWorker: failed to parse options_json for strategy '{}': {}",
                        strategy.name(), e.getMessage());
            }
        }

        OllamaRequest ollamaReq = new OllamaRequest(
                strategy.modelId(),
                prompt,
                null,
                options,
                config.timeoutOrDefault()
        );

        long startMs = System.currentTimeMillis();
        String englishText = null;
        String failureReason = null;
        String retryAfter = null;
        OllamaResponse ollamaResp = null;

        try {
            ollamaResp = ollamaAdapter.generate(ollamaReq);
            String raw = ollamaResp.responseText().trim();
            if (raw.startsWith("English:")) {
                raw = raw.substring("English:".length()).trim();
            }
            englishText = raw.isEmpty() ? null : raw;
            if (englishText == null) {
                failureReason = "empty_response";
            }
        } catch (OllamaException e) {
            if (isTransient(e)) {
                failureReason = "unreachable";
                retryAfter = ISO_UTC.format(Instant.now().plusSeconds(300));
            } else {
                failureReason = "adapter_error";
            }
            log.warn("TranslationWorker: Ollama error for strategy '{}': {}", strategy.name(), e.getMessage());
        }

        long latencyMs = System.currentTimeMillis() - startMs;
        String now = ISO_UTC.format(Instant.now());

        // Write / update cache
        if (existingCacheRowId != null) {
            cacheRepo.updateOutcome(existingCacheRowId,
                    englishText, failureReason, retryAfter,
                    (int) latencyMs,
                    ollamaResp != null ? ollamaResp.promptEvalCount() : null,
                    ollamaResp != null ? ollamaResp.evalCount() : null,
                    ollamaResp != null ? ollamaResp.evalDurationNs() : null);
        } else {
            TranslationCacheRow cacheRow = new TranslationCacheRow(
                    0, hash, row.sourceText(), strategy.id(),
                    englishText, null, null,
                    failureReason, retryAfter,
                    (int) latencyMs,
                    ollamaResp != null ? ollamaResp.promptEvalCount() : null,
                    ollamaResp != null ? ollamaResp.evalCount() : null,
                    ollamaResp != null ? ollamaResp.evalDurationNs() : null,
                    now
            );
            cacheRepo.insert(cacheRow);
        }

        log.info("TranslationWorker: row={} strategy={} latency={}ms success={}",
                row.id(), strategy.name(), latencyMs, englishText != null);

        if (englishText != null) {
            // Success — dispatch callback and mark done
            callbackDispatcher.dispatch(row.callbackKind(), row.callbackId(), englishText);
            queueRepo.markDone(row.id(), now);
        } else {
            // Failure — increment attempt or mark permanently failed
            int nextAttempt = row.attemptCount() + 1;
            if (nextAttempt >= config.maxAttemptsOrDefault()) {
                String reason = failureReason != null ? failureReason : "unknown";
                queueRepo.markFailed(row.id(), reason, now);
                log.warn("TranslationWorker: row={} permanently failed after {} attempts: {}",
                        row.id(), nextAttempt, reason);
            } else {
                queueRepo.incrementAttempt(row.id(), failureReason);
                log.debug("TranslationWorker: row={} attempt={} failed, re-queued",
                        row.id(), nextAttempt);
            }
        }
    }

    private static boolean isTransient(OllamaException e) {
        String msg = e.getMessage();
        return msg != null && (msg.contains("Connection refused")
                || msg.contains("ConnectException")
                || msg.contains("SocketTimeoutException")
                || msg.contains("timeout")
                || msg.contains("unreachable"));
    }
}
