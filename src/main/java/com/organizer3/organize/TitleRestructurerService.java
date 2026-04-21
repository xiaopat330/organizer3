package com.organizer3.organize;

import com.organizer3.config.volume.MediaConfig;
import com.organizer3.filesystem.VolumeFileSystem;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Phase 2 of the organize pipeline: arrange the title folder's contents into the
 * layout convention — cover at base, videos in a child subfolder. See
 * {@code spec/PROPOSAL_ORGANIZE_PIPELINE.md} §3.2.
 *
 * <p>For each video file at the title's base, pick a subfolder by filename hint and
 * move it there:
 * <ul>
 *   <li>name contains {@code -4K} → {@code 4K/}</li>
 *   <li>name contains {@code -h265} → {@code h265/}</li>
 *   <li>otherwise → {@code video/}</li>
 * </ul>
 * Videos already inside any subfolder are left alone. Covers are never moved.
 *
 * <p>No video-metadata probing; the folder name is a cosmetic format hint only
 * (per user guidance, §3.2 of the spec). Collisions at the target subfolder are
 * skipped with a reason.
 */
@Slf4j
public class TitleRestructurerService {

    private final MediaConfig media;

    public TitleRestructurerService(MediaConfig media) {
        this.media = media == null ? MediaConfig.DEFAULTS : media;
    }

    public Result apply(VolumeFileSystem fs, Path titleFolder, boolean dryRun) throws IOException {
        if (!fs.exists(titleFolder) || !fs.isDirectory(titleFolder)) {
            throw new IllegalArgumentException("Title folder does not exist or is not a directory: " + titleFolder);
        }

        List<Action> planned = new ArrayList<>();
        List<Action> collisions = new ArrayList<>();

        for (Path child : fs.listDirectory(titleFolder)) {
            if (fs.isDirectory(child)) continue;                 // children dirs untouched
            String name = filename(child);
            if (!isVideo(name)) continue;                         // only videos get restructured

            String subfolder = pickSubfolder(name);
            Path subfolderPath = titleFolder.resolve(subfolder);
            Path dest = subfolderPath.resolve(name);

            if (fs.exists(dest)) {
                collisions.add(new Action(child.toString(), dest.toString(), "destination already exists"));
            } else {
                planned.add(new Action(child.toString(), dest.toString(), null));
            }
        }

        if (dryRun) {
            return new Result(true, titleFolder.toString(), planned, collisions, List.of(), List.of());
        }

        List<Action> moved = new ArrayList<>();
        List<Action> failed = new ArrayList<>();
        for (Action a : planned) {
            try {
                Path dest = Path.of(a.to());
                fs.createDirectories(dest.getParent());
                fs.move(Path.of(a.from()), dest);
                log.info("FS mutation [TitleRestructurer.restructure]: moved video — titleFolder={} from={} to={}",
                        titleFolder, a.from(), a.to());
                moved.add(a);
            } catch (IOException e) {
                log.warn("FS mutation [TitleRestructurer.restructure] failed — from={} to={} error={}",
                        a.from(), a.to(), e.getMessage());
                failed.add(new Action(a.from(), a.to(), e.getMessage()));
            }
        }

        return new Result(false, titleFolder.toString(), planned, collisions, moved, failed);
    }

    static String pickSubfolder(String filename) {
        if (filename == null) return "video";
        String lower = filename.toLowerCase(Locale.ROOT);
        if (lower.contains("-4k"))   return "4K";
        if (lower.contains("-h265")) return "h265";
        return "video";
    }

    private boolean isVideo(String filename) {
        if (filename == null) return false;
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) return false;
        String ext = filename.substring(dot + 1).toLowerCase(Locale.ROOT);
        for (String e : media.effectiveVideoExtensions()) if (e.equalsIgnoreCase(ext)) return true;
        return false;
    }

    private static String filename(Path p) {
        Path n = p.getFileName();
        return n == null ? p.toString() : n.toString();
    }

    public record Action(String from, String to, String note) {}

    public record Result(
            boolean dryRun,
            String titleFolder,
            List<Action> planned,
            List<Action> collisions,
            List<Action> moved,
            List<Action> failed
    ) {}
}
