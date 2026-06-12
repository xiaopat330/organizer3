package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.javdb.enrichment.AttributionAuditService;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;

import java.util.List;

/**
 * Find titles whose javdb-enrichment cast list does not contain the linked actress —
 * a strong signal that the wrong slug was picked from javdb's search results when
 * the product code happens to be reused across studios/eras.
 *
 * <p>Thin wrapper delegating to {@link AttributionAuditService}. Output shape is
 * identical to the previous direct-query implementation.
 */
public class FindEnrichmentCastMismatchesTool implements Tool {

    private static final int DEFAULT_LIMIT = 200;
    private static final int MAX_LIMIT     = 5000;

    private final AttributionAuditService auditService;

    public FindEnrichmentCastMismatchesTool(AttributionAuditService auditService) {
        this.auditService = auditService;
    }

    @Override public String name()        { return "find_enrichment_cast_mismatches"; }
    @Override public String description() {
        return "Find enriched titles whose javdb cast does not include the actress they're linked to. "
             + "Strong signal that the wrong slug was picked during enrichment (code-reuse collision). "
             + "Returns count + sample rows. Sentinels excluded.";
    }

    @Override
    public JsonNode inputSchema() {
        return Schemas.object()
                .prop("limit", "integer", "Maximum sample rows to return. Default 200, max 5000.", DEFAULT_LIMIT)
                .build();
    }

    @Override
    public Object call(JsonNode args) {
        int limit = Math.max(1, Math.min(Schemas.optInt(args, "limit", DEFAULT_LIMIT), MAX_LIMIT));
        long total = auditService.countCastMismatches();
        List<AttributionAuditService.MismatchDetail> details = auditService.listCastMismatches(limit);
        List<Row> rows = details.stream().map(d -> new Row(
                d.titleId(), d.code(), d.actressId(), d.actressName(),
                d.stageName(), d.javdbSlug(), d.javdbTitleOriginal())).toList();
        return new Result(total, rows);
    }

    public record Row(
            long titleId,
            String code,
            long actressId,
            String actressName,
            String stageName,
            String javdbSlug,
            String javdbTitleOriginal
    ) {}

    public record Result(long total, List<Row> sample) {}
}
