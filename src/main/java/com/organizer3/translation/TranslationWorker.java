package com.organizer3.translation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.organizer3.translation.ollama.OllamaAdapter;
import com.organizer3.translation.ollama.OllamaException;
import com.organizer3.translation.ollama.OllamaRequest;
import com.organizer3.translation.ollama.OllamaResponse;
import com.organizer3.repository.ActressRepository;
import com.organizer3.translation.repository.StageNameSuggestionRepository;
import com.organizer3.translation.repository.TranslationCacheRepository;
import com.organizer3.translation.repository.TranslationQueueRepository;
import com.organizer3.translation.repository.TranslationStrategyRepository;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

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

    /**
     * Refusal pattern: matches model outputs that indicate the model refused to translate.
     *
     * <p>Each broad keyword requires a refusal-specific continuation to avoid false positives
     * from legitimate JAV title translations. Concretely:
     * <ul>
     *   <li>{@code cannot} / {@code unable to} / {@code i can't} must be followed by
     *       help/assist/translate/provide — not words like "forget", "hold back", or "move"
     *       that appear in normal titles (e.g. ごめんなさい→"sorry", できない→"can't").</li>
     *   <li>{@code sorry} alone is not enough; it must precede a refusal continuation.</li>
     * </ul>
     * Anchor terms that are always refusal indicators (safety, refuse, not appropriate,
     * i am programmed) are kept as-is since they don't appear in translated titles.
     */
    private static final Pattern REFUSAL_PATTERN = Pattern.compile(
            "(?:cannot|can not) (?:help|assist|translate|provide|process)" +
            "|unable to (?:help|assist|translate|provide|process)" +
            "|not able to (?:help|assist|translate|provide|process)" +
            "|sorry[,;]?\\s*(?:(?:but|however|unfortunately)\\s+)?(?:i |we )?(?:cannot|can't|am unable|will not|won't) (?:help|assist|translate|provide|process|do that|do this)" +
            "|i can't (?:help|assist|translate|provide|do that|do this)" +
            "|i am programmed|i'm programmed" +
            "|safety|not appropriate" +
            "|(?:i )?refuse to (?:translate|assist|help|process)",
            Pattern.CASE_INSENSITIVE
    );

    /**
     * A response is considered a "hard refusal" if it is empty (null) OR if it is short
     * (≤ 80 chars) AND matches the refusal pattern. Short-but-valid translations are allowed
     * through; only short + refusal-keyword triggers escalation.
     */
    static boolean isRefusal(String text) {
        if (text == null || text.isBlank()) return true;
        // Short output + refusal keyword = refusal
        return text.length() <= 80 && REFUSAL_PATTERN.matcher(text).find();
    }

    private final OllamaAdapter ollamaAdapter;
    private final TranslationStrategyRepository strategyRepo;
    private final TranslationCacheRepository cacheRepo;
    private final TranslationQueueRepository queueRepo;
    private final CallbackDispatcher callbackDispatcher;
    private final TranslationConfig config;
    private final ObjectMapper json;
    private final OllamaModelState modelState;
    private final HealthGate healthGate;
    /** Optional — null when stage-name repos are not wired (e.g. in tests). */
    private final StageNameSuggestionRepository stageNameSuggestionRepo;
    /** Never null — defaults to {@link ExplicitTermSubstitutor#EMPTY} (no-op). */
    private final ExplicitTermSubstitutor explicitTermSubstitutor;
    /** Optional — null in tests. When set, per-title actress stage names are substituted. */
    private final ActressRepository actressRepo;

    public TranslationWorker(OllamaAdapter ollamaAdapter,
                              TranslationStrategyRepository strategyRepo,
                              TranslationCacheRepository cacheRepo,
                              TranslationQueueRepository queueRepo,
                              CallbackDispatcher callbackDispatcher,
                              TranslationConfig config,
                              ObjectMapper json) {
        this(ollamaAdapter, strategyRepo, cacheRepo, queueRepo, callbackDispatcher, config, json,
                new OllamaModelState());
    }

    public TranslationWorker(OllamaAdapter ollamaAdapter,
                              TranslationStrategyRepository strategyRepo,
                              TranslationCacheRepository cacheRepo,
                              TranslationQueueRepository queueRepo,
                              CallbackDispatcher callbackDispatcher,
                              TranslationConfig config,
                              ObjectMapper json,
                              OllamaModelState modelState) {
        this(ollamaAdapter, strategyRepo, cacheRepo, queueRepo, callbackDispatcher, config, json,
                modelState,
                new HealthGate(ollamaAdapter, cacheRepo, config));
    }

    public TranslationWorker(OllamaAdapter ollamaAdapter,
                              TranslationStrategyRepository strategyRepo,
                              TranslationCacheRepository cacheRepo,
                              TranslationQueueRepository queueRepo,
                              CallbackDispatcher callbackDispatcher,
                              TranslationConfig config,
                              ObjectMapper json,
                              OllamaModelState modelState,
                              HealthGate healthGate) {
        this(ollamaAdapter, strategyRepo, cacheRepo, queueRepo, callbackDispatcher, config, json,
                modelState, healthGate, null);
    }

    public TranslationWorker(OllamaAdapter ollamaAdapter,
                              TranslationStrategyRepository strategyRepo,
                              TranslationCacheRepository cacheRepo,
                              TranslationQueueRepository queueRepo,
                              CallbackDispatcher callbackDispatcher,
                              TranslationConfig config,
                              ObjectMapper json,
                              OllamaModelState modelState,
                              HealthGate healthGate,
                              StageNameSuggestionRepository stageNameSuggestionRepo) {
        this(ollamaAdapter, strategyRepo, cacheRepo, queueRepo, callbackDispatcher, config, json,
                modelState, healthGate, stageNameSuggestionRepo, ExplicitTermSubstitutor.EMPTY);
    }

    public TranslationWorker(OllamaAdapter ollamaAdapter,
                              TranslationStrategyRepository strategyRepo,
                              TranslationCacheRepository cacheRepo,
                              TranslationQueueRepository queueRepo,
                              CallbackDispatcher callbackDispatcher,
                              TranslationConfig config,
                              ObjectMapper json,
                              OllamaModelState modelState,
                              HealthGate healthGate,
                              StageNameSuggestionRepository stageNameSuggestionRepo,
                              ExplicitTermSubstitutor explicitTermSubstitutor) {
        this(ollamaAdapter, strategyRepo, cacheRepo, queueRepo, callbackDispatcher, config, json,
                modelState, healthGate, stageNameSuggestionRepo, explicitTermSubstitutor, null);
    }

    public TranslationWorker(OllamaAdapter ollamaAdapter,
                              TranslationStrategyRepository strategyRepo,
                              TranslationCacheRepository cacheRepo,
                              TranslationQueueRepository queueRepo,
                              CallbackDispatcher callbackDispatcher,
                              TranslationConfig config,
                              ObjectMapper json,
                              OllamaModelState modelState,
                              HealthGate healthGate,
                              StageNameSuggestionRepository stageNameSuggestionRepo,
                              ExplicitTermSubstitutor explicitTermSubstitutor,
                              ActressRepository actressRepo) {
        this.ollamaAdapter           = ollamaAdapter;
        this.strategyRepo            = strategyRepo;
        this.cacheRepo               = cacheRepo;
        this.queueRepo               = queueRepo;
        this.callbackDispatcher      = callbackDispatcher;
        this.config                  = config;
        this.json                    = json;
        this.modelState              = modelState;
        this.healthGate              = healthGate;
        this.stageNameSuggestionRepo = stageNameSuggestionRepo;
        this.explicitTermSubstitutor = explicitTermSubstitutor != null
                ? explicitTermSubstitutor : ExplicitTermSubstitutor.EMPTY;
        this.actressRepo             = actressRepo;
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
            String cachedText = cached.get().bestTranslation();
            callbackDispatcher.dispatch(row.callbackKind(), row.callbackId(), cachedText);
            // Stage-name fan-out: even on cache hit, fire the dispatcher so fan-out runs for
            // subsequent identical-source enqueues (spec §11.4).
            if (stageNameSuggestionRepo != null
                    && TranslationServiceImpl.looksLikeStageName(row.sourceText())) {
                try {
                    String now = ISO_UTC.format(Instant.now());
                    long suggestionId = stageNameSuggestionRepo.recordSuggestionAndGetId(
                            row.sourceText(), cachedText, now);
                    callbackDispatcher.dispatch("stage_name_suggestion", suggestionId, cachedText);
                } catch (Exception e) {
                    log.warn("TranslationWorker: cache-hit stage-name fan-out failed for row={}: {}",
                            row.id(), e.getMessage());
                }
            }
            queueRepo.markDone(row.id(), ISO_UTC.format(Instant.now()));
            return true;
        }

        // Execute translation
        executeAndRecord(row, strategy, hash, cached.map(TranslationCacheRow::id).orElse(null));
        return true;
    }

    /**
     * Worker loop — runs until the thread is interrupted. Sleeps between polls when idle.
     * Pauses when the health gate reports unhealthy (Ollama unreachable or model missing).
     */
    @Override
    public void run() {
        log.info("TranslationWorker: started");
        int pollMs = config.workerPollIntervalSecondsOrDefault() * 1000;
        while (!Thread.currentThread().isInterrupted()) {
            try {
                // Health gate — pause if Ollama is unreachable or tier-1 model is missing
                if (!healthGate.isHealthy()) {
                    HealthStatus status = healthGate.currentStatus();
                    log.warn("TranslationWorker: pausing — unhealthy: {}", status.message());
                    Thread.sleep(30_000L); // wait 30s before re-checking
                    continue;
                }

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
        String promptInput = explicitTermSubstitutor.substitute(row.sourceText());
        if (actressRepo != null && "title".equals(row.callbackKind())) {
            java.util.Map<String, String> stageNames = actressRepo.findStageNameMapForTitle(row.callbackId());
            if (!stageNames.isEmpty()) {
                promptInput = new ExplicitTermSubstitutor(stageNames).substitute(promptInput);
            }
        }
        String prompt = strategy.promptTemplate().replace("{jp}", promptInput);

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
        boolean escalateToTier2 = false;
        String tier2Reason = null;

        try {
            ollamaResp = ollamaAdapter.generate(ollamaReq);
            // Update model state tracking
            modelState.setCurrentModelId(strategy.modelId());

            String raw = ollamaResp.responseText().trim();
            if (raw.startsWith("English:")) {
                raw = raw.substring("English:".length()).trim();
            }
            String cleaned = raw.isEmpty() ? null : raw;

            // Check for refusal (empty or refusal-pattern match on short output)
            if (isRefusal(cleaned)) {
                escalateToTier2 = true;
                tier2Reason = "refused";
                failureReason = "refused";
                log.info("TranslationWorker: row={} strategy={} REFUSED — escalating to tier-2",
                        row.id(), strategy.name());
            // Check for sanitization (explicit JP input but no explicit EN output)
            } else if (SanitizationDetector.isSanitized(row.sourceText(), cleaned)) {
                escalateToTier2 = true;
                tier2Reason = "sanitized";
                failureReason = "sanitized";
                log.info("TranslationWorker: row={} strategy={} SANITIZED — escalating to tier-2",
                        row.id(), strategy.name());
            } else {
                englishText = cleaned;
            }
        } catch (OllamaException e) {
            if (isTransient(e)) {
                failureReason = "unreachable";
                retryAfter = ISO_UTC.format(Instant.now().plusSeconds(300));
                // Force the health gate to re-probe immediately so the worker loop pauses
                // on the next iteration rather than burning through the queue for up to 30s
                // on stale "healthy" cache while Ollama is down.
                healthGate.refresh();
            } else {
                failureReason = "adapter_error";
            }
            log.warn("TranslationWorker: Ollama error for strategy '{}': {}", strategy.name(), e.getMessage());
        }

        long latencyMs = System.currentTimeMillis() - startMs;
        String now = ISO_UTC.format(Instant.now());

        // Write / update cache (records tier-1 result including refusal/sanitization)
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

        log.info("TranslationWorker: row={} strategy={} model={} latency={}ms promptTokens={} evalTokens={} sourceLen={} success={} escalate={} cacheHit=false",
                row.id(), strategy.name(), strategy.modelId(), latencyMs,
                ollamaResp != null ? ollamaResp.promptEvalCount() : null,
                ollamaResp != null ? ollamaResp.evalCount() : null,
                row.sourceText().length(),
                englishText != null, escalateToTier2);

        // Stage-name suggestion hook: if the source looks like a stage name and we got a result,
        // record it for human review and dispatch the fan-out callback.
        // Parallel write — does not affect cache or queue outcome.
        Long stageNameSuggestionId = null;
        if (englishText != null && stageNameSuggestionRepo != null
                && TranslationServiceImpl.looksLikeStageName(row.sourceText())) {
            try {
                stageNameSuggestionId = stageNameSuggestionRepo.recordSuggestionAndGetId(
                        row.sourceText(), englishText, now);
                log.debug("TranslationWorker: stage-name suggestion recorded for row={} suggestionId={}",
                        row.id(), stageNameSuggestionId);
            } catch (Exception e) {
                log.warn("TranslationWorker: failed to record stage-name suggestion for row={}: {}",
                        row.id(), e.getMessage());
            }
        }

        if (escalateToTier2) {
            // Mark as tier_2_pending — the Tier2BatchSweeper will handle it
            queueRepo.markTier2Pending(row.id(), tier2Reason, now);
            log.info("TranslationWorker: row={} marked tier_2_pending ({})", row.id(), tier2Reason);
        } else if (englishText != null) {
            // Success — dispatch original callback, then unconditionally dispatch stage-name
            // fan-out if this was a stage-name completion (regardless of originating callbackKind).
            callbackDispatcher.dispatch(row.callbackKind(), row.callbackId(), englishText);
            if (stageNameSuggestionId != null) {
                callbackDispatcher.dispatch("stage_name_suggestion", stageNameSuggestionId, englishText);
            }
            queueRepo.markDone(row.id(), now);
        } else {
            // Non-escalating failure (transient/adapter error) — increment attempt or mark permanently failed
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
