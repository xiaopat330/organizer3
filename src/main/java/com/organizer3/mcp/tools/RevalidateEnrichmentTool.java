package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.javdb.enrichment.RevalidationPendingRepository;
import com.organizer3.javdb.enrichment.RevalidationService;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * Manual trigger for enrichment re-validation.
 *
 * <p>Accepts exactly one of three mutually exclusive modes:
 * <ul>
 *   <li>{@code title_id} — revalidate a single title by ID</li>
 *   <li>{@code drain} — drain the dirty queue ({@code revalidation_pending}), up to 100 rows</li>
 *   <li>{@code safety_net} — run a safety-net slice (up to 50 UNKNOWN / stale rows)</li>
 * </ul>
 */
@Slf4j
public class RevalidateEnrichmentTool implements Tool {

    private final RevalidationService revalidationService;
    private final RevalidationPendingRepository pendingRepo;

    public RevalidateEnrichmentTool(RevalidationService revalidationService,
                                     RevalidationPendingRepository pendingRepo) {
        this.revalidationService = revalidationService;
        this.pendingRepo         = pendingRepo;
    }

    @Override public String name()        { return "revalidate_enrichment"; }
    @Override public String description() {
        return "Re-validate enrichment confidence for titles using cached filmography data (no HTTP). "
             + "Supply exactly one of: title_id (single title), drain:true (flush dirty queue up to 100), "
             + "safety_net:true (UNKNOWN/stale slice up to 50).";
    }

    @Override
    public JsonNode inputSchema() {
        return Schemas.object()
                .prop("title_id",   "integer", "Revalidate a single title by its DB id.")
                .prop("drain",      "boolean", "If true, drain the revalidation_pending dirty queue (up to 100 rows).")
                .prop("safety_net", "boolean", "If true, run a safety-net slice over UNKNOWN/stale rows (up to 50).")
                .build();
    }

    @Override
    public Object call(JsonNode args) {
        boolean hasTitleId   = args.hasNonNull("title_id");
        boolean hasDrain     = args.hasNonNull("drain")      && args.get("drain").asBoolean();
        boolean hasSafetyNet = args.hasNonNull("safety_net") && args.get("safety_net").asBoolean();

        int modeCount = (hasTitleId ? 1 : 0) + (hasDrain ? 1 : 0) + (hasSafetyNet ? 1 : 0);
        if (modeCount != 1) {
            throw new IllegalArgumentException(
                    "Supply exactly one of: title_id, drain:true, safety_net:true (got " + modeCount + " modes)");
        }

        RevalidationService.RevalidationSummary summary;
        if (hasTitleId) {
            long titleId = Schemas.requireLong(args, "title_id");
            log.info("MCP revalidate_enrichment: single title_id={}", titleId);
            summary = revalidationService.revalidateOne(titleId);
        } else if (hasDrain) {
            log.info("MCP revalidate_enrichment: drain mode");
            List<Long> ids = pendingRepo.drainBatch(100).stream()
                    .map(RevalidationPendingRepository.Pending::titleId)
                    .toList();
            log.info("MCP revalidate_enrichment: drained {} pending rows", ids.size());
            summary = revalidationService.revalidateBatch(ids);
        } else {
            log.info("MCP revalidate_enrichment: safety_net mode");
            summary = revalidationService.revalidateSafetyNetSlice(50);
        }

        log.info("MCP revalidate_enrichment: done — {}", summary.describe());
        return summary;
    }
}
