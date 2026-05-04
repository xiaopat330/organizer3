package com.organizer3.translation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.regex.Pattern;

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

    /** Heuristic: short text containing Japanese characters, likely a stage name. */
    private static final Pattern JP_CHAR = Pattern.compile("[\\u3041-\\u3096\\u30A1-\\u30FA\\u4E00-\\u9FFF]");

    final OllamaAdapter ollamaAdapter;
    final TranslationStrategyRepository strategyRepo;
    final TranslationCacheRepository cacheRepo;
    final TranslationQueueRepository queueRepo;
    final TranslationConfig config;
    final CallbackDispatcher callbackDispatcher;
    final HealthGate healthGate;
    final ObjectMapper objectMapper;
    final StageNameLookupRepository stageNameLookupRepo;
    final StageNameSuggestionRepository stageNameSuggestionRepo;

    /** Full constructor including HealthGate, ObjectMapper, and stage-name repos. */
    public TranslationServiceImpl(OllamaAdapter ollamaAdapter,
                                   TranslationStrategyRepository strategyRepo,
                                   TranslationCacheRepository cacheRepo,
                                   TranslationQueueRepository queueRepo,
                                   TranslationConfig config,
                                   CallbackDispatcher callbackDispatcher,
                                   HealthGate healthGate,
                                   ObjectMapper objectMapper,
                                   StageNameLookupRepository stageNameLookupRepo,
                                   StageNameSuggestionRepository stageNameSuggestionRepo) {
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
    }

    /** Backward-compatible constructor: creates a default HealthGate and ObjectMapper; no stage-name repos. */
    public TranslationServiceImpl(OllamaAdapter ollamaAdapter,
                                   TranslationStrategyRepository strategyRepo,
                                   TranslationCacheRepository cacheRepo,
                                   TranslationQueueRepository queueRepo,
                                   TranslationConfig config,
                                   CallbackDispatcher callbackDispatcher) {
        this(ollamaAdapter, strategyRepo, cacheRepo, queueRepo, config, callbackDispatcher,
                new HealthGate(ollamaAdapter, cacheRepo, config),
                new ObjectMapper(),
                null, null);
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
            log.debug("getCached: HIT (human corrected) hash={}", hash.substring(0, 8));
            return Optional.of(humanCorrected);
        }
        if (tier1Text != null) {
            log.debug("getCached: HIT (tier-1) hash={}", hash.substring(0, 8));
            return Optional.of(tier1Text);
        }
        if (tier2Text != null) {
            log.debug("getCached: HIT (tier-2) hash={}", hash.substring(0, 8));
            return Optional.of(tier2Text);
        }

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

        // Cache miss — enqueue for async processing
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

    /**
     * Returns true if the text is short (&lt;20 characters) and contains at least one
     * Japanese character — a heuristic for stage-name candidates.
     */
    static boolean looksLikeStageName(String text) {
        if (text == null || text.length() >= 20) return false;
        return JP_CHAR.matcher(text).find();
    }

    @Override
    public TranslationServiceStats stats() {
        long stageNameLookupSize = stageNameLookupRepo != null ? stageNameLookupRepo.countAll() : 0L;
        long stageNameSuggestionsUnreviewed = stageNameSuggestionRepo != null ? stageNameSuggestionRepo.countUnreviewed() : 0L;
        return new TranslationServiceStats(
                cacheRepo.countTotal(),
                cacheRepo.countSuccessful(),
                cacheRepo.countFailed(),
                queueRepo.countByStatus(TranslationQueueRow.STATUS_PENDING),
                queueRepo.countByStatus(TranslationQueueRow.STATUS_IN_FLIGHT),
                queueRepo.countByStatus(TranslationQueueRow.STATUS_DONE),
                queueRepo.countByStatus(TranslationQueueRow.STATUS_FAILED),
                queueRepo.countTier2Pending(),
                stageNameLookupSize,
                stageNameSuggestionsUnreviewed
        );
    }

    @Override
    public HealthStatus getHealth() {
        return healthGate.currentStatus();
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
                log.info("translation: strategy={} model={} latency=0ms promptTokens=null evalTokens=null sourceLen={} success=true cacheHit=true",
                        strategy.name(), strategy.modelId(), normalised.length());
                return best;
            }
        }

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
            ollamaResp = ollamaAdapter.generate(ollamaReq);
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

        // Stage-name suggestion hook: if the source looks like a stage name and we got a result,
        // record it for human review (parallel write, does not affect cache outcome).
        if (englishText != null && stageNameSuggestionRepo != null && looksLikeStageName(normalised)) {
            try {
                stageNameSuggestionRepo.recordSuggestion(normalised, englishText, now);
                log.debug("requestTranslationSync: stage-name suggestion recorded for '{}'", normalised);
            } catch (Exception e) {
                log.warn("requestTranslationSync: failed to record stage-name suggestion: {}", e.getMessage());
            }
        }

        return englishText;
    }
}
