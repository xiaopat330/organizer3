package com.organizer3.web.routes;

import com.organizer3.avatars.CustomAvatarService;
import io.javalin.Javalin;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * REST endpoints for the Custom Profile Images feature.
 *
 * <ul>
 *   <li>{@code GET  /api/actresses/{id}/title-covers}  — cover tiles for the avatar picker</li>
 *   <li>{@code POST /api/actresses/{id}/custom-avatar} — upload/replace a custom avatar</li>
 *   <li>{@code DELETE /api/actresses/{id}/custom-avatar} — remove a custom avatar (idempotent)</li>
 *   <li>{@code GET  /actress-custom-avatars/{file}}    — serve stored custom avatar files</li>
 * </ul>
 */
@Slf4j
@RequiredArgsConstructor
public class CustomAvatarRoutes {

    private static final long MAX_BYTES = 5L * 1024 * 1024;   // 5 MB
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of("image/jpeg", "image/png");

    private static final Map<String, String> MIME_TYPES = Map.of(
            "jpg",  "image/jpeg",
            "jpeg", "image/jpeg",
            "png",  "image/png"
    );

    private final CustomAvatarService service;
    private final Path customAvatarsRoot;

    public void register(Javalin app) {

        // ── GET title-covers ────────────────────────────────────────────────────

        app.get("/api/actresses/{id}/title-covers", ctx -> {
            long id;
            try { id = Long.parseLong(ctx.pathParam("id")); }
            catch (NumberFormatException e) { ctx.status(400); return; }

            var result = service.listTitleCovers(id);
            if (result.isEmpty()) { ctx.status(404); return; }
            ctx.json(result.get());
        });

        // ── POST custom-avatar ──────────────────────────────────────────────────

        app.post("/api/actresses/{id}/custom-avatar", ctx -> {
            long id;
            try { id = Long.parseLong(ctx.pathParam("id")); }
            catch (NumberFormatException e) { ctx.status(400); return; }

            // 415 before reading body
            String ct = ctx.contentType();
            if (ct == null || ALLOWED_CONTENT_TYPES.stream().noneMatch(ct::startsWith)) {
                ctx.status(415).json(Map.of("error", "Content-Type must be image/jpeg or image/png"));
                return;
            }

            // 413 before reading body (Content-Length fast-path)
            long contentLength = ctx.contentLength();
            if (contentLength > MAX_BYTES) {
                ctx.status(413).json(Map.of("error", "Image exceeds 5 MB limit"));
                return;
            }

            byte[] bytes = ctx.bodyAsBytes();
            if (bytes.length > MAX_BYTES) {
                ctx.status(413).json(Map.of("error", "Image exceeds 5 MB limit"));
                return;
            }

            try {
                var result = service.setCustomAvatar(id, bytes);
                if (result.isEmpty()) { ctx.status(404); return; }
                ctx.json(result.get());
            } catch (IllegalArgumentException e) {
                ctx.status(400).json(Map.of("error", e.getMessage()));
            }
        });

        // ── DELETE custom-avatar ────────────────────────────────────────────────

        app.delete("/api/actresses/{id}/custom-avatar", ctx -> {
            long id;
            try { id = Long.parseLong(ctx.pathParam("id")); }
            catch (NumberFormatException e) { ctx.status(400); return; }

            Optional<Boolean> result = service.clearCustomAvatar(id);
            if (result.isEmpty()) { ctx.status(404); return; }
            ctx.status(204);
        });

        // ── GET static custom avatar files ──────────────────────────────────────

        app.get("/actress-custom-avatars/{file}", ctx -> {
            String file = ctx.pathParam("file");
            if (file.contains("..") || file.contains("/")) {
                ctx.status(400);
                return;
            }
            Path target = customAvatarsRoot.resolve(file).normalize();
            if (!target.startsWith(customAvatarsRoot.normalize())) {
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
