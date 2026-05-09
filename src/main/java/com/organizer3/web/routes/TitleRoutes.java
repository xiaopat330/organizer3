package com.organizer3.web.routes;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.organizer3.config.volume.OrganizerConfig;
import com.organizer3.config.volume.ServerConfig;
import com.organizer3.config.volume.VolumeConfig;
import com.organizer3.model.Title;
import com.organizer3.model.Video;
import com.organizer3.repository.TitleRepository;
import com.organizer3.repository.VideoRepository;
import com.organizer3.smb.SmbConnectionFactory;
import com.organizer3.titlefolder.TitleFolderService;
import com.organizer3.titlefolder.TitleFolderService.MovePair;
import com.organizer3.trash.Trash;
import com.organizer3.web.ActressBrowseService;
import com.organizer3.web.TagCatalogLoader;
import com.organizer3.web.TitleBrowseService;
import com.organizer3.web.TitleBrowseService.FlagResult;
import io.javalin.Javalin;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Title-browse routes: /api/titles/*, /api/labels/*, /api/tags, /api/tools/*,
 * /api/studio-groups/*, /api/pool/*, /api/collections/*, /api/companies,
 * plus mutation routes on /api/titles/{code}/visit|favorite|bookmark.
 *
 * {@code actressBrowseService} and {@code titleRepo} may be null; the
 * corresponding routes are skipped when the dep is absent.
 */
@Slf4j
public class TitleRoutes {

    private final TitleBrowseService browseService;
    private final ActressBrowseService actressBrowseService;
    private final TitleRepository titleRepo;
    private final TitleFolderService folderService;
    private final SmbConnectionFactory smbFactory;
    private final OrganizerConfig organizerConfig;
    private final VideoRepository videoRepo;

    /** Backwards-compatible 3-arg constructor; folder-contents routes are inactive. */
    public TitleRoutes(TitleBrowseService browseService,
                       ActressBrowseService actressBrowseService,
                       TitleRepository titleRepo) {
        this(browseService, actressBrowseService, titleRepo, null, null, null, null);
    }

    /** Full constructor — injects folder-contents dependencies to activate the three new routes. */
    public TitleRoutes(TitleBrowseService browseService,
                       ActressBrowseService actressBrowseService,
                       TitleRepository titleRepo,
                       TitleFolderService folderService,
                       SmbConnectionFactory smbFactory,
                       OrganizerConfig organizerConfig,
                       VideoRepository videoRepo) {
        this.browseService        = browseService;
        this.actressBrowseService = actressBrowseService;
        this.titleRepo            = titleRepo;
        this.folderService        = folderService;
        this.smbFactory           = smbFactory;
        this.organizerConfig      = organizerConfig;
        this.videoRepo            = videoRepo;
    }

