package com.organizer3.web;

import com.organizer3.config.AppConfig;
import com.organizer3.covers.CoverPath;
import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Embedded web server for read-only browsing and querying.
 *
 * Starts alongside the interactive shell and shuts down when the app exits.
 * All endpoints are read-only — no mutations are exposed through the web layer.
 */
public class WebServer {
    private static final Logger log = LoggerFactory.getLogger(WebServer.class);
    private static final int DEFAULT_PORT = 8080;

    private static final Map<String, String> MIME_TYPES = Map.of(
            "jpg",  "image/jpeg",
            "jpeg", "image/jpeg",
            "png",  "image/png",
            "webp", "image/webp",
            "gif",  "image/gif"
    );

    private final Javalin app;
    private final int port;

    /** Full constructor: enables title browsing, actress browsing, and cover serving. */
    public WebServer(int port, TitleBrowseService browseService,
                     ActressBrowseService actressBrowseService, Path coversRoot) {
        this.port = port;
        this.app = Javalin.create(config ->
                config.staticFiles.add("/public", Location.CLASSPATH));
        registerRoutes(browseService, actressBrowseService, coversRoot);
    }

    /** Convenience constructor using the default port. */
    public WebServer(TitleBrowseService browseService,
                     ActressBrowseService actressBrowseService, Path coversRoot) {
        this(DEFAULT_PORT, browseService, actressBrowseService, coversRoot);
    }

    /** Minimal constructor for tests that only need the lifecycle and static file behaviour. */
    public WebServer(int port) {
        this(port, null, null, null);
    }

    private void registerRoutes(TitleBrowseService browseService,
                                ActressBrowseService actressBrowseService, Path coversRoot) {
        app.get("/api/config", ctx -> {
            var cfg = AppConfig.get().volumes();
            String appName = cfg.appName();
            Integer maxBrowseTitles = cfg.maxBrowseTitles();
            ctx.json(Map.of(
                    "appName", appName != null ? appName : "organizer3",
                    "maxBrowseTitles", maxBrowseTitles != null ? maxBrowseTitles : 500
            ));
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
            var volumes = cfg.volumes().stream()
                    .filter(v -> conventionalWithQueueTypes.contains(v.structureType()))
                    .map(v -> Map.of("id", (Object) v.id(), "smbPath", (Object) v.smbPath()))
                    .toList();
            var result = new LinkedHashMap<String, Object>();
            if (pool != null) result.put("pool", pool);
            result.put("volumes", volumes);
            ctx.json(result);
        });

        if (browseService != null) {
            app.get("/api/titles", ctx -> {
                int offset = ctx.queryParamAsClass("offset", Integer.class).getOrDefault(0);
                int limit  = ctx.queryParamAsClass("limit",  Integer.class).getOrDefault(24);
                offset = Math.max(offset, 0);
                limit  = Math.max(1, Math.min(limit, TitleBrowseService.MAX_LIMIT));
                ctx.json(browseService.findRecent(offset, limit));
            });

            app.get("/api/queues/{volumeId}/titles", ctx -> {
                String volumeId = ctx.pathParam("volumeId");
                int offset = ctx.queryParamAsClass("offset", Integer.class).getOrDefault(0);
                int limit  = ctx.queryParamAsClass("limit",  Integer.class).getOrDefault(24);
                offset = Math.max(offset, 0);
                limit  = Math.max(1, Math.min(limit, TitleBrowseService.MAX_LIMIT));
                ctx.json(browseService.findByVolumeQueue(volumeId, offset, limit));
            });
        }

        if (actressBrowseService != null) {
            app.get("/api/actresses/index", ctx ->
                    ctx.json(actressBrowseService.findPrefixIndex()));

            app.get("/api/actresses", ctx -> {
                String prefix = ctx.queryParam("prefix");
                String tier   = ctx.queryParam("tier");
                if (prefix != null && !prefix.isBlank()) {
                    ctx.json(actressBrowseService.findByPrefix(prefix));
                } else if (tier != null && !tier.isBlank()) {
                    try {
                        ctx.json(actressBrowseService.findByTier(tier));
                    } catch (IllegalArgumentException e) {
                        ctx.status(400);
                    }
                } else {
                    ctx.status(400);
                }
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
                offset = Math.max(offset, 0);
                limit  = Math.max(1, Math.min(limit, TitleBrowseService.MAX_LIMIT));
                ctx.json(actressBrowseService.findTitlesByActress(id, offset, limit));
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
