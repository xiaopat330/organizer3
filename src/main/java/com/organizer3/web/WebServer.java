package com.organizer3.web;

import com.hierynomus.smbj.share.File;
import com.organizer3.avstars.model.AvVideo;
import com.organizer3.avstars.repository.AvActressRepository;
import com.organizer3.avstars.AvScreenshotService;
import com.organizer3.avstars.repository.AvScreenshotRepository;
import com.organizer3.config.AppConfig;
import com.organizer3.config.volume.VolumeConfig;
import com.organizer3.media.ThumbnailService;
import com.organizer3.media.VideoProbe;
import com.organizer3.repository.TitleRepository;
import com.organizer3.repository.WatchHistoryRepository;
import com.organizer3.smb.SmbConnectionFactory;
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

    /**
     * Registers the web terminal WebSocket endpoint ({@code /ws/terminal}).
     * Call after construction, before {@link #start()}.
     */
    public void registerTerminal(WebTerminalHandler handler) {
        handler.register(app);
    }

    /**
     * Mounts the MCP (Model Context Protocol) endpoint on this server.
     * The endpoint is disabled if {@code mcp.enabled: false} in config.
     * Call after construction, before {@link #start()}.
     */
    public void registerMcp(com.organizer3.mcp.McpServer mcp) {
        mcp.register(app);
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
            new com.organizer3.web.routes.TitleRoutes(browseService, actressBrowseService, titleRepo).register(app);
        }

        if (searchService != null) {
            new com.organizer3.web.routes.SearchRoutes(searchService, titleRepo, browseService).register(app);
        }

        if (actressBrowseService != null) {
            new com.organizer3.web.routes.ActressRoutes(actressBrowseService).register(app);
        }

        if (coversRoot != null) {
            new com.organizer3.web.routes.CoverRoutes(coversRoot).register(app);
        }

        new com.organizer3.web.routes.VideoRoutes(videoStreamService, thumbnailService, videoProbe).register(app);

        if (watchHistoryRepo != null) {
            new com.organizer3.web.routes.WatchHistoryRoutes(watchHistoryRepo).register(app);
        }
    }

    /**
     * Registers AV Stars API routes. Call after construction, before {@link #start()}.
     *
     * @param avBrowseService  AV actress browse service
     * @param avHeadshotsDir   local directory containing cached headshot images
     */
    public void registerAvRoutes(com.organizer3.avstars.web.AvBrowseService avBrowseService,
                                 Path avHeadshotsDir,
                                 SmbConnectionFactory smbFactory,
                                 com.organizer3.avstars.repository.AvVideoRepository avVideoRepo,
                                 AvActressRepository avActressRepo,
                                 AvScreenshotRepository avScreenshotRepo,
                                 Path avScreenshotsDir,
                                 com.organizer3.avstars.repository.AvTagDefinitionRepository avTagDefRepo,
                                 AvScreenshotService avScreenshotService) {
        // List all AV actresses (index grid + favorites + bookmarks)
        app.get("/api/av/actresses", ctx -> {
            String mode = ctx.queryParam("mode");
            if ("favorites".equals(mode)) {
                ctx.json(avBrowseService.findFavorites());
            } else {
                ctx.json(avBrowseService.findAll());
            }
        });

        // Full actress profile
        app.get("/api/av/actresses/{id}", ctx -> {
            long id;
            try { id = Long.parseLong(ctx.pathParam("id")); }
            catch (NumberFormatException e) { ctx.status(400); return; }
            var detail = avBrowseService.getActressDetail(id);
            if (detail.isEmpty()) { ctx.status(404); return; }
            ctx.json(detail.get());
        });

        // Videos for an actress
        app.get("/api/av/actresses/{id}/videos", ctx -> {
            long id;
            try { id = Long.parseLong(ctx.pathParam("id")); }
            catch (NumberFormatException e) { ctx.status(400); return; }
            ctx.json(avBrowseService.findVideosForActress(id));
        });

        // Toggle favorite on actress
        app.post("/api/av/actresses/{id}/favorite", ctx -> {
            long id;
            try { id = Long.parseLong(ctx.pathParam("id")); }
            catch (NumberFormatException e) { ctx.status(400); return; }
            boolean fav = Boolean.parseBoolean(ctx.queryParam("value"));
            var detail = avBrowseService.toggleActressFavorite(id, fav);
            ctx.json(Map.of("favorite", detail.isFavorite()));
        });

        // Toggle bookmark on actress
        app.post("/api/av/actresses/{id}/bookmark", ctx -> {
            long id;
            try { id = Long.parseLong(ctx.pathParam("id")); }
            catch (NumberFormatException e) { ctx.status(400); return; }
            boolean bm = Boolean.parseBoolean(ctx.queryParam("value"));
            var detail = avBrowseService.toggleActressBookmark(id, bm);
            ctx.json(Map.of("bookmark", detail.isBookmark()));
        });

        // Record a visit to an AV actress profile (5-second debounce on the client)
        app.post("/api/av/actresses/{id}/visit", ctx -> {
            long id;
            try { id = Long.parseLong(ctx.pathParam("id")); }
            catch (NumberFormatException e) { ctx.status(400); return; }
            var summary = avBrowseService.recordVisit(id);
            ctx.json(Map.of("visitCount", summary.getVisitCount(),
                            "lastVisitedAt", summary.getLastVisitedAt() != null ? summary.getLastVisitedAt() : ""));
        });

        // Video curation toggles
        app.post("/api/av/videos/{id}/favorite", ctx -> {
            long id;
            try { id = Long.parseLong(ctx.pathParam("id")); }
            catch (NumberFormatException e) { ctx.status(400); return; }
            boolean fav = Boolean.parseBoolean(ctx.queryParam("value"));
            var v = avBrowseService.toggleVideoFavorite(id, fav);
            ctx.json(Map.of("favorite", v.isFavorite()));
        });

        app.post("/api/av/videos/{id}/bookmark", ctx -> {
            long id;
            try { id = Long.parseLong(ctx.pathParam("id")); }
            catch (NumberFormatException e) { ctx.status(400); return; }
            boolean bm = Boolean.parseBoolean(ctx.queryParam("value"));
            var v = avBrowseService.toggleVideoBookmark(id, bm);
            ctx.json(Map.of("bookmark", v.isBookmark()));
        });

        app.post("/api/av/videos/{id}/watch", ctx -> {
            long id;
            try { id = Long.parseLong(ctx.pathParam("id")); }
            catch (NumberFormatException e) { ctx.status(400); return; }
            var v = avBrowseService.recordVideoWatch(id);
            ctx.json(Map.of("watched", v.isWatched(), "watchCount", v.getWatchCount()));
        });

        // On-demand screenshot generation
        app.post("/api/av/videos/{id}/screenshots", ctx -> {
            long id;
            try { id = Long.parseLong(ctx.pathParam("id")); }
            catch (NumberFormatException e) { ctx.status(400); return; }
            List<String> urls = avScreenshotService.generateForVideo(id);
            ctx.json(Map.of("screenshotUrls", urls));
        });

        // Full detail for a single video (includes SMB URL)
        app.get("/api/av/videos/{id}", ctx -> {
            long id;
            try { id = Long.parseLong(ctx.pathParam("id")); }
            catch (NumberFormatException e) { ctx.status(400); return; }
            var detail = avBrowseService.getVideoDetail(id);
            if (detail.isEmpty()) { ctx.status(404); return; }
            ctx.json(detail.get());
        });

        // AV video streaming with HTTP Range support (used by AvScreenshotsCommand via JavaCV)
        app.get("/api/av/stream/{videoId}", ctx -> {
            long videoId;
            try { videoId = Long.parseLong(ctx.pathParam("videoId")); }
            catch (NumberFormatException e) { ctx.status(400); return; }

            AvVideo video = avVideoRepo.findById(videoId).orElse(null);
            if (video == null) { ctx.status(404); return; }

            var actress = avActressRepo.findById(video.getAvActressId()).orElse(null);
            if (actress == null) { ctx.status(404); return; }

            // Full SMB path relative to the share: actress folder + video relative path
            String smbRelPath = actress.getFolderName() + "/" + video.getRelativePath();
            String ext = video.getExtension() != null ? video.getExtension().toLowerCase() : "";
            Map<String, String> videoMimes = Map.of(
                    "mp4", "video/mp4", "m4v", "video/mp4",
                    "mkv", "video/x-matroska", "avi", "video/x-msvideo",
                    "mov", "video/quicktime", "wmv", "video/x-ms-wmv",
                    "ts", "video/mp2t");
            String contentType = videoMimes.getOrDefault(ext, "application/octet-stream");

            try (SmbShareHandle handle = smbFactory.open(video.getVolumeId())) {
                long fileSize = handle.fileSize(smbRelPath);
                String rangeHeader = ctx.header("Range");

                if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                    String rangeSpec = rangeHeader.substring(6);
                    String[] parts = rangeSpec.split("-", 2);
                    long start = Long.parseLong(parts[0]);
                    long end = (parts.length > 1 && !parts[1].isEmpty())
                            ? Long.parseLong(parts[1]) : fileSize - 1;
                    end = Math.min(end, fileSize - 1);
                    long contentLength = end - start + 1;

                    ctx.status(206);
                    ctx.header("Content-Type", contentType);
                    ctx.header("Content-Length", String.valueOf(contentLength));
                    ctx.header("Content-Range", "bytes " + start + "-" + end + "/" + fileSize);
                    ctx.header("Accept-Ranges", "bytes");

                    try (File smbFile = handle.openFileHandle(smbRelPath)) {
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
                    ctx.status(200);
                    ctx.header("Content-Type", contentType);
                    ctx.header("Content-Length", String.valueOf(fileSize));
                    ctx.header("Accept-Ranges", "bytes");

                    try (File smbFile = handle.openFileHandle(smbRelPath)) {
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
                log.warn("AV stream failed for video {}: {}", videoId, e.getMessage());
                if (!ctx.res().isCommitted()) {
                    ctx.status(502).result("Stream error: " + e.getMessage());
                }
            }
        });

        // Serve cached screenshot images from data/av_screenshots/<videoId>/<seq>.jpg
        if (avScreenshotsDir != null) {
            app.get("/api/av/screenshots/{videoId}/{seq}", ctx -> {
                long videoId;
                int seq;
                try {
                    videoId = Long.parseLong(ctx.pathParam("videoId"));
                    seq = Integer.parseInt(ctx.pathParam("seq"));
                } catch (NumberFormatException e) { ctx.status(400); return; }

                Path target = avScreenshotsDir.resolve(String.valueOf(videoId))
                        .resolve(seq + ".jpg").normalize();
                if (!target.startsWith(avScreenshotsDir.normalize())) { ctx.status(400); return; }
                if (!Files.isRegularFile(target)) { ctx.status(404); return; }
                ctx.contentType("image/jpeg");
                ctx.result(Files.newInputStream(target));
            });
        }

        // Tag definitions
        if (avTagDefRepo != null) {
            app.get("/api/av/tags", ctx -> ctx.json(avTagDefRepo.findAll()));
        }

        // Serve cached headshot images from data/av_headshots/
        if (avHeadshotsDir != null) {
            app.get("/api/av/headshots/{file}", ctx -> {
                String file = ctx.pathParam("file");
                if (file.contains("..") || file.contains("/")) { ctx.status(400); return; }
                Path target = avHeadshotsDir.resolve(file).normalize();
                if (!target.startsWith(avHeadshotsDir.normalize())) { ctx.status(400); return; }
                if (!Files.isRegularFile(target)) { ctx.status(404); return; }
                String ext = file.contains(".") ? file.substring(file.lastIndexOf('.') + 1).toLowerCase() : "";
                ctx.contentType(MIME_TYPES.getOrDefault(ext, "application/octet-stream"));
                ctx.result(Files.newInputStream(target));
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
