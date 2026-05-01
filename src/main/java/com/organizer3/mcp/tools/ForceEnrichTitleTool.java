package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.javdb.JavdbClient;
import com.organizer3.javdb.JavdbNotFoundException;
import com.organizer3.javdb.enrichment.EnrichmentQueue;
import com.organizer3.javdb.enrichment.EnrichmentReviewQueueRepository;
import com.organizer3.javdb.enrichment.JavdbEnrichmentRepository;
import com.organizer3.javdb.enrichment.JavdbExtractor;
import com.organizer3.javdb.enrichment.JavdbStagingRepository;
import com.organizer3.javdb.enrichment.RevalidationPendingRepository;
import com.organizer3.javdb.enrichment.TitleExtract;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;
import com.organizer3.model.Title;
import com.organizer3.repository.TitleRepository;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.Jdbi;

import java.util.regex.Pattern;

/**
 * Force-enriches a title with a user-supplied javdb slug, bypassing the resolver
 * and write-time cast gate. For rare cases where the user knows the correct slug
 * that the resolver missed or returned wrong.
 *
 * <p>Gate-bypass is by design — this is the explicit user-confirmed override path.
 */
@Slf4j
public class ForceEnrichTitleTool implements Tool {

    private static final Pattern SLUG_PATTERN = Pattern.compile("[A-Za-z0-9]+");

    private final Jdbi jdbi;
    private final TitleRepository titleRepo;
    private final JavdbClient client;
    private final JavdbExtractor extractor;
    private final JavdbStagingRepository stagingRepo;
    private final JavdbEnrichmentRepository enrichmentRepo;
    private final EnrichmentReviewQueueRepository reviewQueueRepo;
    private final RevalidationPendingRepository revalidationPendingRepo;
    private final EnrichmentQueue enrichmentQueue;

    public ForceEnrichTitleTool(Jdbi jdbi,
                                TitleRepository titleRepo,
                                JavdbClient client,
                                JavdbExtractor extractor,
                                JavdbStagingRepository stagingRepo,
                                JavdbEnrichmentRepository enrichmentRepo,
                                EnrichmentReviewQueueRepository reviewQueueRepo,
                                RevalidationPendingRepository revalidationPendingRepo,
                                EnrichmentQueue enrichmentQueue) {
        this.jdbi                   = jdbi;
        this.titleRepo              = titleRepo;
        this.client                 = client;
        this.extractor              = extractor;
        this.stagingRepo            = stagingRepo;
        this.enrichmentRepo         = enrichmentRepo;
        this.reviewQueueRepo        = reviewQueueRepo;
        this.revalidationPendingRepo = revalidationPendingRepo;
        this.enrichmentQueue        = enrichmentQueue;
    }

    @Override public String name() { return "force_enrich_title"; }

    @Override public String description() {
        return "Force-enrich a title with a user-supplied javdb slug, bypassing the resolver and "
             + "write-time cast gate. For rare javdb gaps where the user knows the correct slug. "
             + "Use dry_run=true (default) to preview without writing.";
    }

    @Override
    public JsonNode inputSchema() {
        return Schemas.object()
                .prop("title_id", "integer", "DB id of the title to enrich.")
                .prop("slug",     "string",  "javdb slug (alphanumeric only, e.g. AbCd12).")
                .prop("dry_run",  "boolean", "If true (default), parse and return without writing.")
                .require("title_id", "slug")
                .build();
    }

    @Override
    public Object call(JsonNode args) {
        long   titleId = Schemas.requireLong(args, "title_id");
        String slug    = Schemas.requireString(args, "slug").trim();
        boolean dryRun = !args.has("dry_run") || args.get("dry_run").asBoolean(true);

        if (!SLUG_PATTERN.matcher(slug).matches()) {
            throw new IllegalArgumentException(
                    "slug must match [A-Za-z0-9]+ — got: " + slug);
        }

        Title title = titleRepo.findById(titleId)
                .orElseThrow(() -> new IllegalArgumentException("Title not found: " + titleId));

        String html;
        try {
            html = client.fetchTitlePage(slug);
        } catch (JavdbNotFoundException e) {
            log.info("force_enrich: slug {} returned 404 for title {}", slug, title.getCode());
            return new Result(false, "slug_not_found", null, null);
        }

        TitleExtract extract = extractor.extractTitle(html, title.getCode(), slug);

        if (dryRun) {
            String summary = buildDryRunSummary(titleId, slug, extract);
            return new Result(true, null, extract, summary);
        }

        String rawPath = stagingRepo.saveTitleRaw(slug, extract);
        enrichmentRepo.upsertEnrichment(titleId, slug, rawPath, extract,
                "manual", "HIGH", false, "manual_override");

        // Resolve all open queue rows for this title and enqueue revalidation.
        jdbi.useTransaction(h -> {
            reviewQueueRepo.resolveAllOpenForTitle(titleId, "manual_override", h);
            revalidationPendingRepo.enqueue(titleId, "manual_override", h);
            enrichmentQueue.dischargeFailedFetchTitle(titleId, "manual_override", h);
        });

        log.info("force_enrich: wrote enrichment for title {} (code={}, slug={})",
                titleId, title.getCode(), slug);

        String summary = "Wrote enrichment for title " + titleId + " (" + title.getCode()
                + ") with slug=" + slug + ", title_original=" + extract.titleOriginal();
        return new Result(true, null, null, summary);
    }

    private String buildDryRunSummary(long titleId, String slug, TitleExtract extract) {
        return "[dry_run] Would write enrichment for title " + titleId
                + " with slug=" + slug
                + ", title_original=" + extract.titleOriginal()
                + ", cast=" + (extract.cast() == null ? 0 : extract.cast().size()) + " entries"
                + ", tags=" + (extract.tags() == null ? 0 : extract.tags().size());
    }

    /**
     * @param ok      whether the operation succeeded (or would succeed in dry_run)
     * @param error   present only when {@code ok=false}; e.g. {@code "slug_not_found"}
     * @param extract present in dry_run success: the parsed page data
     * @param message human-readable summary of what happened / would happen
     */
    public record Result(boolean ok, String error, TitleExtract extract, String message) {}
}
