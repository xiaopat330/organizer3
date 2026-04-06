package com.organizer3.web;

import com.organizer3.covers.CoverPath;
import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

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

    /** Full constructor: enables title browsing and cover serving. */
    public WebServer(int port, TitleBrowseService browseService, Path coversRoot) {
        this.port = port;
        this.app = Javalin.create(config ->
                config.staticFiles.add("/public", Location.CLASSPATH));
        registerRoutes(browseService, coversRoot);
    }

    /** Convenience constructor using the default port. */
    public WebServer(TitleBrowseService browseService, Path coversRoot) {
        this(DEFAULT_PORT, browseService, coversRoot);
    }

    /** Minimal constructor for tests that only need the lifecycle and static file behaviour. */
    public WebServer(int port) {
        this(port, null, null);
    }

    private void registerRoutes(TitleBrowseService browseService, Path coversRoot) {
        if (browseService != null) {
            app.get("/api/titles", ctx -> {
                int offset = ctx.queryParamAsClass("offset", Integer.class).getOrDefault(0);
                int limit  = ctx.queryParamAsClass("limit",  Integer.class).getOrDefault(24);
                offset = Math.max(offset, 0);
                limit  = Math.max(1, Math.min(limit, TitleBrowseService.MAX_LIMIT));
                ctx.json(browseService.findRecent(offset, limit));
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
