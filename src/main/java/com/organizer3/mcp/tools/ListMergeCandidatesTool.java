package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;
import com.organizer3.model.MergeCandidate;
import com.organizer3.repository.MergeCandidateRepository;

import java.util.List;

/**
 * List undecided merge candidates — pairs of title codes that look like the same release
 * indexed twice under different spellings. Each candidate carries a confidence tier
 * ({@code code-normalization} or {@code variant-suffix}) and the two candidate codes.
 *
 * <p>Use {@code decide_merge_candidate} to record MERGE or DISMISS decisions, then
 * {@code execute_merges} to action MERGE decisions.
 */
public class ListMergeCandidatesTool implements Tool {

    private final MergeCandidateRepository repo;

    public ListMergeCandidatesTool(MergeCandidateRepository repo) {
        this.repo = repo;
    }

    @Override public String name()        { return "list_merge_candidates"; }
    @Override public String description() {
        return "List undecided merge candidate pairs — title codes that appear to be duplicate indexings "
             + "of the same release. Use decide_merge_candidate to record MERGE or DISMISS, "
             + "then execute_merges to action pending MERGE decisions.";
    }

    @Override
    public JsonNode inputSchema() {
        return Schemas.empty();
    }

    @Override
    public Object call(JsonNode args) {
        List<MergeCandidate> pending = repo.listPending();
        List<Row> rows = pending.stream()
                .map(c -> new Row(c.getId(), c.getTitleCodeA(), c.getTitleCodeB(),
                        c.getConfidence(), c.getDetectedAt()))
                .toList();
        return new Result(rows.size(), rows);
    }

    public record Row(long id, String titleCodeA, String titleCodeB,
                      String confidence, String detectedAt) {}
    public record Result(int count, List<Row> candidates) {}
}
