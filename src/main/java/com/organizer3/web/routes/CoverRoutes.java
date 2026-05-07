package com.organizer3.web.routes;

import com.organizer3.covers.CoverPath;
import io.javalin.Javalin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
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

    private static final List<String> PROBE_EXTS = List.of("jpg", "jpeg", "png", "webp", "gif");

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

        // Batch existence check: { codes: [...] } → { code: url|null, ... }
        // Used by UI surfaces (translation dashboard, etc.) to make title codes
        // clickable only when a cover image is actually available.
        app.post("/api/covers/resolve-batch", ctx -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = ctx.bodyAsClass(Map.class);
            Object raw = body != null ? body.get("codes") : null;
            Map<String, String> result = new LinkedHashMap<>();
            if (raw instanceof Collection<?> codes) {
                for (Object c : codes) {
                    if (!(c instanceof String code) || code.isBlank()) continue;
                    result.put(code, resolveCoverUrl(code));
                }
            }
            ctx.json(result);
        });
    }

    /**
     * Resolves a title code (e.g. "ABP-123") to a /covers/... URL if a local cover exists,
     * else null. Mirrors {@link com.organizer3.covers.CoverPath#findByCode} but operates
     * directly against this route's coversRoot to avoid an extra dataDir derivation.
     */
    private String resolveCoverUrl(String titleCode) {
        int dash = titleCode.indexOf('-');
        if (dash <= 0) return null;
        String label = titleCode.substring(0, dash).toUpperCase();
        String rest  = titleCode.substring(dash + 1).replaceAll("[^0-9].*$", "");
        if (rest.isEmpty()) return null;
        String baseCode;
        try {
            baseCode = String.format("%s-%05d", label, Integer.parseInt(rest));
        } catch (NumberFormatException e) {
            return null;
        }
        Path dir = coversRoot.resolve(label);
        if (!Files.isDirectory(dir)) return null;
        for (String ext : PROBE_EXTS) {
            Path candidate = dir.resolve(baseCode + "." + ext);
            if (Files.isRegularFile(candidate)) {
                return "/covers/" + label + "/" + baseCode + "." + ext;
            }
        }
        return null;
    }
}
