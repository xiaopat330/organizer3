package com.organizer3.command;

import com.organizer3.filesystem.VolumeFileSystem;
import com.organizer3.model.Video;
import com.organizer3.repository.VideoRepository;
import com.organizer3.shell.SessionContext;
import com.organizer3.shell.io.CommandIO;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.List;

/**
 * Fill in {@code size_bytes} for videos on the currently-mounted volume that don't
 * have it set yet. Uses {@link VolumeFileSystem#size} — a single SMB stat per file.
 *
 * <p>Usage: {@code backfill-sizes}. Requires an active mount. Resumable: successful
 * writes set {@code size_bytes} non-null, so a re-run picks up where the previous
 * one left off (whether interrupted by Ctrl-C, lost connection, or a full pass).
 */
@Slf4j
public class BackfillSizesCommand implements Command {

    private static final int BATCH_SIZE = 200;

    private final VideoRepository videoRepo;

    public BackfillSizesCommand(VideoRepository videoRepo) {
        this.videoRepo = videoRepo;
    }

    @Override public String name()        { return "backfill-sizes"; }
    @Override public String description() { return "Fill videos.size_bytes for the mounted volume (one SMB stat per row)"; }

    @Override
    public void execute(String[] args, SessionContext ctx, CommandIO io) {
        String mountedVolumeId = ctx.getMountedVolumeId();
        if (mountedVolumeId == null) {
            io.println("No volume mounted. Run 'mount <volumeId>' first.");
            return;
        }
        if (!ctx.isConnected()) {
            io.println("Active volume connection is not live.");
            return;
        }
        VolumeFileSystem fs = ctx.getActiveConnection().fileSystem();

        long total = videoRepo.countWithoutSize(mountedVolumeId);
        if (total == 0) {
            io.println("No rows without size on volume " + mountedVolumeId + ".");
            return;
        }
        io.println("Filling size for " + total + " row(s) on volume " + mountedVolumeId + "...");

        int done = 0, ok = 0, failed = 0;
        long cursor = 0;
        while (true) {
            List<Video> batch = videoRepo.findWithoutSize(mountedVolumeId, cursor, BATCH_SIZE);
            if (batch.isEmpty()) break;
            for (Video v : batch) {
                cursor = Math.max(cursor, v.getId());
                try {
                    long size = fs.size(v.getPath());
                    videoRepo.updateSize(v.getId(), size);
                    ok++;
                } catch (IOException e) {
                    failed++;
                    log.warn("Size stat failed for video {} ({}): {}", v.getId(), v.getPath(), e.getMessage());
                }
                done++;
                if (done % 100 == 0 || done == total) {
                    io.println("  " + done + "/" + total + "  (ok=" + ok + ", failed=" + failed + ")");
                }
            }
        }
        io.println("Done. ok=" + ok + ", failed=" + failed + ", total=" + done);
    }
}
