package com.organizer3.enrichment.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.organizer3.config.EnrichmentAssistConfig;
import com.organizer3.javdb.enrichment.EnrichmentReviewQueueRepository;
import com.organizer3.javdb.enrichment.EnrichmentReviewQueueRepository.AssistContext;
import com.organizer3.javdb.enrichment.EnrichmentReviewQueueRepository.OpenRow;
import com.organizer3.ollama.OllamaModelOrchestrator;
import com.organizer3.translation.ollama.OllamaResponse;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Reusable two-pass batched ensemble processor for the AI picker assist.
 *
 * <p>Processes a list of open review-queue rows in chunks. Within each chunk:
 * <ol>
 *   <li>All primary-model (phi4) calls are submitted and awaited — pass 1.</li>
 *   <li>All secondary-model (gemma3) calls are submitted and awaited — pass 2.</li>
 *   <li>Vote, apply post-processing overrides, persist, optionally auto-apply.</li>
 * </ol>
 *
 * <p>Batching by model minimises Ollama model-switch overhead — at most
 * {@code 2 * ceil(rows / chunkSize)} switches instead of {@code 2 * rows}.
 *
 * <p>Per-row failures (materialize error, model timeout, vote failure) are encoded as
 * an {@code error} outcome and persisted; they never poison the rest of the batch.
 *
 * <p>Used by {@link com.organizer3.utilities.task.javdb.AiAssistBackfillTask} and the
 * bulk endpoint in {@code WorkflowRoutes}. Backfill passes {@code autoApplier = null}
 * to skip re-application of already-resolved rows; the bulk endpoint passes the live
 * instance so newly agreed rows are applied immediately.
 */
@Slf4j
public class BatchedEnsembleProcessor {

    /** Outcome counts returned to callers. */
    public record ProcessingResult(int processed, int agreed, int autoApplied, int errors) {}

    /**
     * Sink for progress updates. Callers may also react to per-row lifecycle events.
     * All methods have no-op defaults so callers only override what they need.
     */
    public interface ProgressSink {
        /** Called after each row finishes (within a chunk). */
        default void update(int done, int total, String detail) {}

        /** Called at the start of each chunk with a human-readable log line. */
        default void log(String line) {}

        /** Called when a row transitions from queued to actively processing. */
        default void rowStarted(long rowId, String code) {}

        /**
         * Called after vote+persist for a row, whether success or error.
         * {@code result} is never null; error rows have outcome {@code "error"}.
         */
        default void rowProcessed(long rowId, String code, AssistResult result) {}
    }

    /** Returns {@code true} when the caller wants to abort the batch. */
    public interface CancellationCheck {
        boolean isCancelled();
    }

    private final EnrichmentReviewQueueRepository reviewQueueRepo;
    private final OllamaModelOrchestrator orchestrator;
    private final EnrichmentAssistConfig assistConfig;
    private final PostProcessingRules postProcessing;
    private final ObjectMapper objectMapper;
    private final EnrichmentAutoApplier autoApplier; // null → skip auto-apply (backfill path)

    public BatchedEnsembleProcessor(EnrichmentReviewQueueRepository reviewQueueRepo,
                                    OllamaModelOrchestrator orchestrator,
                                    EnrichmentAssistConfig assistConfig,
                                    PostProcessingRules postProcessing,
                                    ObjectMapper objectMapper,
                                    EnrichmentAutoApplier autoApplier) {
        this.reviewQueueRepo = Objects.requireNonNull(reviewQueueRepo, "reviewQueueRepo");
        this.orchestrator    = Objects.requireNonNull(orchestrator, "orchestrator");
        this.assistConfig    = Objects.requireNonNull(assistConfig, "assistConfig");
        this.postProcessing  = Objects.requireNonNull(postProcessing, "postProcessing");
        this.objectMapper    = Objects.requireNonNull(objectMapper, "objectMapper");
        this.autoApplier     = autoApplier; // nullable
    }

