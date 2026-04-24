package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;
import com.organizer3.repository.MergeCandidateRepository;

import java.time.Instant;
import java.util.Set;

/**
 * Record a MERGE or DISMISS decision on a merge candidate.
 *
 * <p>For MERGE, {@code winnerCode} must be one of the candidate's two codes — it identifies
 * which title row survives after {@code execute_merges} runs. For DISMISS, {@code winnerCode}
 * is ignored.
 *
 * <p>Requires mutations to be enabled.
 */
public class DecideMergeCandidateTool implements Tool {

    private static final Set<String> VALID_DECISIONS = Set.of("MERGE", "DISMISS");

    private final MergeCandidateRepository repo;

    public DecideMergeCandidateTool(MergeCandidateRepository repo) {
        this.repo = repo;
    }

    @Override public String name()        { return "decide_merge_candidate"; }
    @Override public String description() {
        return "Record MERGE or DISMISS on a merge candidate pair. "
             + "For MERGE, supply winnerCode (the surviving title code). "
             + "Use execute_merges to action all pending MERGE decisions.";
    }

    @Override
    public JsonNode inputSchema() {
        return Schemas.object()
                .prop("id",         "integer", "Merge candidate id (from list_merge_candidates).")
                .prop("decision",   "string",  "MERGE or DISMISS.")
                .prop("winnerCode", "string",  "Required when decision is MERGE: the surviving title code.")
                .require("id", "decision")
                .build();
    }

    @Override
    public Object call(JsonNode args) {
        long   id         = Schemas.requireLong(args, "id");
        String decision   = Schemas.requireString(args, "decision").trim().toUpperCase();
        String winnerCode = Schemas.optString(args, "winnerCode", null);

        if (!VALID_DECISIONS.contains(decision)) {
            throw new IllegalArgumentException("decision must be MERGE or DISMISS — got: " + decision);
        }
        if ("MERGE".equals(decision) && (winnerCode == null || winnerCode.isBlank())) {
            throw new IllegalArgumentException("winnerCode is required when decision is MERGE");
        }

        String resolvedWinner = "MERGE".equals(decision) ? winnerCode.trim() : null;
        repo.decide(id, decision, resolvedWinner, Instant.now().toString());
        return new Result("recorded", id, decision, resolvedWinner);
    }

    public record Result(String outcome, long id, String decision, String winnerCode) {}
}
