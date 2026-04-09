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
 * <p>Thumbnail count is duration-based: one thumbnail every {@code intervalMinutes}
 * minutes, clamped between {@value #MIN_THUMBNAILS} and {@value #MAX_THUMBNAILS}.
 *
 * <p>Thumbnails are cached on local disk under {@code <dataDir>/thumbnails/<titleCode>/<videoFilename>/}.
 * A {@code .count} marker file records the expected total so subsequent status
 * checks don't need to re-probe the video duration.
 *
 * <p>Generation is async — callers get an empty list on first request and poll
 * until thumbnails appear. FFmpeg opens the video via the local HTTP streaming
 * endpoint so it can use Range requests for O(1) seeking instead of reading
 * the entire file sequentially over SMB.
 */
@Slf4j
public class ThumbnailService {

    private static final int THUMBNAIL_WIDTH = 240;
    static final int MIN_THUMBNAILS = 4;
    static final int MAX_THUMBNAILS = 30;
    private static final String COUNT_FILE = ".count";

    private final Path thumbnailRoot;
    private final int intervalMinutes;
    private final int serverPort;
    private final Set<String> generating = ConcurrentHashMap.newKeySet();

    public ThumbnailService(Path thumbnailRoot, int intervalMinutes, int serverPort) {
        this.thumbnailRoot = thumbnailRoot;
        this.intervalMinutes = intervalMinutes;
        this.serverPort = serverPort;
    }

    /**
     * Computes the number of thumbnails for a given duration.
     * Visible for testing.
     */
    int computeCount(long durationSeconds) {
        if (durationSeconds <= 0) return MIN_THUMBNAILS;
        double minutes = durationSeconds / 60.0;
        int count = Math.max(MIN_THUMBNAILS, (int) Math.round(minutes / intervalMinutes));
        return Math.min(count, MAX_THUMBNAILS);
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

        // Read expected count from marker file if generation has already run
        int expectedCount = readCountFile(videoDir).orElse(-1);

        List<String> cached = expectedCount > 0
                ? findCachedThumbnails(video.getId(), videoDir, expectedCount)
                : List.of();

        String generationKey = titleCode + "/" + video.getFilename();
        boolean isGenerating = generating.contains(generationKey);

        // Kick off generation if count unknown or not complete, and not already running
        boolean needsGeneration = expectedCount < 0 || cached.size() < expectedCount;
        if (needsGeneration && !isGenerating) {
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
        result.put("total", expectedCount > 0 ? expectedCount : 0);
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
     */
    public Path root() {
        return thumbnailRoot;
    }

    /**
     * Returns the configured interval in minutes.
     */
    public int intervalMinutes() {
        return intervalMinutes;
    }

    private Path resolveVideoDir(String titleCode, String videoFilename) {
        return thumbnailRoot.resolve(titleCode).resolve(videoFilename);
    }

    private Optional<Integer> readCountFile(Path videoDir) {
        Path countFile = videoDir.resolve(COUNT_FILE);
        if (!Files.isRegularFile(countFile)) return Optional.empty();
        try {
            return Optional.of(Integer.parseInt(Files.readString(countFile).trim()));
        } catch (IOException | NumberFormatException e) {
            return Optional.empty();
        }
    }

    private void writeCountFile(Path videoDir, int count) {
        try {
            Files.writeString(videoDir.resolve(COUNT_FILE), String.valueOf(count));
        } catch (IOException e) {
            log.warn("Failed to write count file in {}: {}", videoDir, e.getMessage());
        }
    }

    private List<String> findCachedThumbnails(long videoId, Path videoDir, int expectedCount) {
        if (!Files.isDirectory(videoDir)) return List.of();
        List<String> urls = new ArrayList<>();
        for (int i = 1; i <= expectedCount; i++) {
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

        FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(streamUrl);
        grabber.setOption("skip_frame", "noref");
        grabber.setAudioChannels(0);
        grabber.start();

        try {
            long durationMicros = grabber.getLengthInTime();
            if (durationMicros <= 0) {
                log.warn("Could not determine duration for video {}", video.getId());
                return;
            }

            long durationSeconds = durationMicros / 1_000_000;
            int thumbnailCount = computeCount(durationSeconds);

            log.info("Generating {} thumbnails ({}min intervals) for video {} ({}s) via {}",
                    thumbnailCount, intervalMinutes, video.getId(), durationSeconds, streamUrl);

            // Write count marker so status endpoint knows the expected total
            writeCountFile(videoDir, thumbnailCount);

            Java2DFrameConverter converter = new Java2DFrameConverter();
            int generated = 0;

            for (int i = 0; i < thumbnailCount; i++) {
                // Spread evenly across 3%-97% of the video
                double fraction = 0.03 + (0.94 * i / (thumbnailCount - 1));
                long timestamp = (long) (durationMicros * fraction);
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
