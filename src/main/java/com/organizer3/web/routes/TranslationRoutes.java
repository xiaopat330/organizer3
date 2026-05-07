package com.organizer3.web.routes;

import com.organizer3.javdb.enrichment.JavdbEnrichmentRepository;
import com.organizer3.translation.HealthStatus;
import com.organizer3.translation.TitleTranslationSweeper;
import com.organizer3.translation.TranslationConfig;
import com.organizer3.translation.TranslationRequest;
import com.organizer3.translation.TranslationService;
import com.organizer3.translation.TranslationServiceStats;
import com.organizer3.translation.repository.TranslationCacheRepository;
import com.organizer3.translation.repository.TranslationQueueRepository;
import com.organizer3.translation.repository.TranslationStrategyRepository;
import io.javalin.Javalin;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.Jdbi;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * REST endpoints for the Translation Tools UI page.
 *
 * <ul>
 *   <li>{@code GET  /api/translation/stats}            — enriched operational stats</li>
 *   <li>{@code GET  /api/translation/strategies}       — all active strategy rows</li>
 *   <li>{@code GET  /api/translation/recent-failures}  — last N failed cache rows</li>
 *   <li>{@code POST /api/translation/manual}           — sync translate (no queue row)</li>
 *   <li>{@code POST /api/translation/bulk}             — enqueue catalog rows with dedup</li>
 *   <li>{@code GET  /api/translation/health}           — health status</li>
 * </ul>
 */
@Slf4j
public class TranslationRoutes {

    private static final int DEFAULT_FAILURE_LIMIT = 20;

    private final TranslationService service;
    private final TranslationStrategyRepository strategyRepo;
    private final TranslationCacheRepository cacheRepo;
    private final TranslationQueueRepository queueRepo;
    private final Jdbi jdbi;
    private final JavdbEnrichmentRepository enrichmentRepo;
    private final TranslationConfig translationConfig;
    private final TitleTranslationSweeper titleSweeper;
    private final com.organizer3.translation.OllamaModelState ollamaModelState;
    private final com.organizer3.translation.ExplicitTermSubstitutor explicitSubstitutor;
    private final com.organizer3.translation.ollama.OllamaAdapter ollamaAdapter;

    public TranslationRoutes(TranslationService service,
                              TranslationStrategyRepository strategyRepo,
                              TranslationCacheRepository cacheRepo,
                              TranslationQueueRepository queueRepo,
                              Jdbi jdbi,
                              JavdbEnrichmentRepository enrichmentRepo,
                              TranslationConfig translationConfig,
                              TitleTranslationSweeper titleSweeper,
                              com.organizer3.translation.OllamaModelState ollamaModelState,
                              com.organizer3.translation.ExplicitTermSubstitutor explicitSubstitutor,
                              com.organizer3.translation.ollama.OllamaAdapter ollamaAdapter) {
        this.service              = service;
        this.strategyRepo         = strategyRepo;
        this.cacheRepo            = cacheRepo;
        this.queueRepo            = queueRepo;
        this.jdbi                 = jdbi;
        this.enrichmentRepo       = enrichmentRepo;
        this.translationConfig    = translationConfig;
        this.titleSweeper         = titleSweeper;
        this.ollamaModelState     = ollamaModelState;
        this.explicitSubstitutor  = explicitSubstitutor;
        this.ollamaAdapter        = ollamaAdapter;
    }

