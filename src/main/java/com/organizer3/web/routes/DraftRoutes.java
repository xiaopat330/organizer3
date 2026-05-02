package com.organizer3.web.routes;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.organizer3.javdb.draft.DraftCoverScratchStore;
import com.organizer3.javdb.draft.DraftNotFoundException;
import com.organizer3.javdb.draft.DraftPopulator;
import com.organizer3.javdb.draft.DraftPromotionService;
import com.organizer3.javdb.draft.DraftTitleEnrichmentRepository;
import com.organizer3.javdb.draft.DraftTitleRepository;
import com.organizer3.javdb.draft.OptimisticLockException;
import com.organizer3.javdb.draft.PreFlightFailedException;
import com.organizer3.javdb.draft.PreFlightResult;
import com.organizer3.javdb.draft.PromotionException;
import com.organizer3.web.ImageFetcher;
import io.javalin.Javalin;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * HTTP routes for Draft Mode (Phase 2 + Phase 3: populate, cover management,
 * validate, and promote).
 *
 * <pre>
 * POST   /api/drafts/:titleId/populate      → populate a new draft from javdb
 * GET    /api/drafts/:titleId/cover         → fetch the scratch cover image
 * POST   /api/drafts/:titleId/cover/refetch → re-fetch cover from javdb
 * DELETE /api/drafts/:titleId/cover         → delete the scratch cover
 * POST   /api/drafts/:titleId/validate      → run pre-flight; returns {ok, errors}
 * POST   /api/drafts/:titleId/promote       → promote draft to canonical state
 * </pre>
 *
 * <p>{@code :titleId} is the canonical {@code titles.id}.
 *
 * <p>See spec/PROPOSAL_DRAFT_MODE.md §12.1 and §13.
 */
@Slf4j
public final class DraftRoutes {

    private static final Logger LOG = LoggerFactory.getLogger(DraftRoutes.class);

    private final DraftPopulator                  populator;
    private final DraftTitleRepository            draftTitleRepo;
    private final DraftTitleEnrichmentRepository  draftEnrichRepo;
    private final DraftCoverScratchStore          coverStore;
    private final ImageFetcher                    imageFetcher;
    private final DraftPromotionService           promotionService;
    private final ObjectMapper                    json;
    /** Needed for the bulk-enrich preview eligibility queries (Phase 6). */
    private final Jdbi                            jdbi;

    /** Full constructor (Phase 3+, Phase 6). */
    public DraftRoutes(DraftPopulator populator,
                       DraftTitleRepository draftTitleRepo,
                       DraftTitleEnrichmentRepository draftEnrichRepo,
                       DraftCoverScratchStore coverStore,
                       ImageFetcher imageFetcher,
                       DraftPromotionService promotionService,
                       ObjectMapper json,
                       Jdbi jdbi) {
        this.populator        = populator;
        this.draftTitleRepo   = draftTitleRepo;
        this.draftEnrichRepo  = draftEnrichRepo;
        this.coverStore       = coverStore;
        this.imageFetcher     = imageFetcher;
        this.promotionService = promotionService;
        this.json             = json;
        this.jdbi             = jdbi;
    }

    /** Phase 3 backward-compat constructor (no jdbi). */
    public DraftRoutes(DraftPopulator populator,
                       DraftTitleRepository draftTitleRepo,
                       DraftTitleEnrichmentRepository draftEnrichRepo,
                       DraftCoverScratchStore coverStore,
                       ImageFetcher imageFetcher,
                       DraftPromotionService promotionService,
                       ObjectMapper json) {
        this(populator, draftTitleRepo, draftEnrichRepo, coverStore, imageFetcher,
                promotionService, json, null);
    }

    /** Legacy constructor for Phase 2 tests (no promotion service, no jdbi). */
    public DraftRoutes(DraftPopulator populator,
                       DraftTitleRepository draftTitleRepo,
                       DraftTitleEnrichmentRepository draftEnrichRepo,
                       DraftCoverScratchStore coverStore,
                       ImageFetcher imageFetcher) {
        this(populator, draftTitleRepo, draftEnrichRepo, coverStore, imageFetcher, null, new ObjectMapper(), null);
    }

