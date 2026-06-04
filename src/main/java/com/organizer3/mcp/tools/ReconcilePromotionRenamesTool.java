package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.javdb.draft.PromotionFolderRenameReconciler;
import com.organizer3.javdb.draft.PromotionFolderRenameReconciler.Candidate;
import com.organizer3.javdb.draft.PromotionFolderRenameReconciler.ReconcileResult;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * On-demand counterpart to {@link com.organizer3.javdb.draft.PromotionRenameReconcileScheduler}.
 *
 * <p>Finds promoted titles ({@code grade_source='enrichment'}) whose live staging folder on the
 * unsorted volume is not yet normalized to {@code Actress (CODE)}, and (when {@code dryRun=false})
 * re-runs the canonical rename to heal them.
 *
 * <p>Reaches the unsorted volume through the renamer's own SMB connection factory — it does NOT
 * depend on a session-mounted volume, so there is no mount gate here.
 *
 * <p>Default {@code dryRun: true}.
 */
@Slf4j
public class ReconcilePromotionRenamesTool implements Tool {

    private final PromotionFolderRenameReconciler reconciler;

    public ReconcilePromotionRenamesTool(PromotionFolderRenameReconciler reconciler) {
        this.reconciler = reconciler;
    }

    @Override public String name() { return "reconcile_promotion_renames"; }

    @Override
    public String description() {
        return "Finds promoted titles (grade_source='enrichment') whose unsorted-volume staging "
             + "folder is not yet normalized to 'Actress (CODE)', and re-runs the canonical rename "
             + "to heal them. dryRun:true (default) returns the candidates needing rename without "
             + "mutating; dryRun:false performs the renames and returns a tally.";
    }

    @Override
    public JsonNode inputSchema() {
        return Schemas.object()
                .prop("limit",  "integer", "Max promoted titles to scan per call (default 200).")
                .prop("dryRun", "boolean", "If true (default), return candidates without renaming.", true)
                .build();
    }

    @Override
    public Object call(JsonNode args) {
        int limit = Schemas.optInt(args, "limit", 200);
        boolean dryRun = Schemas.optBoolean(args, "dryRun", true);

        if (dryRun) {
            List<Candidate> all = reconciler.findCandidates(limit);
            List<Map<String, Object>> needing = all.stream()
                    .filter(Candidate::needsRename)
                    .map(c -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("code", c.code());
                        m.put("actress", c.actressName());
                        m.put("currentPath", c.currentPath());
                        m.put("targetName", c.targetName());
                        return m;
                    })
                    .toList();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("dryRun", true);
            result.put("candidates", all.size());
            result.put("needsRename", needing.size());
            result.put("titles", needing);
            return result;
        }

        ReconcileResult tally = reconciler.reconcile(limit);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dryRun", false);
        result.put("candidates", tally.candidates());
        result.put("renamed", tally.renamed());
        result.put("alreadyOk", tally.alreadyOk());
        result.put("failed", tally.failed());
        return result;
    }
}