    public void register(Javalin app) {
        app.get("/api/titles", ctx -> {
            String search              = ctx.queryParam("search");
            String favorites           = ctx.queryParam("favorites");
            String bookmarks           = ctx.queryParam("bookmarks");
            String tagsParam           = ctx.queryParam("tags");
            String enrichTagIdsParam   = ctx.queryParam("enrichmentTagIds");
            String codeParam           = ctx.queryParam("code");
            String company             = ctx.queryParam("company");
            String sort                = ctx.queryParam("sort");
            String order               = ctx.queryParam("order");
            int offset = ctx.queryParamAsClass("offset", Integer.class).getOrDefault(0);
            int limit  = ctx.queryParamAsClass("limit",  Integer.class).getOrDefault(24);
            offset = Math.max(offset, 0);
            limit  = Math.max(1, limit);
            boolean hasEnrichTags = enrichTagIdsParam != null && !enrichTagIdsParam.isBlank();
            if (search != null && !search.isBlank()) {
                ctx.json(browseService.searchByCodePaged(search.trim(), offset, limit));
            } else if ("true".equals(favorites)) {
                ctx.json(browseService.findFavoritesPaged(offset, limit));
            } else if ("true".equals(bookmarks)) {
                ctx.json(browseService.findBookmarksPaged(offset, limit));
            } else if (codeParam != null || company != null || sort != null || order != null
                       || (tagsParam != null && !tagsParam.isBlank()) || hasEnrichTags) {
                List<String> tags = (tagsParam != null && !tagsParam.isBlank())
                        ? List.of(tagsParam.split(",")) : List.of();
                List<Long> enrichmentTagIds = (enrichTagIdsParam != null && !enrichTagIdsParam.isBlank())
                        ? java.util.Arrays.stream(enrichTagIdsParam.split(","))
                            .map(String::trim).filter(s -> !s.isEmpty())
                            .map(Long::parseLong).toList()
                        : List.of();
                ctx.json(browseService.findLibraryPaged(codeParam, company, tags, enrichmentTagIds, sort, order, offset, limit));
            } else {
                ctx.json(browseService.findRecent(offset, limit));
            }
        });

        app.get("/api/titles/tag-counts", ctx -> {
            long totalTitles = browseService.countAll();
            Map<String, Long> counts = browseService.getTagCounts();
            ctx.json(Map.of("totalTitles", totalTitles, "counts", counts));
        });

        app.get("/api/labels/autocomplete", ctx -> {
            String prefix = ctx.queryParam("prefix");
            ctx.json(browseService.labelAutocomplete(prefix));
        });

        app.get("/api/tags", ctx -> ctx.json(new TagCatalogLoader().load()));
        app.get("/api/tools/volumes", ctx -> ctx.json(browseService.listVolumes()));
        app.get("/api/tools/duplicates", ctx -> {
            int offset = ctx.queryParamAsClass("offset", Integer.class).getOrDefault(0);
            int limit  = ctx.queryParamAsClass("limit",  Integer.class).getOrDefault(50);
            String volumeId = ctx.queryParam("volumeId");
            offset = Math.max(offset, 0);
            ctx.json(browseService.findDuplicatesPaged(offset, limit, volumeId));
        });
        app.get("/api/titles/labels",  ctx -> ctx.json(browseService.listLabels()));
        app.get("/api/titles/studios", ctx -> ctx.json(browseService.listStudioGroups()));
        app.get("/api/studio-groups/{slug}/companies", ctx -> {
            String slug = ctx.pathParam("slug");
            ctx.json(actressBrowseService.listGroupCompaniesByTitleCount(slug));
        });
        app.get("/api/titles/top-actresses", ctx -> {
            String labelsParam = ctx.queryParam("labels");
            if (labelsParam == null || labelsParam.isBlank()) { ctx.json(List.of()); return; }
            List<String> labels = List.of(labelsParam.split(","));
            int limit = ctx.queryParamAsClass("limit", Integer.class).getOrDefault(10);
            ctx.json(browseService.topActressesByLabels(labels, Math.min(limit, 50)));
        });
        app.get("/api/titles/newest-actresses", ctx -> {
            String labelsParam = ctx.queryParam("labels");
            if (labelsParam == null || labelsParam.isBlank()) { ctx.json(List.of()); return; }
            List<String> labels = List.of(labelsParam.split(","));
            int limit = ctx.queryParamAsClass("limit", Integer.class).getOrDefault(10);
            ctx.json(browseService.newestActressesByLabels(labels, Math.min(limit, 50)));
        });

        app.get("/api/titles/dashboard", ctx ->
                ctx.json(browseService.buildDashboard()));

        app.get("/api/titles/spotlight", ctx -> {
            String exclude = ctx.queryParam("exclude");
            var result = browseService.getSpotlight(exclude);
            if (result == null) ctx.status(204);
            else ctx.json(result);
        });

        app.get("/api/titles/random", ctx -> {
            int limit = ctx.queryParamAsClass("limit", Integer.class).getOrDefault(24);
            limit = Math.max(1, limit);
            ctx.json(browseService.findRandom(limit));
        });

        app.get("/api/queues/{volumeId}/titles", ctx -> {
            String volumeId = ctx.pathParam("volumeId");
            int offset = ctx.queryParamAsClass("offset", Integer.class).getOrDefault(0);
            int limit  = ctx.queryParamAsClass("limit",  Integer.class).getOrDefault(24);
            offset = Math.max(offset, 0);
            limit  = Math.max(1, limit);
            ctx.json(browseService.findByVolumeQueue(volumeId, offset, limit));
        });

        app.get("/api/pool/{volumeId}/titles", ctx -> {
            String volumeId = ctx.pathParam("volumeId");
            int offset = ctx.queryParamAsClass("offset", Integer.class).getOrDefault(0);
            int limit  = ctx.queryParamAsClass("limit",  Integer.class).getOrDefault(24);
            offset = Math.max(offset, 0);
            limit  = Math.max(1, limit);
            String company   = ctx.queryParam("company");
            String tagsParam = ctx.queryParam("tags");
            List<String> tags = (tagsParam != null && !tagsParam.isBlank())
                    ? List.of(tagsParam.split(",")) : List.of();
            if ((company != null && !company.isBlank()) || !tags.isEmpty()) {
                ctx.json(browseService.findByVolumePartitionFiltered(volumeId, "pool", company, tags, offset, limit));
            } else {
                ctx.json(browseService.findByVolumePartition(volumeId, "pool", offset, limit));
            }
        });

        app.get("/api/pool/{volumeId}/tags", ctx -> {
            String volumeId = ctx.pathParam("volumeId");
            ctx.json(browseService.findTagsForPool(volumeId));
        });

        app.get("/api/collections/titles", ctx -> {
            int offset = ctx.queryParamAsClass("offset", Integer.class).getOrDefault(0);
            int limit  = ctx.queryParamAsClass("limit",  Integer.class).getOrDefault(24);
            offset = Math.max(offset, 0);
            limit  = Math.max(1, limit);
            String company   = ctx.queryParam("company");
            String tagsParam = ctx.queryParam("tags");
            List<String> tags = (tagsParam != null && !tagsParam.isBlank())
                    ? List.of(tagsParam.split(",")) : List.of();
            if ((company != null && !company.isBlank()) || !tags.isEmpty()) {
                ctx.json(browseService.findByVolumePagedFiltered("collections", company, tags, offset, limit));
            } else {
                ctx.json(browseService.findByVolumePaged("collections", offset, limit));
            }
        });

        app.get("/api/collections/tags", ctx -> ctx.json(browseService.findTagsForCollections()));
        app.get("/api/companies", ctx -> ctx.json(browseService.listAllCompanies()));

        app.post("/api/titles/{code}/visit", ctx -> {
            String code = ctx.pathParam("code");
            browseService.recordVisit(code).ifPresentOrElse(
                    stats -> ctx.json(Map.of(
                            "visitCount", stats.visitCount(),
                            "lastVisitedAt", stats.lastVisitedAt() != null ? stats.lastVisitedAt() : "")),
                    () -> ctx.status(404).json(Map.of("error", "Title not found"))
            );
        });

        app.post("/api/titles/{code}/favorite", ctx -> {
            String code = ctx.pathParam("code");
            FlagResult result = browseService.toggleFavorite(code);
            if (result instanceof FlagResult.NotFound) {
                ctx.status(404).json(Map.of("error", "Title not found"));
            } else if (result instanceof FlagResult.Refused r) {
                ctx.status(400).json(Map.of("error", r.reason()));
            } else {
                var state = ((FlagResult.Ok) result).state();
                log.info("Title modified — code={} favorite={}", code, state.favorite());
                ctx.json(Map.of("code", state.code(), "favorite", state.favorite(),
                        "bookmark", state.bookmark(), "rejected", state.rejected()));
            }
        });

        app.post("/api/titles/{code}/bookmark", ctx -> {
            String code = ctx.pathParam("code");
            FlagResult result = browseService.toggleBookmark(code);
            if (result instanceof FlagResult.NotFound) {
                ctx.status(404).json(Map.of("error", "Title not found"));
            } else if (result instanceof FlagResult.Refused r) {
                ctx.status(400).json(Map.of("error", r.reason()));
            } else {
                var state = ((FlagResult.Ok) result).state();
                log.info("Title modified — code={} bookmark={}", code, state.bookmark());
                ctx.json(Map.of("code", state.code(), "favorite", state.favorite(),
                        "bookmark", state.bookmark(), "rejected", state.rejected()));
            }
        });

        app.post("/api/titles/{code}/reject", ctx -> {
            String code = ctx.pathParam("code");
            FlagResult result = browseService.toggleRejected(code);
            if (result instanceof FlagResult.NotFound) {
                ctx.status(404).json(Map.of("error", "Title not found"));
            } else {
                var state = ((FlagResult.Ok) result).state();
                log.info("Title modified — code={} rejected={}", code, state.rejected());
                ctx.json(Map.of("code", state.code(), "favorite", state.favorite(),
                        "bookmark", state.bookmark(), "rejected", state.rejected()));
            }
        });

        if (folderService != null && smbFactory != null && organizerConfig != null) {
            registerFolderContentsRoutes(app);
        }
    }

