package com.organizer3.utilities.task.javdb;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.organizer3.config.EnrichmentAssistConfig;
import com.organizer3.enrichment.ai.AssistPromptBuilder;
import com.organizer3.enrichment.ai.AssistResult;
import com.organizer3.enrichment.ai.EnsembleAssistCaller;
import com.organizer3.enrichment.ai.PostProcessingRules;
import com.organizer3.javdb.enrichment.EnrichmentReviewQueueRepository;
import com.organizer3.javdb.enrichment.EnrichmentReviewQueueRepository.BackfillCandidate;
import com.organizer3.ollama.OllamaModelOrchestrator;
import com.organizer3.translation.ollama.OllamaRequest;
import com.organizer3.translation.ollama.OllamaResponse;
import com.organizer3.utilities.task.Task;
import com.organizer3.utilities.task.TaskIO;
import com.organizer3.utilities.task.TaskInputs;
import com.organizer3.utilities.task.TaskSpec;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Phase 4 Track C / Phase 5 Track A — one-shot historical accuracy backfill for
 * the AI picker assist.
 *
 * <p>Replays the ensemble (primary + secondary models) against every already-resolved
 * {@code ambiguous} review-queue row that has a corresponding
 * {@code title_javdb_enrichment.javdb_slug} (the human-picked ground truth). Persists
 * the resulting AI suggestion to the same {@code ai_suggestion_*} columns and writes
 * a final JSON report at {@code <dataDir>/ai-assist-backfill-{date}.json}.
 *
 * <p><b>Phase 5 Track A — batched-by-model</b>: rather than calling the ensemble
 * row-by-row (phi → gemma → phi → gemma) this task now processes N rows of the
 * primary model first, then N rows of the secondary, votes inline, persists, and
 * advances to the next chunk. The orchestrator therefore performs at most
 * {@code 2 * ceil(rows / batchSize)} model switches instead of {@code 2 * rows}.
 * Chunk size is configurable via {@code enrichment.assist.backfillBatchSize}
 * (default 20).
 *
 * <p><b>Robustness</b>: per-row submission / parse failures are encoded as abstain
 * for that model and never poison the batch. The vote then routes the row as
 * {@code phi4_only}, {@code gemma_only}, or {@code both_abstain} as appropriate.
 * Cancellation is checked at chunk boundaries; partial reports are always written.
 */
@Slf4j
public final class AiAssistBackfillTask implements Task {

    public static final String ID = "enrichment.ai_assist_backfill";

    private static final TaskSpec SPEC = new TaskSpec(
            ID,
            "AI assist — backfill historical accuracy",
            "One-shot: replays the ensemble against already-resolved ambiguous rows to "
                    + "measure accuracy against the human-picked slug. Writes report to "
                    + "data/ai-assist-backfill-{date}.json. Long-running (multiple hours on "
                    + "the full corpus); respects cancellation and finalises the report on exit.",
            List.of()
    );

    /** Outcomes that count as a non-abstain AI pick for the {@code match_rate_on_picked} stat. */
    private static final Set<String> PICKED_OUTCOMES =
            Set.of("agreed", "phi4_only", "gemma_only", "agreed_with_override");

    private final EnrichmentReviewQueueRepository reviewQueueRepo;
    private final OllamaModelOrchestrator orchestrator;
    private final EnrichmentAssistConfig assistConfig;
    private final PostProcessingRules postProcessing;
    private final ObjectMapper objectMapper;
    private final Path dataDir;

    public AiAssistBackfillTask(EnrichmentReviewQueueRepository reviewQueueRepo,
                                OllamaModelOrchestrator orchestrator,
                                EnrichmentAssistConfig assistConfig,
                                PostProcessingRules postProcessing,
                                ObjectMapper objectMapper,
                                Path dataDir) {
        this.reviewQueueRepo = Objects.requireNonNull(reviewQueueRepo, "reviewQueueRepo");
        this.orchestrator    = Objects.requireNonNull(orchestrator, "orchestrator");
        this.assistConfig    = Objects.requireNonNull(assistConfig, "assistConfig");
        this.postProcessing  = Objects.requireNonNull(postProcessing, "postProcessing");
        this.objectMapper    = Objects.requireNonNull(objectMapper, "objectMapper");
        this.dataDir         = Objects.requireNonNull(dataDir, "dataDir");
    }

    @Override
    public TaskSpec spec() { return SPEC; }

