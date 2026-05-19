package com.organizer3.web.routes;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.organizer3.covers.CoverPath;
import com.organizer3.enrichment.ai.AssistResult;
import com.organizer3.enrichment.ai.BatchedEnsembleProcessor;
import com.organizer3.enrichment.ai.EnsembleAssistCaller;
import com.organizer3.enrichment.ai.EnrichmentAutoApplier;
import com.organizer3.javdb.enrichment.EnrichmentReviewQueueRepository;
import com.organizer3.javdb.enrichment.EnrichmentReviewQueueRepository.AssistContext;
import com.organizer3.javdb.enrichment.EnrichmentReviewQueueRepository.OpenRow;
import io.javalin.Javalin;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.Jdbi;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Enrichment Workflow surface.
 *
 * <ul>
 *   <li>{@code GET  /api/enrichment/workflow/rows} — unified list joining the fetch queue,
 *       review queue, and enrichment table into per-title state rows.</li>
 *   <li>{@code POST /api/enrichment/workflow/{queueRowId}/ai-assist} — trigger the ensemble
 *       AI caller for one review-queue row; runs synchronously, returns updated AI fields.</li>
 *   <li>{@code POST /api/enrichment/workflow/ai-assist-all} — queue AI assist for all open
 *       ambiguous rows lacking a suggestion; dispatches asynchronously, returns 202 with a count.</li>
 * </ul>
 */
@Slf4j
public class WorkflowRoutes {

    private static final int DEFAULT_LIMIT  = 200;
    private static final int MAX_LIMIT      = 1000;

