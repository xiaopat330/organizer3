package com.organizer3.avstars.command;

import com.organizer3.avstars.AvScreenshotService;
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

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Generates screenshot frames for all videos belonging to a named AV actress.
 *
 * <p>Delegates per-video generation to {@link AvScreenshotService}.
 *
 * <p>Usage: {@code av screenshots <actress-name>}
 */
@Slf4j
@RequiredArgsConstructor
public class AvScreenshotsCommand implements Command {

    /** Max seconds per video before we cancel and move on. Generous vs the ~3s we observe for healthy files. */
    private static final int PER_VIDEO_TIMEOUT_SEC = 120;

    private final AvActressRepository actressRepo;
    private final AvVideoRepository videoRepo;
    private final AvScreenshotRepository screenshotRepo;
    private final AvScreenshotService screenshotService;

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
        int timedOut = 0;
        int total = pending.size();
        // Dedicated single-thread executor so one wedged FFmpeg call (pathological file,
        // stuck demuxer) can't hang the command. If a video exceeds PER_VIDEO_TIMEOUT_SEC
        // we cancel the future, abandon the executor, and move on. The native JavaCV
        // thread may keep spinning in the background — acceptable tradeoff vs hanging.
        ExecutorService generator = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "av-screenshot-gen");
            t.setDaemon(true);
            return t;
        });
        try (var progress = io.startProgress("Screenshots", total)) {
            for (int i = 0; i < total; i++) {
                AvVideo video = pending.get(i);
                int remaining = total - i;
                progress.setLabel("[" + remaining + "/" + total + "] " + video.getFilename());

                final long vid = video.getId();
                Future<List<String>> future = generator.submit(() -> screenshotService.generateForVideo(vid));
                List<String> urls;
                try {
                    urls = future.get(PER_VIDEO_TIMEOUT_SEC, TimeUnit.SECONDS);
                } catch (TimeoutException te) {
                    future.cancel(true);
                    log.warn("Screenshot generation timed out for video {} ({}) after {}s — abandoning executor",
                            video.getId(), video.getFilename(), PER_VIDEO_TIMEOUT_SEC);
                    io.println("  " + video.getFilename() + ": TIMEOUT after " + PER_VIDEO_TIMEOUT_SEC + "s");
                    // The FFmpeg native thread may be stuck — replace the executor so the
                    // next video gets a fresh worker and the old one is abandoned.
                    generator.shutdownNow();
                    generator = Executors.newSingleThreadExecutor(r -> {
                        Thread t = new Thread(r, "av-screenshot-gen");
                        t.setDaemon(true);
                        return t;
                    });
                    timedOut++;
                    progress.advance();
                    continue;
                } catch (Exception e) {
                    log.warn("Screenshot generation errored for video {} ({}): {}",
                            video.getId(), video.getFilename(), e.getMessage(), e);
                    urls = List.of();
                }
                progress.advance();
                if (!urls.isEmpty()) {
                    io.println("  " + video.getFilename() + ": " + urls.size() + " frames");
                    done++;
                } else {
                    io.println("  " + video.getFilename() + ": failed");
                    failed++;
                }
            }
        } finally {
            generator.shutdownNow();
        }

        io.println("Done: " + done + " processed, " + failed + " failed, " + timedOut + " timed out.");
    }
}
