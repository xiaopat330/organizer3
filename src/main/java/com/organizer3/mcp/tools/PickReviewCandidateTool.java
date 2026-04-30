package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.organizer3.javdb.enrichment.EnrichmentReviewQueueRepository;
import com.organizer3.javdb.enrichment.JavdbEnrichmentRepository;
import com.organizer3.javdb.enrichment.JavdbStagingRepository;
import com.organizer3.javdb.enrichment.RevalidationPendingRepository;
import com.organizer3.javdb.enrichment.TitleExtract;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.Jdbi;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Picks one candidate from an ambiguous review queue row's snapshot and writes enrichment.
 *
 * <p>Uses the snapshot stored in {@code enrichment_review_queue.detail} — no re-fetch.
 * Gate-bypass by design: the picker is itself the confirmation gate (spec §3A line 369).
 *
 * <p>Mutation-gated.
 */
@Slf4j
public class PickReviewCandidateTool implements Tool {

    private static final Pattern SLUG_PATTERN = Pattern.compile("[A-Za-z0-9]+");

    private final Jdbi jdbi;
    private final EnrichmentReviewQueueRepository reviewQueueRepo;
    private final JavdbEnrichmentRepository enrichmentRepo;
    private final JavdbStagingRepository stagingRepo;
    private final RevalidationPendingRepository revalidationPendingRepo;
    private final ObjectMapper json;

    public PickReviewCandidateTool(Jdbi jdbi,
                                   EnrichmentReviewQueueRepository reviewQueueRepo,
                                   JavdbEnrichmentRepository enrichmentRepo,
                                   JavdbStagingRepository stagingRepo,
                                   RevalidationPendingRepository revalidationPendingRepo) {
        this.jdbi                   = jdbi;
        this.reviewQueueRepo        = reviewQueueRepo;
        this.enrichmentRepo         = enrichmentRepo;
        this.stagingRepo            = stagingRepo;
        this.revalidationPendingRepo = revalidationPendingRepo;
        this.json                   = new ObjectMapper();
    }

    @Override public String name() { return "pick_review_candidate"; }

    @Override public String description() {
        return "Pick one candidate from an ambiguous review-queue row and write enrichment from the "
             + "stored snapshot. No re-fetch — uses detail.candidates[]. "
             + "Returns ok=false with an error code if the row is missing, already resolved, "
             + "wrong reason, or the snapshot is absent/the slug not found in it.";
    }

    @Override
    public JsonNode inputSchema() {
        return Schemas.object()
                .prop("queue_row_id", "integer", "Id of the open ambiguous review queue row.")
                .prop("slug",         "string",  "javdb slug to pick (must appear in detail.candidates[].slug).")
                .require("queue_row_id", "slug")
                .build();
    }

    @Override
    public Object call(JsonNode args) throws Exception {
        long   queueRowId = Schemas.requireLong(args, "queue_row_id");
        String slug       = Schemas.requireString(args, "slug").trim();

        if (!SLUG_PATTERN.matcher(slug).matches()) {
            throw new IllegalArgumentException("slug must match [A-Za-z0-9]+ — got: " + slug);
        }

        EnrichmentReviewQueueRepository.OpenRow row = reviewQueueRepo.findOpenById(queueRowId)
                .orElse(null);
        if (row == null) {
            return Result.err("row_not_found", "Queue row " + queueRowId + " not found or already resolved");
        }
        if (!"ambiguous".equals(row.reason())) {
            return Result.err("wrong_reason", "Row " + queueRowId + " has reason '" + row.reason()
                    + "' — pick_review_candidate only applies to ambiguous rows");
        }
        if (row.detail() == null || row.detail().isBlank()) {
            return Result.err("snapshot_missing",
                    "Row " + queueRowId + " has no candidate snapshot — call refresh_review_candidates first");
        }

        JsonNode snapshot;
        try {
            snapshot = json.readTree(row.detail());
        } catch (Exception e) {
            return Result.err("snapshot_parse_error", "Could not parse detail JSON: " + e.getMessage());
        }

        JsonNode candidatesNode = snapshot.path("candidates");
        if (!candidatesNode.isArray() || candidatesNode.isEmpty()) {
            return Result.err("snapshot_missing", "Snapshot has no candidates");
        }

        JsonNode matched = null;
        for (JsonNode c : candidatesNode) {
            if (slug.equals(c.path("slug").asText(null))) {
                matched = c;
                break;
            }
        }
        if (matched == null) {
            return Result.err("slug_not_in_candidates",
                    "Slug '" + slug + "' not found in snapshot candidates for row " + queueRowId);
        }

        String titleCode = snapshot.path("code").asText(row.titleCode());
        TitleExtract extract = buildExtract(titleCode, slug, matched);

        String rawPath = stagingRepo.saveTitleRaw(slug, extract);
        jdbi.useTransaction(h -> {
            enrichmentRepo.upsertEnrichment(row.titleId(), slug, rawPath, extract,
                    "manual_picker", "HIGH", false, "manual_picker");
            reviewQueueRepo.resolveAllOpenForTitle(row.titleId(), "manual_picker", h);
            revalidationPendingRepo.enqueue(row.titleId(), "manual_picker", h);
        });

        log.info("pick_review_candidate: wrote enrichment for title {} (code={}, slug={}) via picker",
                row.titleId(), titleCode, slug);
        return Result.ok("Enrichment written for " + titleCode + " with slug=" + slug);
    }

    private TitleExtract buildExtract(String titleCode, String slug, JsonNode c) {
        List<TitleExtract.CastEntry> cast = new ArrayList<>();
        for (JsonNode ce : c.path("cast")) {
            cast.add(new TitleExtract.CastEntry(
                    ce.path("slug").asText(null),
                    ce.path("name").asText(null),
                    ce.path("gender").asText(null)));
        }

        List<String> tags = new ArrayList<>();
        for (JsonNode t : c.path("tags")) tags.add(t.asText());

        List<String> thumbs = new ArrayList<>();
        for (JsonNode t : c.path("thumbnail_urls")) thumbs.add(t.asText());

        return new TitleExtract(
                titleCode,
                slug,
                c.path("title_original").asText(null),
                c.path("release_date").asText(null),
                c.hasNonNull("duration_minutes") ? c.get("duration_minutes").asInt() : null,
                c.path("maker").asText(null),
                c.path("publisher").asText(null),
                c.path("series").asText(null),
                c.hasNonNull("rating_avg")   ? c.get("rating_avg").asDouble()   : null,
                c.hasNonNull("rating_count") ? c.get("rating_count").asInt()    : null,
                tags,
                cast,
                c.path("cover_url").asText(null),
                thumbs,
                c.path("fetched_at").asText(null),
                c.path("cast_empty").asBoolean(false),
                false
        );
    }

    public record Result(boolean ok, String error, String message) {
        static Result ok(String message)              { return new Result(true,  null,  message); }
        static Result err(String error, String msg)   { return new Result(false, error, msg); }
    }
}
