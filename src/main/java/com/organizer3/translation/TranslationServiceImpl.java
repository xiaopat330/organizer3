package com.organizer3.translation;

import com.organizer3.translation.ollama.OllamaAdapter;
import com.organizer3.translation.repository.TranslationCacheRepository;
import com.organizer3.translation.repository.TranslationQueueRepository;
import com.organizer3.translation.repository.TranslationStrategyRepository;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

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

    final OllamaAdapter ollamaAdapter;
    final TranslationStrategyRepository strategyRepo;
    final TranslationCacheRepository cacheRepo;
    final TranslationQueueRepository queueRepo;
    final TranslationConfig config;
    final CallbackDispatcher callbackDispatcher;

    public TranslationServiceImpl(OllamaAdapter ollamaAdapter,
                                   TranslationStrategyRepository strategyRepo,
                                   TranslationCacheRepository cacheRepo,
                                   TranslationQueueRepository queueRepo,
                                   TranslationConfig config,
                                   CallbackDispatcher callbackDispatcher) {
        this.ollamaAdapter       = ollamaAdapter;
        this.strategyRepo        = strategyRepo;
        this.cacheRepo           = cacheRepo;
        this.queueRepo           = queueRepo;
        this.config              = config;
        this.callbackDispatcher  = callbackDispatcher;
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
        throw new UnsupportedOperationException(
                "resolveStageName is a Phase 5 feature — not yet implemented");
    }

    @Override
    public TranslationServiceStats stats() {
        return new TranslationServiceStats(
                cacheRepo.countTotal(),
                cacheRepo.countSuccessful(),
                cacheRepo.countFailed(),
                queueRepo.countByStatus(TranslationQueueRow.STATUS_PENDING),
                queueRepo.countByStatus(TranslationQueueRow.STATUS_IN_FLIGHT),
                queueRepo.countByStatus(TranslationQueueRow.STATUS_DONE),
                queueRepo.countByStatus(TranslationQueueRow.STATUS_FAILED),
                queueRepo.countTier2Pending()
        );
    }
}
