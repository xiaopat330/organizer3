package com.organizer3.web.routes;

import com.organizer3.javdb.draft.DraftCoverScratchStore;
import com.organizer3.javdb.draft.DraftPopulator;
import com.organizer3.javdb.draft.DraftTitleEnrichmentRepository;
import com.organizer3.javdb.draft.DraftTitleRepository;
import com.organizer3.web.ImageFetcher;
import io.javalin.Javalin;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.Map;
import java.util.Optional;

/**
 * HTTP routes for Draft Mode (Phase 2: populate + cover management).
 *
 * <pre>
 * POST   /api/drafts/:titleId/populate      → populate a new draft from javdb
 * GET    /api/drafts/:titleId/cover         → fetch the scratch cover image
 * POST   /api/drafts/:titleId/cover/refetch → re-fetch cover from javdb
 * DELETE /api/drafts/:titleId/cover         → delete the scratch cover
 * </pre>
 *
 * <p>{@code :titleId} is the canonical {@code titles.id}.
 *
 * <p>Phase 3 routes (validate, promote, discard, GET draft, PATCH draft) are
 * not implemented here — they belong to Phase 3 of the Draft Mode project.
 *
 * <p>See spec/PROPOSAL_DRAFT_MODE.md §12.1 and §13.
 */
@Slf4j
@RequiredArgsConstructor
public final class DraftRoutes {

    private static final Logger LOG = LoggerFactory.getLogger(DraftRoutes.class);

    private final DraftPopulator                  populator;
    private final DraftTitleRepository            draftTitleRepo;
    private final DraftTitleEnrichmentRepository  draftEnrichRepo;
    private final DraftCoverScratchStore          coverStore;
    private final ImageFetcher                    imageFetcher;

    public void register(Javalin app) {

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
    }
}
