package com.organizer3.utilities.task.javdb;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.organizer3.config.EnrichmentAssistConfig;
import com.organizer3.enrichment.ai.AssistResult;
import com.organizer3.enrichment.ai.BatchedEnsembleProcessor;
import com.organizer3.enrichment.ai.PostProcessingRules;
import com.organizer3.javdb.enrichment.EnrichmentReviewQueueRepository;
import com.organizer3.javdb.enrichment.EnrichmentReviewQueueRepository.BackfillCandidate;
import com.organizer3.ollama.OllamaModelOrchestrator;
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
 * <p><b>Phase 5 Track A — batched-by-model</b>: delegated to
 * {@link BatchedEnsembleProcessor}, which processes N rows of the primary model first,
 * then N rows of the secondary, votes inline, persists, and advances to the next chunk.
 * The orchestrator therefore performs at most {@code 2 * ceil(rows / batchSize)} model
 * switches instead of {@code 2 * rows}. Chunk size is configurable via
 * {@code enrichment.assist.backfillBatchSize} (default 20).
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
    private final BatchedEnsembleProcessor processor;
    private final ObjectMapper objectMapper;
    private final Path dataDir;

    public AiAssistBackfillTask(EnrichmentReviewQueueRepository reviewQueueRepo,
                                OllamaModelOrchestrator orchestrator,
                                EnrichmentAssistConfig assistConfig,
                                PostProcessingRules postProcessing,
                                ObjectMapper objectMapper,
                                Path dataDir) {
        this.reviewQueueRepo = Objects.requireNonNull(reviewQueueRepo, "reviewQueueRepo");
        this.objectMapper    = Objects.requireNonNull(objectMapper, "objectMapper");
        this.dataDir         = Objects.requireNonNull(dataDir, "dataDir");
        // autoApplier is null — backfill rows are already resolved; re-applying would be wrong.
        this.processor = new BatchedEnsembleProcessor(
                reviewQueueRepo, orchestrator, assistConfig, postProcessing, objectMapper, null);
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

        // Per-outcome counters (preserves insertion order in the report).
        Map<String, int[]> byOutcome = new LinkedHashMap<>();
        for (String key : List.of("agreed", "phi4_only", "gemma_only",
                                  "agreed_with_override", "conflict",
                                  "both_abstain", "error")) {
            byOutcome.put(key, new int[]{0, 0}); // [n, matches]
        }
        List<Map<String, Object>> mismatches = new ArrayList<>();

        // Build an index from titleId to ground-truth slug for mismatch tracking.
        Map<Long, String> groundTruth = new LinkedHashMap<>();
        for (BackfillCandidate c : rows) {
            groundTruth.put(c.id(), c.groundTruthSlug()); // keyed by queue row id
        }

        // Convert to OpenRows for the processor.
        List<EnrichmentReviewQueueRepository.OpenRow> openRows = new ArrayList<>(rows.size());
        for (BackfillCandidate c : rows) {
            openRows.add(synthesizeOpenRow(c));
        }

        boolean[] cancelled = {false};
        Path reportPath = reportPath();

        BatchedEnsembleProcessor.ProgressSink sink = new BatchedEnsembleProcessor.ProgressSink() {
            @Override
            public void update(int done, int total2, String detail) {
                io.phaseProgress("backfill", done, total2, detail);
            }

            @Override
            public void log(String line) {
                io.phaseLog("backfill", line);
            }

            @Override
            public void rowProcessed(long rowId, String code, AssistResult result) {
                String outcomeKey = result.outcome();
                int[] bucket = byOutcome.computeIfAbsent(outcomeKey, k -> new int[]{0, 0});
                bucket[0] += 1;

                String aiSlug    = result.suggestedSlug();
                String truth     = groundTruth.get(rowId);
                if (aiSlug != null && aiSlug.equals(truth)) {
                    bucket[1] += 1;
                } else if (aiSlug != null && !aiSlug.equals(truth)) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("queue_row_id",      rowId);
                    entry.put("title_code",        code);
                    entry.put("ai_slug",           aiSlug);
                    entry.put("ground_truth_slug", truth);
                    entry.put("reason",            result.reason());
                    mismatches.add(entry);
                }
            }
        };

        BatchedEnsembleProcessor.CancellationCheck check = () -> {
            boolean c = io.isCancellationRequested();
            if (c) cancelled[0] = true;
            return c;
        };

        BatchedEnsembleProcessor.ProcessingResult result;
        try {
            result = processor.process(openRows, sink, check);
        } finally {
            // ALWAYS finalise the report — even on cancellation or unexpected throw —
            // so partial runs leave a readable artifact.
            try {
                writeReport(reportPath, cancelled[0], byOutcome, mismatches);
                io.phaseLog("backfill", "Report written to " + reportPath);
            } catch (IOException e) {
                io.phaseLog("backfill", "FAILED to write report: " + e.getMessage());
                log.warn("[ai-assist] backfill: failed to write report at {}: {}",
                        reportPath, e.getMessage());
            }
        }

        double matchRate = matchRateOnPicked(byOutcome);
        String summary = "processed=" + result.processed()
                + " match_rate=" + formatPct(matchRate)
                + (cancelled[0] ? " (cancelled)" : "")
                + " report=" + reportPath.getFileName();
        io.phaseEnd("backfill", "ok", summary);

        log.info("[ai-assist] backfill complete: processed={} match_rate={}% report={}",
                result.processed(), formatPct(matchRate), reportPath);
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Builds a minimal {@link EnrichmentReviewQueueRepository.OpenRow} from a backfill candidate
     * so the processor can read {@code id}, {@code titleId}, {@code titleCode}, and {@code detail}.
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
                             boolean wasCancelled,
                             Map<String, int[]> byOutcome,
                             List<Map<String, Object>> mismatches) throws IOException {
        Files.createDirectories(path.getParent());

        Map<String, Object> outcomes = new LinkedHashMap<>();
        for (Map.Entry<String, int[]> e : byOutcome.entrySet()) {
            int[] v = e.getValue();
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("n", v[0]);
            b.put("matches", v[1]);
            outcomes.put(e.getKey(), b);
        }

        int total = byOutcome.values().stream().mapToInt(v -> v[0]).sum();

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("completed_at",         Instant.now().toString());
        report.put("cancelled",            wasCancelled);
        report.put("processed",            total);
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