    /**
     * Process all {@code rows} in batched two-pass fashion.
     *
     * @param rows       rows to process; must be non-null (may be empty)
     * @param sink       progress + lifecycle callbacks; use a no-op instance if unwanted
     * @param cancelled  checked at chunk boundaries; abort is cooperative
     * @return aggregate counts over the entire run
     */
    public ProcessingResult process(List<OpenRow> rows,
                                    ProgressSink sink,
                                    CancellationCheck cancelled) {
        int total     = rows.size();
        int processed = 0;
        int agreed    = 0;
        int autoApplied = 0;
        int errors    = 0;

        int chunkSize    = Math.max(1, assistConfig.backfillBatchSize());
        String primary   = assistConfig.primaryModel();
        String secondary = assistConfig.secondaryModel();

        for (int chunkStart = 0; chunkStart < total; chunkStart += chunkSize) {
            if (cancelled.isCancelled()) break;

            int chunkEnd = Math.min(chunkStart + chunkSize, total);
            List<OpenRow> chunk = rows.subList(chunkStart, chunkEnd);
            int n = chunk.size();

            String chunkLine = "Batch chunk=" + (chunkStart + 1) + ".." + chunkEnd + "/" + total;
            log.info("[ai-assist] {}", chunkLine);
            sink.log(chunkLine);

            // ── Materialize prompt inputs for the chunk. ─────────────────────
            List<AssistPromptBuilder.Input> inputs = new ArrayList<>(n);
            for (OpenRow row : chunk) {
                AssistContext ctx = reviewQueueRepo.findContextForAssist(row.titleId());
                AssistPromptBuilder.Input rawInput;
                try {
                    rawInput = EnsembleAssistCaller.materializeInput(
                            objectMapper, row, ctx.folderPath(), ctx.actressNames());
                } catch (Exception e) {
                    log.warn("[ai-assist] materialize failed id={} code={}: {}",
                            row.id(), row.titleCode(), e.getMessage());
                    inputs.add(null);
                    continue;
                }
                if (rawInput.candidates() == null || rawInput.candidates().isEmpty()) {
                    log.warn("[ai-assist] zero-candidate row id={} code={}", row.id(), row.titleCode());
                    inputs.add(null);
                    continue;
                }
                List<AssistPromptBuilder.Input.Candidate> filtered =
                        postProcessing.prefilterCandidates(rawInput, rawInput.candidates());
                inputs.add(filtered == rawInput.candidates()
                        ? rawInput
                        : new AssistPromptBuilder.Input(
                                rawInput.code(), rawInput.label(), rawInput.folderPath(),
                                rawInput.actressNames(), rawInput.linkedSlugs(), filtered));
            }

            // ── Pass 1: submit all primary-model prompts. ─────────────────────
            List<CompletableFuture<OllamaResponse>> phi4Futures = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                AssistPromptBuilder.Input in = inputs.get(i);
                phi4Futures.add(in == null ? null : safeSubmit(primary, in, chunk.get(i).titleCode()));
            }
            List<Object[]> phi4Results = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                if (inputs.get(i) == null) { phi4Results.add(abstain("invalid")); continue; }
                phi4Results.add(awaitAndParse(phi4Futures.get(i), primary,
                        chunk.get(i).titleCode(), inputs.get(i).candidates().size()));
            }

            if (cancelled.isCancelled()) break;

