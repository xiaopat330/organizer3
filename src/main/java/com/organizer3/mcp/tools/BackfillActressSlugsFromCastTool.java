package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.organizer3.javdb.enrichment.EnrichmentReviewQueueRepository;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.statement.UnableToExecuteStatementException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sqlite.SQLiteException;

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
 * <p>Conflict handling: if the slug is already owned by a different actress, no INSERT is
 * attempted; instead a {@code slug_conflict} row is written to {@code enrichment_review_queue}
 * so the user can resolve it manually. The batch continues normally — one conflict does not
 * abort the rest.
 *
 * <p>Limitations: only recovers slugs that appear in our existing cast_json. Actresses with
 * 0 correctly-enriched titles (where the slug bug affected all of theirs) need a different
 * path (manual entry or javdb actor search) — those land in the no_match queue post-cleanup.
 */
public class BackfillActressSlugsFromCastTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(BackfillActressSlugsFromCastTool.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** SQLite extended result code for UNIQUE constraint violation. */
    private static final int SQLITE_CONSTRAINT_UNIQUE = 2067;

    /** The exact constraint text that must appear for a slug conflict. */
    private static final String SLUG_CONSTRAINT_TEXT = "javdb_actress_staging.javdb_slug";

    private final Jdbi jdbi;
    private final EnrichmentReviewQueueRepository reviewQueueRepo;

    public BackfillActressSlugsFromCastTool(Jdbi jdbi) {
        this(jdbi, null);
    }

    public BackfillActressSlugsFromCastTool(Jdbi jdbi, EnrichmentReviewQueueRepository reviewQueueRepo) {
        this.jdbi = jdbi;
        this.reviewQueueRepo = reviewQueueRepo;
    }

    @Override public String name()        { return "backfill_actress_slugs_from_cast"; }
    @Override public String description() {
        return "Backfill javdb_actress_staging slugs from existing cast_json data. "
             + "Finds actresses without a slug whose stage_name appears as a cast entry in some "
             + "enriched title; writes the slug to staging. Idempotent. Set dryRun=true to preview. "
             + "If a slug is already owned by a different actress, writes a slug_conflict row to "
             + "enrichment_review_queue instead of aborting the batch.";
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
                return new Result(candidates.size(), 0, 0, candidates, List.of());
            }

            int written   = 0;
            int conflicts = 0;
            List<ConflictRow> conflictRows = new ArrayList<>();

            for (Candidate c : candidates) {
                try {
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
                } catch (UnableToExecuteStatementException ex) {
                    if (!isSlugUniqueViolation(ex)) {
                        throw ex;  // different constraint — don't swallow
                    }
                    // Slug is already owned by another actress. Surface a review row.
                    log.warn("backfill_slugs: slug '{}' already owned by another actress — claimant={} ({}); writing slug_conflict",
                            c.castSlug(), c.actressId(), c.canonicalName());
                    conflicts++;
                    ConflictRow cr = buildAndEnqueueConflict(c, h);
                    if (cr != null) conflictRows.add(cr);
                }
            }
            return new Result(candidates.size(), written, conflicts, candidates, conflictRows);
        });
    }

    /**
     * Returns true if the exception is specifically a UNIQUE constraint violation on
     * {@code javdb_actress_staging.javdb_slug}. Avoids catching other unique constraints
     * (e.g., the actress_id PK) which have their own ON CONFLICT clause.
     */
    private static boolean isSlugUniqueViolation(UnableToExecuteStatementException ex) {
        Throwable cause = ex.getCause();
        while (cause != null) {
            if (cause instanceof SQLiteException sqe) {
                int code = sqe.getResultCode().code;
                String msg = sqe.getMessage();
                return (code == SQLITE_CONSTRAINT_UNIQUE || code == 19 /* SQLITE_CONSTRAINT base */)
                        && msg != null && msg.contains(SLUG_CONSTRAINT_TEXT);
            }
            cause = cause.getCause();
        }
        return false;
    }

    /**
     * Looks up the incumbent slug owner, builds the detail JSON, and enqueues a
     * {@code slug_conflict} review row. Returns a ConflictRow for the tool response,
     * or null if the incumbent could not be found (should not happen) or review queue
     * is not configured.
     */
    private ConflictRow buildAndEnqueueConflict(Candidate claimant, org.jdbi.v3.core.Handle h) {
        // Look up the incumbent who already owns this slug.
        record IncumbentRow(long actressId, String canonicalName) {}
        var incumbentOpt = h.createQuery("""
                SELECT jas.actress_id, a.canonical_name
                FROM javdb_actress_staging jas
                JOIN actresses a ON a.id = jas.actress_id
                WHERE jas.javdb_slug = :slug
                """)
                .bind("slug", claimant.castSlug())
                .map((rs, ctx) -> new IncumbentRow(
                        rs.getLong("actress_id"),
                        rs.getString("canonical_name")))
                .findOne();

        if (incumbentOpt.isEmpty()) {
            log.warn("backfill_slugs: slug '{}' caused constraint violation but no incumbent found — skipping review row", claimant.castSlug());
            return null;
        }
        IncumbentRow incumbent = incumbentOpt.get();

        // Look up source title_id (required by the review queue schema).
        var titleIdOpt = h.createQuery("SELECT id FROM titles WHERE code = :code")
                .bind("code", claimant.sourceCode())
                .mapTo(Long.class)
                .findOne();

        if (titleIdOpt.isEmpty()) {
            log.warn("backfill_slugs: source title '{}' not found for slug_conflict — skipping review row", claimant.sourceCode());
            return new ConflictRow(claimant.actressId(), claimant.castSlug(), claimant.sourceCode(), incumbent.actressId());
        }
        long titleId = titleIdOpt.get();

        if (reviewQueueRepo == null) {
            log.debug("backfill_slugs: reviewQueueRepo not wired — slug_conflict for {} not enqueued", claimant.castSlug());
            return new ConflictRow(claimant.actressId(), claimant.castSlug(), claimant.sourceCode(), incumbent.actressId());
        }

        // Idempotency: check whether an open slug_conflict row already exists for this
        // (claimant, slug) pair using detail JSON query. The partial unique index on
        // (title_id, reason) doesn't capture our natural key, so we pre-check.
        boolean alreadyQueued = h.createQuery("""
                SELECT COUNT(*) FROM enrichment_review_queue
                WHERE reason = 'slug_conflict'
                  AND resolved_at IS NULL
                  AND json_extract(detail, '$.claimant_actress_id') = :claimantId
                  AND json_extract(detail, '$.slug') = :slug
                """)
                .bind("claimantId", claimant.actressId())
                .bind("slug", claimant.castSlug())
                .mapTo(Integer.class)
                .one() > 0;

        if (!alreadyQueued) {
            ObjectNode detail = MAPPER.createObjectNode();
            detail.put("slug",                  claimant.castSlug());
            detail.put("claimant_actress_id",   claimant.actressId());
            detail.put("claimant_actress_name", claimant.canonicalName());
            detail.put("incumbent_actress_id",  incumbent.actressId());
            detail.put("incumbent_actress_name", incumbent.canonicalName());
            detail.put("source_title_code",     claimant.sourceCode());

            reviewQueueRepo.enqueueWithDetail(
                    titleId,
                    claimant.castSlug(),
                    "slug_conflict",
                    "backfill_cast",
                    detail.toString(),
                    h);
        }

        return new ConflictRow(claimant.actressId(), claimant.castSlug(), claimant.sourceCode(), incumbent.actressId());
    }

    public record Candidate(
            long actressId,
            String canonicalName,
            String stageName,
            String castSlug,
            String sourceCode
    ) {}

    public record ConflictRow(
            long actressId,
            String slug,
            String sourceCode,
            long incumbentActressId
    ) {}

    public record Result(int candidates, int written, int conflicts,
                         List<Candidate> rows, List<ConflictRow> conflictRows) {}
}
