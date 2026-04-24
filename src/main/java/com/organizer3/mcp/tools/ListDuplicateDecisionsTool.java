package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;
import com.organizer3.model.DuplicateDecision;
import com.organizer3.repository.DuplicateDecisionRepository;

import java.util.List;

/**
 * List all pending Duplicate Triage decisions (executed_at IS NULL).
 * Decisions are keyed by (titleCode, volumeId, nasPath) and carry a
 * KEEP | TRASH | VARIANT verdict.
 */
public class ListDuplicateDecisionsTool implements Tool {

    private final DuplicateDecisionRepository repo;

    public ListDuplicateDecisionsTool(DuplicateDecisionRepository repo) {
        this.repo = repo;
    }

    @Override public String name()        { return "list_duplicate_decisions"; }
    @Override public String description() {
        return "List all pending Duplicate Triage decisions (KEEP / TRASH / VARIANT) that have not yet been executed.";
    }

    @Override
    public JsonNode inputSchema() {
        return Schemas.empty();
    }

    @Override
    public Object call(JsonNode args) {
        List<DuplicateDecision> pending = repo.listPending();
        List<Row> rows = pending.stream()
                .map(d -> new Row(d.getTitleCode(), d.getVolumeId(), d.getNasPath(),
                        d.getDecision(), d.getCreatedAt()))
                .toList();
        return new Result(rows.size(), rows);
    }

    public record Row(String titleCode, String volumeId, String nasPath,
                      String decision, String createdAt) {}
    public record Result(int count, List<Row> decisions) {}
}
