package com.organizer3.web.routes;

import com.organizer3.covers.CoverPath;
import com.organizer3.model.Title;
import com.organizer3.web.CoverWriteService;
import com.organizer3.web.ImageFetcher;
import com.organizer3.web.UnsortedEditorService;
import com.organizer3.web.UnsortedEditorService.ActressEntry;
import io.javalin.Javalin;
import io.javalin.http.UploadedFile;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * HTTP endpoints for the Title Editor. See {@code spec/PROPOSAL_TITLE_EDITOR.md}.
 */
@Slf4j
public class UnsortedEditorRoutes {

    private final UnsortedEditorService editor;
    private final CoverWriteService coverWrite;
    private final ImageFetcher imageFetcher;
    private final CoverPath coverPath;

    public UnsortedEditorRoutes(UnsortedEditorService editor,
                                CoverWriteService coverWrite,
                                ImageFetcher imageFetcher,
                                CoverPath coverPath) {
        this.editor = editor;
        this.coverWrite = coverWrite;
        this.imageFetcher = imageFetcher;
        this.coverPath = coverPath;
    }

    public void register(Javalin app) {
        app.get("/api/unsorted/titles", ctx -> ctx.json(editor.listEligible()));

        app.get("/api/unsorted/titles/{id}", ctx -> {
            long id;
            try { id = Long.parseLong(ctx.pathParam("id")); }
            catch (NumberFormatException e) { ctx.status(400); return; }
            var detail = editor.findEligibleById(id);
            if (detail.isEmpty()) { ctx.status(404); return; }
            ctx.json(detail.get());
        });

        app.put("/api/unsorted/titles/{id}/actresses", ctx -> {
            long id;
            try { id = Long.parseLong(ctx.pathParam("id")); }
            catch (NumberFormatException e) { ctx.status(400); return; }

            ActressSaveBody body;
            try {
                body = ctx.bodyAsClass(ActressSaveBody.class);
            } catch (Exception e) {
                ctx.status(400).result("Invalid body: " + e.getMessage());
                return;
            }
            if (body == null) {
                ctx.status(400).result("Missing body");
                return;
            }

            try {
                var detailOpt = editor.findEligibleById(id);
                if (detailOpt.isEmpty()) { ctx.status(404); return; }
                boolean isDuplicate = detailOpt.get().duplicate();

                UnsortedEditorService.SaveResult result;
                if (isDuplicate) {
                    // Actress + cover mutations are disallowed on duplicates; only descriptor
                    // / folder rename proceeds. Any actress payload is intentionally ignored.
                    result = editor.saveDuplicateRename(id, body.descriptor);
                } else {
                    if (body.actresses == null || body.primary == null) {
                        ctx.status(400).result("Missing actresses or primary");
                        return;
                    }
                    List<ActressEntry> entries = body.actresses.stream()
                            .map(e -> new ActressEntry(e.id, e.newName))
                            .toList();
                    ActressEntry primary = new ActressEntry(body.primary.id, body.primary.newName);
                    result = editor.replaceActresses(id, entries, primary, body.descriptor, body.tags);
                }
                Map<String, Object> resp = new java.util.LinkedHashMap<>();
                resp.put("actressIds", result.actressIds());
                resp.put("primaryActressId", result.primaryActressId());
                resp.put("folderRenamed", result.folderRenamed());
                if (result.folderPath() != null) resp.put("folderPath", result.folderPath());
                ctx.json(resp);
            } catch (IllegalArgumentException e) {
                ctx.status(400).result(e.getMessage());
            } catch (IllegalStateException e) {
                ctx.status(409).result(e.getMessage());
            } catch (RuntimeException e) {
                log.warn("Actress save failed: {}", e.getMessage());
                ctx.status(500).result(e.getMessage());
            }
        });

        app.get("/api/unsorted/actresses/search", ctx -> {
            String q = ctx.queryParam("q");
            int limit = ctx.queryParamAsClass("limit", Integer.class).getOrDefault(20);
            limit = Math.max(1, Math.min(limit, 50));
            ctx.json(editor.searchActresses(q, limit));
        });

        app.post("/api/unsorted/titles/{id}/cover", ctx -> {
            long id;
            try { id = Long.parseLong(ctx.pathParam("id")); }
            catch (NumberFormatException e) { ctx.status(400); return; }

            var detailOpt = editor.findEligibleById(id);
            if (detailOpt.isEmpty()) { ctx.status(404); return; }
            var detail = detailOpt.get();
            if (detail.duplicate()) {
                ctx.status(409).result("Cover changes are disabled for duplicates — reuse the existing cover.");
                return;
            }

            byte[] bytes;
            String extension;
            String contentType = ctx.contentType();
            String source;
            if (contentType != null && contentType.startsWith("multipart/")) {
                UploadedFile uf = ctx.uploadedFile("file");
                if (uf == null) { ctx.status(400).result("Missing file part"); return; }
                extension = guessExtensionFromUpload(uf.contentType(), uf.filename());
                if (extension == null) { ctx.status(400).result("Unsupported image type"); return; }
                bytes = uf.content().readAllBytes();
                if (bytes.length > ImageFetcher.MAX_BYTES) {
                    ctx.status(413).result("File too large"); return;
                }
                source = "upload=\"" + uf.filename() + "\"";
            } else {
                CoverUrlBody body;
                try { body = ctx.bodyAsClass(CoverUrlBody.class); }
                catch (Exception e) { ctx.status(400).result("Invalid body"); return; }
                if (body == null || body.url == null || body.url.isBlank()) {
                    ctx.status(400).result("Missing url"); return;
                }
                try {
                    var fetched = imageFetcher.fetch(body.url);
                    bytes = fetched.bytes();
                    extension = fetched.extension();
                } catch (ImageFetcher.ImageFetchException e) {
                    ctx.status(400).result(e.getMessage()); return;
                }
                source = "url=" + body.url;
            }
            log.info("TitleEditor: cover save request — titleId={} code={} ext={} bytes={} source={}",
                    id, detail.detail().code(), extension, bytes.length, source);

            Title title = Title.builder()
                    .code(detail.detail().code())
                    .baseCode(detail.detail().baseCode())
                    .label(detail.detail().label())
                    .build();
            try {
                coverWrite.save(title, detail.detail().folderPath(), bytes, extension);
            } catch (IOException e) {
                log.warn("Cover save failed for title {}: {}", id, e.getMessage());
                ctx.status(500).result("Save failed: " + e.getMessage());
                return;
            }

            boolean hasCoverNow = coverPath.exists(title);
            ctx.json(Map.of(
                    "extension", extension,
                    "hasCover", hasCoverNow));
        });
    }

    private static String guessExtensionFromUpload(String contentType, String filename) {
        if (contentType != null) {
            String ct = contentType.toLowerCase();
            if (ct.contains("jpeg") || ct.contains("jpg")) return "jpg";
            if (ct.contains("png"))  return "png";
            if (ct.contains("webp")) return "webp";
            if (ct.contains("gif"))  return "gif";
        }
        if (filename != null) {
            String f = filename.toLowerCase();
            if (f.endsWith(".jpg") || f.endsWith(".jpeg")) return "jpg";
            if (f.endsWith(".png"))  return "png";
            if (f.endsWith(".webp")) return "webp";
            if (f.endsWith(".gif"))  return "gif";
        }
        return null;
    }

    // ── Body DTOs ────────────────────────────────────────────────────────

    public static class ActressItem {
        public Long id;
        public String newName;
    }

    public static class ActressSaveBody {
        public List<ActressItem> actresses;
        public ActressItem primary;
        public String descriptor;
        public List<String> tags;
    }

    public static class CoverUrlBody {
        public String url;
    }
}
