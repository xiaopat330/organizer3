package com.organizer3.command;

import com.organizer3.model.Video;
import com.organizer3.repository.VideoRepository;
import com.organizer3.shell.SessionContext;
import com.organizer3.shell.io.CommandIO;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Populate video metadata columns (duration, resolution, codec, container) for rows
 * where {@code duration_sec IS NULL}. Resumable — re-running picks up from where it
 * left off, because successful probes mark rows by setting duration_sec non-null.
 *
 * <p>Usage:
 * <ul>
 *   <li>{@code probe videos} — process the currently-mounted volume</li>
 *   <li>{@code probe videos <volumeId>} — explicit volume (must be the active mount)</li>
 * </ul>
 *
 * <p>Requires an active SMB mount because VideoProbe streams through the HTTP endpoint,
 * which in turn requires the volume's files to be readable.
 */
@Slf4j
public class ProbeVideosCommand implements Command {

    /** Batch size for findUnprobed. Small enough to give progress feedback, large enough to avoid query overhead. */
    private static final int BATCH_SIZE = 50;

    private final VideoRepository videoRepo;
    private final BiFunction<Long, String, Map<String, Object>> prober;

    public ProbeVideosCommand(VideoRepository videoRepo,
                              BiFunction<Long, String, Map<String, Object>> prober) {
        this.videoRepo = videoRepo;
        this.prober = prober;
    }

    @Override public String name()        { return "probe videos"; }
    @Override public String description() { return "Backfill video metadata for the mounted volume (probe videos [volumeId])"; }

    @Override
    public void execute(String[] args, SessionContext ctx, CommandIO io) {
        String mountedVolumeId = ctx.getMountedVolumeId();
        if (mountedVolumeId == null) {
            io.println("No volume mounted. Run 'mount <volumeId>' first.");
            return;
        }
        String targetVolumeId = args.length >= 2 ? args[1] : mountedVolumeId;
        if (!targetVolumeId.equals(mountedVolumeId)) {
            io.println("Volume '" + targetVolumeId + "' is not the active mount (currently: '"
                    + mountedVolumeId + "'). Probing needs the volume mounted.");
            return;
        }

        long total = videoRepo.countUnprobed(targetVolumeId);
        if (total == 0) {
            io.println("No unprobed videos on volume " + targetVolumeId + ".");
            return;
        }
        io.println("Probing " + total + " video(s) on volume " + targetVolumeId + "...");

        int done = 0, ok = 0, failed = 0;
        long cursor = 0;
        while (true) {
            List<Video> batch = videoRepo.findUnprobed(targetVolumeId, cursor, BATCH_SIZE);
            if (batch.isEmpty()) break;

            for (Video v : batch) {
                cursor = Math.max(cursor, v.getId()); // advance past every row whether or not probe succeeds
                Map<String, Object> meta = prober.apply(v.getId(), v.getFilename());
                if (meta.isEmpty() || !meta.containsKey("durationSeconds")) {
                    failed++;
                    log.warn("Probe failed for video {} ({})", v.getId(), v.getFilename());
                } else {
                    applyMetadata(v, meta);
                    ok++;
                }
                done++;
                if (done % 10 == 0 || done == total) {
                    io.println("  " + done + "/" + total + "  (ok=" + ok + ", failed=" + failed + ")");
                }
            }
        }
        io.println("Done. ok=" + ok + ", failed=" + failed + ", total=" + done);
    }

    private void applyMetadata(Video v, Map<String, Object> meta) {
        Long durationSec = toLong(meta.get("durationSeconds"));
        Integer width    = toInt(meta.get("width"));
        Integer height   = toInt(meta.get("height"));
        String videoCodec = nullIfUnknown(toStr(meta.get("videoCodec")));
        String audioCodec = nullIfUnknown(toStr(meta.get("audioCodec")));
        String container  = extractContainer(v.getFilename());
        videoRepo.updateMetadata(v.getId(), durationSec, width, height, videoCodec, audioCodec, container);
    }

    private static String extractContainer(String filename) {
        if (filename == null) return null;
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) return null;
        return filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String nullIfUnknown(String s) {
        return (s == null || "unknown".equalsIgnoreCase(s)) ? null : s;
    }

    private static Long    toLong(Object o) { return o instanceof Number n ? n.longValue()  : null; }
    private static Integer toInt (Object o) { return o instanceof Number n ? n.intValue()   : null; }
    private static String  toStr (Object o) { return o == null ? null : o.toString(); }
}
