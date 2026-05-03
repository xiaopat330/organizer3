package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.javdb.enrichment.EnrichmentRunner;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.Jdbi;

import java.util.ArrayList;
import java.util.List;

/**
 * Backfills {@code actress_aliases} from every actress's {@code alternate_names_json}.
 *
 * <p>Iterates all actresses where {@code alternate_names_json} is non-empty. For each
 * entry, upserts any {@code alternate_names[].name} values not already present in
 * {@code actress_aliases}. At the end (unless {@code dry_run=true}), calls
 * {@link EnrichmentRunner#recoverCastAnomaliesAfterMatcherFix()} — a YAML alias backfill
 * is a matcher upgrade and should trigger the same recovery sweep.
 *
 * <p>Idempotent: a second run finds all aliases already present and inserts 0.
 * {@code dry_run=true} reports what would happen but does not write — and critically,
 * does NOT run the recovery sweep (which resolves queue rows destructively).
 */
@Slf4j
public class BackfillYamlAliasesTool implements Tool {

    private final Jdbi jdbi;
    private final EnrichmentRunner enrichmentRunner;

    public BackfillYamlAliasesTool(Jdbi jdbi, EnrichmentRunner enrichmentRunner) {
        this.jdbi = jdbi;
        this.enrichmentRunner = enrichmentRunner;
    }

    @Override public String name()        { return "backfill_yaml_aliases"; }
    @Override public String description() {
        return "Mirrors alternate_names_json entries into actress_aliases for all actresses. "
             + "Additive-only (never deletes). After inserting aliases, runs the cast-anomaly "
             + "recovery sweep (same trigger as a matcher upgrade). Idempotent. "
             + "Set dry_run=true to preview without writing or running the sweep.";
    }

    @Override
    public JsonNode inputSchema() {
        return Schemas.object()
                .prop("dry_run", "boolean",
                      "When true, report counts without writing or running the recovery sweep. Default false.",
                      false)
                .build();
    }

    @Override
    public Object call(JsonNode args) {
        boolean dryRun = args != null && args.has("dry_run") && args.get("dry_run").asBoolean(false);

        // Count actresses with alternate names — computed before any insertions
        long actressesProcessed = countActressesWithAlternateNames();

        // Load only the (actress_id, alt_name) pairs NOT yet in actress_aliases
        List<AliasCandidate> candidates = loadCandidates();

        // Count total alt entries (including already-mirrored) to derive skipped count
        int totalAltEntries = countTotalAltEntries();
        int aliasesSkippedExisting = totalAltEntries - candidates.size();

        int aliasesInserted = 0;

        for (AliasCandidate c : candidates) {
            if (!dryRun) {
                jdbi.useHandle(h -> h.createUpdate("""
                        INSERT OR IGNORE INTO actress_aliases (actress_id, alias_name)
                        VALUES (:actressId, :aliasName)
                        """)
                        .bind("actressId", c.actressId())
                        .bind("aliasName", c.altName())
                        .execute());
            }
            aliasesInserted++;
        }

        // Run cast-anomaly recovery sweep — only when not dry_run
        int openBefore = countOpenCastAnomalies();
        int castAnomalyRowsRecovered = 0;
        int castAnomalyRowsRemaining;

        if (!dryRun && enrichmentRunner != null) {
            enrichmentRunner.recoverCastAnomaliesAfterMatcherFix();
        }

        int openAfter = countOpenCastAnomalies();
        if (!dryRun) {
            castAnomalyRowsRecovered = Math.max(0, openBefore - openAfter);
        }
        castAnomalyRowsRemaining = openAfter;

        log.info("backfill_yaml_aliases: dryRun={} actressesProcessed={} aliasesInserted={} "
                + "aliasesSkippedExisting={} castAnomalyRowsRecovered={} castAnomalyRowsRemaining={}",
                dryRun, actressesProcessed, aliasesInserted, aliasesSkippedExisting,
                castAnomalyRowsRecovered, castAnomalyRowsRemaining);

        return new Result(
                (int) actressesProcessed,
                aliasesInserted,
                aliasesSkippedExisting,
                castAnomalyRowsRecovered,
                castAnomalyRowsRemaining
        );
    }

    /**
     * Returns all (actress_id, canonical_name, alt_name) pairs from alternate_names_json that
     * are NOT yet present in actress_aliases.
     */
    private List<AliasCandidate> loadCandidates() {
        List<AliasCandidate> results = new ArrayList<>();
        jdbi.useHandle(h -> h.createQuery("""
                SELECT a.id   AS actress_id,
                       a.canonical_name,
                       json_extract(alt.value, '$.name') AS alt_name
                FROM actresses a, json_each(a.alternate_names_json) AS alt
                WHERE a.alternate_names_json IS NOT NULL
                  AND a.alternate_names_json != '[]'
                  AND json_extract(alt.value, '$.name') IS NOT NULL
                  AND json_extract(alt.value, '$.name') != ''
                  AND NOT EXISTS (
                    SELECT 1 FROM actress_aliases aa
                    WHERE aa.actress_id = a.id
                      AND aa.alias_name = json_extract(alt.value, '$.name')
                  )
                ORDER BY a.id
                """)
                .map((rs, ctx) -> new AliasCandidate(
                        rs.getLong("actress_id"),
                        rs.getString("canonical_name"),
                        rs.getString("alt_name")))
                .forEach(results::add));
        return results;
    }

    private long countActressesWithAlternateNames() {
        return jdbi.withHandle(h -> h.createQuery("""
                SELECT COUNT(DISTINCT a.id)
                FROM actresses a, json_each(a.alternate_names_json) AS alt
                WHERE a.alternate_names_json IS NOT NULL
                  AND a.alternate_names_json != '[]'
                  AND json_extract(alt.value, '$.name') IS NOT NULL
                  AND json_extract(alt.value, '$.name') != ''
                """)
                .mapTo(Long.class)
                .one());
    }

    private int countTotalAltEntries() {
        return jdbi.withHandle(h -> h.createQuery("""
                SELECT COUNT(*)
                FROM actresses a, json_each(a.alternate_names_json) AS alt
                WHERE a.alternate_names_json IS NOT NULL
                  AND a.alternate_names_json != '[]'
                  AND json_extract(alt.value, '$.name') IS NOT NULL
                  AND json_extract(alt.value, '$.name') != ''
                """)
                .mapTo(Integer.class)
                .one());
    }

    private int countOpenCastAnomalies() {
        return jdbi.withHandle(h -> h.createQuery("""
                SELECT COUNT(*) FROM enrichment_review_queue
                WHERE reason = 'cast_anomaly' AND resolved_at IS NULL
                """)
                .mapTo(Integer.class)
                .one());
    }

    public record AliasCandidate(long actressId, String canonicalName, String altName) {}

    public record Result(
            int actresses_processed,
            int aliases_inserted,
            int aliases_skipped_existing,
            int cast_anomaly_rows_recovered,
            int cast_anomaly_rows_remaining
    ) {}
}
