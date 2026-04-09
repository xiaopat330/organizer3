package com.organizer3.media;

import com.organizer3.model.Video;
import com.organizer3.smb.SmbConnectionFactory;
import com.organizer3.smb.SmbConnectionFactory.SmbShareHandle;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import lombok.extern.slf4j.Slf4j;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;

/**
 * Generates preview thumbnails from video files via JavaCV (FFmpeg).
 *
 * <p>Thumbnails are cached on local disk under {@code data/thumbnails/<videoId>/}.
 * Generated lazily on first request; subsequent requests serve from cache.
 */
@Slf4j
public class ThumbnailService {

    private static final int THUMBNAIL_COUNT = 4;
    private static final int THUMBNAIL_WIDTH = 320;

    private final Path thumbnailRoot;
    private final SmbConnectionFactory smbFactory;

    public ThumbnailService(Path dataDir, SmbConnectionFactory smbFactory) {
        this.thumbnailRoot = dataDir.resolve("thumbnails");
        this.smbFactory = smbFactory;
    }

    /**
     * Returns thumbnail URLs for the given video. Generates them if not cached.
     * Returns an empty list if generation fails (video inaccessible, unsupported format, etc.).
     */
    public List<String> getThumbnailUrls(Video video) {
        Path videoDir = thumbnailRoot.resolve(String.valueOf(video.getId()));

        // Check cache
        List<String> cached = findCachedThumbnails(video.getId(), videoDir);
        if (!cached.isEmpty()) return cached;

        // Generate
        try {
            return generateThumbnails(video, videoDir);
        } catch (Exception e) {
            log.warn("Failed to generate thumbnails for video {}: {}", video.getId(), e.getMessage());
            return List.of();
        }
    }

    /**
     * Returns the local file path for a thumbnail image, or empty if not found.
     */
    public java.util.Optional<Path> getThumbnailFile(long videoId, String filename) {
        Path file = thumbnailRoot.resolve(String.valueOf(videoId)).resolve(filename);
        return Files.isRegularFile(file) ? java.util.Optional.of(file) : java.util.Optional.empty();
    }

    private List<String> findCachedThumbnails(long videoId, Path videoDir) {
        if (!Files.isDirectory(videoDir)) return List.of();
        List<String> urls = new ArrayList<>();
        for (int i = 1; i <= THUMBNAIL_COUNT; i++) {
            String filename = String.format("thumb_%02d.jpg", i);
            if (Files.exists(videoDir.resolve(filename))) {
                urls.add("/api/videos/" + videoId + "/thumbnails/" + filename);
            }
        }
        return urls.isEmpty() ? List.of() : urls;
    }

    private List<String> generateThumbnails(Video video, Path videoDir) throws IOException {
        Files.createDirectories(videoDir);

        try (SmbShareHandle handle = smbFactory.open(video.getVolumeId());
             InputStream is = handle.openFile(video.getPath().toString())) {

            FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(is);
            grabber.start();

            long duration = grabber.getLengthInTime(); // microseconds
            if (duration <= 0) {
                log.warn("Could not determine duration for video {}", video.getId());
                grabber.stop();
                return List.of();
            }

            Java2DFrameConverter converter = new Java2DFrameConverter();
            List<String> urls = new ArrayList<>();

            for (int i = 0; i < THUMBNAIL_COUNT; i++) {
                // Sample at 10%, 30%, 50%, 70% of duration (avoid very start/end)
                double fraction = 0.1 + (0.6 * i / (THUMBNAIL_COUNT - 1));
                long timestamp = (long) (duration * fraction);
                grabber.setTimestamp(timestamp);

                Frame frame = grabber.grabImage();
                if (frame == null) continue;

                BufferedImage image = converter.convert(frame);
                if (image == null) continue;

                // Scale to thumbnail width
                BufferedImage scaled = scaleImage(image, THUMBNAIL_WIDTH);

                String filename = String.format("thumb_%02d.jpg", i + 1);
                Path outFile = videoDir.resolve(filename);
                ImageIO.write(scaled, "jpg", outFile.toFile());
                urls.add("/api/videos/" + video.getId() + "/thumbnails/" + filename);
            }

            grabber.stop();
            log.info("Generated {} thumbnails for video {}", urls.size(), video.getId());
            return urls;
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