    @Override
    public void run(TaskInputs taskInputs, TaskIO io) throws Exception {
        io.phaseStart("backfill", "Replaying ensemble on resolved ambiguous rows");

        List<BackfillCandidate> rows =
                reviewQueueRepo.listResolvedAmbiguousForBackfill(Integer.MAX_VALUE);
        int total = rows.size();
        io.phaseLog("backfill", "Loaded " + total + " resolved ambiguous row(s) with ground truth");

        int batchSize = Math.max(1, assistConfig.backfillBatchSize());
        io.phaseLog("backfill", "Batch size = " + batchSize);

        // Per-outcome counters (preserves insertion order in the report).
        Map<String, int[]> byOutcome = new LinkedHashMap<>();
        for (String key : List.of("agreed", "phi4_only", "gemma_only",
                                  "agreed_with_override", "conflict",
                                  "both_abstain", "error")) {
            byOutcome.put(key, new int[]{0, 0}); // [n, matches]
        }
        List<Map<String, Object>> mismatches = new ArrayList<>();

        int processed = 0;
        boolean cancelled = false;
        Path reportPath = reportPath();
        String primaryModel = assistConfig.primaryModel();
        String secondaryModel = assistConfig.secondaryModel();

        try {
            for (int chunkStart = 0; chunkStart < total; chunkStart += batchSize) {
                if (io.isCancellationRequested()) {
                    io.phaseLog("backfill", "Cancelled after " + processed + " of " + total + " row(s)");
                    cancelled = true;
                    break;
                }

                int chunkEnd = Math.min(chunkStart + batchSize, total);
                List<BackfillCandidate> chunk = rows.subList(chunkStart, chunkEnd);
                int chunkSize = chunk.size();

                log.info("[ai-assist] backfill batch: chunk={}..{}/{}",
                        chunkStart + 1, chunkEnd, total);
                io.phaseLog("backfill", "Batch chunk=" + (chunkStart + 1) + ".." + chunkEnd + "/" + total);

                // ── Build rowInputs for the chunk (prefilter applied per row). ───────
                List<AssistPromptBuilder.Input> rowInputs = new ArrayList<>(chunkSize);
                // null entry = row is structurally invalid (zero candidates); skipped in all phases.
                List<Boolean> invalid = new ArrayList<>(chunkSize);

                for (BackfillCandidate row : chunk) {
                    EnrichmentReviewQueueRepository.OpenRow openRow = synthesizeOpenRow(row);
                    EnrichmentReviewQueueRepository.AssistContext ctx =
                            reviewQueueRepo.findContextForAssist(row.titleId());

                    AssistPromptBuilder.Input rawInput;
                    try {
                        rawInput = EnsembleAssistCaller.materializeInput(
                                objectMapper, openRow, ctx.folderPath(), ctx.actressNames());
                    } catch (Exception e) {
                        log.warn("[ai-assist] backfill: materialize failed for queue_row_id={} code={}: {}",
                                row.id(), row.titleCode(), e.getMessage());
                        rowInputs.add(null);
                        invalid.add(true);
                        continue;
                    }

                    if (rawInput.candidates() == null || rawInput.candidates().isEmpty()) {
                        log.warn("[ai-assist] backfill: zero-candidate row queue_row_id={} code={}",
                                row.id(), row.titleCode());
                        rowInputs.add(null);
                        invalid.add(true);
                        continue;
                    }

                    List<AssistPromptBuilder.Input.Candidate> filtered =
                            postProcessing.prefilterCandidates(rawInput, rawInput.candidates());
                    AssistPromptBuilder.Input input = (filtered == rawInput.candidates())
                            ? rawInput
                            : new AssistPromptBuilder.Input(
                                    rawInput.code(), rawInput.label(), rawInput.folderPath(),
                                    rawInput.actressNames(), rawInput.linkedSlugs(), filtered);
                    rowInputs.add(input);
                    invalid.add(false);
                }

                // ── PHASE 1: submit all primary-model prompts, collect responses. ─
                List<CompletableFuture<OllamaResponse>> phi4Futures = new ArrayList<>(chunkSize);
                for (int i = 0; i < chunkSize; i++) {
                    AssistPromptBuilder.Input in = rowInputs.get(i);
                    if (in == null) { phi4Futures.add(null); continue; }
                    phi4Futures.add(safeSubmit(primaryModel, in, chunk.get(i).titleCode()));
                }
                List<Integer> phi4Picks = new ArrayList<>(chunkSize);
                List<String>  phi4Confs = new ArrayList<>(chunkSize);
                List<String>  phi4Reasons = new ArrayList<>(chunkSize);
                for (int i = 0; i < chunkSize; i++) {
                    if (rowInputs.get(i) == null) {
                        phi4Picks.add(null); phi4Confs.add(null); phi4Reasons.add(null);
                        continue;
                    }
                    Object[] parsed = awaitAndParse(phi4Futures.get(i), primaryModel,
                            chunk.get(i).titleCode(), rowInputs.get(i).candidates().size());
                    phi4Picks.add((Integer) parsed[0]);
                    phi4Confs.add((String) parsed[1]);
                    phi4Reasons.add((String) parsed[2]);
                }

                if (io.isCancellationRequested()) {
                    io.phaseLog("backfill", "Cancelled mid-chunk after primary phase");
                    cancelled = true;
                    break;
                }

                // ── PHASE 2: submit all secondary-model prompts, collect responses.
                List<CompletableFuture<OllamaResponse>> gemmaFutures = new ArrayList<>(chunkSize);
                for (int i = 0; i < chunkSize; i++) {
                    AssistPromptBuilder.Input in = rowInputs.get(i);
                    if (in == null) { gemmaFutures.add(null); continue; }
                    gemmaFutures.add(safeSubmit(secondaryModel, in, chunk.get(i).titleCode()));
                }
                List<Integer> gemmaPicks = new ArrayList<>(chunkSize);
                List<String>  gemmaConfs = new ArrayList<>(chunkSize);
                List<String>  gemmaReasons = new ArrayList<>(chunkSize);
                for (int i = 0; i < chunkSize; i++) {
                    if (rowInputs.get(i) == null) {
                        gemmaPicks.add(null); gemmaConfs.add(null); gemmaReasons.add(null);
                        continue;
                    }
                    Object[] parsed = awaitAndParse(gemmaFutures.get(i), secondaryModel,
                            chunk.get(i).titleCode(), rowInputs.get(i).candidates().size());
                    gemmaPicks.add((Integer) parsed[0]);
                    gemmaConfs.add((String) parsed[1]);
                    gemmaReasons.add((String) parsed[2]);
                }

                // ── PHASE 3: vote + apply post-processing + persist + accumulate. ─
                for (int i = 0; i < chunkSize; i++) {
                    BackfillCandidate row = chunk.get(i);
                    io.phaseProgress("backfill", chunkStart + i, total,
                            "queue_row_id=" + row.id() + " code=" + row.titleCode());

                    AssistResult result;
                    String outcomeKey;
                    if (rowInputs.get(i) == null) {
                        result = new AssistResult("error", null, null,
                                "materialize_failed_or_zero_candidates", null, null);
                        outcomeKey = "error";
                    } else {
                        try {
                            AssistPromptBuilder.Input input = rowInputs.get(i);
                            AssistResult preOverride = EnsembleAssistCaller.vote(
                                    phi4Picks.get(i), phi4Confs.get(i), phi4Reasons.get(i),
                                    gemmaPicks.get(i), gemmaConfs.get(i), gemmaReasons.get(i),
                                    input.candidates());
                            result = postProcessing.applyOverrides(input, preOverride, input.candidates());
                            outcomeKey = result.outcome();
                            log.info("[ai-assist] code={} outcome={} confidence={} phi4={} gemma={}",
                                    row.titleCode(), result.outcome(), result.confidence(),
                                    result.phi4Pick(), result.gemmaPick());
                        } catch (Exception e) {
                            log.warn("[ai-assist] backfill: vote/override failed for queue_row_id={} code={}: {}",
                                    row.id(), row.titleCode(), e.getMessage());
                            result = new AssistResult("error", null, null,
                                    "vote_failed: " + e.getMessage(), null, null);
                            outcomeKey = "error";
                        }
                    }

                    try {
                        reviewQueueRepo.setAiSuggestion(
                                row.id(),
                                result.suggestedSlug(),
                                result.outcome(),
                                result.reason(),
                                Instant.now());
                    } catch (Exception e) {
                        log.warn("[ai-assist] backfill: setAiSuggestion failed for queue_row_id={}: {}",
                                row.id(), e.getMessage());
                    }

                    int[] bucket = byOutcome.computeIfAbsent(outcomeKey, k -> new int[]{0, 0});
                    bucket[0] += 1;
                    String aiSlug = result.suggestedSlug();
                    String truth  = row.groundTruthSlug();
                    if (aiSlug != null && aiSlug.equals(truth)) {
                        bucket[1] += 1;
                    } else if (aiSlug != null && !aiSlug.equals(truth)) {
                        Map<String, Object> entry = new LinkedHashMap<>();
                        entry.put("queue_row_id",      row.id());
                        entry.put("title_code",        row.titleCode());
                        entry.put("ai_slug",           aiSlug);
                        entry.put("ground_truth_slug", truth);
                        entry.put("reason",            result.reason());
                        mismatches.add(entry);
                    }

                    processed++;
                }
            }
        } finally {
            // ALWAYS finalize the report — even on cancellation or unexpected throw upstream —
            // so partial runs leave a readable artifact.
            try {
                writeReport(reportPath, processed, byOutcome, mismatches, cancelled);
                io.phaseLog("backfill", "Report written to " + reportPath);
            } catch (IOException e) {
                io.phaseLog("backfill", "FAILED to write report: " + e.getMessage());
                log.warn("[ai-assist] backfill: failed to write report at {}: {}",
                        reportPath, e.getMessage());
            }
        }

        double matchRate = matchRateOnPicked(byOutcome);
        String summary = "processed=" + processed
                + " match_rate=" + formatPct(matchRate)
                + (cancelled ? " (cancelled)" : "")
                + " report=" + reportPath.getFileName();
        io.phaseEnd("backfill", "ok", summary);

        log.info("[ai-assist] backfill complete: processed={} match_rate={}% report={}",
                processed, formatPct(matchRate), reportPath);
    }

