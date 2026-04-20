package com.organizer3.web.routes;

import com.organizer3.covers.CoverPath;
import io.javalin.Javalin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Serves cover images from a local directory. */
public class CoverRoutes {

    private static final Map<String, String> MIME_TYPES = Map.of(
            "jpg",  "image/jpeg",
            "jpeg", "image/jpeg",
            "png",  "image/png",
            "webp", "image/webp",
            "gif",  "image/gif"
    );

    private final Path coversRoot;

    public CoverRoutes(Path coversRoot) {
        this.coversRoot = coversRoot;
    }

    public void register(Javalin app) {
        app.get("/covers/{label}/{file}", ctx -> {
            String label = ctx.pathParam("label");
            String file  = ctx.pathParam("file");
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