    private final EnrichmentReviewQueueRepository reviewQueueRepo;
    private final EnsembleAssistCaller            ensembleAssistCaller;
    private final EnrichmentAutoApplier           autoApplier;   // may be null in tests
    private final BatchedEnsembleProcessor        batchedProcessor; // may be null — tests/legacy constructors
    private final Jdbi                            jdbi;
    private final CoverPath                       coverPath;  // may be null in tests / early setup
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());

    // Single-thread executor — all ai-assist submissions are serialised to avoid
    // overloading the Ollama process (mirrors the sweeper's serial-row constraint).
    private final ExecutorService aiExecutor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "workflow-ai-assist");
                t.setDaemon(true);
                return t;
            });

    // Per-row AI assist tracking. aiQueued holds rows submitted but not yet running;
    // aiProcessing holds the single row currently being evaluated (null when idle).
    // Both are read by the /rows endpoint on every poll to derive live state.
    final Set<Long>          aiQueued     = ConcurrentHashMap.newKeySet();
    final AtomicReference<Long> aiProcessing = new AtomicReference<>(null);

    public WorkflowRoutes(EnrichmentReviewQueueRepository reviewQueueRepo,
                          EnsembleAssistCaller ensembleAssistCaller,
                          Jdbi jdbi) {
        this(reviewQueueRepo, ensembleAssistCaller, null, null, jdbi, null);
    }

    public WorkflowRoutes(EnrichmentReviewQueueRepository reviewQueueRepo,
                          EnsembleAssistCaller ensembleAssistCaller,
                          Jdbi jdbi,
                          CoverPath coverPath) {
        this(reviewQueueRepo, ensembleAssistCaller, null, null, jdbi, coverPath);
    }

    public WorkflowRoutes(EnrichmentReviewQueueRepository reviewQueueRepo,
                          EnsembleAssistCaller ensembleAssistCaller,
                          EnrichmentAutoApplier autoApplier,
                          Jdbi jdbi,
                          CoverPath coverPath) {
        this(reviewQueueRepo, ensembleAssistCaller, autoApplier, null, jdbi, coverPath);
    }

    public WorkflowRoutes(EnrichmentReviewQueueRepository reviewQueueRepo,
                          EnsembleAssistCaller ensembleAssistCaller,
                          EnrichmentAutoApplier autoApplier,
                          BatchedEnsembleProcessor batchedProcessor,
                          Jdbi jdbi,
                          CoverPath coverPath) {
        this.reviewQueueRepo      = reviewQueueRepo;
        this.ensembleAssistCaller = ensembleAssistCaller;
        this.autoApplier          = autoApplier;
        this.batchedProcessor     = batchedProcessor;
        this.jdbi                 = jdbi;
        this.coverPath            = coverPath;
    }

    public void register(Javalin app) {

        // GET /api/enrichment/workflow/rows
        app.get("/api/enrichment/workflow/rows", ctx -> {
            int limit = DEFAULT_LIMIT;
            String limitParam = ctx.queryParam("limit");
            if (limitParam != null) {
                try { limit = Math.min(MAX_LIMIT, Integer.parseInt(limitParam)); }
                catch (NumberFormatException ignored) {}
            }

            List<Map<String, Object>> rows = queryWorkflowRows(limit);
            ctx.json(rows);
        });

        // POST /api/enrichment/workflow/{queueRowId}/ai-assist
        // Validates the row then submits to the background executor; returns 202 immediately.
        app.post("/api/enrichment/workflow/{queueRowId}/ai-assist", ctx -> {
            long queueRowId;
            try {
                queueRowId = Long.parseLong(ctx.pathParam("queueRowId"));
            } catch (NumberFormatException e) {
                ctx.status(400).json(Map.of("error", "Invalid queueRowId"));
                return;
            }

            Optional<OpenRow> rowOpt = reviewQueueRepo.findOpenById(queueRowId);
            if (rowOpt.isEmpty()) {
                ctx.status(404).json(Map.of("error", "Queue row not found or already resolved"));
                return;
            }
            OpenRow row = rowOpt.get();

            if (!"ambiguous".equals(row.reason())) {
                ctx.status(400).json(Map.of("error", "AI assist only applies to ambiguous rows"));
                return;
            }

            submitAiAssist(row);
            ctx.status(202).json(Map.of("queueRowId", row.id(), "queued", true));
        });

        // POST /api/enrichment/workflow/ai-assist-all
        // Runs all open ambiguous rows awaiting AI through the batched ensemble processor.
        // Returns 202 with { "queued": N } immediately; processing happens on the background
        // executor. The processor performs at most 2*ceil(N/batchSize) model switches
        // instead of 2*N (the serial-per-row cost of the old implementation).
        app.post("/api/enrichment/workflow/ai-assist-all", ctx -> {
            List<OpenRow> pending = reviewQueueRepo.listOpenAwaitingAi(MAX_LIMIT);
            int count = pending.size();

            if (count == 0) {
                ctx.status(200).json(Map.of("queued", 0, "message", "No rows awaiting AI"));
                return;
            }

            if (batchedProcessor != null) {
                // Mark all rows queued synchronously so the /rows endpoint shows live state
                // before the executor thread picks them up.
                for (OpenRow row : pending) {
                    aiQueued.add(row.id());
                }

                // Dispatch one task for the entire batch — the processor handles chunking.
                aiExecutor.submit(() -> {
                    BatchedEnsembleProcessor.ProgressSink sink =
                            new BatchedEnsembleProcessor.ProgressSink() {
                                @Override
                                public void rowStarted(long rowId, String code) {
                                    aiQueued.remove(rowId);
                                    aiProcessing.set(rowId);
                                }
                                @Override
                                public void rowProcessed(long rowId, String code, AssistResult result) {
                                    aiProcessing.set(null);
                                }
                            };
                    BatchedEnsembleProcessor.CancellationCheck neverCancel = () -> false;
                    try {
                        batchedProcessor.process(pending, sink, neverCancel);
                    } catch (Exception e) {
                        log.warn("[workflow] ai-assist-all batch failed: {}", e.getMessage());
                    } finally {
                        // Clean up any rows that didn't reach rowProcessed (e.g. early exit).
                        for (OpenRow row : pending) {
                            aiQueued.remove(row.id());
                        }
                        aiProcessing.set(null);
                    }
                });
            } else {
                // Fallback for tests / legacy constructors that don't wire batchedProcessor.
                for (OpenRow row : pending) {
                    submitAiAssist(row);
                }
            }

            log.info("[workflow] ai-assist-all queued {} rows", count);
            ctx.status(202).json(Map.of("queued", count));
        });
    }

    // ── Workflow query ────────────────────────────────────────────────────────────

    /**
     * Returns a unified list of workflow rows. Each row represents a title that is
     * either pending/in-flight in the fetch queue, or has an open review-queue entry.
     *
     * <p>State derivation (in priority order):
     * <ol>
     *   <li>{@code judging}            — row is currently being evaluated by AI (aiProcessing)</li>
     *   <li>{@code queued_for_ai}      — row is queued for AI evaluation (aiQueued)</li>
     *   <li>{@code fetching}           — fetch queue has an in_flight job for this title</li>
     *   <li>{@code queued}             — fetch queue has a pending/paused job</li>
     *   <li>{@code ambiguous}          — reason is 'ambiguous', AI has not run yet (or agreed slip-through)</li>
     *   <li>{@code split_decision}     — AI ran, models disagreed (conflict)</li>
     *   <li>{@code partial_vote}       — AI ran, only one model voted (phi4_only / gemma_only)</li>
     *   <li>{@code no_verdict}         — AI ran but both abstained or errored</li>
     *   <li>{@code other_intervention} — reason is anything other than 'ambiguous'</li>
     * </ol>
     *
     * <p>Actress names are fetched in one batched query to avoid N+1.
     */
    private List<Map<String, Object>> queryWorkflowRows(int limit) {
        return jdbi.withHandle(h -> {
            // Pull open review-queue rows (they are the primary surface of interest).
            String reviewSql = """
                    SELECT q.id           AS q_id,
                           q.title_id     AS title_id,
                           t.code         AS title_code,
                           q.slug         AS slug,
                           q.reason       AS reason,
                           q.resolver_source AS resolver_source,
                           q.created_at   AS created_at,
                           q.detail       AS detail,
                           q.ai_suggestion_slug        AS ai_suggestion_slug,
                           q.ai_suggestion_confidence  AS ai_suggestion_confidence,
                           q.ai_suggestion_reason      AS ai_suggestion_reason,
                           q.ai_suggestion_at          AS ai_suggestion_at,
                           q.ai_auto_applied           AS ai_auto_applied,
                           q.ai_auto_apply_attempts    AS ai_auto_apply_attempts,
                           q.ai_phi4_slug              AS ai_phi4_slug,
                           q.ai_gemma_slug             AS ai_gemma_slug,
                           -- fetch queue state for this title (most urgent non-done status)
                           (SELECT jeq.status
                            FROM javdb_enrichment_queue jeq
                            WHERE jeq.job_type = 'fetch_title'
                              AND jeq.target_id = q.title_id
                              AND jeq.status NOT IN ('done', 'cancelled')
                            ORDER BY CASE jeq.status
                              WHEN 'in_flight' THEN 0
                              WHEN 'pending'   THEN 1
                              WHEN 'paused'    THEN 2
                              WHEN 'failed'    THEN 3
                              ELSE 4 END
                            LIMIT 1) AS fetch_status
                    FROM enrichment_review_queue q
                    JOIN titles t ON t.id = q.title_id
                    WHERE q.resolved_at IS NULL
                    ORDER BY q.created_at DESC
                    LIMIT :limit
                    """;

            List<Map<String, Object>> rawRows = h.createQuery(reviewSql)
                    .bind("limit", limit)
                    .mapToMap()
                    .list();

            if (rawRows.isEmpty()) return List.of();

            // Batch fetch actress names for all title ids to avoid N+1.
            List<Long> titleIds = new ArrayList<>();
            for (Map<String, Object> r : rawRows) {
                Object tid = r.get("title_id");
                if (tid != null) titleIds.add(((Number) tid).longValue());
            }

            Map<Long, List<String>> actressesByTitle = batchFetchActressNames(h, titleIds);

            // Build output rows.
            List<Map<String, Object>> result = new ArrayList<>(rawRows.size());
            for (Map<String, Object> r : rawRows) {
                long titleId   = ((Number) r.get("title_id")).longValue();
                long queueId   = ((Number) r.get("q_id")).longValue();
                String fetchStatus = (String) r.get("fetch_status");
                String reason      = (String) r.get("reason");

                // Check live tracking state first; fall through to DB-derived state.
                String state;
                if (Long.valueOf(queueId).equals(aiProcessing.get())) {
                    state = "judging";
                } else if (aiQueued.contains(queueId)) {
                    state = "queued_for_ai";
                } else {
                    state = deriveState(fetchStatus, reason,
                            (String) r.get("ai_suggestion_at"),
                            (String) r.get("ai_suggestion_confidence"));
                }

                Map<String, Object> row = new LinkedHashMap<>();
                String titleCode = (String) r.get("title_code");

                row.put("id",        queueId);
                row.put("queueId",   queueId);
                row.put("titleCode", titleCode);
                row.put("actresses", actressesByTitle.getOrDefault(titleId, List.of()));
                row.put("state",     state);
                row.put("reason",    reason);
                row.put("slug",      r.get("slug"));
                row.put("detail",    r.get("detail"));

                // Title's local cover image (null if none on disk).
                row.put("coverUrl", resolveCoverUrl(titleCode));

                // AI suggestion fields — only present for ambiguous rows.
                row.put("aiSuggestionSlug",       r.get("ai_suggestion_slug"));
                row.put("aiSuggestionConfidence", r.get("ai_suggestion_confidence"));
                row.put("aiSuggestionReason",     r.get("ai_suggestion_reason"));
                row.put("aiSuggestionAt",         r.get("ai_suggestion_at"));
                row.put("aiAutoApplied",          intToBool(r.get("ai_auto_applied")));

                // Per-model slugs (V64+); null when model abstained or AI hasn't run.
                row.put("aiPhi4Slug",  r.get("ai_phi4_slug"));
                row.put("aiGemmaSlug", r.get("ai_gemma_slug"));

                result.add(row);
            }
            return result;
        });
    }

    /**
     * Batches a single SQL query to get actress canonical names for a list of title ids.
     * Returns a map of title_id → list of canonical names, ordered by canonical_name.
     */
    private static Map<Long, List<String>> batchFetchActressNames(
            org.jdbi.v3.core.Handle h, List<Long> titleIds) {
        if (titleIds.isEmpty()) return Map.of();

        String inClause = String.join(",",
                Collections.nCopies(titleIds.size(), "?"));

        String sql = "SELECT ta.title_id, a.canonical_name"
                + " FROM title_actresses ta"
                + " JOIN actresses a ON a.id = ta.actress_id"
                + " WHERE ta.title_id IN (" + inClause + ")"
                + " ORDER BY ta.title_id, a.canonical_name";

        Map<Long, List<String>> result = new HashMap<>();
        var q = h.createQuery(sql);
        for (int i = 0; i < titleIds.size(); i++) {
            q = q.bind(i, titleIds.get(i));
        }
        for (Map<String, Object> row : q.mapToMap().list()) {
            long tid  = ((Number) row.get("title_id")).longValue();
            String nm = (String) row.get("canonical_name");
            result.computeIfAbsent(tid, k -> new ArrayList<>()).add(nm);
        }
        return result;
    }

    /**
     * Derives the UI state string from the fetch-queue status, review-queue reason, and AI outcome.
     *
     * <ul>
     *   <li>{@code queued}           — fetch queue has a pending/paused job for this title</li>
     *   <li>{@code fetching}         — fetch HTTP call is in flight</li>
     *   <li>{@code ambiguous}        — reason is 'ambiguous', AI has not run yet</li>
     *   <li>{@code split_decision}   — AI ran, outcome is 'conflict'</li>
     *   <li>{@code partial_vote}     — AI ran, outcome is 'phi4_only' or 'gemma_only'</li>
     *   <li>{@code no_verdict}       — AI ran, outcome is 'both_abstain', 'error', or unrecognised</li>
     *   <li>{@code other_intervention} — reason is anything other than 'ambiguous'</li>
     * </ul>
     *
     * <p>'agreed' rows should have auto-applied out of the queue; if one slips through it is
     * treated as {@code ambiguous} so the user can act on it.
     */
    static String deriveState(String fetchStatus, String reason,
                              String aiSuggestionAt, String aiOutcome) {
        if ("in_flight".equals(fetchStatus)) return "fetching";
        if ("pending".equals(fetchStatus) || "paused".equals(fetchStatus)) return "queued";

        if ("ambiguous".equals(reason)) {
            if (aiSuggestionAt == null) return "ambiguous";
            // AI has run — map outcome to state.
            if ("conflict".equals(aiOutcome))                     return "split_decision";
            if ("phi4_only".equals(aiOutcome)
                    || "gemma_only".equals(aiOutcome))            return "partial_vote";
            if ("agreed".equals(aiOutcome))                       return "ambiguous"; // defensive: should have auto-applied
            // both_abstain, error, null, or unknown
            return "no_verdict";
        }
        return "other_intervention";
    }

    // ── Background AI assist ──────────────────────────────────────────────────────

    /**
     * Marks {@code row} as queued then submits it to the serial executor. Returns immediately.
     * The executor runnable transitions queued → processing → idle and persists the result.
     */
    private void submitAiAssist(OpenRow row) {
        aiQueued.add(row.id());
        aiExecutor.submit(() -> {
            aiQueued.remove(row.id());
            aiProcessing.set(row.id());
            try {
                AssistContext ctx = reviewQueueRepo.findContextForAssist(row.titleId());
                AssistResult result = ensembleAssistCaller.evaluate(
                        row, ctx.folderPath(), ctx.actressNames());

                reviewQueueRepo.setAiSuggestion(
                        row.id(),
                        result.suggestedSlug(),
                        result.outcome(),
                        result.reason(),
                        Instant.now(),
                        result.phi4Slug(),
                        result.gemmaSlug());

                log.info("[workflow] ai-assist code={} outcome={}", row.titleCode(), result.outcome());

                if ("agreed".equals(result.outcome()) && autoApplier != null) {
                    OpenRow updated = reviewQueueRepo.findOpenById(row.id()).orElse(row);
                    boolean applied = autoApplier.apply(updated);
                    if (applied) {
                        log.info("[workflow] ai-assist auto-applied code={} slug={}", row.titleCode(), result.suggestedSlug());
                    } else {
                        log.warn("[workflow] ai-assist auto-apply failed code={} slug={}", row.titleCode(), result.suggestedSlug());
                    }
                }
            } catch (Exception e) {
                log.warn("[workflow] ai-assist failed code={}: {}", row.titleCode(), e.getMessage());
                try {
                    reviewQueueRepo.setAiSuggestion(row.id(), null, "error",
                            truncate(e.getMessage(), 240), Instant.now(), null, null);
                } catch (RuntimeException ignored) {}
            } finally {
                aiProcessing.set(null);
            }
        });
    }

    // ── Utilities ─────────────────────────────────────────────────────────────────

    /**
     * Returns the web-accessible URL for the title's local cover image, or {@code null}
     * if no cover file exists on disk or {@code coverPath} was not injected.
     * Pattern: {@code /covers/<LABEL>/<baseCode>.<ext>}
     */
    private String resolveCoverUrl(String titleCode) {
        if (coverPath == null || titleCode == null) return null;
        return coverPath.findByCode(titleCode)
                .map(p -> {
                    // Extract label dir and filename for URL construction.
                    // Path is <coversRoot>/<LABEL>/<baseCode>.<ext>
                    String label    = p.getParent().getFileName().toString();
                    String filename = p.getFileName().toString();
                    return "/covers/" + label + "/" + filename;
                })
                .orElse(null);
    }

    private static boolean intToBool(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        return ((Number) v).intValue() != 0;
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
