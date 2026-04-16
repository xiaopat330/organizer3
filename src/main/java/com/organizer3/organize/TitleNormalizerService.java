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
 * Phase 1 of the organize pipeline: rename a title's cover + single-video to the
 * canonical {@code {CODE}.{ext}} form. See {@code spec/PROPOSAL_ORGANIZE_PIPELINE.md} §3.1.
 *
 * <p>Scope (ships in step 3 of §9):
 * <ul>
 *   <li>Cover rename: if the title folder has exactly one cover file at its base,
 *       rename it to {@code {CODE}.{ext}} (keeping the original extension).</li>
 *   <li>Video rename: if there is exactly one video file anywhere in the title
 *       folder or its immediate child subfolders, rename it to {@code {CODE}.{ext}}.</li>
 * </ul>
 *
 * <p>Deferred: legacy {@code removelist}/{@code replacelist} token normalization on
 * multi-file cases. Will revisit after probe-backfill unblocks multi-file handling
 * per §6.2.
 */
@Slf4j
public class TitleNormalizerService {

    private final MediaConfig media;

    public TitleNormalizerService(MediaConfig media) {
        this.media = media == null ? MediaConfig.DEFAULTS : media;
    }

    public Result apply(VolumeFileSystem fs, Path titleFolder, String titleCode, boolean dryRun) throws IOException {
        if (!fs.exists(titleFolder) || !fs.isDirectory(titleFolder)) {
            throw new IllegalArgumentException("Title folder does not exist or is not a directory: " + titleFolder);
        }
        if (titleCode == null || titleCode.isBlank()) {
            throw new IllegalArgumentException("titleCode is required");
        }

        List<Path> baseChildren = fs.listDirectory(titleFolder);
        List<Path> coversAtBase = new ArrayList<>();
        List<Path> videosAnywhere = new ArrayList<>();
        for (Path child : baseChildren) {
            if (fs.isDirectory(child)) {
                // one level deeper for video counting
                for (Path inner : fs.listDirectory(child)) {
                    if (fs.isDirectory(inner)) continue;
                    if (isVideo(filename(inner))) videosAnywhere.add(inner);
                }
            } else {
                String name = filename(child);
                if (isCover(name)) coversAtBase.add(child);
                if (isVideo(name)) videosAnywhere.add(child);
            }
        }

        List<Action> planned = new ArrayList<>();
        List<Skip> skipped = new ArrayList<>();

        // Cover: exactly one at base
        if (coversAtBase.size() == 1) {
            Path src = coversAtBase.get(0);
            String srcName = filename(src);
            String target = titleCode + extensionWithDot(srcName);
            if (srcName.equals(target)) {
                skipped.add(new Skip("cover", srcName, "already canonical"));
            } else {
                planned.add(new Action("cover-rename", src.toString(), target));
            }
        } else if (coversAtBase.isEmpty()) {
            skipped.add(new Skip("cover", null, "no cover at base"));
        } else {
            skipped.add(new Skip("cover", null,
                    "multiple covers at base (" + coversAtBase.size() + ") — resolve with trash_duplicate_cover first"));
        }

        // Video: exactly one anywhere in title folder or one level deep
        if (videosAnywhere.size() == 1) {
            Path src = videosAnywhere.get(0);
            String srcName = filename(src);
            String target = titleCode + extensionWithDot(srcName);
            if (srcName.equals(target)) {
                skipped.add(new Skip("video", srcName, "already canonical"));
            } else {
                planned.add(new Action("video-rename", src.toString(), target));
            }
        } else if (videosAnywhere.isEmpty()) {
            skipped.add(new Skip("video", null, "no videos found"));
        } else {
            skipped.add(new Skip("video", null,
                    "multi-file title (" + videosAnywhere.size() + " videos) — deferred until probe-backfill"));
        }

        if (dryRun) {
            return new Result(true, titleFolder.toString(), planned, List.of(), List.of(), skipped);
        }

        List<Action> applied = new ArrayList<>();
        List<Action> failed = new ArrayList<>();
        for (Action a : planned) {
            try {
                fs.rename(Path.of(a.from()), a.to());
                applied.add(a);
            } catch (IOException e) {
                failed.add(new Action(a.op(), a.from(), a.to() + "  (error: " + e.getMessage() + ")"));
            }
        }

        return new Result(false, titleFolder.toString(), planned, applied, failed, skipped);
    }

    private boolean isCover(String filename) {
        return matchesAnyExtension(filename, media.effectiveCoverExtensions());
    }

    private boolean isVideo(String filename) {
        return matchesAnyExtension(filename, media.effectiveVideoExtensions());
    }

    private static boolean matchesAnyExtension(String filename, List<String> extensions) {
        if (filename == null) return false;
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) return false;
        String ext = filename.substring(dot + 1).toLowerCase(Locale.ROOT);
        for (String e : extensions) if (e.equalsIgnoreCase(ext)) return true;
        return false;
    }

    private static String extensionWithDot(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot < 0) return "";
        // preserve original-case extension so e.g. "MIDE-123.JPG" stays ".JPG" on rename
        return filename.substring(dot);
    }

    private static String filename(Path p) {
        Path n = p.getFileName();
        return n == null ? p.toString() : n.toString();
    }

    // ── result shapes ──────────────────────────────────────────────────────

    public record Action(String op, String from, String to) {}

    public record Skip(String kind, String filename, String reason) {}

    public record Result(
            boolean dryRun,
            String titleFolder,
            List<Action> planned,
            List<Action> applied,
            List<Action> failed,
            List<Skip> skipped
    ) {}
}
