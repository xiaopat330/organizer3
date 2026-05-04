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

        // Try all active strategies — return the first hit with a best translation
        List<TranslationStrategy> strategies = strategyRepo.findAllActive();
        for (TranslationStrategy strategy : strategies) {
            Optional<TranslationCacheRow> row = cacheRepo.findByHashAndStrategy(hash, strategy.id());
            if (row.isPresent()) {
                String best = row.get().bestTranslation();
                if (best != null) {
                    log.debug("getCached: HIT strategy={} hash={}", strategy.name(), hash.substring(0, 8));
                    return Optional.of(best);
                }
            }
        }
        log.debug("getCached: MISS hash={}", hash.substring(0, 8));
        return Optional.empty();
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
                queueRepo.countByStatus(TranslationQueueRow.STATUS_FAILED)
        );
    }
}
