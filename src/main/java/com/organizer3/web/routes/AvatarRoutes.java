package com.organizer3.web.routes;

import io.javalin.Javalin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Serves locally cached actress avatar images from {@code <dataDir>/actress-avatars/}. */
public class AvatarRoutes {

    private static final Map<String, String> MIME_TYPES = Map.of(
            "jpg",  "image/jpeg",
            "jpeg", "image/jpeg",
            "png",  "image/png",
            "webp", "image/webp",
            "gif",  "image/gif"
    );

    private final Path avatarsRoot;

    public AvatarRoutes(Path avatarsRoot) {
        this.avatarsRoot = avatarsRoot;
    }

    public void register(Javalin app) {
        app.get("/actress-avatars/{file}", ctx -> {
            String file = ctx.pathParam("file");
            if (file.contains("..") || file.contains("/")) {
                ctx.status(400);
                return;
            }
            Path target = avatarsRoot.resolve(file).normalize();
            if (!target.startsWith(avatarsRoot.normalize())) {
                ctx.status(400);
                return;
            }
            if (!Files.isRegularFile(target)) {
                ctx.status(404);
                return;
            }
            int dot = file.lastIndexOf('.');
            String ext = dot >= 0 ? file.substring(dot + 1).toLowerCase() : "";
            ctx.contentType(MIME_TYPES.getOrDefault(ext, "application/octet-stream"));
            ctx.result(Files.newInputStream(target));
        });
    }
}
