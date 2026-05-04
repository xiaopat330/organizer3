package com.organizer3.web.routes;

import com.organizer3.translation.HealthStatus;
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

    public TranslationRoutes(TranslationService service,
                              TranslationStrategyRepository strategyRepo,
                              TranslationCacheRepository cacheRepo,
                              TranslationQueueRepository queueRepo,
                              Jdbi jdbi) {
        this.service       = service;
        this.strategyRepo  = strategyRepo;
        this.cacheRepo     = cacheRepo;
        this.queueRepo     = queueRepo;
        this.jdbi          = jdbi;
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
                statsMap.put("queuePending", base.queuePending());
                statsMap.put("queueInFlight", base.queueInFlight());
                statsMap.put("queueDone", base.queueDone());
                statsMap.put("queueFailed", base.queueFailed());
                statsMap.put("queueTier2Pending", base.queueTier2Pending());
                statsMap.put("throughputLastHour", throughputLastHour);
                statsMap.put("topN", List.of());  // usage count data not yet tracked
                statsMap.put("stageNameLookupSize", base.stageNameLookupSize());
                statsMap.put("stageNameSuggestionsUnreviewed", base.stageNameSuggestionsUnreviewed());
                ctx.json(statsMap);
            } catch (Exception e) {
                log.error("GET /api/translation/stats failed", e);
                ctx.status(500).json(Map.of("error", e.getMessage()));
            }
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
                ctx.json(cacheRepo.findRecentFailures(limit));
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

        // GET /api/translation/health
        app.get("/api/translation/health", ctx -> {
            try {
                HealthStatus status = service.getHealth();
                ctx.json(Map.of(
                        "ollamaReachable", status.ollamaReachable(),
                        "tier1ModelPresent", status.tier1ModelPresent(),
                        "latencyOk", status.latencyOk(),
                        "latencyP95Ms", status.latencyP95Ms() != null ? status.latencyP95Ms() : 0,
                        "overall", status.overall(),
                        "message", status.message()
                ));
            } catch (Exception e) {
                log.error("GET /api/translation/health failed", e);
                ctx.status(500).json(Map.of("error", e.getMessage()));
            }
        });
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

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