    /**
     * Registers only the three folder-contents routes. Called by
     * {@code WebServer.registerTitleFolderContents} after the 3-arg TitleRoutes have already
     * registered the base title routes; avoids double-registering everything.
     */
    public void registerFolderContentsOnly(Javalin app) {
        if (folderService != null && smbFactory != null && organizerConfig != null) {
            registerFolderContentsRoutes(app);
        }
    }

    private void registerFolderContentsRoutes(Javalin app) {
        // ── GET /api/titles/{code}/folder-contents ─────────────────────────────
        app.get("/api/titles/{code}/folder-contents", ctx -> {
            String code = ctx.pathParam("code");
            Title title = titleRepo.findByCode(code).orElse(null);
            if (title == null) {
                ctx.status(404).json(Map.of("error", "Title not found"));
                return;
            }
            List<?> locs = title.getLocations();
            if (locs.size() != 1) {
                ctx.status(400).json(Map.of("error",
                        "title has " + locs.size() + " locations; folder-contents requires exactly 1"));
                return;
            }

            String volumeId = title.getVolumeId();
            Path folder     = title.getPath();
            try (var handle = smbFactory.open(volumeId)) {
                var fs = handle.fileSystem();
                var contents = folderService.listContents(fs, code, volumeId, folder);
                ctx.json(contents);
            } catch (IOException e) {
                log.warn("GET folder-contents failed for {} — {}", code, e.getMessage());
                ctx.status(500).json(Map.of("error", "Could not open volume: " + e.getMessage()));
            }
        });

        // ── POST /api/titles/{code}/videos/{filename}/trash ────────────────────
        app.post("/api/titles/{code}/videos/{filename}/trash", ctx -> {
            String code     = ctx.pathParam("code");
            String filename = ctx.pathParam("filename"); // Javalin URL-decodes path params

            Title title = titleRepo.findByCode(code).orElse(null);
            if (title == null) {
                ctx.status(404).json(Map.of("error", "Title not found"));
                return;
            }
            List<?> locs = title.getLocations();
            if (locs.size() != 1) {
                ctx.status(400).json(Map.of("error",
                        "title has " + locs.size() + " locations; folder-contents requires exactly 1"));
                return;
            }

            // Look up the Video DB row for this title + filename.
            Optional<Video> videoOpt = videoRepo.findByTitle(title.getId()).stream()
                    .filter(v -> filename.equals(v.getFilename()))
                    .findFirst();
            if (videoOpt.isEmpty()) {
                ctx.status(404).json(Map.of("error", "video not found in this title's folder"));
                return;
            }
            Video video = videoOpt.get();

            String volumeId = title.getVolumeId();
            try {
                Trash trash = buildTrash(volumeId, Clock.systemUTC());
                TitleFolderService.TrashOutcome outcome =
                        folderService.trashVideo(trash, video, "Admin tab — per-row trash");
                if (outcome.success()) {
                    log.info("HTTP trash video — code={} filename={} trashedTo={}", code, filename, outcome.trashedTo());
                    ctx.json(Map.of("success", true, "trashedTo", outcome.trashedTo().toString()));
                } else {
                    log.warn("HTTP trash video failed — code={} filename={} error={}", code, filename, outcome.error());
                    ctx.status(500).json(Map.of("error", outcome.error()));
                }
            } catch (IllegalArgumentException e) {
                ctx.status(400).json(Map.of("error", e.getMessage()));
            } catch (IOException e) {
                log.warn("HTTP trash video IO error — code={} filename={} error={}", code, filename, e.getMessage());
                ctx.status(500).json(Map.of("error", "Could not open volume: " + e.getMessage()));
            }
        });

        // ── GET /api/titles/{code}/normalize-proposal ─────────────────────────
        //
        // Returns the proposed set of file moves needed to bring the title's folder
        // to canonical layout (covers at base named {CODE}.ext; videos in subfolder).
        // Optional query param ?excludeRelPaths=rel1,rel2 lets the client exclude
        // files with pending trash stages from the proposal.
        app.get("/api/titles/{code}/normalize-proposal", ctx -> {
            String code = ctx.pathParam("code");
            Title title = titleRepo.findByCode(code).orElse(null);
            if (title == null) {
                ctx.status(404).json(Map.of("error", "Title not found"));
                return;
            }
            List<?> locs = title.getLocations();
            if (locs.size() != 1) {
                ctx.status(400).json(Map.of("error",
                        "title has " + locs.size() + " locations; normalization requires exactly 1"));
                return;
            }

            String excludeParam = ctx.queryParam("excludeRelPaths");
            Set<String> excludes = (excludeParam != null && !excludeParam.isBlank())
                    ? Set.of(excludeParam.split(","))
                    : Set.of();

            String volumeId = title.getVolumeId();
            Path folder     = title.getPath();
            try (var handle = smbFactory.open(volumeId)) {
                var fs = handle.fileSystem();
                var plan = folderService.planNormalization(fs, code, folder, excludes);
                ctx.json(plan);
            } catch (IllegalArgumentException e) {
                log.warn("GET normalize-proposal validation error for {} — {}", code, e.getMessage());
                ctx.status(400).json(Map.of("error", e.getMessage()));
            } catch (IOException e) {
                log.warn("GET normalize-proposal failed for {} — {}", code, e.getMessage());
                ctx.status(500).json(Map.of("error", "Could not open volume: " + e.getMessage()));
            }
        });

        // ── POST /api/titles/{code}/apply-moves ───────────────────────────────
        //
        // Executes the user-confirmed move set. Body (JSON):
        //   { "moves": [{"from": "...", "to": "..."}, ...] }
        //
        // On validation failure returns 400 before any FS mutation.
        app.post("/api/titles/{code}/apply-moves", ctx -> {
            String code = ctx.pathParam("code");
            Title title = titleRepo.findByCode(code).orElse(null);
            if (title == null) {
                ctx.status(404).json(Map.of("error", "Title not found"));
                return;
            }
            List<?> locs = title.getLocations();
            if (locs.size() != 1) {
                ctx.status(400).json(Map.of("error",
                        "title has " + locs.size() + " locations; normalize requires exactly 1"));
                return;
            }

            // Parse request body.
            Map<String, Object> body;
            try {
                body = new ObjectMapper().readValue(ctx.body(), new TypeReference<>() {});
            } catch (Exception e) {
                ctx.status(400).json(Map.of("error", "Invalid JSON body: " + e.getMessage()));
                return;
            }

            // Extract moves list from body.
            List<MovePair> moves;
            Object movesRaw = body.get("moves");
            if (movesRaw instanceof List<?> movesList) {
                try {
                    moves = new ObjectMapper().convertValue(movesList,
                            new TypeReference<List<MovePair>>() {});
                } catch (Exception e) {
                    ctx.status(400).json(Map.of("error", "Invalid moves format: " + e.getMessage()));
                    return;
                }
            } else {
                ctx.status(400).json(Map.of("error", "Request body must contain a 'moves' array"));
                return;
            }

            if (moves.isEmpty()) {
                ctx.json(Map.of("movedCount", 0, "moved", List.of()));
                return;
            }

            String volumeId = title.getVolumeId();
            Path folder     = title.getPath();
            try (var handle = smbFactory.open(volumeId)) {
                var fs      = handle.fileSystem();
                var outcome = folderService.executeNormalization(fs, folder, moves);
                log.info("HTTP apply-moves — code={} movedCount={}", code, outcome.movedCount());
                ctx.json(outcome);
            } catch (IllegalArgumentException e) {
                log.warn("POST apply-moves validation failed for {} — {}", code, e.getMessage());
                ctx.status(400).json(Map.of("error", e.getMessage()));
            } catch (IOException e) {
                log.warn("POST apply-moves IO error for {} — {}", code, e.getMessage());
                ctx.status(500).json(Map.of("error", "Could not open volume: " + e.getMessage()));
            }
        });

        // ── POST /api/titles/{code}/covers/{filename}/trash ────────────────────
        app.post("/api/titles/{code}/covers/{filename}/trash", ctx -> {
            String code     = ctx.pathParam("code");
            String filename = ctx.pathParam("filename"); // Javalin URL-decodes path params

            // Reject filenames that contain path separators — base-level only.
            if (filename.contains("/") || filename.contains("\\")
                    || filename.equals(".") || filename.equals("..")) {
                ctx.status(400).json(Map.of("error", "filename must be a base-level filename"));
                return;
            }

            Title title = titleRepo.findByCode(code).orElse(null);
            if (title == null) {
                ctx.status(404).json(Map.of("error", "Title not found"));
                return;
            }
            List<?> locs = title.getLocations();
            if (locs.size() != 1) {
                ctx.status(400).json(Map.of("error",
                        "title has " + locs.size() + " locations; folder-contents requires exactly 1"));
                return;
            }

            String volumeId = title.getVolumeId();
            Path folder     = title.getPath();

            // Path-traversal guard: resolved path must stay within folder.
            Path resolved  = folder.resolve(filename).normalize();
            Path normalFolder = folder.normalize();
            if (!resolved.startsWith(normalFolder) || resolved.equals(normalFolder)) {
                ctx.status(400).json(Map.of("error", "filename must be a base-level filename"));
                return;
            }

            try (var handle = smbFactory.open(volumeId)) {
                var fs = handle.fileSystem();
                if (!fs.exists(resolved)) {
                    ctx.status(404).json(Map.of("error", "cover not found at title folder base"));
                    return;
                }

                Trash trash = buildTrash(volumeId, Clock.systemUTC());
                TitleFolderService.TrashOutcome outcome =
                        folderService.trashCover(trash, folder, filename, "Admin tab — per-row trash");
                if (outcome.success()) {
                    log.info("HTTP trash cover — code={} filename={} trashedTo={}", code, filename, outcome.trashedTo());
                    ctx.json(Map.of("success", true, "trashedTo", outcome.trashedTo().toString()));
                } else {
                    log.warn("HTTP trash cover failed — code={} filename={} error={}", code, filename, outcome.error());
                    ctx.status(500).json(Map.of("error", outcome.error()));
                }
            } catch (IllegalArgumentException e) {
                ctx.status(400).json(Map.of("error", e.getMessage()));
            } catch (IOException e) {
                log.warn("HTTP trash cover IO error — code={} filename={} error={}", code, filename, e.getMessage());
                ctx.status(500).json(Map.of("error", "Could not open volume: " + e.getMessage()));
            }
        });
    }

    /**
     * Builds a {@link Trash} primitive for the given volume.
     *
     * @throws IllegalArgumentException if the volume or server is not configured or has no trash folder
     * @throws IOException if the SMB share cannot be opened
     */
    private Trash buildTrash(String volumeId, Clock clock) throws IOException {
        VolumeConfig vol = organizerConfig.findById(volumeId).orElseThrow(
                () -> new IllegalArgumentException("Volume not in config: " + volumeId));
        ServerConfig srv = organizerConfig.findServerById(vol.server()).orElseThrow(
                () -> new IllegalArgumentException("Server not in config: " + vol.server()));
        if (srv.trash() == null || srv.trash().isBlank()) {
            throw new IllegalArgumentException(
                    "Server '" + srv.id() + "' has no 'trash:' folder configured.");
        }
        var handle = smbFactory.open(volumeId);
        var fs     = handle.fileSystem();
        return new Trash(fs, volumeId, srv.trash(), clock);
    }
}