    // ------------------------------------------------------------------ helpers

    /** Submit one Ollama request; encode submit-time failures as a null future (abstain). */
    private CompletableFuture<OllamaResponse> safeSubmit(String model,
                                                         AssistPromptBuilder.Input input,
                                                         String code) {
        try {
            OllamaRequest req = EnsembleAssistCaller.buildRequest(
                    model, input, /* formatJson */ true,
                    EnsembleAssistCaller.KEEP_ALIVE_DEFAULT);
            return orchestrator.submit(model, req);
        } catch (RuntimeException e) {
            log.warn("[ai-assist] backfill: model {} submission failed for code={}: {}",
                    model, code, e.getMessage());
            return null;
        }
    }

    /** Await one model future and parse the JSON reply. Any failure → abstain triple. */
    private Object[] awaitAndParse(CompletableFuture<OllamaResponse> future,
                                   String model, String code, int numCandidates) {
        if (future == null) return new Object[]{null, null, "submit_failed"};
        OllamaResponse resp;
        try {
            resp = future.get(EnsembleAssistCaller.MODEL_FUTURE_TIMEOUT.toMillis(),
                    TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            log.warn("[ai-assist] backfill: model {} timed out for code={}", model, code);
            future.cancel(true);
            return new Object[]{null, null, "timeout"};
        } catch (ExecutionException ee) {
            log.warn("[ai-assist] backfill: model {} call failed for code={}: {}",
                    model, code,
                    ee.getCause() != null ? ee.getCause().toString() : ee.getMessage());
            return new Object[]{null, null, "call_failed"};
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.warn("[ai-assist] backfill: model {} interrupted for code={}", model, code);
            return new Object[]{null, null, "interrupted"};
        }
        String raw = resp != null ? resp.responseText() : null;
        return EnsembleAssistCaller.parseReply(objectMapper, model, code, raw, numCandidates);
    }

    /**
     * Builds a minimal {@link EnrichmentReviewQueueRepository.OpenRow} from a backfill candidate
     * so {@link EnsembleAssistCaller#materializeInput} can read {@code id}, {@code titleCode},
     * and {@code detail}.
     */
    private static EnrichmentReviewQueueRepository.OpenRow synthesizeOpenRow(BackfillCandidate c) {
        return new EnrichmentReviewQueueRepository.OpenRow(
                c.id(),
                c.titleId(),
                c.titleCode(),
                null,                   // slug — unused
                "ambiguous",
                null,                   // resolverSource — unused
                null,                   // createdAt — unused
                c.detail(),
                null, null, null, null, false);
    }

    private Path reportPath() {
        String date = LocalDate.now(ZoneOffset.UTC).toString();
        return dataDir.resolve("ai-assist-backfill-" + date + ".json");
    }

    private void writeReport(Path path,
                             int processed,
                             Map<String, int[]> byOutcome,
                             List<Map<String, Object>> mismatches,
                             boolean cancelled) throws IOException {
        Files.createDirectories(path.getParent());

        Map<String, Object> outcomes = new LinkedHashMap<>();
        for (Map.Entry<String, int[]> e : byOutcome.entrySet()) {
            int[] v = e.getValue();
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("n", v[0]);
            b.put("matches", v[1]);
            outcomes.put(e.getKey(), b);
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("completed_at",         Instant.now().toString());
        report.put("cancelled",            cancelled);
        report.put("processed",            processed);
        report.put("by_outcome",           outcomes);
        report.put("match_rate_on_picked", matchRateOnPicked(byOutcome));
        report.put("mismatches",           mismatches);

        objectMapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), report);
    }

    private static double matchRateOnPicked(Map<String, int[]> byOutcome) {
        int n = 0;
        int matches = 0;
        for (Map.Entry<String, int[]> e : byOutcome.entrySet()) {
            if (!PICKED_OUTCOMES.contains(e.getKey())) continue;
            n       += e.getValue()[0];
            matches += e.getValue()[1];
        }
        if (n == 0) return 0.0;
        return ((double) matches) / ((double) n);
    }

    private static String formatPct(double rate) {
        return String.format("%.1f", rate * 100.0);
    }
}