    public void register(Javalin app) {
        registerBulkEnrichPreview(app);

        // ── POST /api/drafts/:titleId/populate ────────────────────────────────

        app.post("/api/drafts/{titleId}/populate", ctx -> {
            long titleId;
            try {
                titleId = Long.parseLong(ctx.pathParam("titleId"));
            } catch (NumberFormatException e) {
                ctx.status(400).json(Map.of("error", "titleId must be a long integer"));
                return;
            }

            DraftPopulator.PopulateResult result = populator.populate(titleId);
            switch (result.status()) {
                case CREATED -> ctx.status(201).json(Map.of("draftTitleId", result.draftTitleId()));
                case ALREADY_EXISTS -> ctx.status(409).json(Map.of("error", "draft already exists for this title"));
                case TITLE_NOT_FOUND -> ctx.status(404).json(Map.of("error", "title not found"));
                case JAVDB_NOT_FOUND -> ctx.status(422).json(Map.of("error", "no javdb match for this title code"));
                case JAVDB_ERROR -> ctx.status(502).json(Map.of("error", "javdb fetch failed"));
            }
        });

        // ── GET /api/drafts/:titleId/cover ────────────────────────────────────

        app.get("/api/drafts/{titleId}/cover", ctx -> {
            long titleId;
            try {
                titleId = Long.parseLong(ctx.pathParam("titleId"));
            } catch (NumberFormatException e) {
                ctx.status(400).json(Map.of("error", "titleId must be a long integer"));
                return;
            }

            // Resolve titleId → draftTitleId
            var draft = draftTitleRepo.findByTitleId(titleId);
            if (draft.isEmpty()) {
                ctx.status(404).json(Map.of("error", "no active draft for this title"));
                return;
            }
            long draftId = draft.get().getId();

            Optional<InputStream> maybeStream = coverStore.openStream(draftId);
            if (maybeStream.isEmpty()) {
                ctx.status(404).json(Map.of("error", "no cover stored for this draft"));
                return;
            }
            ctx.contentType("image/jpeg").result(maybeStream.get());
        });

        // ── POST /api/drafts/:titleId/cover/refetch ───────────────────────────

        app.post("/api/drafts/{titleId}/cover/refetch", ctx -> {
            long titleId;
            try {
                titleId = Long.parseLong(ctx.pathParam("titleId"));
            } catch (NumberFormatException e) {
                ctx.status(400).json(Map.of("error", "titleId must be a long integer"));
                return;
            }

            var draft = draftTitleRepo.findByTitleId(titleId);
            if (draft.isEmpty()) {
                ctx.status(404).json(Map.of("error", "no active draft for this title"));
                return;
            }
            long draftId = draft.get().getId();

            // Look up the cover URL server-side from the stored enrichment row.
            var enrichment = draftEnrichRepo.findByDraftId(draftId);
            String coverUrl = enrichment.map(e -> e.getCoverUrl()).orElse(null);
            if (coverUrl == null || coverUrl.isBlank()) {
                ctx.status(422).json(Map.of("error", "no cover URL on file for this draft — populate first"));
                return;
            }

            try {
                ImageFetcher.Fetched fetched = imageFetcher.fetch(coverUrl);
                coverStore.write(draftId, fetched.bytes());
                ctx.status(200).json(Map.of("ok", true, "bytes", fetched.bytes().length));
            } catch (Exception e) {
                LOG.warn("cover refetch failed for draft id={} url={}", draftId, coverUrl, e);
                ctx.status(502).json(Map.of("error", "cover fetch failed: " + e.getMessage()));
            }
        });

        // ── DELETE /api/drafts/:titleId/cover ─────────────────────────────────

        app.delete("/api/drafts/{titleId}/cover", ctx -> {
            long titleId;
            try {
                titleId = Long.parseLong(ctx.pathParam("titleId"));
            } catch (NumberFormatException e) {
                ctx.status(400).json(Map.of("error", "titleId must be a long integer"));
                return;
            }

            var draft = draftTitleRepo.findByTitleId(titleId);
            if (draft.isEmpty()) {
                ctx.status(404).json(Map.of("error", "no active draft for this title"));
                return;
            }
            long draftId = draft.get().getId();

            coverStore.delete(draftId);
            ctx.status(204);
        });

        // ── POST /api/drafts/:titleId/validate ────────────────────────────────
        // Runs pre-flight; returns 200 + {ok, errors}. Never 4xx for validation failures
        // (those are user-correctable). 404 when no draft exists.

        app.post("/api/drafts/{titleId}/validate", ctx -> {
            long titleId = parseTitleId(ctx);
            if (titleId < 0) return;

            if (promotionService == null) {
                ctx.status(503).json(Map.of("error", "promotion service not configured"));
                return;
            }

            var draft = draftTitleRepo.findByTitleId(titleId);
            if (draft.isEmpty()) {
                ctx.status(404).json(Map.of("error", "no active draft for this title"));
                return;
            }

            // expectedUpdatedAt is optional for validate (no write, no lock semantics needed)
            String expectedUpdatedAt = null;
            try {
                JsonNode body = ctx.bodyAsClass(JsonNode.class);
                if (body != null && body.has("expectedUpdatedAt")) {
                    expectedUpdatedAt = body.get("expectedUpdatedAt").asText(null);
                }
            } catch (Exception ignored) { /* empty/missing body is fine for validate */ }

            try {
                PreFlightResult result = promotionService.preflight(
                        draft.get().getId(), expectedUpdatedAt);
                ctx.status(200).json(Map.of("ok", result.ok(), "errors", result.errors()));
            } catch (Exception e) {
                LOG.warn("validate: unexpected error for titleId={}", titleId, e);
                ctx.status(500).json(Map.of("error", "internal error during validation"));
            }
        });

        // ── POST /api/drafts/:titleId/promote ─────────────────────────────────
        // Validate + promote in one call.
        // 200 + {titleId} on success.
        // 404 if no draft. 409 on optimistic-lock conflict. 400/422 on validation failure.
        // 500 on unexpected error.

        app.post("/api/drafts/{titleId}/promote", ctx -> {
            long titleId = parseTitleId(ctx);
            if (titleId < 0) return;

            if (promotionService == null) {
                ctx.status(503).json(Map.of("error", "promotion service not configured"));
                return;
            }

            // Body: { expectedUpdatedAt: <token> }
            String expectedUpdatedAt = null;
            try {
                JsonNode body = ctx.bodyAsClass(JsonNode.class);
                if (body != null && body.has("expectedUpdatedAt")) {
                    expectedUpdatedAt = body.get("expectedUpdatedAt").asText(null);
                }
            } catch (Exception e) {
                ctx.status(400).json(Map.of("error", "invalid request body"));
                return;
            }

            var draft = draftTitleRepo.findByTitleId(titleId);
            if (draft.isEmpty()) {
                ctx.status(404).json(Map.of("error", "no active draft for this title"));
                return;
            }
            long draftTitleId = draft.get().getId();

            try {
                long promotedTitleId = promotionService.promote(draftTitleId, expectedUpdatedAt);
                ctx.status(200).json(Map.of("titleId", promotedTitleId));
            } catch (com.organizer3.javdb.draft.DraftNotFoundException e) {
                ctx.status(404).json(Map.of("error", "draft not found"));
            } catch (OptimisticLockException e) {
                ctx.status(409).json(Map.of("error", "conflict", "detail", e.getMessage()));
            } catch (PreFlightFailedException e) {
                ctx.status(422).json(Map.of("ok", false, "errors", e.getErrors()));
            } catch (PromotionException e) {
                LOG.error("promote: transaction failure for titleId={} code={}", titleId, e.getCode(), e);
                ctx.status(500).json(Map.of("error", e.getCode(), "detail", e.getMessage()));
            } catch (Exception e) {
                LOG.error("promote: unexpected error for titleId={}", titleId, e);
                ctx.status(500).json(Map.of("error", "internal error during promotion"));
            }
        });
    }

