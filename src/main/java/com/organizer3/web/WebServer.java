package com.organizer3.web;

import com.hierynomus.smbj.share.File;
import com.organizer3.config.AppConfig;
import com.organizer3.config.volume.VolumeConfig;
import com.organizer3.covers.CoverPath;
import com.organizer3.media.ThumbnailService;
import com.organizer3.media.VideoProbe;
import com.organizer3.model.Video;
import com.organizer3.model.WatchHistory;
import com.organizer3.model.Title;
import com.organizer3.repository.TitleRepository;
import com.organizer3.repository.WatchHistoryRepository;
import com.organizer3.smb.SmbConnectionFactory.SmbShareHandle;
import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

/**
 * Embedded web server for read-only browsing and querying.
 *
 * Starts alongside the interactive shell and shuts down when the app exits.
 * All endpoints are read-only — no mutations are exposed through the web layer.
 */
@Slf4j
public class WebServer {
    public static final int DEFAULT_PORT = 8080;

    private static final Map<String, String> MIME_TYPES = Map.of(
            "jpg",  "image/jpeg",
            "jpeg", "image/jpeg",
            "png",  "image/png",
            "webp", "image/webp",
            "gif",  "image/gif"
    );

    private final Javalin app;
    private final int port;

    /** Full constructor: enables title browsing, actress browsing, cover serving, and video streaming. */
    public WebServer(int port, TitleBrowseService browseService,
                     ActressBrowseService actressBrowseService, Path coversRoot,
                     VideoStreamService videoStreamService, ThumbnailService thumbnailService,
                     VideoProbe videoProbe, WatchHistoryRepository watchHistoryRepo,
                     TitleRepository titleRepo, SearchService searchService) {
        this.port = port;
        this.app = Javalin.create(config ->
                config.staticFiles.add("/public", Location.CLASSPATH));
        registerRoutes(browseService, actressBrowseService, coversRoot,
                videoStreamService, thumbnailService, videoProbe, watchHistoryRepo, titleRepo,
                searchService);
    }

    /** Convenience constructor using the default port. */
    public WebServer(TitleBrowseService browseService,
                     ActressBrowseService actressBrowseService, Path coversRoot,
                     VideoStreamService videoStreamService, ThumbnailService thumbnailService,
                     VideoProbe videoProbe, WatchHistoryRepository watchHistoryRepo,
                     TitleRepository titleRepo, SearchService searchService) {
        this(DEFAULT_PORT, browseService, actressBrowseService, coversRoot,
                videoStreamService, thumbnailService, videoProbe, watchHistoryRepo, titleRepo,
                searchService);
    }

    /** Minimal constructor for tests that only need the lifecycle and static file behaviour. */
    public WebServer(int port) {
        this(port, null, null, null, null, null, null, null, null, null);
    }

