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
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Batched sweeper that drains {@code tier_2_pending} queue rows through the tier-2 (qwen2.5:14b)
 * model.
 *
 * <p>Per Appendix E.2 of spec/PROPOSAL_TRANSLATION_SERVICE.md: per-item single tier-2 retries
 * are economically unviable (~82 s overhead per item for model swap). Tier-2 work is always
 * batched: threshold = {@code tier2BatchSize} items OR {@code tier2MaxWaitMinutes} timeout,
 * whichever comes first.
 *
 * <p>Drain logic:
 * <ol>
 *   <li>Count {@code tier_2_pending} rows.</li>
 *   <li>If count &ge; threshold OR oldest row is &gt; maxWait: drain all pending rows.</li>
 *   <li>For each row: look up its tier-1 strategy's {@code tier2_strategy_id}, call Ollama with
 *       that strategy, write the result to a NEW cache row keyed on the tier-2 strategy id.</li>
 *   <li>On success: dispatch callback, mark queue row {@code done}.</li>
 *   <li>On tier-2 failure: write cache row with {@code failure_reason='sanitized_both_tiers'},
 *       mark queue row {@code failed}.</li>
 * </ol>
 *
 * <p>Tier-2 work is NEVER run inline in the regular worker loop.
 */
@Slf4j
public class Tier2BatchSweeper implements Runnable {

    private static final DateTimeFormatter ISO_UTC =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private final OllamaAdapter ollamaAdapter;
    private final TranslationStrategyRepository strategyRepo;
    private final TranslationCacheRepository cacheRepo;
    private final TranslationQueueRepository queueRepo;
    private final CallbackDispatcher callbackDispatcher;
    private final TranslationConfig config;
    private final ObjectMapper json;
    private final OllamaModelState modelState;

    public Tier2BatchSweeper(OllamaAdapter ollamaAdapter,
                              TranslationStrategyRepository strategyRepo,
                              TranslationCacheRepository cacheRepo,
                              TranslationQueueRepository queueRepo,
                              CallbackDispatcher callbackDispatcher,
                              TranslationConfig config,
                              ObjectMapper json,
                              OllamaModelState modelState) {
        this.ollamaAdapter      = ollamaAdapter;
        this.strategyRepo       = strategyRepo;
        this.cacheRepo          = cacheRepo;
        this.queueRepo          = queueRepo;
        this.callbackDispatcher = callbackDispatcher;
        this.config             = config;
        this.json               = json;
        this.modelState         = modelState;
    }

    @Override
    public void run() {
        try {
            int count = queueRepo.countTier2Pending();
            if (count == 0) {
                log.debug("Tier2BatchSweeper: no tier_2_pending rows");
                return;
            }

            boolean shouldDrain = false;
            String triggerReason = null;

            int batchThreshold = config.tier2BatchSizeOrDefault();
            if (count >= batchThreshold) {
                shouldDrain = true;
                triggerReason = "batch threshold reached (" + count + " >= " + batchThreshold + ")";
            }

            if (!shouldDrain) {
                String oldestAt = queueRepo.oldestTier2PendingSubmittedAt();
                if (oldestAt != null) {
                    long waitMaxSeconds = (long) config.tier2MaxWaitMinutesOrDefault() * 60;
                    Instant oldestInstant = Instant.parse(oldestAt);
                    long ageSeconds = Instant.now().getEpochSecond() - oldestInstant.getEpochSecond();
                    if (ageSeconds >= waitMaxSeconds) {
                        shouldDrain = true;
                        triggerReason = "max wait exceeded (oldest row " + ageSeconds + "s old)";
                    }
                }
            }

            if (!shouldDrain) {
                log.debug("Tier2BatchSweeper: {} tier_2_pending rows below threshold, skipping", count);
                return;
            }

            log.info("Tier2BatchSweeper: draining {} tier_2_pending rows — {}", count, triggerReason);
            drainAll();

        } catch (Exception e) {
            log.error("Tier2BatchSweeper: unexpected error during sweep", e);
        }
    }

    /**
     * Drain all current tier_2_pending rows through tier-2.
     * Package-private for direct test invocation.
     */
    void drainAll() {
        List<TranslationQueueRow> pending = queueRepo.findTier2Pending();
        log.info("Tier2BatchSweeper: processing {} tier_2_pending rows", pending.size());

        for (TranslationQueueRow row : pending) {
            try {
                processRow(row);
            } catch (Exception e) {
                log.error("Tier2BatchSweeper: error processing row={}, skipping", row.id(), e);
            }
        }
    }