    /** Parses titleId from the path; writes 400 and returns -1 on failure. */
    private long parseTitleId(io.javalin.http.Context ctx) {
        try {
            return Long.parseLong(ctx.pathParam("titleId"));
        } catch (NumberFormatException e) {
            ctx.status(400).json(Map.of("error", "titleId must be a long integer"));
            return -1L;
        }
    }

    // ── Phase 6: bulk-enrich preview ─────────────────────────────────────────

    /**
     * Returns eligibility counts for a proposed bulk-enrich operation.
     *
     * <p>{@code POST /api/drafts/bulk-enrich/preview}
     * Body: {@code { "titleIds": [1, 2, 3, ...] }}
     * Response: {@code { eligibleCount, alreadyDrafted, alreadyCurated, eligibleIds: [...] }}
     *
     * <p>Used by the "Enrich N visible" confirm modal on the Queue screen to show
     * exact exclusion counts before launching the task.
     */
    void registerBulkEnrichPreview(Javalin app) {
        app.post("/api/drafts/bulk-enrich/preview", ctx -> {
            if (jdbi == null) {
                ctx.status(501).json(Map.of("error", "bulk-enrich preview not configured"));
                return;
            }
            JsonNode body = json.readTree(ctx.body());
            if (body == null || !body.has("titleIds")) {
                ctx.status(400).json(Map.of("error", "body must contain titleIds array"));
                return;
            }
            List<Long> titleIds = json.convertValue(body.get("titleIds"),
                    new TypeReference<List<Long>>() {});

            List<Long> eligible    = new ArrayList<>();
            int alreadyDrafted     = 0;
            int alreadyCurated     = 0;

            for (Long titleId : titleIds) {
                boolean hasDraft = jdbi.withHandle(h ->
                        h.createQuery("SELECT COUNT(*) FROM draft_titles WHERE title_id = :id")
                                .bind("id", titleId).mapTo(Integer.class).one()) > 0;
                if (hasDraft) {
                    alreadyDrafted++;
                    continue;
                }
                boolean hasCurated = jdbi.withHandle(h ->
                        h.createQuery("SELECT COUNT(*) FROM title_javdb_enrichment WHERE title_id = :id")
                                .bind("id", titleId).mapTo(Integer.class).one()) > 0;
                if (hasCurated) {
                    alreadyCurated++;
                    continue;
                }
                eligible.add(titleId);
            }

            ctx.json(Map.of(
                    "eligibleCount",   eligible.size(),
                    "alreadyDrafted",  alreadyDrafted,
                    "alreadyCurated",  alreadyCurated,
                    "eligibleIds",     eligible
            ));
        });
    }
}
