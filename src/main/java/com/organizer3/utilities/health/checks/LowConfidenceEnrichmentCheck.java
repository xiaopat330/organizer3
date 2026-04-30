package com.organizer3.utilities.health.checks;

import com.organizer3.utilities.health.LibraryHealthCheck;
import org.jdbi.v3.core.Jdbi;

import java.util.List;

/**
 * Titles in {@code title_javdb_enrichment} with {@code confidence = 'LOW'}.
 *
 * <p>LOW confidence means revalidation confirmed that the linked javdb slug does not appear
 * in any anchor actress's cached filmography — the enrichment is likely wrong. These rows
 * should be reviewed and re-enriched. HIGH and UNKNOWN are not flagged: HIGH is correct,
 * UNKNOWN is the un-revalidated steady state and would produce noise at volume.
 */
public final class LowConfidenceEnrichmentCheck implements LibraryHealthCheck {

    private static final int SAMPLE_LIMIT = 50;

    private final Jdbi jdbi;

    public LowConfidenceEnrichmentCheck(Jdbi jdbi) { this.jdbi = jdbi; }

    @Override public String id() { return "enrichment_low_confidence"; }
    @Override public String label() { return "Low-confidence enrichments"; }
    @Override public String description() {
        return "Enriched titles where javdb's cast list doesn't include the linked actress. Likely wrong-slug; review and re-enrich.";
    }
    @Override public FixRouting fixRouting() { return FixRouting.SURFACE_ONLY; }

    @Override
    public CheckResult run() {
        List<Finding> rows = jdbi.withHandle(h -> h.createQuery("""
                        SELECT tje.title_id, t.code, tje.javdb_slug, tje.resolver_source
                        FROM title_javdb_enrichment tje
                        JOIN titles t ON t.id = tje.title_id
                        WHERE tje.confidence = 'LOW'
                        ORDER BY t.code
                        LIMIT :lim
                        """)
                .bind("lim", SAMPLE_LIMIT)
                .map((rs, ctx) -> {
                    String detail = "slug=" + rs.getString("javdb_slug");
                    String src = rs.getString("resolver_source");
                    if (src != null) detail += " src=" + src;
                    return new Finding(
                            String.valueOf(rs.getLong("title_id")),
                            rs.getString("code"),
                            detail);
                })
                .list());

        int total = jdbi.withHandle(h -> h.createQuery("""
                        SELECT COUNT(*) FROM title_javdb_enrichment WHERE confidence = 'LOW'
                        """).mapTo(Integer.class).one());

        return new CheckResult(total, rows);
    }
}
