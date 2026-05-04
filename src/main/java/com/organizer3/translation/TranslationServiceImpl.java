package com.organizer3.translation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.organizer3.translation.ollama.OllamaAdapter;
import com.organizer3.translation.ollama.OllamaException;
import com.organizer3.translation.ollama.OllamaRequest;
import com.organizer3.translation.ollama.OllamaResponse;
import com.organizer3.translation.repository.TranslationCacheRepository;
import com.organizer3.translation.repository.TranslationStrategyRepository;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Phase 1 implementation of {@link TranslationService}.
 *
 * <p>All translation is synchronous (in-thread). Callers wanting non-blocking behaviour must
 * invoke {@link #requestTranslation} from a background thread. The async queue (Phase 2) will
 * make this automatic.
 *
 * <p>Strategy selection delegates to {@link StrategySelector}. Cache key derivation
 * delegates to {@link TranslationNormalization}.
 */
@Slf4j
public class TranslationServiceImpl implements TranslationService {

    private static final DateTimeFormatter ISO_UTC =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private final OllamaAdapter ollamaAdapter;
    private final TranslationStrategyRepository strategyRepo;
    private final TranslationCacheRepository cacheRepo;
    private final TranslationConfig config;
    private final ObjectMapper json;

    public TranslationServiceImpl(OllamaAdapter ollamaAdapter,
                                   TranslationStrategyRepository strategyRepo,
                                   TranslationCacheRepository cacheRepo,
                                   TranslationConfig config,
                                   ObjectMapper json) {
        this.ollamaAdapter = ollamaAdapter;
        this.strategyRepo  = strategyRepo;
        this.cacheRepo     = cacheRepo;
        this.config        = config;
        this.json          = json;
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
            // If this is a transient failure whose retry_after has passed, re-attempt
            if (row.failureReason() != null && !"unreachable".equals(row.failureReason())) {
                log.debug("requestTranslation: permanent failure cached for hash={}", hash.substring(0, 8));
                return row.id();
            }
            if (row.retryAfter() != null) {
                Instant retryAt = Instant.parse(row.retryAfter());
                if (Instant.now().isBefore(retryAt)) {
                    log.debug("requestTranslation: transient failure cached, retry_after not reached, skipping");
                    return row.id();
                }
                // retry_after elapsed — fall through to re-attempt
                log.info("requestTranslation: retrying after transient failure hash={}", hash.substring(0, 8));
            } else if (row.bestTranslation() != null) {
                log.debug("requestTranslation: cache HIT strategy={} hash={}", strategy.name(), hash.substring(0, 8));
                return row.id();
            }
        }

        // Build and execute the translation
        return executeTranslation(normalised, hash, strategy, req, existing.map(TranslationCacheRow::id).orElse(null));
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
                cacheRepo.countFailed()
        );
    }

    // -------------------------------------------------------------------------
    // Internal translation execution
    // -------------------------------------------------------------------------

    private long executeTranslation(String normalised,
                                     String hash,
                                     TranslationStrategy strategy,
                                     TranslationRequest req,
                                     Long existingRowId) {
        String prompt = buildPrompt(strategy.promptTemplate(), normalised);

        Map<String, Object> options = null;
        if (strategy.optionsJson() != null) {
            try {
                options = json.readValue(strategy.optionsJson(), new TypeReference<>() {});
            } catch (Exception e) {
                log.warn("requestTranslation: failed to parse options_json for strategy '{}': {}",
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
            // Strip a leading "English:" marker if the model echoed it
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
            log.warn("requestTranslation: Ollama error for strategy '{}': {}", strategy.name(), e.getMessage());
        }

        long latencyMs = System.currentTimeMillis() - startMs;
        String cachedAt = ISO_UTC.format(Instant.now());

        if (existingRowId != null) {
            cacheRepo.updateOutcome(existingRowId,
                    englishText, failureReason, retryAfter,
                    (int) latencyMs,
                    ollamaResp != null ? ollamaResp.promptEvalCount() : null,
                    ollamaResp != null ? ollamaResp.evalCount() : null,
                    ollamaResp != null ? ollamaResp.evalDurationNs() : null);
            log.info("requestTranslation: updated cache row={} strategy={} latency={}ms success={}",
                    existingRowId, strategy.name(), latencyMs, englishText != null);
            return existingRowId;
        } else {
            TranslationCacheRow row = new TranslationCacheRow(
                    0, // id assigned by DB
                    hash,
                    normalised,
                    strategy.id(),
                    englishText,
                    null, null, // human_corrected_text, human_corrected_at
                    failureReason,
                    retryAfter,
                    (int) latencyMs,
                    ollamaResp != null ? ollamaResp.promptEvalCount() : null,
                    ollamaResp != null ? ollamaResp.evalCount() : null,
                    ollamaResp != null ? ollamaResp.evalDurationNs() : null,
                    cachedAt
            );
            long id = cacheRepo.insert(row);
            log.info("requestTranslation: cached row={} strategy={} latency={}ms success={}",
                    id, strategy.name(), latencyMs, englishText != null);
            return id;
        }
    }

    private static String buildPrompt(String template, String sourceText) {
        return template.replace("{jp}", sourceText);
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
