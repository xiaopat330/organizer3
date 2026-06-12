package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.javdb.enrichment.AttributionAuditService;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;

import java.util.List;

/**
 * For each multi-actress title, flag credited actresses who never co-occur with the rest
 * of the cast on any other title — a strong signal of a typo or mis-credit.
 *
 * <p>Thin wrapper delegating to {@link AttributionAuditService}. Output shape is
 * identical to the previous direct-query implementation.
 */
public class FindSuspectCreditsTool implements Tool {

    private static final int DEFAULT_MIN_CAST = 3;
    private static final int DEFAULT_LIMIT    = 50;
    private static final int MAX_LIMIT        = 500;

    private final AttributionAuditService auditService;

    public FindSuspectCreditsTool(AttributionAuditService auditService) {
        this.auditService = auditService;
    }

    @Override public String name()        { return "find_suspect_credits"; }
    @Override public String description() {
        return "Find multi-actress titles where one credited actress never co-occurs with any other cast member "
             + "on any other title. Strong signal of a typo or mis-credit (e.g. a misspelled name in a compilation).";
    }

    @Override
    public JsonNode inputSchema() {
        return Schemas.object()
                .prop("min_cast_size", "integer", "Only inspect titles with at least this many credited actresses. Default 3.", DEFAULT_MIN_CAST)
                .prop("limit",         "integer", "Maximum number of suspect credits to return. Default 50, max 500.", DEFAULT_LIMIT)
                .build();
    }

    @Override
    public Object call(JsonNode args) {
        int minCast = Math.max(2, Schemas.optInt(args, "min_cast_size", DEFAULT_MIN_CAST));
        int limit   = Math.max(1, Math.min(Schemas.optInt(args, "limit", DEFAULT_LIMIT), MAX_LIMIT));
        AttributionAuditService.SuspectResult result = auditService.findSuspectCredits(minCast, limit);
        List<Suspect> suspects = result.suspects().stream().map(d -> {
            List<Member> otherMembers = d.otherCast().stream()
                    .map(m -> new Member(m.actressId(), m.name())).toList();
            return new Suspect(d.titleId(), d.titleCode(),
                    new Member(d.suspect().actressId(), d.suspect().name()),
                    d.suspectTotalTitleCount(), otherMembers);
        }).toList();
        return new Result(suspects.size(), suspects);
    }

    public record Member(long actressId, String name) {}
    public record Suspect(
            long titleId,
            String titleCode,
            Member suspect,
            int suspectTotalTitleCount,
            List<Member> otherCast
    ) {}
    public record Result(int count, List<Suspect> suspects) {}
}