            // ── Pass 2: submit all secondary-model prompts. ───────────────────
            List<CompletableFuture<OllamaResponse>> gemmaFutures = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                AssistPromptBuilder.Input in = inputs.get(i);
                gemmaFutures.add(in == null ? null : safeSubmit(secondary, in, chunk.get(i).titleCode()));
            }
            List<Object[]> gemmaResults = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                if (inputs.get(i) == null) { gemmaResults.add(abstain("invalid")); continue; }
                gemmaResults.add(awaitAndParse(gemmaFutures.get(i), secondary,
                        chunk.get(i).titleCode(), inputs.get(i).candidates().size()));
            }

            // ── Vote + persist + notify sink. ─────────────────────────────────
            for (int i = 0; i < n; i++) {
                OpenRow row = chunk.get(i);
                sink.rowStarted(row.id(), row.titleCode());

                AssistResult result;
                if (inputs.get(i) == null) {
                    result = new AssistResult("error", null, null,
                            "materialize_failed_or_zero_candidates", null, null);
                } else {
                    try {
                        AssistPromptBuilder.Input input = inputs.get(i);
                        Object[] p4 = phi4Results.get(i);
                        Object[] gm = gemmaResults.get(i);
                        AssistResult preOverride = EnsembleAssistCaller.vote(
                                (Integer) p4[0], (String) p4[1], (String) p4[2],
                                (Integer) gm[0], (String) gm[1], (String) gm[2],
                                input.candidates());
                        result = postProcessing.applyOverrides(input, preOverride, input.candidates());
                        log.info("[ai-assist] id={} code={} outcome={} confidence={}",
                                row.id(), row.titleCode(), result.outcome(), result.confidence());
                    } catch (Exception e) {
                        log.warn("[ai-assist] vote/override failed id={} code={}: {}",
                                row.id(), row.titleCode(), e.getMessage());
                        result = new AssistResult("error", null, null,
                                "vote_failed: " + e.getMessage(), null, null);
                    }
                }

                try {
                    reviewQueueRepo.setAiSuggestion(
                            row.id(),
                            result.suggestedSlug(),
                            result.outcome(),
                            result.reason(),
                            Instant.now(),
                            result.phi4Slug(),
                            result.gemmaSlug());
                } catch (Exception e) {
                    log.warn("[ai-assist] setAiSuggestion failed id={}: {}", row.id(), e.getMessage());
                }

                if ("agreed".equals(result.outcome()) || "agreed_with_override".equals(result.outcome())) {
                    agreed++;
                    if (autoApplier != null) {
                        try {
                            OpenRow updated = reviewQueueRepo.findOpenById(row.id()).orElse(row);
                            boolean applied = autoApplier.apply(updated);
                            if (applied) {
                                autoApplied++;
                                log.info("[ai-assist] auto-applied id={} code={} slug={}",
                                        row.id(), row.titleCode(), result.suggestedSlug());
                            } else {
                                log.warn("[ai-assist] auto-apply failed id={} code={} slug={}",
                                        row.id(), row.titleCode(), result.suggestedSlug());
                            }
                        } catch (Exception e) {
                            log.warn("[ai-assist] auto-apply threw id={}: {}", row.id(), e.getMessage());
                        }
                    }
                }

                if ("error".equals(result.outcome())) errors++;

                processed++;
                sink.rowProcessed(row.id(), row.titleCode(), result);
                sink.update(processed, total, "id=" + row.id() + " code=" + row.titleCode());
            }
        }

        return new ProcessingResult(processed, agreed, autoApplied, errors);
    }

    // ── private helpers ───────────────────────────────────────────────────────

    private CompletableFuture<OllamaResponse> safeSubmit(String model,
                                                          AssistPromptBuilder.Input input,
                                                          String code) {
        try {
            return orchestrator.submit(model,
                    EnsembleAssistCaller.buildRequest(model, input, true,
                            EnsembleAssistCaller.KEEP_ALIVE_DEFAULT));
        } catch (RuntimeException e) {
            log.warn("[ai-assist] model {} submit failed code={}: {}", model, code, e.getMessage());
            return null;
        }
    }

    private Object[] awaitAndParse(CompletableFuture<OllamaResponse> future,
                                   String model, String code, int numCandidates) {
        if (future == null) return abstain("submit_failed");
        OllamaResponse resp;
        try {
            resp = future.get(EnsembleAssistCaller.MODEL_FUTURE_TIMEOUT.toMillis(),
                    TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            log.warn("[ai-assist] model {} timed out code={}", model, code);
            future.cancel(true);
            return abstain("timeout");
        } catch (ExecutionException ee) {
            log.warn("[ai-assist] model {} call failed code={}: {}",
                    model, code, ee.getCause() != null ? ee.getCause().toString() : ee.getMessage());
            return abstain("call_failed");
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.warn("[ai-assist] model {} interrupted code={}", model, code);
            return abstain("interrupted");
        }
        String raw = resp != null ? resp.responseText() : null;
        return EnsembleAssistCaller.parseReply(objectMapper, model, code, raw, numCandidates);
    }

    private static Object[] abstain(String reason) {
        return new Object[]{null, null, reason};
    }
}
