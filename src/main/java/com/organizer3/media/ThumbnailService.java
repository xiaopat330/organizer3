package com.organizer3.media;

import com.organizer3.model.Video;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;
import javax.imageio.ImageIO;
import lombok.extern.slf4j.Slf4j;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;

/**
 * Generates preview thumbnails from video files via JavaCV (FFmpeg).
 *
 * <p>Thumbnails are cached on local disk under {@code data/thumbnails/<titleCode>/<videoFilename>/}.
 * This content-stable keying means thumbnails survive database rebuilds — the same video
 * always maps to the same directory regardless of its database ID.
 *
 * <p>Generation is async — callers get an empty list on first request and poll
 * until thumbnails appear. FFmpeg opens the video via the local HTTP streaming
 * endpoint so it can use Range requests for O(1) seeking instead of reading
 * the entire file sequentially over SMB.
 */
@Slf4j
public class ThumbnailService {

    private static final int THUMBNAIL_WIDTH = 320;

    private final Path thumbnailRoot;
    private final int thumbnailCount;
    private final int serverPort;
    private final Set<String> generating = ConcurrentHashMap.newKeySet();

    public ThumbnailService(Path thumbnailRoot, int thumbnailCount, int serverPort) {
        this.thumbnailRoot = thumbnailRoot;
        this.thumbnailCount = thumbnailCount;
        this.serverPort = serverPort;
    }

    /**
     * Returns thumbnail status: URLs generated so far, expected total, and whether
     * generation is still in progress. Kicks off async generation on first call.
     *
     * @param titleCode the title's product code (e.g. "ABP-123")
     * @param video     the video record
     */
    public Map<String, Object> getThumbnailStatus(String titleCode, Video video) {
        Path videoDir = resolveVideoDir(titleCode, video.getFilename());
        List<String> cached = findCachedThumbnails(video.getId(), videoDir);

        String generationKey = titleCode + "/" + video.getFilename();
        boolean isGenerating = generating.contains(generationKey);

        // Kick off generation if not complete and not already running
        if (cached.size() < thumbnailCount && !isGenerating) {
            if (generating.add(generationKey)) {
                isGenerating = true;
                CompletableFuture.runAsync(() -> {
                    try {
                        generateThumbnails(video, videoDir);
                    } catch (Exception e) {
                        log.warn("Thumbnail generation failed for {} {}: {}",
                                titleCode, video.getFilename(), e.getMessage());
                    } finally {
                        generating.remove(generationKey);
                    }
                });
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("urls", cached);
        result.put("total", thumbnailCount);
        result.put("generating", isGenerating);
        return result;
    }

    /**
     * Returns the local file path for a thumbnail image, or empty if not found.
     */
    public Optional<Path> getThumbnailFile(String titleCode, String videoFilename, String thumbFilename) {
        Path file = resolveVideoDir(titleCode, videoFilename).resolve(thumbFilename);
        return Files.isRegularFile(file) ? Optional.of(file) : Optional.empty();
    }

    /**
     * Returns the root directory where all thumbnails are stored.
     * Used by the pruning command to walk the directory tree.
     */
    public Path root() {
        return thumbnailRoot;
    }

    /**
     * Returns the expected thumbnail count (for pruning / validation).
     */
    public int thumbnailCount() {
        return thumbnailCount;
    }

    private Path resolveVideoDir(String titleCode, String videoFilename) {
        return thumbnailRoot.resolve(titleCode).resolve(videoFilename);
    }

    private List<String> findCachedThumbnails(long videoId, Path videoDir) {
        if (!Files.isDirectory(videoDir)) return List.of();
        List<String> urls = new ArrayList<>();
        for (int i = 1; i <= thumbnailCount; i++) {
            String filename = String.format("thumb_%02d.jpg", i);
            if (Files.exists(videoDir.resolve(filename))) {
                urls.add("/api/videos/" + videoId + "/thumbnails/" + filename);
            }
        }
        return urls.isEmpty() ? List.of() : urls;
    }

    private void generateThumbnails(Video video, Path videoDir) throws IOException {
        Files.createDirectories(videoDir);

        String streamUrl = "http://localhost:" + serverPort + "/api/stream/" + video.getId();
        log.info("Generating {} thumbnails for video {} via {}", thumbnailCount, video.getId(), streamUrl);

        FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(streamUrl);
        grabber.setOption("skip_frame", "noref");
        grabber.setAudioChannels(0);
        grabber.start();

        try {
            long duration = grabber.getLengthInTime(); // microseconds
            if (duration <= 0) {
                log.warn("Could not determine duration for video {}", video.getId());
                return;
            }

            Java2DFrameConverter converter = new Java2DFrameConverter();
            int generated = 0;

            for (int i = 0; i < thumbnailCount; i++) {
                double fraction = 0.1 + (0.6 * i / (thumbnailCount - 1));
                long timestamp = (long) (duration * fraction);
                grabber.setTimestamp(timestamp, true);

                Frame frame = grabber.grabImage();
                if (frame == null) continue;

                BufferedImage image = converter.convert(frame);
                if (image == null) continue;

                BufferedImage scaled = scaleImage(image, THUMBNAIL_WIDTH);
                String filename = String.format("thumb_%02d.jpg", i + 1);
                Path outFile = videoDir.resolve(filename);
                ImageIO.write(scaled, "jpg", outFile.toFile());
                generated++;
            }

            log.info("Generated {} thumbnails for video {}", generated, video.getId());
        } finally {
            grabber.stop();
            grabber.release();
        }
    }

    private static BufferedImage scaleImage(BufferedImage src, int targetWidth) {
        double ratio = (double) targetWidth / src.getWidth();
        int targetHeight = (int) (src.getHeight() * ratio);
        BufferedImage scaled = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, targetWidth, targetHeight, null);
        g.dispose();
        return scaled;
    }
}