    private void processRow(TranslationQueueRow row) {
        String now = ISO_UTC.format(Instant.now());

        // Look up the tier-2 strategy via the tier-1 strategy's tier2_strategy_id
        Optional<TranslationStrategy> tier2StrategyOpt =
                StrategySelector.pickFallback(row.strategyId(), strategyRepo);

        if (tier2StrategyOpt.isEmpty()) {
            log.warn("Tier2BatchSweeper: row={} strategy={} has no tier-2 fallback configured — marking failed",
                    row.id(), row.strategyId());
            queueRepo.markFailed(row.id(), "no tier-2 fallback configured", now);
            return;
        }

        TranslationStrategy tier2Strategy = tier2StrategyOpt.get();
        String prompt = tier2Strategy.promptTemplate().replace("{jp}", row.sourceText());

        Map<String, Object> options = null;
        if (tier2Strategy.optionsJson() != null) {
            try {
                options = json.readValue(tier2Strategy.optionsJson(), new TypeReference<>() {});
            } catch (Exception e) {
                log.warn("Tier2BatchSweeper: failed to parse options_json for strategy '{}': {}",
                        tier2Strategy.name(), e.getMessage());
            }
        }

        OllamaRequest ollamaReq = new OllamaRequest(
                tier2Strategy.modelId(),
                prompt,
                null,
                options,
                config.timeoutOrDefault()
        );

        long startMs = System.currentTimeMillis();
        String englishText = null;
        String failureReason = null;
        OllamaResponse ollamaResp = null;

        try {
            ollamaResp = ollamaAdapter.generate(ollamaReq);
            modelState.setCurrentModelId(tier2Strategy.modelId());

            String raw = ollamaResp.responseText().trim();
            if (raw.startsWith("English:")) {
                raw = raw.substring("English:".length()).trim();
            }
            String cleaned = raw.isEmpty() ? null : raw;

            // Check refusal
            if (cleaned == null || cleaned.isBlank() || isRefusal(cleaned)) {
                failureReason = "sanitized_both_tiers";
                log.info("Tier2BatchSweeper: row={} tier-2 REFUSED — permanent failure", row.id());
            // Check sanitization
            } else if (SanitizationDetector.isSanitized(row.sourceText(), cleaned)) {
                failureReason = "sanitized_both_tiers";
                log.info("Tier2BatchSweeper: row={} tier-2 SANITIZED — permanent failure", row.id());
            } else {
                englishText = cleaned;
            }
        } catch (OllamaException e) {
            failureReason = "unreachable";
            log.warn("Tier2BatchSweeper: Ollama error for strategy '{}' row={}: {}",
                    tier2Strategy.name(), row.id(), e.getMessage());
        }

        long latencyMs = System.currentTimeMillis() - startMs;

        // Write a NEW cache row keyed on the tier-2 strategy id
        String hash = TranslationNormalization.hashOf(row.sourceText());
        Optional<TranslationCacheRow> existing = cacheRepo.findByHashAndStrategy(hash, tier2Strategy.id());

        if (existing.isPresent()) {
            cacheRepo.updateOutcome(existing.get().id(),
                    englishText, failureReason, null,
                    (int) latencyMs,
                    ollamaResp != null ? ollamaResp.promptEvalCount() : null,
                    ollamaResp != null ? ollamaResp.evalCount() : null,
                    ollamaResp != null ? ollamaResp.evalDurationNs() : null);
        } else {
            TranslationCacheRow cacheRow = new TranslationCacheRow(
                    0, hash, row.sourceText(), tier2Strategy.id(),
                    englishText, null, null,
                    failureReason, null,
                    (int) latencyMs,
                    ollamaResp != null ? ollamaResp.promptEvalCount() : null,
                    ollamaResp != null ? ollamaResp.evalCount() : null,
                    ollamaResp != null ? ollamaResp.evalDurationNs() : null,
                    now
            );
            cacheRepo.insert(cacheRow);
        }

        log.info("Tier2BatchSweeper: row={} tier2={} latency={}ms success={}",
                row.id(), tier2Strategy.name(), latencyMs, englishText != null);

        if (englishText != null) {
            callbackDispatcher.dispatch(row.callbackKind(), row.callbackId(), englishText);
            queueRepo.markDone(row.id(), now);
        } else {
            // Both tiers failed — permanent failure
            queueRepo.markFailed(row.id(), failureReason != null ? failureReason : "tier-2 failed", now);
            log.warn("Tier2BatchSweeper: row={} permanently failed ({})", row.id(), failureReason);
        }
    }

    /**
     * Check if a tier-2 output is a hard refusal (same heuristic as tier-1 worker).
     */
    private static boolean isRefusal(String text) {
        if (text == null || text.isBlank()) return true;
        return text.length() <= 80 && REFUSAL_PATTERN.matcher(text).find();
    }

    private static final java.util.regex.Pattern REFUSAL_PATTERN = java.util.regex.Pattern.compile(
            "cannot|unable to|sorry|i am programmed|safety|refuse|not appropriate|i can't",
            java.util.regex.Pattern.CASE_INSENSITIVE
    );
}
