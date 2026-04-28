package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;
import org.jdbi.v3.core.Jdbi;

import java.util.ArrayList;
import java.util.List;

/**
 * Find titles whose javdb-enrichment cast list does not contain the linked actress —
 * a strong signal that the wrong slug was picked from javdb's search results when
 * the product code happens to be reused across studios/eras (e.g. STAR-### at S1
 * and STAR-### at SOD Create are different titles with different cast).
 *
 * <p>The check joins each enriched title to its linked actresses and verifies that
 * the cast_json blob contains the actress's {@code stage_name} (or any of her
 * {@code alternate_names_json} entries). Whitespace is stripped on both sides of
 * the comparison since javdb returns names without spaces (e.g. {@code 紗倉まな})
 * but our stage_names sometimes carry a family/given separator (e.g. {@code 椎名 そら}).
 *
 * <p>Sentinel actresses (Various / Unknown / Amateur) are excluded — they're never
 * directly resolvable to a real javdb identity, so a "mismatch" is meaningless.
 *
 * <p>Limitations: this is a heuristic, not a proof. False negatives are possible if
 * javdb credits the actress under an alias we haven't recorded. The actress-anchored
 * resolver in step 3 of the slug-verification proposal is the authoritative check —
 * this tool is a fast pre-deploy measurement and a post-cleanup confirmation surface.
 */
public class FindEnrichmentCastMismatchesTool implements Tool {

    private static final int DEFAULT_LIMIT = 200;
    private static final int MAX_LIMIT     = 5000;

    private final Jdbi jdbi;

    public FindEnrichmentCastMismatchesTool(Jdbi jdbi) { this.jdbi = jdbi; }

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

        return jdbi.withHandle(h -> {
            // Total count is unbounded by limit so the caller sees the true scope.
            long total = h.createQuery("SELECT COUNT(*) " + MISMATCH_FROM + " WHERE " + MISMATCH_WHERE)
                    .mapTo(Long.class).one();

            // Sample rows ordered by actress so the caller can see which actresses are most affected.
            List<Row> rows = new ArrayList<>();
            h.createQuery("""
                    SELECT t.id            AS title_id,
                           t.code          AS code,
                           a.id            AS actress_id,
                           a.canonical_name AS actress_name,
                           a.stage_name    AS stage_name,
                           e.javdb_slug    AS javdb_slug,
                           e.title_original AS javdb_title_original
                    """ + MISMATCH_FROM + " WHERE " + MISMATCH_WHERE + """

                    ORDER BY a.canonical_name, t.code
                    LIMIT :limit
                    """)
                    .bind("limit", limit)
                    .map((rs, ctx) -> new Row(
                            rs.getLong("title_id"),
                            rs.getString("code"),
                            rs.getLong("actress_id"),
                            rs.getString("actress_name"),
                            rs.getString("stage_name"),
                            rs.getString("javdb_slug"),
                            rs.getString("javdb_title_original")))
                    .forEach(rows::add);
            return new Result(total, rows);
        });
    }

    // The mismatch query, split into FROM/WHERE so the count and sample share definitions.
    private static final String MISMATCH_FROM = """
            FROM titles t
            JOIN title_actresses ta ON ta.title_id = t.id
            JOIN actresses a        ON a.id = ta.actress_id
            JOIN title_javdb_enrichment e ON e.title_id = t.id
            """;

    // Whitespace-stripped LIKE comparison against stage_name and any alternate_names_json entry.
    // Sentinel actresses are excluded; rows missing stage_name can't be checked, so they're skipped.
    private static final String MISMATCH_WHERE = """
            COALESCE(a.is_sentinel, 0) = 0
              AND a.stage_name IS NOT NULL
              AND e.cast_json IS NOT NULL
              AND REPLACE(e.cast_json, ' ', '') NOT LIKE '%' || REPLACE(a.stage_name, ' ', '') || '%'
              AND (
                a.alternate_names_json IS NULL
                OR NOT EXISTS (
                  SELECT 1 FROM json_each(a.alternate_names_json) alt
                  WHERE json_extract(alt.value, '$.name') IS NOT NULL
                    AND REPLACE(e.cast_json, ' ', '')
                        LIKE '%' || REPLACE(json_extract(alt.value, '$.name'), ' ', '') || '%'
                )
              )
            """;

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
