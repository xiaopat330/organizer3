package com.organizer3.organize;

import com.organizer3.config.volume.MediaConfig;
import com.organizer3.filesystem.VolumeFileSystem;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Read-only audit of a fresh-prepped queue partition. Classifies each child folder
 * at the partition root into graduation-readiness buckets.
 *
 * <p>Post-prep workflow: {@code prep_fresh_videos} drops raw files into
 * {@code (CODE)/<video|h265>/} skeletons. A human then curates — looks up the
 * actress for the product code, renames the folder to {@code Actress Name (CODE)},
 * adds a cover image at the folder base. Once both are done, the folder can
 * graduate to a letter-volume queue. This service surfaces which folders are at
 * which stage of that curation.
 *
 * <p>Buckets:
 * <ul>
 *   <li>{@link Bucket#READY} — actress prefix present, cover at base, has video</li>
 *   <li>{@link Bucket#NEEDS_COVER} — actress prefix present, has video, no cover at base</li>
 *   <li>{@link Bucket#NEEDS_ACTRESS} — bare {@code (CODE)} folder shape, has video</li>
 *   <li>{@link Bucket#EMPTY} — skeleton-shaped but no video inside (investigate)</li>
 *   <li>{@link Bucket#OTHER} — folder that doesn't match either skeleton shape
 *       (actress workspace folders, free-form dirs). Reported but not actionable.</li>
 * </ul>
 */
@Slf4j
public class FreshAuditService {

    /** Bare code folder: {@code (ONED-1234)}, no prefix. */
    private static final Pattern BARE_CODE     = Pattern.compile("^\\([^)]+\\)$");
    /** Actress-prefixed code folder: {@code Yua Aida (ONED-1234)}. */
    private static final Pattern ACTRESS_CODE  = Pattern.compile("^(.+) \\([^)]+\\)$");

    private final MediaConfig media;

    public FreshAuditService(MediaConfig media) {
        this.media = media == null ? MediaConfig.DEFAULTS : media;
    }

    /** Audit all immediate child folders of {@code partitionRoot}. */
    public Result audit(VolumeFileSystem fs, Path partitionRoot) throws IOException {
        if (!fs.exists(partitionRoot) || !fs.isDirectory(partitionRoot)) {
            throw new IllegalArgumentException(
                    "Partition root does not exist or is not a directory: " + partitionRoot);
        }
        List<Entry> entries = new ArrayList<>();
        Map<Bucket, Integer> counts = new EnumMap<>(Bucket.class);
        for (Bucket b : Bucket.values()) counts.put(b, 0);

        for (Path child : fs.listDirectory(partitionRoot)) {
            if (!fs.isDirectory(child)) continue;
            Entry e = classifyOne(fs, child);
            entries.add(e);
            counts.put(e.bucket(), counts.get(e.bucket()) + 1);
        }
        entries.sort((a, b) -> a.folderName().compareToIgnoreCase(b.folderName()));
        return new Result(partitionRoot.toString(), entries.size(), counts, entries);
    }

    /** Classify a single folder. Package-private for tests. */
    Entry classifyOne(VolumeFileSystem fs, Path folder) throws IOException {
        String name = folder.getFileName() == null ? folder.toString() : folder.getFileName().toString();
        boolean bare        = BARE_CODE.matcher(name).matches();
        Matcher am          = ACTRESS_CODE.matcher(name);
        boolean withActress = am.matches();

        LocalDate mtime = safeMtime(fs, folder);

        if (!bare && !withActress) {
            return new Entry(name, Bucket.OTHER, mtime, false, false);
        }

        boolean hasCoverAtBase = false;
        boolean hasVideoInside = false;
        for (Path child : fs.listDirectory(folder)) {
            if (fs.isDirectory(child)) {
                // descend one level — prep output is (CODE)/<subfolder>/<video>
                if (!hasVideoInside) {
                    for (Path grandchild : fs.listDirectory(child)) {
                        if (!fs.isDirectory(grandchild) && isVideo(filename(grandchild))) {
                            hasVideoInside = true;
                            break;
                        }
                    }
                }
            } else {
                String fn = filename(child);
                if (isCover(fn)) hasCoverAtBase = true;
                if (isVideo(fn)) hasVideoInside = true;  // videos occasionally live at folder base
            }
        }

        Bucket b;
        if (!hasVideoInside)      b = Bucket.EMPTY;
        else if (bare)            b = Bucket.NEEDS_ACTRESS;
        else if (!hasCoverAtBase) b = Bucket.NEEDS_COVER;
        else                      b = Bucket.READY;
        return new Entry(name, b, mtime, hasCoverAtBase, hasVideoInside);
    }

    private LocalDate safeMtime(VolumeFileSystem fs, Path p) {
        try { return fs.getLastModifiedDate(p); } catch (IOException e) { return null; }
    }

    private boolean isVideo(String filename) {
        if (filename == null) return false;
        int dot = filename.lastIndexOf('.');
        if (dot < 0) return false;
        String ext = filename.substring(dot + 1).toLowerCase(Locale.ROOT);
        for (String e : media.effectiveVideoExtensions()) if (e.equalsIgnoreCase(ext)) return true;
        return false;
    }

    private boolean isCover(String filename) {
        if (filename == null) return false;
        int dot = filename.lastIndexOf('.');
        if (dot < 0) return false;
        String ext = filename.substring(dot + 1).toLowerCase(Locale.ROOT);
        for (String e : media.effectiveCoverExtensions()) if (e.equalsIgnoreCase(ext)) return true;
        return false;
    }

    private static String filename(Path p) {
        Path n = p.getFileName();
        return n == null ? p.toString() : n.toString();
    }

    // ── result shapes ──────────────────────────────────────────────────────

    public enum Bucket { READY, NEEDS_ACTRESS, NEEDS_COVER, EMPTY, OTHER }

    public record Entry(
            String folderName,
            Bucket bucket,
            LocalDate lastModified,
            boolean hasCoverAtBase,
            boolean hasVideoInside
    ) {}

    public record Result(
            String partitionRoot,
            int total,
            Map<Bucket, Integer> counts,
            List<Entry> entries
    ) {}
}