    public void register(Javalin app) {

        // GET /api/translation/stats
        app.get("/api/translation/stats", ctx -> {
            try {
                TranslationServiceStats base = service.stats();
                long throughputLastHour = cacheRepo.recentThroughputCount(Duration.ofHours(1));
                LinkedHashMap<String, Object> statsMap = new LinkedHashMap<>();
                statsMap.put("cacheTotal", base.cacheTotal());
                statsMap.put("cacheSuccessful", base.cacheSuccessful());
                statsMap.put("cacheFailed", base.cacheFailed());
                statsMap.put("cacheFailedSanitizedBothTiers", base.cacheFailedSanitizedBothTiers());
                statsMap.put("cacheFailedSanitized", base.cacheFailedSanitized());
                statsMap.put("cacheFailedUnreachable", base.cacheFailedUnreachable());
                statsMap.put("cacheFailedRefused", base.cacheFailedRefused());
                statsMap.put("queuePending", base.queuePending());
                statsMap.put("queueInFlight", base.queueInFlight());
                statsMap.put("queueDone", base.queueDone());
                statsMap.put("queueFailed", base.queueFailed());
                statsMap.put("queueTier2Pending", base.queueTier2Pending());
                statsMap.put("throughputLastHour", throughputLastHour);
                statsMap.put("topN", List.of());  // usage count data not yet tracked
                statsMap.put("stageNameLookupSize", base.stageNameLookupSize());
                statsMap.put("stageNameSuggestionsUnreviewed", base.stageNameSuggestionsUnreviewed());
                statsMap.put("currentModelId", ollamaModelState.getCurrentModelId());
                statsMap.put("explicitSubsRowsTouched", explicitSubstitutor.getRowsTouched());
                statsMap.put("explicitSubsApplied",     explicitSubstitutor.getSubstitutionsApplied());
                statsMap.put("explicitSubsMapSize",     explicitSubstitutor.size());
                ctx.json(statsMap);
            } catch (Exception e) {
                log.error("GET /api/translation/stats failed", e);
                ctx.status(500).json(Map.of("error", e.getMessage()));
            }
        });

        // GET /api/translation/explicit-substitutions — the loaded JP→EN substitution map.
        // Used by the live activity feed to highlight terms that were rewritten before the
        // LLM call.
        app.get("/api/translation/explicit-substitutions", ctx -> {
            ctx.json(explicitSubstitutor.entries());
        });

        // GET /api/translation/strategies
        app.get("/api/translation/strategies", ctx -> {
            try {
                ctx.json(strategyRepo.findAllActive());
            } catch (Exception e) {
                log.error("GET /api/translation/strategies failed", e);
                ctx.status(500).json(Map.of("error", e.getMessage()));
            }
        });

        // GET /api/translation/recent-failures?limit=20
        app.get("/api/translation/recent-failures", ctx -> {
            try {
                int limit = DEFAULT_FAILURE_LIMIT;
                String limitParam = ctx.queryParam("limit");
                if (limitParam != null) {
                    try {
                        limit = Integer.parseInt(limitParam);
                        if (limit < 1 || limit > 200) {
                            ctx.status(400).json(Map.of("error", "limit must be between 1 and 200"));
                            return;
                        }
                    } catch (NumberFormatException e) {
                        ctx.status(400).json(Map.of("error", "limit must be an integer"));
                        return;
                    }
                }
                var failures = cacheRepo.findRecentFailures(limit);
                // Enrich with the title.code for any failure whose source_text was queued
                // through the title_original_en callback. n+1 over a small (≤200) result set
                // is acceptable; the lookup is keyed on the indexed source_text column.
                List<Map<String, Object>> enriched = new ArrayList<>(failures.size());
                for (var f : failures) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id",                  f.id());
                    row.put("sourceText",          f.sourceText());
                    row.put("englishText",         f.englishText());
                    row.put("failureReason",       f.failureReason());
                    row.put("retryAfter",          f.retryAfter());
                    row.put("latencyMs",           f.latencyMs());
                    row.put("promptTokens",        f.promptTokens());
                    row.put("evalTokens",          f.evalTokens());
                    row.put("cachedAt",            f.cachedAt());
                    row.put("titleCode",           lookupTitleCodeForSourceText(f.sourceText()));
                    enriched.add(row);
                }
                ctx.json(enriched);
            } catch (Exception e) {
                log.error("GET /api/translation/recent-failures failed", e);
                ctx.status(500).json(Map.of("error", e.getMessage()));
            }
        });

