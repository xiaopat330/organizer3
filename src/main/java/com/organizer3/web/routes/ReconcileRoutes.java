package com.organizer3.web.routes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.organizer3.repository.ReconcileReportRepository;
import com.organizer3.sync.ReconcileDetailSerializer;
import com.organizer3.sync.ReconcileReport;
import com.organizer3.sync.ReconcileService;
import io.javalin.Javalin;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * HTTP surface for the reconcile-only pass.
 *
 * <ul>
 *   <li>{@code POST /api/reconcile/run} — run the reconcile pass (optional body:
 *       {@code {"verbose": bool, "sweep": bool}}); returns a summary map plus optional
 *       {@code sweptCount} if sweep was requested. Persists with
 *       {@code triggered_by='manual'}.</li>
 *   <li>{@code GET /api/reconcile/recent?limit=N} — returns the N most recent persisted
 *       reports, newest first. Default limit = 10.</li>
 * </ul>
 */
@Slf4j
public class ReconcileRoutes {

    private static final int DEFAULT_RECENT_LIMIT = 10;

    private final ReconcileService reconcileService;
    private final ReconcileReportRepository reportRepo;
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());

    public ReconcileRoutes(ReconcileService reconcileService,
                           ReconcileReportRepository reportRepo) {
        this.reconcileService = reconcileService;
        this.reportRepo = reportRepo;
    }

    public void register(Javalin app) {

        // POST /api/reconcile/run
        app.post("/api/reconcile/run", ctx -> {
            boolean verbose = false;
            boolean sweep   = false;

            String body = ctx.body();
            if (body != null && !body.isBlank()) {
                try {
                    JsonNode node = json.readTree(body);
                    if (node.has("verbose")) verbose = node.get("verbose").asBoolean(false);
                    if (node.has("sweep"))   sweep   = node.get("sweep").asBoolean(false);
                } catch (Exception e) {
                    log.warn("Could not parse reconcile/run body: {}", e.getMessage());
                }
            }

            ReconcileReport report = reconcileService.run(verbose);
            String detailJson = ReconcileDetailSerializer.toJson(report);
            long rowId = reconcileService.persist(report, "manual", detailJson);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("reportId",                rowId);
            result.put("generatedAt",             report.generatedAt().toString());
            result.put("duplicateLiveLocations",  report.duplicateLiveLocations());
            result.put("pendingGrace",            report.pendingGrace());
            result.put("oldestPendingGraceDays",  report.oldestPendingGraceDays());
            result.put("pastGraceStragglers",     report.pastGraceStragglers());
            result.put("actressFolderMismatches", report.actressFolderMismatches());
            result.put("clean",                   report.isClean());

            if (sweep) {
                int deleted = reconcileService.sweepPastGraceStragglers();
                if (deleted < 0) {
                    result.put("sweptCount", -1);
                    result.put("sweepResult", "REFUSED — catastrophic-delete guard tripped");
                } else {
                    result.put("sweptCount", deleted);
                    result.put("sweepResult", "Deleted " + deleted + " past-grace stale rows");
                }
            }

            ctx.json(result);
        });

        // GET /api/reconcile/recent?limit=N
        app.get("/api/reconcile/recent", ctx -> {
            int limit = DEFAULT_RECENT_LIMIT;
            String limitParam = ctx.queryParam("limit");
            if (limitParam != null) {
                try { limit = Integer.parseInt(limitParam); }
                catch (NumberFormatException ignored) {}
            }
            ctx.json(reportRepo.findRecent(Math.max(1, Math.min(limit, 100))));
        });
    }
}
