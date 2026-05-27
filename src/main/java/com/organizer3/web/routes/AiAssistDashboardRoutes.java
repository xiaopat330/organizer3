package com.organizer3.web.routes;

import com.organizer3.javdb.enrichment.EnrichmentReviewQueueRepository;
import com.organizer3.ollama.OllamaModelOrchestrator;
import io.javalin.Javalin;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Exposes read-only AI-assist dashboard aggregates.
 *
 * <ul>
 *   <li>{@code GET /api/enrichment/assist/dashboard} — summary counts + outcome breakdown</li>
 *   <li>{@code GET /api/enrichment/assist/queue-preview} — next items awaiting AI (param: limit)</li>
 *   <li>{@code GET /api/enrichment/assist/recent} — recently processed rows (params: limit, since)</li>
 * </ul>
 */
@Slf4j
public class AiAssistDashboardRoutes {

    private final OllamaModelOrchestrator orchestrator;
    private final EnrichmentReviewQueueRepository reviewQueueRepo;

    public AiAssistDashboardRoutes(OllamaModelOrchestrator orchestrator,
                                   EnrichmentReviewQueueRepository reviewQueueRepo) {
        this.orchestrator    = orchestrator;
        this.reviewQueueRepo = reviewQueueRepo;
    }

    public void register(Javalin app) {

        // GET /api/enrichment/assist/dashboard
        app.get("/api/enrichment/assist/dashboard", ctx -> {
            OllamaModelOrchestrator.QueueDepths depths = orchestrator.getQueueDepths();

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("awaitingAi",          reviewQueueRepo.countAwaitingAi());
            body.put("inFlight",             depths.inFlight());
            body.put("orchestratorQueued",   depths.queued());
            body.put("processedTotal",       reviewQueueRepo.countProcessed());
            body.put("autoApplied",          reviewQueueRepo.countAutoApplied());
            body.put("outcomeCounts",        reviewQueueRepo.outcomeCounts());
            ctx.json(body);
        });

        // GET /api/enrichment/assist/queue-preview?limit=15
        app.get("/api/enrichment/assist/queue-preview", ctx -> {
            int limit = clamp(queryInt(ctx.queryParam("limit"), 15), 1, 100);
            List<Map<String, Object>> rows = reviewQueueRepo.listOpenAwaitingAi(limit).stream()
                    .map(row -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("reviewQueueId", row.id());
                        m.put("titleId",       row.titleId());
                        m.put("code",          row.titleCode());
                        m.put("createdAt",     row.createdAt());
                        return m;
                    })
                    .toList();
            ctx.json(rows);
        });

        // GET /api/enrichment/assist/recent?limit=50&since=<iso>
        app.get("/api/enrichment/assist/recent", ctx -> {
            int    limit = clamp(queryInt(ctx.queryParam("limit"), 50), 1, 200);
            String since = ctx.queryParam("since");

            List<Map<String, Object>> rows = reviewQueueRepo.listRecentlyProcessed(limit, since).stream()
                    .map(row -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("reviewQueueId", row.reviewQueueId());
                        m.put("code",          row.code());
                        m.put("outcome",       row.outcome());
                        m.put("slug",          row.slug());
                        m.put("reason",        row.reason());
                        m.put("autoApplied",   row.autoApplied());
                        m.put("at",            row.at());
                        return m;
                    })
                    .toList();
            ctx.json(rows);
        });
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static int queryInt(String raw, int defaultValue) {
        if (raw == null || raw.isBlank()) return defaultValue;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
