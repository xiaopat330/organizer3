package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;
import com.organizer3.repository.ReconcileReportRepository;
import com.organizer3.sync.ReconcileDetailSerializer;
import com.organizer3.sync.ReconcileReport;
import com.organizer3.sync.ReconcileService;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MCP tool: {@code reconcile_locations}
 *
 * <p>Runs the reconcile-only pass and returns the report as structured JSON. The pass is
 * read-mostly (no filesystem I/O). The optional {@code sweep} flag drops past-grace stale rows
 * immediately (subject to the catastrophic-delete guard).
 *
 * <p>Each call persists a report row with {@code triggered_by='manual'}.
 */
public class ReconcileLocationsTool implements Tool {

    private final ReconcileService reconcileService;
    private final ReconcileReportRepository reportRepo;

    public ReconcileLocationsTool(ReconcileService reconcileService,
                                  ReconcileReportRepository reportRepo) {
        this.reconcileService = reconcileService;
        this.reportRepo = reportRepo;
    }

    @Override public String name() { return "reconcile_locations"; }

    @Override
    public String description() {
        return "Run the reconcile-only pass: examines title_locations DB state and reports "
             + "duplicate live locations, pending-grace titles, past-grace stragglers, and "
             + "actress-folder mismatches. No filesystem I/O. Pass verbose=true for detail lists, "
             + "sweep=true to drop past-grace rows immediately.";
    }

    @Override
    public JsonNode inputSchema() {
        return Schemas.object()
                .prop("verbose", "boolean", "Populate detail lists in the report. Default false.")
                .prop("sweep",   "boolean", "Drop past-grace stale rows immediately (subject to "
                        + "catastrophic-delete guard). Default false.")
                .build();
    }

    @Override
    public Object call(JsonNode args) {
        boolean verbose = Schemas.optBoolean(args, "verbose", false);
        boolean sweep   = Schemas.optBoolean(args, "sweep",   false);

        ReconcileReport report = reconcileService.run(verbose);

        // Persist with triggered_by='manual'
        String detailJson = ReconcileDetailSerializer.toJson(report);
        long rowId = reconcileService.persist(report, "manual", detailJson);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reportId",               rowId);
        result.put("generatedAt",            report.generatedAt().toString());
        result.put("duplicateLiveLocations", report.duplicateLiveLocations());
        result.put("pendingGrace",           report.pendingGrace());
        result.put("oldestPendingGraceDays", report.oldestPendingGraceDays());
        result.put("pastGraceStragglers",    report.pastGraceStragglers());
        result.put("actressFolderMismatches",report.actressFolderMismatches());
        result.put("clean",                  report.isClean());

        if (verbose) {
            result.put("duplicateLiveDetails",  report.duplicateLiveDetails());
            result.put("pendingGraceDetails",    report.pendingGraceDetails());
            result.put("pastGraceDetails",       report.pastGraceDetails());
            result.put("mismatchDetails",        report.mismatchDetails());
        }

        if (sweep) {
            int deleted = reconcileService.sweepPastGraceStragglers();
            if (deleted < 0) {
                result.put("sweepResult", "REFUSED — catastrophic-delete guard tripped. "
                        + "Investigate before retrying.");
            } else {
                result.put("sweepResult", "Deleted " + deleted + " past-grace stale rows.");
            }
        }

        return result;
    }
}
