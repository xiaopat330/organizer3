package com.organizer3.avstars.command;

import com.organizer3.avstars.model.AvActress;
import com.organizer3.avstars.model.AvVideo;
import com.organizer3.avstars.repository.AvActressRepository;
import com.organizer3.avstars.repository.AvScreenshotRepository;
import com.organizer3.avstars.repository.AvVideoRepository;
import com.organizer3.command.Command;
import com.organizer3.shell.SessionContext;
import com.organizer3.shell.io.CommandIO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * Generates screenshot frames for all videos belonging to a named AV actress.
 *
 * <p>Frames are extracted via JavaCV (FFmpeg) over the local HTTP streaming endpoint,
 * so no temp file copy is required. Saves 10 frames at evenly-spaced offsets
 * (5%, 15%, …, 95%) to {@code data/av_screenshots/<videoId>/<seq>.jpg}.
 *
 * <p>Usage: {@code av screenshots <actress-name>}
 *
 * <p>Requires a running web server (started at app launch) and a mounted AV volume
 * accessible via the streaming endpoint. Skips videos that already have screenshots.
 * Re-runs from scratch on videos whose screenshots were deleted.
 */
@Slf4j
@RequiredArgsConstructor
public class AvScreenshotsCommand implements Command {

    private static final int FRAME_COUNT = 10;
    private static final int SCREENSHOT_WIDTH = 480;
    /** Percentage offsets at which frames are captured (0.0–1.0). */
    private static final double[] OFFSETS = { 0.05, 0.15, 0.25, 0.35, 0.45, 0.55, 0.65, 0.75, 0.85, 0.95 };

    private final AvActressRepository actressRepo;
    private final AvVideoRepository videoRepo;
    private final AvScreenshotRepository screenshotRepo;
    private final Path screenshotDir;
    private final int webServerPort;

    @Override
    public String name() { return "av screenshots"; }

    @Override
    public String description() {
        return "Generate screenshot frames for all videos of an AV actress: av screenshots <name>";
    }

    @Override
    public void execute(String[] args, SessionContext ctx, CommandIO io) {
        if (args.length < 2) {
            io.println("Usage: av screenshots <actress-name>");
            return;
        }

        String query = String.join(" ", Arrays.copyOfRange(args, 1, args.length)).toLowerCase();
        List<AvActress> all = actressRepo.findAllByVideoCountDesc();
        List<AvActress> matches = all.stream()
                .filter(a -> a.getStageName().toLowerCase().contains(query)
                          || a.getFolderName().toLowerCase().contains(query))
                .toList();

        if (matches.isEmpty()) {
            io.println("No actress found matching '" + query + "'.");
            return;
        }
        if (matches.size() > 1) {
            io.println("Multiple matches — be more specific:");
            matches.forEach(a -> io.println("  " + a.getStageName() + " (" + a.getFolderName() + ")"));
            return;
        }

        AvActress actress = matches.get(0);
        List<AvVideo> videos = videoRepo.findByActress(actress.getId());

        List<AvVideo> pending = videos.stream()
                .filter(v -> screenshotRepo.countByVideoId(v.getId()) == 0)
                .toList();

        io.println("Actress: " + actress.getStageName()
                + " — " + pending.size() + "/" + videos.size() + " video(s) need screenshots");

        if (pending.isEmpty()) {
            io.println("All screenshots already generated.");
            return;
        }

        int done = 0;
        int failed = 0;
        for (AvVideo video : pending) {
            try {
                int generated = generateScreenshots(video, io);
                if (generated > 0) {
                    done++;
                } else {
                    failed++;
                }
            } catch (Exception e) {
                io.println("  [ERROR] " + video.getFilename() + ": " + e.getMessage());
                log.warn("Screenshot generation failed for video {}: {}", video.getId(), e.getMessage(), e);
                failed++;
            }
        }

        io.println("Done: " + done + " processed, " + failed + " failed.");
    }

    private int generateScreenshots(AvVideo video, CommandIO io) throws IOException {
        String streamUrl = "http://localhost:" + webServerPort + "/api/av/stream/" + video.getId();
        io.status("  " + video.getFilename() + " …");

        FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(streamUrl);
        grabber.setOption("skip_frame", "noref");
        grabber.setAudioChannels(0);
        grabber.start();

        try {
            long durationMicros = grabber.getLengthInTime();
            if (durationMicros <= 0) {
                io.clearStatus();
                io.println("  " + video.getFilename() + ": skipped (could not determine duration)");
                return 0;
            }

            Path videoDir = screenshotDir.resolve(String.valueOf(video.getId()));
            Files.createDirectories(videoDir);

            Java2DFrameConverter converter = new Java2DFrameConverter();
            int generated = 0;

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

                screenshotRepo.insert(video.getId(), i, outFile.toString());
                generated++;
            }

            io.clearStatus();
            io.println("  " + video.getFilename() + ": " + generated + " frames");
            log.info("Generated {} screenshots for video {} ({})", generated, video.getId(), video.getFilename());
            return generated;

        } finally {
            grabber.stop();
            grabber.release();
        }
    }

    private static BufferedImage scaleImage(BufferedImage src, int targetWidth) {
        if (src.getWidth() <= targetWidth) return src;
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