    private void registerRoutes(TitleBrowseService browseService,
                                ActressBrowseService actressBrowseService, Path coversRoot,
                                VideoStreamService videoStreamService,
                                ThumbnailService thumbnailService,
                                VideoProbe videoProbe,
                                WatchHistoryRepository watchHistoryRepo,
                                TitleRepository titleRepo,
                                SearchService searchService) {
        app.get("/api/config", ctx -> {
            var cfg = AppConfig.get().volumes();
            String appName = cfg.appName();
            var result = new LinkedHashMap<String, Object>();
            result.put("appName", appName != null ? appName : "organizer3");
            result.put("maxBrowseTitles",    cfg.maxBrowseTitles()    != null ? cfg.maxBrowseTitles()    : 500);
            result.put("maxRandomTitles",    cfg.maxRandomTitles()    != null ? cfg.maxRandomTitles()    : 500);
            result.put("maxRandomActresses", cfg.maxRandomActresses() != null ? cfg.maxRandomActresses() : 500);
            result.put("thumbnailColumns",   cfg.thumbnailColumns()   != null ? cfg.thumbnailColumns()   : 5);
            result.put("coverCropPercent",   cfg.coverCropPercent()   != null ? cfg.coverCropPercent()   : 47);
            var exhibitionVolumes = cfg.volumes().stream()
                    .filter(v -> "exhibition".equals(v.group()))
                    .map(VolumeConfig::id)
                    .toList();
            var archiveVolumes = cfg.volumes().stream()
                    .filter(v -> "archive".equals(v.group()))
                    .map(VolumeConfig::id)
                    .toList();
            result.put("exhibitionVolumes", exhibitionVolumes);
            result.put("archiveVolumes", archiveVolumes);
            ctx.json(result);
        });

        app.get("/api/queues/volumes", ctx -> {
            var cfg = AppConfig.get().volumes();
            // Conventional volumes: have a structured partition (stars/) AND a "queue" unstructured partition.
            // These are library volumes with a queue/ inbox subfolder.
            var conventionalWithQueueTypes = cfg.structures().stream()
                    .filter(s -> s.structuredPartition() != null &&
                                 s.unstructuredPartitions() != null &&
                                 s.unstructuredPartitions().stream().anyMatch(p -> "queue".equals(p.id())))
                    .map(s -> s.id())
                    .collect(Collectors.toSet());
            // Pool volumes: no structured partition, primary partition is "queue" (pure intake volumes).
            var queueOnlyTypes = cfg.structures().stream()
                    .filter(s -> s.structuredPartition() == null &&
                                 s.unstructuredPartitions() != null &&
                                 s.unstructuredPartitions().stream().anyMatch(p -> "queue".equals(p.id())))
                    .map(s -> s.id())
                    .collect(Collectors.toSet());
            var pool = cfg.volumes().stream()
                    .filter(v -> queueOnlyTypes.contains(v.structureType()))
                    .map(v -> Map.of("id", (Object) v.id(), "smbPath", (Object) v.smbPath()))
                    .findFirst()
                    .orElse(null);
            // Sort-pool volumes: flat directory of title folders (structureType = sort_pool).
            var sortPoolTypes = cfg.structures().stream()
                    .filter(s -> "sort_pool".equals(s.id()))
                    .map(s -> s.id())
                    .collect(Collectors.toSet());
            var sortPool = cfg.volumes().stream()
                    .filter(v -> sortPoolTypes.contains(v.structureType()) && !"classic_pool".equals(v.id()))
                    .map(v -> Map.of("id", (Object) v.id(), "smbPath", (Object) v.smbPath()))
                    .findFirst()
                    .orElse(null);
            var classicPool = cfg.volumes().stream()
                    .filter(v -> "classic_pool".equals(v.id()))
                    .map(v -> Map.of("id", (Object) v.id(), "smbPath", (Object) v.smbPath()))
                    .findFirst()
                    .orElse(null);
            var volumes = cfg.volumes().stream()
                    .filter(v -> conventionalWithQueueTypes.contains(v.structureType()))
                    .map(v -> Map.of("id", (Object) v.id(), "smbPath", (Object) v.smbPath()))
                    .toList();
            var result = new LinkedHashMap<String, Object>();
            if (pool != null) result.put("pool", pool);
            if (sortPool != null) result.put("sortPool", sortPool);
            if (classicPool != null) result.put("classicPool", classicPool);
            result.put("volumes", volumes);
            ctx.json(result);
        });

        if (browseService != null) {
            app.get("/api/titles", ctx -> {
                String search    = ctx.queryParam("search");
                String favorites = ctx.queryParam("favorites");
                String bookmarks = ctx.queryParam("bookmarks");
                String tagsParam = ctx.queryParam("tags");
                int offset = ctx.queryParamAsClass("offset", Integer.class).getOrDefault(0);
                int limit  = ctx.queryParamAsClass("limit",  Integer.class).getOrDefault(24);
                offset = Math.max(offset, 0);
                limit  = Math.max(1, Math.min(limit, TitleBrowseService.MAX_LIMIT));
                if (search != null && !search.isBlank()) {
                    ctx.json(browseService.searchByCodePaged(search.trim(), offset, limit));
                } else if ("true".equals(favorites)) {
                    ctx.json(browseService.findFavoritesPaged(offset, limit));
                } else if ("true".equals(bookmarks)) {
                    ctx.json(browseService.findBookmarksPaged(offset, limit));
                } else if (tagsParam != null && !tagsParam.isBlank()) {
                    List<String> tags = List.of(tagsParam.split(","));
                    ctx.json(browseService.findByTagsPaged(tags, offset, limit));
                } else {
                    ctx.json(browseService.findRecent(offset, limit));
                }
            });

            app.get("/api/tags", ctx -> ctx.json(new TagCatalogLoader().load()));
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

            app.get("/api/titles/dashboard", ctx -> {
                ctx.json(browseService.buildDashboard());
            });

            app.get("/api/titles/spotlight", ctx -> {
                String exclude = ctx.queryParam("exclude");
                var result = browseService.getSpotlight(exclude);
                if (result == null) ctx.status(204);
                else ctx.json(result);
            });

            app.get("/api/titles/random", ctx -> {
                int limit = ctx.queryParamAsClass("limit", Integer.class).getOrDefault(24);
                limit = Math.max(1, Math.min(limit, TitleBrowseService.MAX_LIMIT));
                ctx.json(browseService.findRandom(limit));
            });

            app.get("/api/queues/{volumeId}/titles", ctx -> {
                String volumeId = ctx.pathParam("volumeId");
                int offset = ctx.queryParamAsClass("offset", Integer.class).getOrDefault(0);
                int limit  = ctx.queryParamAsClass("limit",  Integer.class).getOrDefault(24);
                offset = Math.max(offset, 0);
                limit  = Math.max(1, Math.min(limit, TitleBrowseService.MAX_LIMIT));
                ctx.json(browseService.findByVolumeQueue(volumeId, offset, limit));
            });

            app.get("/api/pool/{volumeId}/titles", ctx -> {
                String volumeId = ctx.pathParam("volumeId");
                int offset = ctx.queryParamAsClass("offset", Integer.class).getOrDefault(0);
                int limit  = ctx.queryParamAsClass("limit",  Integer.class).getOrDefault(24);
                offset = Math.max(offset, 0);
                limit  = Math.max(1, Math.min(limit, TitleBrowseService.MAX_LIMIT));
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
                limit  = Math.max(1, Math.min(limit, TitleBrowseService.MAX_LIMIT));
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

            app.get("/api/collections/tags", ctx -> {
                ctx.json(browseService.findTagsForCollections());
            });

            app.get("/api/companies", ctx -> {
                ctx.json(browseService.listAllCompanies());
            });
        }

        if (searchService != null) {
            app.get("/api/search", ctx -> {
                String q = ctx.queryParam("q");
                if (q == null || q.isBlank()) {
                    ctx.json(Map.of("actresses", List.of(), "titles", List.of(),
                            "labels", List.of(), "companies", List.of()));
                    return;
                }
                String matchMode = ctx.queryParam("matchMode");
                boolean startsWith = "startsWith".equals(matchMode);
                ctx.json(searchService.search(q.trim(), startsWith));
            });

            app.get("/api/titles/by-code/{code}", ctx -> {
                String code = ctx.pathParam("code").toUpperCase();
                if (titleRepo == null) { ctx.status(503); return; }
                if (!titleRepo.findByCode(code).isPresent()) { ctx.status(404); return; }
                // Return full TitleSummary so callers get coverUrl, actressName, etc.
                if (browseService != null) {
                    List<TitleSummary> hits = browseService.searchByCodePaged(code, 0, 10);
                    TitleSummary exact = hits.stream()
                            .filter(ts -> code.equals(ts.getCode()))
                            .findFirst()
                            .orElse(null);
                    if (exact != null) { ctx.json(exact); return; }
                }
                // Fallback: bare id+code (shouldn't happen in production)
                titleRepo.findByCode(code)
                        .ifPresent(t -> ctx.json(Map.of("id", t.getId(), "code", t.getCode())));
            });
        }

        if (actressBrowseService != null) {
            app.get("/api/actresses/index", ctx ->
                    ctx.json(actressBrowseService.findPrefixIndex()));

            app.get("/api/actresses/tier-counts", ctx -> {
                String prefix = ctx.queryParam("prefix");
                if (prefix == null || prefix.isBlank()) { ctx.status(400); return; }
                ctx.json(actressBrowseService.findTierCountsByPrefix(prefix));
            });

            app.get("/api/actresses/random", ctx -> {
                int limit = ctx.queryParamAsClass("limit", Integer.class).getOrDefault(24);
                limit = Math.max(1, Math.min(limit, TitleBrowseService.MAX_LIMIT));
                ctx.json(actressBrowseService.findRandom(limit));
            });

            app.get("/api/actresses", ctx -> {
                String idsParam     = ctx.queryParam("ids");
                String prefix       = ctx.queryParam("prefix");
                String tier         = ctx.queryParam("tier");
                String volumesParam = ctx.queryParam("volumes");
                String studioGroup  = ctx.queryParam("studioGroup");
                String company      = ctx.queryParam("company");
                String all          = ctx.queryParam("all");
                String favorites    = ctx.queryParam("favorites");
                String bookmarks    = ctx.queryParam("bookmarks");
                String search       = ctx.queryParam("search");
                int offset = ctx.queryParamAsClass("offset", Integer.class).getOrDefault(0);
                int limit  = ctx.queryParamAsClass("limit",  Integer.class).getOrDefault(24);
                offset = Math.max(offset, 0);
                limit  = Math.max(1, Math.min(limit, TitleBrowseService.MAX_LIMIT));
                if (idsParam != null && !idsParam.isBlank()) {
                    List<Long> ids = List.of(idsParam.split(",")).stream()
                            .map(String::trim).filter(s -> !s.isEmpty())
                            .map(Long::parseLong).toList();
                    ctx.json(actressBrowseService.findByIds(ids));
                } else if (search != null && !search.isBlank()) {
                    if (search.trim().length() < 2) {
                        ctx.json(List.of());
                    } else {
                        ctx.json(actressBrowseService.searchByNamePaged(search.trim(), offset, limit));
                    }
                } else if (prefix != null && !prefix.isBlank()) {
                    try {
                        ctx.json(actressBrowseService.findByPrefixPaged(prefix, tier, offset, limit));
                    } catch (IllegalArgumentException e) {
                        ctx.status(400);
                    }
                } else if (tier != null && !tier.isBlank()) {
                    try {
                        ctx.json(actressBrowseService.findByTierPaged(tier, company, offset, limit));
                    } catch (IllegalArgumentException e) {
                        ctx.status(400);
                    }
                } else if (volumesParam != null && !volumesParam.isBlank()) {
                    var volumeIds = List.of(volumesParam.split(","));
                    ctx.json(actressBrowseService.findByVolumesPaged(volumeIds, company, offset, limit));
                } else if (studioGroup != null && !studioGroup.isBlank()) {
                    ctx.json(actressBrowseService.findByStudioGroupPaged(studioGroup, company, offset, limit));
                } else if ("true".equals(all)) {
                    ctx.json(actressBrowseService.findAllPaged(offset, limit));
                } else if ("true".equals(favorites)) {
                    ctx.json(actressBrowseService.findFavoritesPaged(offset, limit));
                } else if ("true".equals(bookmarks)) {
                    ctx.json(actressBrowseService.findBookmarksPaged(offset, limit));
                } else {
                    ctx.status(400);
                }
            });

            app.get("/api/actresses/dashboard", ctx -> {
                ctx.json(actressBrowseService.buildDashboard());
            });

            app.get("/api/actresses/spotlight", ctx -> {
                Long excludeId = null;
                String exclude = ctx.queryParam("exclude");
                if (exclude != null && !exclude.isBlank()) {
                    try { excludeId = Long.parseLong(exclude.trim()); }
                    catch (NumberFormatException e) { ctx.status(400); return; }
                }
                var result = actressBrowseService.getSpotlight(excludeId);
                if (result == null) ctx.status(204);
                else ctx.json(result);
            });

            app.get("/api/actresses/{id}", ctx -> {
                long id;
                try { id = Long.parseLong(ctx.pathParam("id")); }
                catch (NumberFormatException e) { ctx.status(400); return; }
                actressBrowseService.findById(id)
                        .ifPresentOrElse(ctx::json, () -> ctx.status(404));
            });

            app.get("/api/actresses/{id}/titles", ctx -> {
                long id;
                try { id = Long.parseLong(ctx.pathParam("id")); }
                catch (NumberFormatException e) { ctx.status(400); return; }
                int offset = ctx.queryParamAsClass("offset", Integer.class).getOrDefault(0);
                int limit  = ctx.queryParamAsClass("limit",  Integer.class).getOrDefault(24);
                String company   = ctx.queryParam("company");
                String tagsParam = ctx.queryParam("tags");
                List<String> tags = (tagsParam != null && !tagsParam.isBlank())
                        ? List.of(tagsParam.split(","))
                        : List.of();
                offset = Math.max(offset, 0);
                limit  = Math.max(1, Math.min(limit, TitleBrowseService.MAX_LIMIT));
                ctx.json(actressBrowseService.findTitlesByActress(id, offset, limit, company, tags));
            });

            app.get("/api/actresses/{id}/tags", ctx -> {
                long id;
                try { id = Long.parseLong(ctx.pathParam("id")); }
                catch (NumberFormatException e) { ctx.status(400); return; }
                ctx.json(actressBrowseService.findTagsForActress(id));
            });

            app.post("/api/actresses/{id}/favorite", ctx -> {
                long id;
                try { id = Long.parseLong(ctx.pathParam("id")); }
                catch (NumberFormatException e) { ctx.status(400); return; }
                actressBrowseService.toggleFavorite(id).ifPresentOrElse(
                        s -> ctx.json(Map.of("id", s.id(),
                                "favorite", s.favorite(),
                                "bookmark", s.bookmark(),
                                "rejected", s.rejected())),
                        () -> ctx.status(404).json(Map.of("error", "Actress not found"))
                );
            });

            app.post("/api/actresses/{id}/bookmark", ctx -> {
                long id;
                try { id = Long.parseLong(ctx.pathParam("id")); }
                catch (NumberFormatException e) { ctx.status(400); return; }
                String valueParam = ctx.queryParam("value");
                Optional<ActressBrowseService.FlagState> result = valueParam != null
                        ? actressBrowseService.setBookmark(id, Boolean.parseBoolean(valueParam))
                        : actressBrowseService.toggleBookmark(id);
                result.ifPresentOrElse(
                        s -> ctx.json(Map.of("id", s.id(),
                                "favorite", s.favorite(),
                                "bookmark", s.bookmark(),
                                "rejected", s.rejected())),
                        () -> ctx.status(404).json(Map.of("error", "Actress not found"))
                );
            });

            app.post("/api/actresses/{id}/reject", ctx -> {
                long id;
                try { id = Long.parseLong(ctx.pathParam("id")); }
                catch (NumberFormatException e) { ctx.status(400); return; }
                actressBrowseService.toggleRejected(id).ifPresentOrElse(
                        s -> ctx.json(Map.of("id", s.id(),
                                "favorite", s.favorite(),
                                "bookmark", s.bookmark(),
                                "rejected", s.rejected())),
                        () -> ctx.status(404).json(Map.of("error", "Actress not found"))
                );
            });

            app.post("/api/actresses/{id}/visit", ctx -> {
                long id;
                try { id = Long.parseLong(ctx.pathParam("id")); }
                catch (NumberFormatException e) { ctx.status(400); return; }
                actressBrowseService.recordVisit(id).ifPresentOrElse(
                        stats -> ctx.json(Map.of(
                                "visitCount", stats.visitCount(),
                                "lastVisitedAt", stats.lastVisitedAt() != null ? stats.lastVisitedAt() : "")),
                        () -> ctx.status(404).json(Map.of("error", "Actress not found"))
                );
            });

            app.post("/api/actresses/{id}/stage-name/search", ctx -> {
                long id;
                try { id = Long.parseLong(ctx.pathParam("id")); }
                catch (NumberFormatException e) { ctx.status(400); return; }
                var result = actressBrowseService.searchStageName(id);
                var body = new java.util.LinkedHashMap<String, Object>();
                body.put("stageName", result.orElse(null));
                ctx.json(body);
            });
        }

        if (coversRoot != null) {
            app.get("/covers/{label}/{file}", ctx -> {
                String label = ctx.pathParam("label");
                String file  = ctx.pathParam("file");
                // Reject any path traversal attempts
                if (label.contains("..") || label.contains("/") || file.contains("..") || file.contains("/")) {
                    ctx.status(400);
                    return;
                }
                Path target = coversRoot.resolve(label).resolve(file).normalize();
                if (!target.startsWith(coversRoot.normalize())) {
                    ctx.status(400);
                    return;
                }
                if (!Files.isRegularFile(target)) {
                    ctx.status(404);
                    return;
                }
                String ext = CoverPath.extensionOf(file);
                ctx.contentType(MIME_TYPES.getOrDefault(ext, "application/octet-stream"));
                ctx.result(Files.newInputStream(target));
            });
        }

        if (videoStreamService != null) {
            // Video discovery: scan SMB for video files belonging to a title
            app.get("/api/titles/{code}/videos", ctx -> {
                String code = ctx.pathParam("code");
                ctx.json(videoStreamService.findVideos(code));
            });

            // Video streaming with HTTP Range support
            app.get("/api/stream/{videoId}", ctx -> {
                long videoId;
                try { videoId = Long.parseLong(ctx.pathParam("videoId")); }
                catch (NumberFormatException e) { ctx.status(400); return; }

                Video video = videoStreamService.findVideoById(videoId).orElse(null);
                if (video == null) { ctx.status(404); return; }

                String smbPath = videoStreamService.smbRelativePath(video);
                String contentType = videoStreamService.mimeType(video);

                try (SmbShareHandle handle = videoStreamService.openStream(video)) {
                    long fileSize = handle.fileSize(smbPath);
                    String rangeHeader = ctx.header("Range");

                    if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                        // Parse range: "bytes=start-" or "bytes=start-end"
                        String rangeSpec = rangeHeader.substring(6);
                        String[] parts = rangeSpec.split("-", 2);
                        long start = Long.parseLong(parts[0]);
                        long end = (parts.length > 1 && !parts[1].isEmpty())
                                ? Long.parseLong(parts[1])
                                : fileSize - 1;
                        end = Math.min(end, fileSize - 1);
                        long contentLength = end - start + 1;

                        ctx.status(206);
                        ctx.header("Content-Type", contentType);
                        ctx.header("Content-Length", String.valueOf(contentLength));
                        ctx.header("Content-Range", "bytes " + start + "-" + end + "/" + fileSize);
                        ctx.header("Accept-Ranges", "bytes");

                        try (File smbFile = handle.openFileHandle(smbPath)) {
                            OutputStream out = ctx.outputStream();
                            byte[] buf = new byte[64 * 1024];
                            long remaining = contentLength;
                            long offset = start;
                            while (remaining > 0) {
                                int toRead = (int) Math.min(buf.length, remaining);
                                int read = smbFile.read(buf, offset, 0, toRead);
                                if (read <= 0) break;
                                out.write(buf, 0, read);
                                offset += read;
                                remaining -= read;
                            }
                            out.flush();
                        }
                    } else {
                        // Full response
                        ctx.status(200);
                        ctx.header("Content-Type", contentType);
                        ctx.header("Content-Length", String.valueOf(fileSize));
                        ctx.header("Accept-Ranges", "bytes");

                        try (File smbFile = handle.openFileHandle(smbPath)) {
                            OutputStream out = ctx.outputStream();
                            byte[] buf = new byte[64 * 1024];
                            long remaining = fileSize;
                            long offset = 0;
                            while (remaining > 0) {
                                int toRead = (int) Math.min(buf.length, remaining);
                                int read = smbFile.read(buf, offset, 0, toRead);
                                if (read <= 0) break;
                                out.write(buf, 0, read);
                                offset += read;
                                remaining -= read;
                            }
                            out.flush();
                        }
                    }
                } catch (IOException e) {
                    log.warn("Stream failed for video {}: {}", videoId, e.getMessage());
                    if (!ctx.res().isCommitted()) {
                        ctx.status(502).result("Stream error: " + e.getMessage());
                    }
                }
            });
        }

        if (thumbnailService != null) {
            // Thumbnail generation: returns list of thumbnail URLs for a video
            app.get("/api/videos/{videoId}/thumbnails", ctx -> {
                long videoId;
                try { videoId = Long.parseLong(ctx.pathParam("videoId")); }
                catch (NumberFormatException e) { ctx.status(400); return; }

                Video video = videoStreamService != null
                        ? videoStreamService.findVideoById(videoId).orElse(null)
                        : null;
                if (video == null) { ctx.status(404); return; }

                String titleCode = videoStreamService.titleCodeForVideo(video);
                if (titleCode == null) { ctx.status(404); return; }

                ctx.json(thumbnailService.getThumbnailStatus(titleCode, video));
            });

            // Serve individual thumbnail images
            app.get("/api/videos/{videoId}/thumbnails/{filename}", ctx -> {
                long videoId;
                try { videoId = Long.parseLong(ctx.pathParam("videoId")); }
                catch (NumberFormatException e) { ctx.status(400); return; }
                String filename = ctx.pathParam("filename");
                if (filename.contains("..") || filename.contains("/")) {
                    ctx.status(400); return;
                }

                Video video = videoStreamService != null
                        ? videoStreamService.findVideoById(videoId).orElse(null)
                        : null;
                if (video == null) { ctx.status(404); return; }

                String titleCode = videoStreamService.titleCodeForVideo(video);
                if (titleCode == null) { ctx.status(404); return; }

                thumbnailService.getThumbnailFile(titleCode, video.getFilename(), filename)
                        .ifPresentOrElse(
                                path -> {
                                    ctx.contentType("image/jpeg");
                                    try { ctx.result(Files.newInputStream(path)); }
                                    catch (IOException e) { ctx.status(500); }
                                },
                                () -> ctx.status(404)
                        );
            });
        }

        if (videoProbe != null && videoStreamService != null) {
            app.get("/api/videos/{videoId}/info", ctx -> {
                long videoId;
                try { videoId = Long.parseLong(ctx.pathParam("videoId")); }
                catch (NumberFormatException e) { ctx.status(400); return; }

                Video video = videoStreamService.findVideoById(videoId).orElse(null);
                if (video == null) { ctx.status(404); return; }

                ctx.json(videoProbe.probe(videoId, video.getFilename()));
            });
        }

        if (watchHistoryRepo != null) {
            app.post("/api/watch-history/{titleCode}", ctx -> {
                String titleCode = ctx.pathParam("titleCode");
                WatchHistory entry = watchHistoryRepo.record(titleCode, java.time.LocalDateTime.now());
                ctx.json(Map.of("id", entry.getId(), "titleCode", entry.getTitleCode(),
                        "watchedAt", entry.getWatchedAt().toString()));
            });

            app.get("/api/watch-history", ctx -> {
                int limit = ctx.queryParamAsClass("limit", Integer.class).getOrDefault(50);
                List<WatchHistory> history = watchHistoryRepo.findAll(limit);
                ctx.json(history.stream().map(e -> Map.of(
                        "id", e.getId(),
                        "titleCode", e.getTitleCode(),
                        "watchedAt", e.getWatchedAt().toString()
                )).toList());
            });

            app.get("/api/watch-history/{titleCode}", ctx -> {
                String titleCode = ctx.pathParam("titleCode");
                List<WatchHistory> history = watchHistoryRepo.findByTitleCode(titleCode);
                ctx.json(history.stream().map(e -> Map.of(
                        "id", e.getId(),
                        "titleCode", e.getTitleCode(),
                        "watchedAt", e.getWatchedAt().toString()
                )).toList());
            });
        }

        if (browseService != null) {
            app.post("/api/titles/{code}/visit", ctx -> {
                String code = ctx.pathParam("code");
                browseService.recordVisit(code).ifPresentOrElse(
                        stats -> ctx.json(Map.of(
                                "visitCount", stats.visitCount(),
                                "lastVisitedAt", stats.lastVisitedAt() != null ? stats.lastVisitedAt() : "")),
                        () -> ctx.status(404).json(Map.of("error", "Title not found"))
                );
            });
        }

        if (titleRepo != null) {
            app.post("/api/titles/{code}/favorite", ctx -> {
                String code = ctx.pathParam("code");
                Title title = titleRepo.findByCode(code).orElse(null);
                if (title == null) { ctx.status(404).json(Map.of("error", "Title not found")); return; }
                boolean newValue = !title.isFavorite();
                titleRepo.toggleFavorite(title.getId(), newValue);
                ctx.json(Map.of("code", code, "favorite", newValue));
            });

            app.post("/api/titles/{code}/bookmark", ctx -> {
                String code = ctx.pathParam("code");
                Title title = titleRepo.findByCode(code).orElse(null);
                if (title == null) { ctx.status(404).json(Map.of("error", "Title not found")); return; }
                String valueParam = ctx.queryParam("value");
                boolean newValue = valueParam != null ? Boolean.parseBoolean(valueParam) : !title.isBookmark();
                titleRepo.toggleBookmark(title.getId(), newValue);
                ctx.json(Map.of("code", code, "bookmark", newValue));
            });
        }
    }

    public void start() {
        app.start(port);
        log.info("Web server started on port {}", port);
    }

    public void stop() {
        app.stop();
        log.info("Web server stopped");
    }

    public int port() {
        return app.port();
    }
}
