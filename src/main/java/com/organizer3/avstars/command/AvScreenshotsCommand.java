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
        for (AvVideo video : pending) {
            io.status("  " + video.getFilename() + " …");
            List<String> urls = screenshotService.generateForVideo(video.getId());
            io.clearStatus();
            if (!urls.isEmpty()) {
                io.println("  " + video.getFilename() + ": " + urls.size() + " frames");
                done++;
            } else {
                io.println("  " + video.getFilename() + ": failed");
                failed++;
            }
        }

        io.println("Done: " + done + " processed, " + failed + " failed.");
    }
}
