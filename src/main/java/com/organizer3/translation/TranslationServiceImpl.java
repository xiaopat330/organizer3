package com.organizer3.translation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.organizer3.ollama.OllamaModelOrchestrator;
import com.organizer3.translation.ollama.OllamaAdapter;
import com.organizer3.translation.ollama.OllamaException;
import com.organizer3.translation.ollama.OllamaRequest;
import com.organizer3.translation.ollama.OllamaResponse;
import com.organizer3.translation.repository.StageNameLookupRepository;
import com.organizer3.translation.repository.StageNameSuggestionRepository;
import com.organizer3.translation.repository.TranslationCacheRepository;
import com.organizer3.translation.repository.TranslationQueueRepository;
import com.organizer3.translation.repository.TranslationStrategyRepository;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Phase 2 implementation of {@link TranslationService}.
 *
 * <p>{@link #requestTranslation} is now async — it enqueues work and returns immediately.
 * A cache hit writes a {@code done} queue row and dispatches the callback synchronously.
 * A cache miss writes a {@code pending} queue row for the worker to process.
 *
 * <p>Strategy selection delegates to {@link StrategySelector}. Cache key derivation
 * delegates to {@link TranslationNormalization}. Ollama execution is in {@link TranslationWorker}.
 */
@Slf4j
public class TranslationServiceImpl implements TranslationService {

    static final DateTimeFormatter ISO_UTC =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    /** Failure reason set by Tier2BatchSweeper when both tier-1 and tier-2 sanitize. */
    public static final String SANITIZED_BOTH_TIERS = "sanitized_both_tiers";

    final OllamaAdapter ollamaAdapter;
    /**
     * Orchestrator that batches {@code generate} calls by model. ALL {@code generate} traffic
     * is routed through it (Phase 4 Track A). The adapter ref above is retained only for
     * {@link HealthGate} construction in convenience constructors.
     */
    final OllamaModelOrchestrator orchestrator;
    final TranslationStrategyRepository strategyRepo;
    final TranslationCacheRepository cacheRepo;
    final TranslationQueueRepository queueRepo;
    final TranslationConfig config;
    final CallbackDispatcher callbackDispatcher;
    final HealthGate healthGate;
    final ObjectMapper objectMapper;
    final StageNameLookupRepository stageNameLookupRepo;
    final StageNameSuggestionRepository stageNameSuggestionRepo;
    /** Never null — defaults to {@link ExplicitTermSubstitutor#EMPTY} (no-op). */
    final ExplicitTermSubstitutor explicitTermSubstitutor;

    /** Since-startup counters of cache lookups serviced by this instance. Reset on restart. */
    private final AtomicLong cacheLookupHits = new AtomicLong();
    private final AtomicLong cacheLookupMisses = new AtomicLong();

    /** Full constructor including HealthGate, ObjectMapper, stage-name repos, and term substitutor. */
    public TranslationServiceImpl(OllamaModelOrchestrator orchestrator,
                                   OllamaAdapter ollamaAdapter,
                                   TranslationStrategyRepository strategyRepo,
                                   TranslationCacheRepository cacheRepo,
                                   TranslationQueueRepository queueRepo,
                                   TranslationConfig config,
                                   CallbackDispatcher callbackDispatcher,
                                   HealthGate healthGate,
                                   ObjectMapper objectMapper,
                                   StageNameLookupRepository stageNameLookupRepo,
                                   StageNameSuggestionRepository stageNameSuggestionRepo,
                                   ExplicitTermSubstitutor explicitTermSubstitutor) {
        this.orchestrator            = orchestrator;
        this.ollamaAdapter           = ollamaAdapter;
        this.strategyRepo            = strategyRepo;
        this.cacheRepo               = cacheRepo;
        this.queueRepo               = queueRepo;
        this.config                  = config;
        this.callbackDispatcher      = callbackDispatcher;
        this.healthGate              = healthGate;
        this.objectMapper            = objectMapper;
        this.stageNameLookupRepo     = stageNameLookupRepo;
        this.stageNameSuggestionRepo = stageNameSuggestionRepo;
        this.explicitTermSubstitutor = explicitTermSubstitutor != null
                ? explicitTermSubstitutor : ExplicitTermSubstitutor.EMPTY;
    }

    /** Backward-compat: without explicitTermSubstitutor (uses EMPTY). */
    public TranslationServiceImpl(OllamaModelOrchestrator orchestrator,
                                   OllamaAdapter ollamaAdapter,
                                   TranslationStrategyRepository strategyRepo,
                                   TranslationCacheRepository cacheRepo,
                                   TranslationQueueRepository queueRepo,
                                   TranslationConfig config,
                                   CallbackDispatcher callbackDispatcher,
                                   HealthGate healthGate,
                                   ObjectMapper objectMapper,
                                   StageNameLookupRepository stageNameLookupRepo,
                                   StageNameSuggestionRepository stageNameSuggestionRepo) {
        this(orchestrator, ollamaAdapter, strategyRepo, cacheRepo, queueRepo, config,
                callbackDispatcher, healthGate, objectMapper, stageNameLookupRepo,
                stageNameSuggestionRepo, ExplicitTermSubstitutor.EMPTY);
    }

    /** Backward-compatible constructor: creates a default HealthGate and ObjectMapper; no stage-name repos. */
    public TranslationServiceImpl(OllamaModelOrchestrator orchestrator,
                                   OllamaAdapter ollamaAdapter,
                                   TranslationStrategyRepository strategyRepo,
                                   TranslationCacheRepository cacheRepo,
                                   TranslationQueueRepository queueRepo,
                                   TranslationConfig config,
                                   CallbackDispatcher callbackDispatcher) {
        this(orchestrator, ollamaAdapter, strategyRepo, cacheRepo, queueRepo, config,
                callbackDispatcher,
                new HealthGate(ollamaAdapter, cacheRepo, config),
                new ObjectMapper(),
                null, null,
                ExplicitTermSubstitutor.EMPTY);
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    @Override
    public Optional<String> getCached(String sourceText) {
        String normalised = TranslationNormalization.normalize(sourceText);
        String hash = TranslationNormalization.sha256Hex(normalised);

        // Lookup priority per §5.5.1:
        //   human_corrected_text (any strategy) > tier-1 english_text > tier-2 english_text > miss
        // Permanent-failure rows (failure_reason='sanitized_both_tiers') return empty.
        List<TranslationStrategy> strategies = strategyRepo.findAllActive();

        // Collect all cache rows for this hash
        String humanCorrected = null;
        String tier1Text = null;
        String tier2Text = null;

        for (TranslationStrategy strategy : strategies) {
            Optional<TranslationCacheRow> rowOpt = cacheRepo.findByHashAndStrategy(hash, strategy.id());
            if (rowOpt.isEmpty()) continue;
            TranslationCacheRow row = rowOpt.get();

            // Human correction wins unconditionally
            if (row.humanCorrectedText() != null) {
                humanCorrected = row.humanCorrectedText();
                break; // Can't beat human correction
            }

            // Permanent failure — skip this row but keep scanning (another strategy may have succeeded)
            if ("sanitized_both_tiers".equals(row.failureReason())) {
                log.debug("getCached: permanent failure row strategy={} hash={}", strategy.name(), hash.substring(0, 8));
                continue; // Do NOT short-circuit — check remaining strategies
            }

            if (row.englishText() != null) {
                boolean isTier2 = isTier2Strategy(strategy, strategies);
                if (isTier2) {
                    if (tier2Text == null) tier2Text = row.englishText();
                } else {
                    if (tier1Text == null) tier1Text = row.englishText();
                }
            }
        }

        if (humanCorrected != null) {
            cacheLookupHits.incrementAndGet();
            log.debug("getCached: HIT (human corrected) hash={}", hash.substring(0, 8));
            return Optional.of(humanCorrected);
        }
        if (tier1Text != null) {
            cacheLookupHits.incrementAndGet();
            log.debug("getCached: HIT (tier-1) hash={}", hash.substring(0, 8));
            return Optional.of(tier1Text);
        }
        if (tier2Text != null) {
            cacheLookupHits.incrementAndGet();
            log.debug("getCached: HIT (tier-2) hash={}", hash.substring(0, 8));
            return Optional.of(tier2Text);
        }

        cacheLookupMisses.incrementAndGet();
        log.debug("getCached: MISS hash={}", hash.substring(0, 8));
        return Optional.empty();
    }

    /**
     * Returns true if the given strategy is a tier-2 strategy (i.e., it appears as the
     * {@code tier2_strategy_id} of some other strategy in the active set).
     */
    private boolean isTier2Strategy(TranslationStrategy candidate, List<TranslationStrategy> allStrategies) {
        long candidateId = candidate.id();
        for (TranslationStrategy s : allStrategies) {
            if (s.tier2StrategyId() != null && s.tier2StrategyId() == candidateId) {
                return true;
            }
        }
        return false;
    }

    @Override
    public long requestTranslation(TranslationRequest req) {
        String normalised = TranslationNormalization.normalize(req.sourceText());
        String hash = TranslationNormalization.sha256Hex(normalised);

        String strategyName = StrategySelector.pick(normalised, req.contextHint(), 1);
        TranslationStrategy strategy = strategyRepo.findByName(strategyName)
                .orElseGet(() -> {
                    log.warn("requestTranslation: strategy '{}' not found, falling back to label_basic", strategyName);
                    return strategyRepo.findByName(StrategySelector.LABEL_BASIC)
                            .orElseThrow(() -> new IllegalStateException(
                                    "No translation strategies seeded — run schema migration to populate translation_strategy table"));
                });

        // Check cache first
        Optional<TranslationCacheRow> existing = cacheRepo.findByHashAndStrategy(hash, strategy.id());
        if (existing.isPresent()) {
            TranslationCacheRow row = existing.get();
            if (row.bestTranslation() != null) {
                // Cache hit — mark done immediately, dispatch callback, return
                cacheLookupHits.incrementAndGet();
                log.info("translation: strategy={} model={} latency=0ms promptTokens=null evalTokens=null sourceLen={} success=true cacheHit=true",
                        strategy.name(), strategy.modelId(), normalised.length());
                log.debug("requestTranslation: cache HIT strategy={} hash={}", strategy.name(), hash.substring(0, 8));
                String now = ISO_UTC.format(Instant.now());
                long queueId = queueRepo.enqueue(normalised, strategy.id(), now,
                        TranslationQueueRow.STATUS_DONE, req.callbackKind(), req.callbackId());
                queueRepo.markDone(queueId, now);
                callbackDispatcher.dispatch(req.callbackKind(), req.callbackId(), row.bestTranslation());
                return queueId;
            }
            // Permanent or transient failure cached — still enqueue so worker can retry
            // (worker's pre-flight cache check will re-evaluate retry_after)
        }

        // Cache miss (or stale failure row) — enqueue for async processing
        cacheLookupMisses.incrementAndGet();
        String now = ISO_UTC.format(Instant.now());
        long queueId = queueRepo.enqueue(normalised, strategy.id(), now,
                TranslationQueueRow.STATUS_PENDING, req.callbackKind(), req.callbackId());
        log.debug("requestTranslation: enqueued queueId={} strategy={} hash={}", queueId, strategy.name(), hash.substring(0, 8));
        return queueId;
    }

    @Override
    public Optional<String> resolveStageName(String kanjiName) {
        if (kanjiName == null || kanjiName.isBlank()) return Optional.empty();
        if (stageNameLookupRepo == null) {
            log.debug("resolveStageName: stage-name repos not wired, returning empty");
            return Optional.empty();
        }
        String normalized = TranslationNormalization.normalize(kanjiName);

        // 1. Curated lookup table first (highest priority)
        Optional<String> curated = stageNameLookupRepo.findRomanizedFor(normalized);
        if (curated.isPresent()) {
            log.debug("resolveStageName: curated HIT for '{}'", normalized);
            return curated;
        }

        // 2. Accepted LLM suggestion
        Optional<String> accepted = stageNameSuggestionRepo.findAcceptedRomaji(normalized);
        if (accepted.isPresent()) {
            log.debug("resolveStageName: accepted suggestion HIT for '{}'", normalized);
            return accepted;
        }

        log.debug("resolveStageName: MISS for '{}'", normalized);
        return Optional.empty();
    }

    @Override
    public Optional<String> resolveOrSuggestStageName(String kanjiName) {
        if (kanjiName == null || kanjiName.isBlank()) return Optional.empty();
        if (stageNameLookupRepo == null || stageNameSuggestionRepo == null) {
            log.debug("resolveOrSuggestStageName: stage-name repos not wired, returning empty");
            return Optional.empty();
        }
        String normalized = TranslationNormalization.normalize(kanjiName);

        Optional<String> curated = stageNameLookupRepo.findRomanizedFor(normalized);
        if (curated.isPresent()) {
            log.debug("resolveOrSuggestStageName: curated HIT for '{}'", normalized);
            return curated;
        }

        Optional<String> suggestion = stageNameSuggestionRepo.findLatestUsableSuggestion(normalized);
        if (suggestion.isPresent()) {
            log.debug("resolveOrSuggestStageName: suggestion HIT for '{}'", normalized);
            return suggestion;
        }

        Optional<TranslationStrategy> strategy = strategyRepo.findByName("label_basic");
        if (strategy.isEmpty()) {
            log.warn("resolveOrSuggestStageName: label_basic strategy not found, cannot enqueue for '{}'", normalized);
            return Optional.empty();
        }
        String now = ISO_UTC.format(Instant.now());
        // Priority=10 puts stage-name rows ahead of bulk title translations (priority=0)
        boolean inserted = queueRepo.enqueueIfAbsent(normalized, strategy.get().id(), now,
                TranslationQueueRow.STATUS_PENDING, null, null, 10);
        log.debug("resolveOrSuggestStageName: MISS for '{}', enqueueIfAbsent={}", normalized, inserted);
        return Optional.empty();
    }

    // FIX 2: Bounded blocking wait for stage-name resolution.
    @Override
    public Optional<String> resolveStageNameBlocking(String kanjiName, long timeoutMs, long pollIntervalMs) {
        if (kanjiName == null || kanjiName.isBlank()) return Optional.empty();

        // Enqueue the work (no-op if already present) and attempt an immediate hit.
        Optional<String> immediate = resolveOrSuggestStageName(kanjiName);
        if (immediate.isPresent()) return immediate;

        // Poll until a suggestion appears or the timeout elapses.
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(pollIntervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            Optional<String> poll = resolveOrSuggestStageName(kanjiName);
            if (poll.isPresent()) {
                log.debug("resolveStageNameBlocking: resolved '{}' after polling", kanjiName);
                return poll;
            }
        }
        log.debug("resolveStageNameBlocking: timed out after {}ms for '{}'", timeoutMs, kanjiName);
        return Optional.empty();
    }

    @Override
    public TranslationServiceStats stats() {
        long stageNameLookupSize = stageNameLookupRepo != null ? stageNameLookupRepo.countAll() : 0L;
        long stageNameSuggestionsUnreviewed = stageNameSuggestionRepo != null ? stageNameSuggestionRepo.countUnreviewed() : 0L;
        return new TranslationServiceStats(
                cacheRepo.countTotal(),
                cacheRepo.countSuccessful(),
                cacheRepo.countFailed(),
                cacheRepo.countByFailureReason(SANITIZED_BOTH_TIERS),
                cacheRepo.countByFailureReason("sanitized"),
                cacheRepo.countByFailureReason("unreachable"),
                cacheRepo.countByFailureReason("refused"),
                queueRepo.countByStatus(TranslationQueueRow.STATUS_PENDING),
                queueRepo.countByStatus(TranslationQueueRow.STATUS_IN_FLIGHT),
                queueRepo.countByStatus(TranslationQueueRow.STATUS_DONE),
                queueRepo.countByStatus(TranslationQueueRow.STATUS_FAILED),
                queueRepo.countTier2Pending(),
                stageNameLookupSize,
                stageNameSuggestionsUnreviewed,
                cacheLookupHits.get(),
                cacheLookupMisses.get()
        );
    }

    @Override
    public HealthStatus getHealth() {
        return healthGate.currentStatus();
    }

    @Override
    public int requeueFailedByReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason is required");
        }
        int queueDeleted = queueRepo.deleteForCacheFailureReason(reason);
        int cacheDeleted = cacheRepo.deleteByFailureReason(reason);
        log.info("requeueFailedByReason: reason='{}' queueRowsDeleted={} cacheRowsDeleted={}",
                reason, queueDeleted, cacheDeleted);
        return cacheDeleted;
    }

    @Override
    public String requestTranslationSync(TranslationRequest req) {
        String normalised = TranslationNormalization.normalize(req.sourceText());
        String hash = TranslationNormalization.sha256Hex(normalised);

        String strategyName = StrategySelector.pick(normalised, req.contextHint(), 1);
        TranslationStrategy strategy = strategyRepo.findByName(strategyName)
                .orElseGet(() -> strategyRepo.findByName(StrategySelector.LABEL_BASIC)
                        .orElseThrow(() -> new IllegalStateException("No translation strategies seeded")));

        // Check cache first — return immediately if we have a result
        Optional<TranslationCacheRow> existing = cacheRepo.findByHashAndStrategy(hash, strategy.id());
        if (existing.isPresent()) {
            String best = existing.get().bestTranslation();
            if (best != null) {
                cacheLookupHits.incrementAndGet();
                log.info("translation: strategy={} model={} latency=0ms promptTokens=null evalTokens=null sourceLen={} success=true cacheHit=true",
                        strategy.name(), strategy.modelId(), normalised.length());
                return best;
            }
        }
        cacheLookupMisses.incrementAndGet();

        // Invoke Ollama synchronously
        String prompt = strategy.promptTemplate().replace("{jp}", normalised);
        Map<String, Object> options = null;
        if (strategy.optionsJson() != null) {
            try {
                options = objectMapper.readValue(strategy.optionsJson(), new TypeReference<>() {});
            } catch (Exception e) {
                log.warn("requestTranslationSync: failed to parse options_json: {}", e.getMessage());
            }
        }
        OllamaRequest ollamaReq = new OllamaRequest(
                strategy.modelId(), prompt, null, options, config.timeoutOrDefault());

        long startMs = System.currentTimeMillis();
        String englishText = null;
        String failureReason = null;
        OllamaResponse ollamaResp = null;

        try {
            ollamaResp = callOllama(ollamaReq);
            String raw = ollamaResp.responseText().trim();
            if (raw.startsWith("English:")) raw = raw.substring("English:".length()).trim();
            String cleaned = raw.isEmpty() ? null : raw;

            if (!TranslationWorker.isRefusal(cleaned) && !SanitizationDetector.isSanitized(normalised, cleaned)) {
                englishText = cleaned;
            } else {
                failureReason = "refused_or_sanitized";
            }
        } catch (OllamaException e) {
            failureReason = "adapter_error";
            log.warn("requestTranslationSync: Ollama error: {}", e.getMessage());
        }

        long latencyMs = System.currentTimeMillis() - startMs;
        String now = ISO_UTC.format(Instant.now());

        log.info("translation: strategy={} model={} latency={}ms promptTokens={} evalTokens={} sourceLen={} success={} cacheHit=false",
                strategy.name(), strategy.modelId(), latencyMs,
                ollamaResp != null ? ollamaResp.promptEvalCount() : null,
                ollamaResp != null ? ollamaResp.evalCount() : null,
                normalised.length(), englishText != null);

        // Write / update cache
        Long existingId = existing.map(TranslationCacheRow::id).orElse(null);
        if (existingId != null) {
            cacheRepo.updateOutcome(existingId, englishText, failureReason, null,
                    (int) latencyMs,
                    ollamaResp != null ? ollamaResp.promptEvalCount() : null,
                    ollamaResp != null ? ollamaResp.evalCount() : null,
                    ollamaResp != null ? ollamaResp.evalDurationNs() : null);
        } else {
            cacheRepo.insert(new TranslationCacheRow(
                    0, hash, normalised, strategy.id(),
                    englishText, null, null,
                    failureReason, null,
                    (int) latencyMs,
                    ollamaResp != null ? ollamaResp.promptEvalCount() : null,
                    ollamaResp != null ? ollamaResp.evalCount() : null,
                    ollamaResp != null ? ollamaResp.evalDurationNs() : null,
                    now));
        }

        // Note: this generic sync endpoint does not record stage-name suggestions —
        // there's no caller-supplied intent signal here. translateStageNameNow is the
        // dedicated stage-name sync path that records suggestions.
        return englishText;
    }

    @Override
    public Optional<String> translateStageNameNow(String kanji) {
        if (kanji == null || kanji.isBlank()) return Optional.empty();
        if (stageNameLookupRepo == null || stageNameSuggestionRepo == null) {
            log.debug("translateStageNameNow: stage-name repos not wired, returning empty");
            return Optional.empty();
        }

        String normalized = TranslationNormalization.normalize(kanji);

        // 1. Curated lookup (highest priority)
        Optional<String> curated = stageNameLookupRepo.findRomanizedFor(normalized);
        if (curated.isPresent()) {
            log.debug("translateStageNameNow: curated HIT for '{}'", normalized);
            return curated;
        }

        // 2. Existing suggestion (accepted or unreviewed)
        Optional<String> suggestion = stageNameSuggestionRepo.findLatestUsableSuggestion(normalized);
        if (suggestion.isPresent()) {
            log.debug("translateStageNameNow: suggestion HIT for '{}'", normalized);
            return suggestion;
        }

        // 3. Synchronous Ollama call
        TranslationStrategy strategy = strategyRepo.findByName("label_basic").orElse(null);
        if (strategy == null) {
            log.warn("translateStageNameNow: label_basic strategy not found for '{}'", normalized);
            return Optional.empty();
        }

        String hash = TranslationNormalization.sha256Hex(normalized);
        Optional<TranslationCacheRow> existingCache = cacheRepo.findByHashAndStrategy(hash, strategy.id());
        if (existingCache.isPresent() && existingCache.get().bestTranslation() != null) {
            cacheLookupHits.incrementAndGet();
            log.debug("translateStageNameNow: cache HIT for '{}'", normalized);
            return Optional.of(existingCache.get().bestTranslation());
        }
        cacheLookupMisses.incrementAndGet();

        String promptInput = explicitTermSubstitutor.substitute(normalized);
        String prompt = strategy.promptTemplate().replace("{jp}", promptInput);
        Map<String, Object> options = null;
        if (strategy.optionsJson() != null) {
            try {
                options = objectMapper.readValue(strategy.optionsJson(), new TypeReference<>() {});
            } catch (Exception e) {
                log.warn("translateStageNameNow: failed to parse options_json: {}", e.getMessage());
            }
        }
        OllamaRequest ollamaReq = new OllamaRequest(
                strategy.modelId(), prompt, null, options, config.timeoutOrDefault());

        long startMs = System.currentTimeMillis();
        String englishText = null;
        String failureReason = null;
        OllamaResponse ollamaResp = null;

        try {
            ollamaResp = callOllama(ollamaReq);
            String raw = ollamaResp.responseText().trim();
            if (raw.startsWith("English:")) raw = raw.substring("English:".length()).trim();
            String cleaned = raw.isEmpty() ? null : raw;

            if (!TranslationWorker.isRefusal(cleaned) && !SanitizationDetector.isSanitized(normalized, cleaned)) {
                englishText = cleaned;
            } else {
                failureReason = "refused_or_sanitized";
                log.info("translateStageNameNow: tier-1 refused/sanitized for '{}'", normalized);
            }
        } catch (OllamaException e) {
            failureReason = "adapter_error";
            log.warn("translateStageNameNow: Ollama error for '{}': {}", normalized, e.getMessage());
        }

        long latencyMs = System.currentTimeMillis() - startMs;
        String now = ISO_UTC.format(Instant.now());

        log.info("translation: strategy={} model={} latency={}ms promptTokens={} evalTokens={} sourceLen={} success={} cacheHit=false",
                strategy.name(), strategy.modelId(), latencyMs,
                ollamaResp != null ? ollamaResp.promptEvalCount() : null,
                ollamaResp != null ? ollamaResp.evalCount() : null,
                normalized.length(), englishText != null);

        // Write / update cache (records success or failure reason)
        Long existingCacheId = existingCache.map(TranslationCacheRow::id).orElse(null);
        if (existingCacheId != null) {
            cacheRepo.updateOutcome(existingCacheId, englishText, failureReason, null,
                    (int) latencyMs,
                    ollamaResp != null ? ollamaResp.promptEvalCount() : null,
                    ollamaResp != null ? ollamaResp.evalCount() : null,
                    ollamaResp != null ? ollamaResp.evalDurationNs() : null);
        } else {
            cacheRepo.insert(new TranslationCacheRow(
                    0, hash, normalized, strategy.id(),
                    englishText, null, null,
                    failureReason, null,
                    (int) latencyMs,
                    ollamaResp != null ? ollamaResp.promptEvalCount() : null,
                    ollamaResp != null ? ollamaResp.evalCount() : null,
                    ollamaResp != null ? ollamaResp.evalDurationNs() : null,
                    now));
        }

        if (englishText == null) {
            return Optional.empty();
        }

        // Write suggestion, dispatch fan-out, clear pending queue row
        try {
            long suggestionId = stageNameSuggestionRepo.recordSuggestionAndGetId(normalized, englishText, now);
            callbackDispatcher.dispatch("stage_name_suggestion", suggestionId, englishText);
            log.debug("translateStageNameNow: suggestion recorded for '{}' suggestionId={}", normalized, suggestionId);
        } catch (Exception e) {
            log.warn("translateStageNameNow: failed to record stage-name suggestion for '{}': {}",
                    normalized, e.getMessage());
        }

        try {
            int deleted = queueRepo.deletePendingForSource(strategy.id(), normalized);
            if (deleted > 0) {
                log.debug("translateStageNameNow: deleted {} pending queue rows for '{}'", deleted, normalized);
            }
        } catch (Exception e) {
            log.warn("translateStageNameNow: failed to delete pending queue rows for '{}': {}",
                    normalized, e.getMessage());
        }

        return Optional.of(englishText);
    }

    /**
     * Route a generate call through the {@link OllamaModelOrchestrator}, unwrapping orchestrator
     * exceptions so existing {@code catch (OllamaException ...)} blocks see the same semantics
     * they saw when calling {@link OllamaAdapter#generate} directly. The {@code .get(...)} timeout
     * is the request's own timeout plus a 30-second cushion.
     */
    private OllamaResponse callOllama(OllamaRequest req) {
        long timeoutSeconds = req.timeout() != null
                ? req.timeout().plusSeconds(30).getSeconds()
                : 330L;
        try {
            return orchestrator.submit(req.modelId(), req).get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof OllamaException oe) throw oe;
            if (cause instanceof RuntimeException re) throw re;
            throw new OllamaException("orchestrator failure", cause != null ? cause : e);
        } catch (TimeoutException e) {
            throw new OllamaException("orchestrator timeout after " + timeoutSeconds + "s", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OllamaException("orchestrator interrupted", e);
        }
    }
}
