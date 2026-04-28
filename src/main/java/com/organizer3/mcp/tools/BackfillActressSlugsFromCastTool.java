package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;
import org.jdbi.v3.core.Jdbi;

import java.util.ArrayList;
import java.util.List;

/**
 * Backfill {@code javdb_actress_staging.javdb_slug} for actresses whose slug appears in some
 * already-enriched title's {@code cast_json} but who don't yet have a staging row with a slug.
 *
 * <p>Covers the chicken-and-egg case where an actress's javdb slug was knowable from at least
 * one correctly-enriched title's cast list but never got recorded — typically because
 * {@code matchAndRecordActressSlug} was added after those titles were fetched, so the
 * runner never got a chance to extract the slug for them.
 *
 * <p>Step 7 of the slug-verification proposal: ensures the actress-anchored resolver path
 * has its prerequisite slug for as many actresses as possible before the cleanup task runs.
 *
 * <p>Match rule: cast entry's {@code name} (whitespace-stripped) equals the actress's
 * {@code stage_name} (whitespace-stripped). Sentinels are skipped. Existing slug rows are
 * preserved (idempotent).
 *
 * <p>Limitations: only recovers slugs that appear in our existing cast_json. Actresses with
 * 0 correctly-enriched titles (where the slug bug affected all of theirs) need a different
 * path (manual entry or javdb actor search) — those land in the no_match queue post-cleanup.
 */
public class BackfillActressSlugsFromCastTool implements Tool {

    private final Jdbi jdbi;

    public BackfillActressSlugsFromCastTool(Jdbi jdbi) { this.jdbi = jdbi; }

    @Override public String name()        { return "backfill_actress_slugs_from_cast"; }
    @Override public String description() {
        return "Backfill javdb_actress_staging slugs from existing cast_json data. "
             + "Finds actresses without a slug whose stage_name appears as a cast entry in some "
             + "enriched title; writes the slug to staging. Idempotent. Set dryRun=true to preview.";
    }

    @Override
    public JsonNode inputSchema() {
        return Schemas.object()
                .prop("dryRun", "boolean", "When true, returns the candidates without writing. Default false.", false)
                .build();
    }

    @Override
    public Object call(JsonNode args) {
        boolean dryRun = args != null && args.has("dryRun") && args.get("dryRun").asBoolean(false);

        return jdbi.inTransaction(h -> {
            // Find every (actress_id, cast_slug) pair where stage_name (whitespace-stripped)
            // matches a cast entry's name and the actress lacks a slug. The DISTINCT keeps
            // one row per actress when she's matched on multiple titles' cast lists.
            List<Candidate> candidates = new ArrayList<>();
            h.createQuery("""
                    SELECT a.id            AS actress_id,
                           a.canonical_name AS canonical_name,
                           a.stage_name    AS stage_name,
                           json_extract(c.value, '$.slug') AS cast_slug,
                           MIN(t.code)     AS source_code
                    FROM title_actresses ta
                    JOIN actresses a ON a.id = ta.actress_id
                    JOIN titles t ON t.id = ta.title_id
                    JOIN title_javdb_enrichment e ON e.title_id = ta.title_id, json_each(e.cast_json) c
                    LEFT JOIN javdb_actress_staging jas ON jas.actress_id = a.id
                    WHERE (jas.javdb_slug IS NULL OR jas.actress_id IS NULL)
                      AND COALESCE(a.is_sentinel, 0) = 0
                      AND a.stage_name IS NOT NULL
                      AND json_extract(c.value, '$.name') IS NOT NULL
                      AND REPLACE(json_extract(c.value, '$.name'), ' ', '')
                          = REPLACE(a.stage_name, ' ', '')
                    GROUP BY a.id, json_extract(c.value, '$.slug')
                    """)
                    .map((rs, ctx) -> new Candidate(
                            rs.getLong("actress_id"),
                            rs.getString("canonical_name"),
                            rs.getString("stage_name"),
                            rs.getString("cast_slug"),
                            rs.getString("source_code")))
                    .forEach(candidates::add);

            if (dryRun) {
                return new Result(candidates.size(), 0, candidates);
            }

            int written = 0;
            for (Candidate c : candidates) {
                int updated = h.createUpdate("""
                        INSERT INTO javdb_actress_staging (actress_id, javdb_slug, source_title_code, status)
                        VALUES (:actressId, :slug, :sourceCode, 'slug_only')
                        ON CONFLICT(actress_id) DO UPDATE SET
                            javdb_slug = excluded.javdb_slug,
                            source_title_code = COALESCE(javdb_actress_staging.source_title_code, excluded.source_title_code)
                        WHERE javdb_actress_staging.javdb_slug IS NULL
                        """)
                        .bind("actressId", c.actressId())
                        .bind("slug", c.castSlug())
                        .bind("sourceCode", c.sourceCode())
                        .execute();
                if (updated > 0) written++;
            }
            return new Result(candidates.size(), written, candidates);
        });
    }

    public record Candidate(
            long actressId,
            String canonicalName,
            String stageName,
            String castSlug,
            String sourceCode
    ) {}

    public record Result(int candidates, int written, List<Candidate> rows) {}
}
