package com.organizer3.web.routes;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.organizer3.javdb.draft.DraftActress;
import com.organizer3.javdb.draft.DraftActressRepository;
import com.organizer3.javdb.draft.DraftCoverScratchStore;
import com.organizer3.javdb.draft.DraftNotFoundException;
import com.organizer3.javdb.draft.DraftPatchService;
import com.organizer3.javdb.draft.DraftPopulator;
import com.organizer3.javdb.draft.DraftPromotionService;
import com.organizer3.javdb.draft.DraftTitle;
import com.organizer3.javdb.draft.DraftTitleActress;
import com.organizer3.javdb.draft.DraftTitleActressesRepository;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * HTTP routes for Draft Mode (Phase 2 + Phase 3: populate, cover management,
 * validate, and promote).
 *
 * <pre>
 * GET    /api/drafts                        → list all active drafts (for queue badging)
 * POST   /api/drafts/:titleId/populate      → populate a new draft from javdb
 * GET    /api/drafts/:titleId               → fetch full draft (title + actresses + enrichment)
 * PATCH  /api/drafts/:titleId               → apply cast resolution / name edits
 * DELETE /api/drafts/:titleId               → discard draft
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
    private final DraftTitleActressesRepository   draftTitleActressesRepo;
    private final DraftActressRepository          draftActressRepo;
    private final DraftCoverScratchStore          coverStore;
    private final ImageFetcher                    imageFetcher;
    private final DraftPromotionService           promotionService;
    private final DraftPatchService               patchService;
    private final ObjectMapper                    json;
    /** Needed for the bulk-enrich preview eligibility queries (Phase 6). */
    private final Jdbi                            jdbi;

    /** Full constructor (Phase 4). */
    public DraftRoutes(DraftPopulator populator,
                       DraftTitleRepository draftTitleRepo,
                       DraftTitleEnrichmentRepository draftEnrichRepo,
                       DraftTitleActressesRepository draftTitleActressesRepo,
                       DraftActressRepository draftActressRepo,
                       DraftCoverScratchStore coverStore,
                       ImageFetcher imageFetcher,
                       DraftPromotionService promotionService,
                       DraftPatchService patchService,
                       ObjectMapper json,
                       Jdbi jdbi) {
        this.populator                = populator;
        this.draftTitleRepo           = draftTitleRepo;
        this.draftEnrichRepo          = draftEnrichRepo;
        this.draftTitleActressesRepo  = draftTitleActressesRepo;
        this.draftActressRepo         = draftActressRepo;
        this.coverStore               = coverStore;
        this.imageFetcher             = imageFetcher;
        this.promotionService         = promotionService;
        this.patchService             = patchService;
        this.json                     = json;
        this.jdbi                     = jdbi;
    }

    /** Backward-compat constructor (Phase 3+, Phase 6 — no patch service). */
    public DraftRoutes(DraftPopulator populator,
                       DraftTitleRepository draftTitleRepo,
                       DraftTitleEnrichmentRepository draftEnrichRepo,
                       DraftCoverScratchStore coverStore,
                       ImageFetcher imageFetcher,
                       DraftPromotionService promotionService,
                       ObjectMapper json,
                       Jdbi jdbi) {
        this(populator, draftTitleRepo, draftEnrichRepo, null, null, coverStore,
                imageFetcher, promotionService, null, json, jdbi);
    }

    /** Backward-compat constructor (Phase 3, no jdbi). */
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
        registerListDrafts(app);
        registerGetDraft(app);
        registerPatchDraft(app);
        registerDeleteDraft(app);

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

    // ── Phase 4: list / get / patch / delete ──────────────────────────────────

    /**
     * {@code GET /api/drafts} — list all active draft titles.
     *
     * <p>Used by the queue sidebar to badge rows that have an open draft.
     * Response: array of {@code { titleId, code, updatedAt }}.
     */
    void registerListDrafts(Javalin app) {
        app.get("/api/drafts", ctx -> {
            // listAll uses offset/limit; for badge display we return all active drafts
            // (volume in practice is small — one draft per title; no pagination in v1).
            List<DraftTitle> all = draftTitleRepo.listAll(0, 10_000);
            List<Map<String, Object>> result = new ArrayList<>();
            for (DraftTitle dt : all) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("titleId",   dt.getTitleId());
                row.put("code",      dt.getCode());
                row.put("updatedAt", dt.getUpdatedAt());
                result.add(row);
            }
            ctx.json(result);
        });
    }

    /**
     * {@code GET /api/drafts/:titleId} — fetch the full current draft.
     *
     * <p>Returns an aggregate including draft title metadata, enrichment, all
     * cast-slot rows with their resolved actress data, and a flag indicating
     * whether the scratch cover is present.
     *
     * <p>Response fields:
     * <pre>
     * {
     *   titleId, code, updatedAt, upstreamChanged, createdAt,
     *   titleOriginal, releaseDate,
     *   enrichment: { javdbSlug, maker, series, tagsJson, castJson,
     *                 ratingAvg, ratingCount, coverUrl },
     *   cast: [{ javdbSlug, stageName, resolution, linkToExistingId,
     *            englishLastName, englishFirstName }],
     *   coverScratchPresent: bool
     * }
     * </pre>
     *
     * <p>Returns 404 if no active draft exists for the given title.
     */
    void registerGetDraft(Javalin app) {
        app.get("/api/drafts/{titleId}", ctx -> {
            long titleId = parseTitleId(ctx);
            if (titleId < 0) return;

            var draft = draftTitleRepo.findByTitleId(titleId);
            if (draft.isEmpty()) {
                ctx.status(404).json(Map.of("error", "no active draft for this title"));
                return;
            }
            DraftTitle dt = draft.get();

            var enrichmentOpt = draftEnrichRepo.findByDraftId(dt.getId());

            List<DraftTitleActress> slots = draftTitleActressesRepo != null
                    ? draftTitleActressesRepo.findByDraftTitleId(dt.getId())
                    : List.of();

            // Build cast array with actress name data joined in.
            List<Map<String, Object>> cast = new ArrayList<>();
            for (DraftTitleActress slot : slots) {
                Map<String, Object> slotMap = new LinkedHashMap<>();
                slotMap.put("javdbSlug",  slot.getJavdbSlug());
                slotMap.put("resolution", slot.getResolution());

                // Join actress data if available.
                if (draftActressRepo != null) {
                    var actressOpt = draftActressRepo.findBySlug(slot.getJavdbSlug());
                    actressOpt.ifPresent(a -> {
                        slotMap.put("stageName",        a.getStageName());
                        slotMap.put("englishLastName",  a.getEnglishLastName());
                        slotMap.put("englishFirstName", a.getEnglishFirstName());
                        slotMap.put("linkToExistingId", a.getLinkToExistingId());
                    });
                }
                cast.add(slotMap);
            }

            // Enrich resolved slots with canonical name + avatar from the actresses table.
            if (jdbi != null) {
                List<Long> linkedIds = cast.stream()
                        .filter(s -> s.get("linkToExistingId") instanceof Long)
                        .map(s -> (Long) s.get("linkToExistingId"))
                        .distinct().toList();
                if (!linkedIds.isEmpty()) {
                    record ActressProfile(long id, String canonicalName, String avatarPath) {}
                    Map<Long, ActressProfile> profiles = jdbi.withHandle(h ->
                            h.createQuery("""
                                SELECT a.id,
                                       a.canonical_name,
                                       COALESCE(a.custom_avatar_path, s.local_avatar_path) AS avatar_path
                                FROM actresses a
                                LEFT JOIN javdb_actress_staging s ON s.actress_id = a.id
                                WHERE a.id IN (<ids>)
                                """)
                             .bindList("ids", linkedIds)
                             .map((rs, c) -> new ActressProfile(
                                     rs.getLong("id"),
                                     rs.getString("canonical_name"),
                                     rs.getString("avatar_path")))
                             .list()
                    ).stream().collect(java.util.stream.Collectors.toMap(ActressProfile::id, p -> p));
                    for (Map<String, Object> s : cast) {
                        if (!(s.get("linkToExistingId") instanceof Long id)) continue;
                        ActressProfile p = profiles.get(id);
                        if (p == null) continue;
                        s.put("linkedActressName", p.canonicalName());
                        if (p.avatarPath() != null)
                            s.put("linkedActressAvatarUrl", "/" + p.avatarPath());
                    }
                }
            }

            Map<String, Object> enrichmentMap = new LinkedHashMap<>();
            enrichmentOpt.ifPresent(e -> {
                enrichmentMap.put("javdbSlug",   e.getJavdbSlug());
                enrichmentMap.put("maker",       e.getMaker());
                enrichmentMap.put("series",      e.getSeries());
                enrichmentMap.put("tagsJson",    e.getTagsJson());
                enrichmentMap.put("castJson",    e.getCastJson());
                enrichmentMap.put("ratingAvg",   e.getRatingAvg());
                enrichmentMap.put("ratingCount", e.getRatingCount());
                enrichmentMap.put("coverUrl",    e.getCoverUrl());
            });

            // Resolve enrichment tags via curated_alias so the draft pane can highlight
            // canonical catalog tags without knowing raw javdb strings.
            if (jdbi != null && enrichmentOpt.isPresent()) {
                String rawTagsJson = enrichmentOpt.get().getTagsJson();
                if (rawTagsJson != null && !rawTagsJson.isBlank()) {
                    try {
                        List<String> rawTags = json.readValue(rawTagsJson, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
                        if (!rawTags.isEmpty()) {
                            List<String> resolvedTags = jdbi.withHandle(h ->
                                h.createQuery("""
                                    SELECT curated_alias
                                    FROM enrichment_tag_definitions
                                    WHERE name IN (<names>)
                                      AND curated_alias IS NOT NULL
                                    """)
                                 .bindList("names", rawTags)
                                 .mapTo(String.class)
                                 .list()
                            );
                            enrichmentMap.put("resolvedTags", resolvedTags);
                        }
                    } catch (Exception ignored) {}
                }
            }

            // Compute grade from the enrichment rating data using the stored rating curve.
            if (jdbi != null && enrichmentOpt.isPresent()) {
                var e = enrichmentOpt.get();
                if (e.getRatingAvg() != null && e.getRatingCount() != null && e.getRatingCount() > 0) {
                    Optional<String> grade = jdbi.withHandle(h ->
                        h.createQuery("SELECT global_mean, global_count, min_credible_votes, cutoffs_json, computed_at FROM rating_curve WHERE id = 1")
                         .map((rs, c) -> com.organizer3.rating.RatingCurve.fromRow(
                                 rs.getDouble("global_mean"),
                                 rs.getInt("global_count"),
                                 rs.getInt("min_credible_votes"),
                                 rs.getString("cutoffs_json"),
                                 rs.getString("computed_at")))
                         .findFirst()
                         .flatMap(curve -> new com.organizer3.rating.RatingScoreCalculator()
                                 .gradeFor(e.getRatingAvg(), e.getRatingCount(), curve)
                                 .map(g -> g.display))
                    );
                    grade.ifPresent(g -> enrichmentMap.put("grade", g));
                }
            }

            boolean hasScratchCover = coverStore.exists(dt.getId());

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("titleId",            dt.getTitleId());
            resp.put("code",               dt.getCode());
            resp.put("updatedAt",          dt.getUpdatedAt());
            resp.put("upstreamChanged",    dt.isUpstreamChanged());
            resp.put("createdAt",          dt.getCreatedAt());
            resp.put("titleOriginal",      dt.getTitleOriginal());
            resp.put("releaseDate",        dt.getReleaseDate());
            resp.put("enrichment",         enrichmentMap);
            resp.put("cast",               cast);
            resp.put("coverScratchPresent", hasScratchCover);

            ctx.json(resp);
        });
    }

    /**
     * {@code PATCH /api/drafts/:titleId} — apply cast resolution / name edits.
     *
     * <p>Body shape:
     * <pre>
     * {
     *   "expectedUpdatedAt": "2024-01-01T00:00:00Z",
     *   "castResolutions": [
     *     { "javdbSlug": "abc", "resolution": "pick", "linkToExistingId": 123 }
     *   ],
     *   "newActresses": [
     *     { "javdbSlug": "xyz", "englishLastName": "Sakura", "englishFirstName": "Mana" }
     *   ]
     * }
     * </pre>
     *
     * <p>Returns 200 + {@code { updatedAt: <new token> }} on success.
     * Returns 404 if no draft, 409 on optimistic-lock conflict, 400 on validation failure,
     * 503 if patch service not configured.
     */
    void registerPatchDraft(Javalin app) {
        app.patch("/api/drafts/{titleId}", ctx -> {
            long titleId = parseTitleId(ctx);
            if (titleId < 0) return;

            if (patchService == null) {
                ctx.status(503).json(Map.of("error", "patch service not configured"));
                return;
            }

            // Parse body.
            JsonNode body;
            try {
                body = json.readTree(ctx.body());
            } catch (Exception e) {
                ctx.status(400).json(Map.of("error", "invalid JSON body"));
                return;
            }

            String expectedUpdatedAt = body.has("expectedUpdatedAt")
                    ? body.get("expectedUpdatedAt").asText(null)
                    : null;

            // Parse castResolutions.
            List<DraftPatchService.CastResolutionEdit> castResolutions = new ArrayList<>();
            if (body.has("castResolutions") && body.get("castResolutions").isArray()) {
                for (JsonNode item : body.get("castResolutions")) {
                    String javdbSlug  = item.has("javdbSlug")  ? item.get("javdbSlug").asText(null)  : null;
                    String resolution = item.has("resolution") ? item.get("resolution").asText(null) : null;
                    Long   linkId     = item.has("linkToExistingId") && !item.get("linkToExistingId").isNull()
                            ? item.get("linkToExistingId").asLong() : null;
                    String lastName   = item.has("englishLastName")  ? item.get("englishLastName").asText(null)  : null;
                    String firstName  = item.has("englishFirstName") ? item.get("englishFirstName").asText(null) : null;
                    if (javdbSlug == null || resolution == null) {
                        ctx.status(400).json(Map.of("error", "each castResolution must have javdbSlug and resolution"));
                        return;
                    }
                    castResolutions.add(new DraftPatchService.CastResolutionEdit(
                            javdbSlug, resolution, linkId, lastName, firstName));
                }
            }

            // Parse newActresses.
            List<DraftPatchService.NewActressEdit> newActresses = new ArrayList<>();
            if (body.has("newActresses") && body.get("newActresses").isArray()) {
                for (JsonNode item : body.get("newActresses")) {
                    String javdbSlug = item.has("javdbSlug")        ? item.get("javdbSlug").asText(null)        : null;
                    String lastName  = item.has("englishLastName")  ? item.get("englishLastName").asText(null)  : null;
                    String firstName = item.has("englishFirstName") ? item.get("englishFirstName").asText(null) : null;
                    if (javdbSlug == null) {
                        ctx.status(400).json(Map.of("error", "each newActress must have javdbSlug"));
                        return;
                    }
                    newActresses.add(new DraftPatchService.NewActressEdit(javdbSlug, lastName, firstName));
                }
            }

            try {
                String newToken = patchService.patch(titleId, expectedUpdatedAt, castResolutions, newActresses);
                ctx.json(Map.of("updatedAt", newToken));
            } catch (DraftNotFoundException e) {
                ctx.status(404).json(Map.of("error", "no active draft for this title"));
            } catch (OptimisticLockException e) {
                ctx.status(409).json(Map.of("error", "conflict", "detail", e.getMessage()));
            } catch (DraftPatchService.PatchValidationException e) {
                ctx.status(400).json(Map.of("errors", e.getErrors()));
            } catch (Exception e) {
                LOG.error("PATCH draft: unexpected error for titleId={}", titleId, e);
                ctx.status(500).json(Map.of("error", "internal error during patch"));
            }
        });
    }

    /**
     * {@code DELETE /api/drafts/:titleId} — discard the draft.
     *
     * <p>Drops the draft rows (cascades to cast slots + enrichment) and deletes
     * the scratch cover if present. Returns 204 on success, 404 if no draft.
     */
    void registerDeleteDraft(Javalin app) {
        app.delete("/api/drafts/{titleId}", ctx -> {
            long titleId = parseTitleId(ctx);
            if (titleId < 0) return;

            var draft = draftTitleRepo.findByTitleId(titleId);
            if (draft.isEmpty()) {
                ctx.status(404).json(Map.of("error", "no active draft for this title"));
                return;
            }
            long draftId = draft.get().getId();

            // Delete scratch cover first (best-effort; draft cascade handles DB rows).
            coverStore.delete(draftId);

            draftTitleRepo.delete(draftId);
            LOG.info("Draft discarded: titleId={} draftId={}", titleId, draftId);
            ctx.status(204);
        });
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