        // POST /api/translation/manual
        // Body: { "sourceText": "...", "contextHint": "label" | "prose" | null }
        // Translates synchronously — no queue row written. May block 30-120s.
        app.post("/api/translation/manual", ctx -> {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> body = ctx.bodyAsClass(Map.class);
                if (body == null) {
                    ctx.status(400).json(Map.of("error", "request body is required"));
                    return;
                }
                String sourceText = asString(body, "sourceText");
                if (sourceText == null || sourceText.isBlank()) {
                    ctx.status(400).json(Map.of("error", "sourceText is required"));
                    return;
                }
                String contextHint = asString(body, "contextHint");

                TranslationRequest req = new TranslationRequest(sourceText, contextHint, null, null);
                String result = service.requestTranslationSync(req);

                if (result != null) {
                    ctx.json(Map.of("englishText", result, "success", true));
                } else {
                    ctx.status(422).json(Map.of("success", false,
                            "message", "Translation failed or was refused — check recent-failures for details"));
                }
            } catch (Exception e) {
                log.error("POST /api/translation/manual failed", e);
                ctx.status(500).json(Map.of("error", e.getMessage()));
            }
        });

        // POST /api/translation/bulk
        // Body: { "items": [ { "sourceText": "...", "callbackKind": "...", "callbackId": 123,
        //                      "contextHint": "label" | "prose" | null } ] }
        // Deduplicates before enqueue — skips items already in active queue.
        app.post("/api/translation/bulk", ctx -> {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> body = ctx.bodyAsClass(Map.class);
                if (body == null) {
                    ctx.status(400).json(Map.of("error", "request body is required"));
                    return;
                }
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> items = (List<Map<String, Object>>) body.get("items");
                if (items == null || items.isEmpty()) {
                    ctx.status(400).json(Map.of("error", "items array is required and must be non-empty"));
                    return;
                }
                if (items.size() > 1000) {
                    ctx.status(400).json(Map.of("error", "items array must have at most 1000 entries"));
                    return;
                }

                int enqueued = 0;
                int skipped = 0;
                List<String> errors = new ArrayList<>();

                for (Map<String, Object> item : items) {
                    String sourceText = asString(item, "sourceText");
                    if (sourceText == null || sourceText.isBlank()) {
                        errors.add("skipped item with blank sourceText");
                        skipped++;
                        continue;
                    }
                    String contextHint = asString(item, "contextHint");
                    String callbackKind = asString(item, "callbackKind");
                    Long callbackId = asLong(item, "callbackId");

                    try {
                        TranslationRequest req = new TranslationRequest(
                                sourceText, contextHint, callbackKind, callbackId);
                        service.requestTranslation(req);
                        enqueued++;
                    } catch (Exception e) {
                        errors.add("error for item '" + sourceText.substring(0, Math.min(20, sourceText.length())) + "': " + e.getMessage());
                        skipped++;
                    }
                }

                ctx.json(Map.of(
                        "enqueued", enqueued,
                        "skipped", skipped,
                        "errors", errors
                ));
            } catch (Exception e) {
                log.error("POST /api/translation/bulk failed", e);
                ctx.status(500).json(Map.of("error", e.getMessage()));
            }
        });

        // GET /api/translation/bulk-candidates
        // Returns (titleId, titleOriginal) from title_javdb_enrichment where
        //   title_original IS NOT NULL AND (title_original_en IS NULL OR title_original_en = '')
        // Used by the bulk-submit UI widget to know what to enqueue.
        app.get("/api/translation/bulk-candidates", ctx -> {
            try {
                List<Map<String, Object>> candidates = jdbi.withHandle(h ->
                        h.createQuery("""
                                SELECT title_id, title_original
                                FROM title_javdb_enrichment
                                WHERE title_original IS NOT NULL
                                  AND (title_original_en IS NULL OR title_original_en = '')
                                ORDER BY title_id
                                """)
                                .map((rs, c) -> {
                                    Map<String, Object> row = new LinkedHashMap<>();
                                    row.put("titleId", rs.getLong("title_id"));
                                    row.put("titleOriginal", rs.getString("title_original"));
                                    return row;
                                })
                                .list()
                );
                ctx.json(candidates);
            } catch (Exception e) {
                log.error("GET /api/translation/bulk-candidates failed", e);
                ctx.status(500).json(Map.of("error", e.getMessage()));
            }
        });

        // GET /api/translation/queue-preview?limit=N
        // Returns the next N pending/in-flight queue rows for the Queue panel UI.
        app.get("/api/translation/queue-preview", ctx -> {
            try {
                int limit = 15;
                String limitParam = ctx.queryParam("limit");
                if (limitParam != null) {
                    try {
                        limit = Integer.parseInt(limitParam);
                        if (limit < 1 || limit > 100) {
                            ctx.status(400).json(Map.of("error", "limit must be between 1 and 100"));
                            return;
                        }
                    } catch (NumberFormatException e) {
                        ctx.status(400).json(Map.of("error", "limit must be an integer"));
                        return;
                    }
                }
                var rows = queueRepo.findNextPending(limit);
                List<Map<String, Object>> enriched = new ArrayList<>(rows.size());
                for (var r : rows) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id",          r.id());
                    row.put("status",      r.status());
                    row.put("titleCode",   lookupTitleCodeForSourceText(r.sourceText()));
                    row.put("sourceText",  r.sourceText());
                    row.put("submittedAt", r.submittedAt());
                    row.put("priority",    r.priority());
                    enriched.add(row);
                }
                ctx.json(enriched);
            } catch (Exception e) {
                log.error("GET /api/translation/queue-preview failed", e);
                ctx.status(500).json(Map.of("error", e.getMessage()));
            }
        });

        // GET /api/translation/recent-events?since=<iso>&limit=N
        // Live activity feed: most recent cache rows for the Translation page,
        // enriched with the title.code where the source_text was queued via the
        // title_original_en callback. Frontend polls this every ~2s using a
        // high-water-mark `since` to fetch only new events.
        app.get("/api/translation/recent-events", ctx -> {
            try {
                int limit = 50;
                String limitParam = ctx.queryParam("limit");
                if (limitParam != null) {
                    try {
                        limit = Integer.parseInt(limitParam);
                        if (limit < 1 || limit > 200) {
                            ctx.status(400).json(Map.of("error", "limit must be between 1 and 200"));
                            return;
                        }
                    } catch (NumberFormatException e) {
                        ctx.status(400).json(Map.of("error", "limit must be an integer"));
                        return;
                    }
                }
                String since = ctx.queryParam("since");
                var rows = cacheRepo.findEventsSince(since, limit);
                List<Map<String, Object>> enriched = new ArrayList<>(rows.size());
                for (var r : rows) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id",            r.id());
                    row.put("titleCode",     lookupTitleCodeForSourceText(r.sourceText()));
                    row.put("sourceText",    r.sourceText());
                    row.put("englishText",   r.englishText());
                    row.put("failureReason", r.failureReason());
                    row.put("latencyMs",     r.latencyMs());
                    row.put("cachedAt",      r.cachedAt());
                    enriched.add(row);
                }
                ctx.json(enriched);
            } catch (Exception e) {
                log.error("GET /api/translation/recent-events failed", e);
                ctx.status(500).json(Map.of("error", e.getMessage()));
            }
        });

        // POST /api/translation/sweep-title-backlog-now — operator-triggered sweep.
        // Runs the same logic as the scheduled sweeper but with a larger limit so a
        // single click drains a sizable backlog without waiting for multiple 5-min ticks.
        // Honors the same NOT EXISTS dedup contract — clicking while the scheduled
        // sweeper is running cannot double-enqueue.
        app.post("/api/translation/sweep-title-backlog-now", ctx -> {
            try {
                int limit = 5000;
                String limitParam = ctx.queryParam("limit");
                if (limitParam != null) {
                    try {
                        limit = Integer.parseInt(limitParam);
                        if (limit < 1 || limit > 10000) {
                            ctx.status(400).json(Map.of("error", "limit must be between 1 and 10000"));
                            return;
                        }
                    } catch (NumberFormatException e) {
                        ctx.status(400).json(Map.of("error", "limit must be an integer"));
                        return;
                    }
                }
                int submitted = titleSweeper.sweepOnce(limit);
                long remaining = enrichmentRepo.countTitlesAwaitingTranslation();
                ctx.json(Map.of(
                        "submitted", submitted,
                        "remaining", remaining,
                        "limit", limit
                ));
            } catch (Exception e) {
                log.error("POST /api/translation/sweep-title-backlog-now failed", e);
                ctx.status(500).json(Map.of("error", e.getMessage()));
            }
        });

        // POST /api/translation/requeue-by-reason?reason=<reason>
        // Generic force-retry: deletes cache + queue rows for a given failure reason so
        // upstream sweepers re-enqueue them on the next tick. Allowed reasons are
        // restricted to the four buckets surfaced on the dashboard.
        app.post("/api/translation/requeue-by-reason", ctx -> {
            String reason = ctx.queryParam("reason");
            if (reason == null || reason.isBlank()) {
                ctx.status(400).json(Map.of("error", "reason query param is required"));
                return;
            }
            if (!Set.of("sanitized", "sanitized_both_tiers", "unreachable", "refused").contains(reason)) {
                ctx.status(400).json(Map.of("error", "reason must be one of: sanitized, sanitized_both_tiers, unreachable, refused"));
                return;
            }
            try {
                int requeued = service.requeueFailedByReason(reason);
                ctx.json(Map.of("reason", reason, "requeued", requeued));
            } catch (Exception e) {
                log.error("POST /api/translation/requeue-by-reason failed for reason={}", reason, e);
                ctx.status(500).json(Map.of("error", e.getMessage()));
            }
        });

        // POST /api/translation/requeue-sanitized-both-tiers — manual force-retry path
        // for cache rows where both tier-1 and tier-2 produced sanitized output. Deletes
        // the linked queue rows + the cache rows; upstream sweepers (TitleTranslationSweeper)
        // re-enqueue the work on their next tick.
        app.post("/api/translation/requeue-sanitized-both-tiers", ctx -> {
            try {
                int requeued = service.requeueFailedByReason(
                        com.organizer3.translation.TranslationServiceImpl.SANITIZED_BOTH_TIERS);
                ctx.json(Map.of("requeued", requeued));
            } catch (Exception e) {
                log.error("POST /api/translation/requeue-sanitized-both-tiers failed", e);
                ctx.status(500).json(Map.of("error", e.getMessage()));
            }
        });

        // GET /api/translation/title-sweeper-status — Phase 6a stat surface.
        // Returns the count of titles whose title_original has not yet been queued for
        // English translation, plus the sweeper's current configuration knobs.
        app.get("/api/translation/title-sweeper-status", ctx -> {
            try {
                long pending = enrichmentRepo.countTitlesAwaitingTranslation();
                ctx.json(Map.of(
                        "pending", pending,
                        "enabled", translationConfig.titleSweeperEnabledOrDefault(),
                        "intervalSeconds", translationConfig.titleSweeperIntervalSecondsOrDefault(),
                        "batchSize", translationConfig.titleSweeperBatchSizeOrDefault()
                ));
            } catch (Exception e) {
                log.error("GET /api/translation/title-sweeper-status failed", e);
                ctx.status(500).json(Map.of("error", e.getMessage()));
            }
        });

        // GET /api/translation/health
        app.get("/api/translation/health", ctx -> {
            try {
                HealthStatus status = service.getHealth();
                LinkedHashMap<String, Object> body = new LinkedHashMap<>();
                body.put("ollamaReachable",  status.ollamaReachable());
                body.put("tier1ModelPresent", status.tier1ModelPresent());
                body.put("latencyOk",        status.latencyOk());
                body.put("latencyP95Ms",     status.latencyP95Ms() != null ? status.latencyP95Ms() : 0);
                body.put("overall",          status.overall());
                body.put("message",          status.message());
                // Best-effort live state from /api/ps. Don't 500 the whole health endpoint
                // if /api/ps is flaky — just omit the field.
                List<Map<String, Object>> loaded = new ArrayList<>();
                if (status.ollamaReachable()) {
                    try {
                        for (com.organizer3.translation.ollama.LoadedOllamaModel m : ollamaAdapter.psModels()) {
                            LinkedHashMap<String, Object> row = new LinkedHashMap<>();
                            row.put("name",         m.name());
                            row.put("sizeBytes",    m.sizeBytes());
                            row.put("vramBytes",    m.vramBytes());
                            row.put("expiresAtIso", m.expiresAtIso());
                            loaded.add(row);
                        }
                    } catch (Exception e) {
                        log.debug("GET /api/translation/health: psModels() failed: {}", e.getMessage());
                    }
                }
                body.put("loadedModels", loaded);
                ctx.json(body);
            } catch (Exception e) {
                log.error("GET /api/translation/health failed", e);
                ctx.status(500).json(Map.of("error", e.getMessage()));
            }
        });
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Resolve the {@code titles.code} (Product Number) for a source text that was queued
     * via the {@code title_original_en} callback. Returns {@code null} if the source text
     * was not associated with a title (e.g., a manual-translate request) or if the title
     * has been deleted.
     *
     * <p>One row per failure is acceptable here — the recent-failures view is bounded
     * to ≤200 rows and the lookup is keyed on a string-equality compare against the
     * (small) translation_queue table.
     */
    private String lookupTitleCodeForSourceText(String sourceText) {
        if (sourceText == null) return null;
        return jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT t.code
                        FROM translation_queue q
                        JOIN titles t ON t.id = q.callback_id
                        WHERE q.source_text   = :src
                          AND q.callback_kind = :kind
                        LIMIT 1
                        """)
                        .bind("src",  sourceText)
                        .bind("kind", "title_javdb_enrichment.title_original_en")
                        .mapTo(String.class)
                        .findFirst()
                        .orElse(null));
    }

    private static String asString(Map<String, Object> map, String key) {
        Object val = map.get(key);
        return val instanceof String s ? s : null;
    }

    private static Long asLong(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val instanceof Number n) return n.longValue();
        return null;
    }
}
