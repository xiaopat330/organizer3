package com.organizer3.utilities.health.checks;

import com.organizer3.utilities.health.LibraryHealthCheck;
import org.jdbi.v3.core.Jdbi;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Open rows in {@code enrichment_review_queue} grouped by reason.
 *
 * <p>Each non-zero bucket becomes one Finding so the user can see at a glance which
 * categories need attention. The sidebar total is the sum of all open queue entries.
 * Resolution happens in the future triage UI (Wave 3A); this check is surface-only.
 */
public final class EnrichmentReviewQueueCheck implements LibraryHealthCheck {

    private final Jdbi jdbi;

    public EnrichmentReviewQueueCheck(Jdbi jdbi) { this.jdbi = jdbi; }

    @Override public String id() { return "enrichment_review_queue"; }
    @Override public String label() { return "Enrichment review queue"; }
    @Override public String description() {
        return "Open queue entries from the write-time gate or revalidation: cast_anomaly (alias drift), ambiguous (code-search refusal), no_match (revalidation downgrade), fetch_failed (parse error). Triage in the resolver UI.";
    }
    @Override public FixRouting fixRouting() { return FixRouting.SURFACE_ONLY; }

    @Override
    public CheckResult run() {
        Map<String, Integer> counts = jdbi.withHandle(h -> h.createQuery("""
                        SELECT reason, COUNT(*) AS n
                        FROM enrichment_review_queue
                        WHERE resolved_at IS NULL
                        GROUP BY reason
                        ORDER BY reason
                        """)
                .map((rs, ctx) -> Map.entry(rs.getString("reason"), rs.getInt("n")))
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));

        if (counts.isEmpty()) return CheckResult.empty();

        List<Finding> rows = new ArrayList<>();
        int total = 0;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            total += e.getValue();
            rows.add(new Finding("reason:" + e.getKey(), e.getKey(), e.getValue() + " open"));
        }

        return new CheckResult(total, rows);
    }
}
