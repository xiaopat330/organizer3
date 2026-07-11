package com.organizer3.web.routes;

import com.hierynomus.smbj.share.File;
import com.organizer3.media.ThumbnailService;
import com.organizer3.media.VideoProbe;
import com.organizer3.model.Video;
import com.organizer3.smb.SmbConnectionFactory.SmbShareHandle;
import com.organizer3.web.VideoStreamService;
import io.javalin.Javalin;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;

/**
 * Video streaming, thumbnails, and probe metadata. Each sub-group is
 * conditional on its service dependency being non-null.
 */
@Slf4j
public class VideoRoutes {

    private final VideoStreamService videoStreamService;
    private final ThumbnailService thumbnailService;
    private final VideoProbe videoProbe;

    public VideoRoutes(VideoStreamService videoStreamService,
                       ThumbnailService thumbnailService,
                       VideoProbe videoProbe) {
        this.videoStreamService = videoStreamService;
        this.thumbnailService = thumbnailService;
        this.videoProbe = videoProbe;
    }

    public void register(Javalin app) {
        if (videoStreamService != null) {
            app.get("/api/titles/{code}/videos", ctx -> {
                String code = ctx.pathParam("code");
                String volumeId = ctx.queryParam("volumeId");
                String locPath = ctx.queryParam("locPath");
                ctx.json(videoStreamService.findVideos(code, volumeId, locPath));
            });

            app.get("/api/stream/{videoId}", ctx -> {
                long videoId;
                try { videoId = Long.parseLong(ctx.pathParam("videoId")); }
                catch (NumberFormatException e) { ctx.status(400); return; }

                Video video = videoStreamService.findVideoById(videoId).orElse(null);
                if (video == null) { ctx.status(404); return; }

                String smbPath = videoStreamService.smbRelativePath(video);
                String contentType = videoStreamService.mimeType(video);

                // Hold ONE handle across setup AND transfer so its in-use lease (inUse > 0) protects the
                // whole response from Wave-4 idle/age recycling: the byte-transfer loop below reads the
                // smbj File with no re-borrow, so lastUsedAtMillis is frozen for the duration and only
                // inUse keeps the connection off the recycler. (Previously setup used withRetry, which
                // released the lease before the transfer even began.)
                SmbShareHandle h;
                try {
                    h = videoStreamService.openStream(video);   // inUse++
                } catch (IOException e) {
                    log.warn("Stream setup failed for video {}: {}", videoId, e.getMessage());
                    videoStreamService.evictPool(video);
                    ctx.status(502).result("Stream error: " + e.getMessage());
                    return;
                }
                try {
                    long fileSize;
                    File smbFile;
                    try {
                        fileSize = h.fileSize(smbPath);
                        smbFile  = h.openFileHandle(smbPath);
                    } catch (IOException stale) {
                        // Preserve the setup auto-recovery that withRetry gave us: a stale pooled
                        // connection (SMB session expired, TCP still "connected") → release, evict,
                        // dial fresh, retry setup once. Still pre-commit, so a hard failure 502s below.
                        h.close();                                   // release the stale lease
                        videoStreamService.evictPool(video);
                        h = videoStreamService.openStream(video);    // fresh, inUse++
                        fileSize = h.fileSize(smbPath);
                        smbFile  = h.openFileHandle(smbPath);
                    }

                    try (File f = smbFile) {
                    String rangeHeader = ctx.header("Range");

                    if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
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

                        OutputStream out = ctx.outputStream();
                        byte[] buf = new byte[64 * 1024];
                        long remaining = contentLength;
                        long offset = start;
                        while (remaining > 0) {
                            int toRead = (int) Math.min(buf.length, remaining);
                            int read = f.read(buf, offset, 0, toRead);
                            if (read <= 0) break;
                            out.write(buf, 0, read);
                            offset += read;
                            remaining -= read;
                        }
                        out.flush();
                    } else {
                        ctx.status(200);
                        ctx.header("Content-Type", contentType);
                        ctx.header("Content-Length", String.valueOf(fileSize));
                        ctx.header("Accept-Ranges", "bytes");

                        OutputStream out = ctx.outputStream();
                        byte[] buf = new byte[64 * 1024];
                        long remaining = fileSize;
                        long offset = 0;
                        while (remaining > 0) {
                            int toRead = (int) Math.min(buf.length, remaining);
                            int read = f.read(buf, offset, 0, toRead);
                            if (read <= 0) break;
                            out.write(buf, 0, read);
                            offset += read;
                            remaining -= read;
                        }
                        out.flush();
                    }
                    }
                } catch (IOException e) {
                    // Mid-stream failure (or a setup-retry that still failed): response may be
                    // partially written, so we can't safely change status. Evict so the next request
                    // gets a fresh connection — this is what saves the user from needing to restart
                    // the app.
                    log.warn("Stream failed mid-response for video {}: {}", videoId, e.getMessage());
                    videoStreamService.evictPool(video);
                    if (!ctx.res().isCommitted()) {
                        ctx.status(502).result("Stream error: " + e.getMessage());
                    }
                } finally {
                    // Release the in-use lease (idempotent) — the last-assigned handle, whether the
                    // original or the fresh one from the stale-setup retry. `h` is a plain local (not
                    // lambda-captured), so this closes exactly the current handle.
                    h.close();
                }
            });
        }

        if (thumbnailService != null) {
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
    }
}
