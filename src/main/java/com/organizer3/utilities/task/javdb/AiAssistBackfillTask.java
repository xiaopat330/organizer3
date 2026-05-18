package com.organizer3.utilities.task.javdb;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.organizer3.enrichment.ai.AssistResult;
import com.organizer3.enrichment.ai.EnsembleAssistCaller;
import com.organizer3.javdb.enrichment.EnrichmentReviewQueueRepository;
import com.organizer3.javdb.enrichment.EnrichmentReviewQueueRepository.BackfillCandidate;
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
 * Phase 4 Track C — one-shot historical accuracy backfill for the AI picker assist.
 *
 * <p>Replays the {@link EnsembleAssistCaller} against every already-resolved
 * {@code ambiguous} review-queue row that has a corresponding
 * {@code title_javdb_enrichment.javdb_slug} (the human-picked ground truth). Persists
 * the resulting AI suggestion to the same {@code ai_suggestion_*} columns and writes
 * a final JSON report at {@code <dataDir>/ai-assist-backfill-{date}.json} containing
 * per-outcome counts, match counts against ground truth, and a list of mismatches.
 *
 * <p><b>Not a permanent task</b>: this runs once to measure historical accuracy of the
 * ensemble against the corpus of human-resolved rows. Re-running it is harmless but
 * just overwrites the {@code ai_suggestion_*} columns with a fresh suggestion (slower,
 * because the models will repeat the work).
 *
 * <p><b>Runtime</b>: on the live corpus (~1,100+ resolved ambiguous rows × ~25s/row for
 * the two-model ensemble) the full sweep takes many hours. The task respects
 * cancellation between rows and finalises the JSON report on the way out, so a
 * partial run is still usable.
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
    private final EnsembleAssistCaller ensembleAssistCaller;
    private final ObjectMapper objectMapper;
    private final Path dataDir;

    public AiAssistBackfillTask(EnrichmentReviewQueueRepository reviewQueueRepo,
                                EnsembleAssistCaller ensembleAssistCaller,
                                ObjectMapper objectMapper,
                                Path dataDir) {
        this.reviewQueueRepo      = Objects.requireNonNull(reviewQueueRepo, "reviewQueueRepo");
        this.ensembleAssistCaller = Objects.requireNonNull(ensembleAssistCaller, "ensembleAssistCaller");
        this.objectMapper         = Objects.requireNonNull(objectMapper, "objectMapper");
        this.dataDir              = Objects.requireNonNull(dataDir, "dataDir");
    }

    @Override
    public TaskSpec spec() { return SPEC; }

    @Override
    public void run(TaskInputs inputs, TaskIO io) throws Exception {
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

        int processed = 0;
        boolean cancelled = false;
        Path reportPath = reportPath();

        try {
            for (int i = 0; i < total; i++) {
                if (io.isCancellationRequested()) {
                    io.phaseLog("backfill", "Cancelled after " + i + " of " + total + " row(s)");
                    cancelled = true;
                    break;
                }
                BackfillCandidate row = rows.get(i);
                io.phaseProgress("backfill", i, total,
                        "queue_row_id=" + row.id() + " code=" + row.titleCode());

                AssistResult result;
                String outcomeKey;
                try {
                    EnrichmentReviewQueueRepository.OpenRow openRow = synthesizeOpenRow(row);
                    EnrichmentReviewQueueRepository.AssistContext ctx =
                            reviewQueueRepo.findContextForAssist(row.titleId());
                    result = ensembleAssistCaller.evaluate(
                            openRow, ctx.folderPath(), ctx.actressNames());
                    outcomeKey = result.outcome();
                } catch (Exception e) {
                    log.warn("[ai-assist] backfill: evaluate failed for queue_row_id={} code={}: {}",
                            row.id(), row.titleCode(), e.getMessage());
                    result = new AssistResult(
                            "error",
                            null,
                            null,
                            "evaluate_failed: " + e.getMessage(),
                            null,
                            null);
                    outcomeKey = "error";
                }

                // Persist the suggestion (overwrites whatever was there).
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

    /**
     * Builds a minimal {@link EnrichmentReviewQueueRepository.OpenRow} from a backfill candidate
     * so we can reuse {@link EnsembleAssistCaller#evaluate}. The caller only reads
     * {@code id}, {@code titleCode}, and {@code detail} from the row.
     */
    private static EnrichmentReviewQueueRepository.OpenRow synthesizeOpenRow(BackfillCandidate c) {
        return new EnrichmentReviewQueueRepository.OpenRow(
                c.id(),
                c.titleId(),
                c.titleCode(),
                null,                   // slug — unused by EnsembleAssistCaller
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
