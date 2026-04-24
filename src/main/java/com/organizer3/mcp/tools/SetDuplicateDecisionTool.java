package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;
import com.organizer3.model.DuplicateDecision;
import com.organizer3.repository.DuplicateDecisionRepository;

import java.time.Instant;
import java.util.Set;

/**
 * Record or clear a Duplicate Triage decision for one title location.
 *
 * <p>Pass {@code decision = "KEEP"}, {@code "TRASH"}, or {@code "VARIANT"} to upsert.
 * Pass {@code decision = "CLEAR"} to remove the decision entirely (no-op if absent).
 * Requires mutations to be enabled.
 */
public class SetDuplicateDecisionTool implements Tool {

    private static final Set<String> VALID_DECISIONS = Set.of("KEEP", "TRASH", "VARIANT", "CLEAR");

    private final DuplicateDecisionRepository repo;

    public SetDuplicateDecisionTool(DuplicateDecisionRepository repo) {
        this.repo = repo;
    }

    @Override public String name()        { return "set_duplicate_decision"; }
    @Override public String description() {
        return "Record or clear a Duplicate Triage decision (KEEP/TRASH/VARIANT/CLEAR) "
             + "for one location of a duplicate title.";
    }

    @Override
    public JsonNode inputSchema() {
        return Schemas.object()
                .prop("titleCode", "string", "Product code (e.g. 'MIDE-123').")
                .prop("volumeId",  "string", "Volume id the location lives on.")
                .prop("nasPath",   "string", "Full NAS path of the location (e.g. '//nas/jav/stars/...').")
                .prop("decision",  "string", "KEEP | TRASH | VARIANT | CLEAR. CLEAR removes the decision.")
                .require("titleCode", "volumeId", "nasPath", "decision")
                .build();
    }

    @Override
    public Object call(JsonNode args) {
        String titleCode = Schemas.requireString(args, "titleCode").trim();
        String volumeId  = Schemas.requireString(args, "volumeId").trim();
        String nasPath   = Schemas.requireString(args, "nasPath").trim();
        String decision  = Schemas.requireString(args, "decision").trim().toUpperCase();

        if (!VALID_DECISIONS.contains(decision)) {
            throw new IllegalArgumentException(
                    "decision must be KEEP, TRASH, VARIANT, or CLEAR — got: " + decision);
        }

        if ("CLEAR".equals(decision)) {
            repo.delete(titleCode, volumeId, nasPath);
            return new Result("cleared", titleCode, volumeId, nasPath, null);
        }

        repo.upsert(DuplicateDecision.builder()
                .titleCode(titleCode)
                .volumeId(volumeId)
                .nasPath(nasPath)
                .decision(decision)
                .createdAt(Instant.now().toString())
                .build());
        return new Result("upserted", titleCode, volumeId, nasPath, decision);
    }

    public record Result(String outcome, String titleCode, String volumeId,
                         String nasPath, String decision) {}
}
