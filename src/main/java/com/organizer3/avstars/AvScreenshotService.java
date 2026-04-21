package com.organizer3.avstars;

import com.organizer3.avstars.repository.AvScreenshotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Core screenshot generation logic, shared between {@code AvScreenshotsCommand}
 * and the on-demand web endpoint.
 */
@Slf4j
@RequiredArgsConstructor
public class AvScreenshotService {

    static final int FRAME_COUNT = 10;
    static final int SCREENSHOT_WIDTH = 480;
    static final double[] OFFSETS = { 0.05, 0.15, 0.25, 0.35, 0.45, 0.55, 0.65, 0.75, 0.85, 0.95 };

    private final AvScreenshotRepository screenshotRepo;
    private final Path screenshotDir;
    private final int webServerPort;

    /**
     * Generates screenshots for the given video ID. If screenshots already exist,
     * returns their URLs immediately without regenerating.
     *
     * @return API URL paths for all generated screenshots, empty list on failure.
     */
    public List<String> generateForVideo(long videoId) {
        // Already done — return existing URLs
        var existing = screenshotRepo.findByVideoId(videoId);
        if (!existing.isEmpty()) {
            return existing.stream()
                    .map(s -> "/api/av/screenshots/" + videoId + "/" + s.getSeq())
                    .toList();
        }

        String streamUrl = "http://localhost:" + webServerPort + "/api/av/stream/" + videoId;
        FFmpegFrameGrabber grabber = null;
        try {
            grabber = new FFmpegFrameGrabber(streamUrl);
            grabber.setOption("skip_frame", "noref");
            // Fail fast if the stream endpoint is unhealthy — prevents FFmpeg's HTTP
            // demuxer from entering an infinite reconnect loop against a 502-returning
            // server. See logs/organizer3.log "AV stream failed" pattern for the class
            // of bug this guards against.
            grabber.setOption("reconnect", "0");
            grabber.setOption("reconnect_streamed", "0");
            grabber.setOption("reconnect_on_network_error", "0");
            grabber.setOption("rw_timeout", "10000000");   // 10s in microseconds
            grabber.setAudioChannels(0);
            grabber.start();

            long durationMicros = grabber.getLengthInTime();
            if (durationMicros <= 0) {
                log.warn("Cannot determine duration for video {} — skipping screenshot generation", videoId);
                return List.of();
            }

            Path videoDir = screenshotDir.resolve(String.valueOf(videoId));
            Files.createDirectories(videoDir);

            List<String> urls = new ArrayList<>();
            try (Java2DFrameConverter converter = new Java2DFrameConverter()) {
                for (int i = 0; i < FRAME_COUNT; i++) {
                    long timestamp = (long) (durationMicros * OFFSETS[i]);
                    grabber.setTimestamp(timestamp, true);
                    Frame frame = grabber.grabImage();
                    if (frame == null) continue;
                    BufferedImage image = converter.convert(frame);
                    if (image == null) continue;
                    BufferedImage scaled = scaleImage(image, SCREENSHOT_WIDTH);
                    Path outFile = videoDir.resolve(i + ".jpg");
                    ImageIO.write(scaled, "jpg", outFile.toFile());
                    screenshotRepo.insert(videoId, i, outFile.toString());
                    urls.add("/api/av/screenshots/" + videoId + "/" + i);
                }
            }

            log.info("Generated {} screenshots for video {}", urls.size(), videoId);
            return urls;

        } catch (Exception e) {
            log.warn("Screenshot generation failed for video {}: {}", videoId, e.getMessage(), e);
            return List.of();
        } finally {
            if (grabber != null) {
                try { grabber.stop(); } catch (Exception ignored) {}
                try { grabber.release(); } catch (Exception ignored) {}
            }
        }
    }

    static BufferedImage scaleImage(BufferedImage src, int targetWidth) {
        if (src.getWidth() <= targetWidth) return src;
        double ratio = (double) targetWidth / src.getWidth();
        int targetHeight = (int) (src.getHeight() * ratio);
        BufferedImage scaled = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, targetWidth, targetHeight, null);
        g.dispose();
        return scaled;
    }
}
