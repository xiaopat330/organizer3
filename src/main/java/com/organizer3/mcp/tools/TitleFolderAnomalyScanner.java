package com.organizer3.mcp.tools;

import com.organizer3.filesystem.VolumeFileSystem;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Shared per-folder layout audit logic, extracted from {@link ScanTitleFolderAnomaliesTool}.
 *
 * <p>Checks a single title folder against the layout convention (cover at base, videos in
 * subfolder). Returns an anomaly list that callers can map to issue kinds.
 *
 * <p>This class is stateless — all state is passed in through {@link #scan}.
 */
public final class TitleFolderAnomalyScanner {

    private static final Set<String> COVER_EXTS = Set.of("jpg", "jpeg", "png", "webp");
    private static final Set<String> VIDEO_EXTS = Set.of("mkv", "mp4", "avi", "wmv", "mov", "m4v", "ts", "mpg", "mpeg", "flv");

    private TitleFolderAnomalyScanner() {}

    /**
     * Scan a single title folder and return the anomaly report.
     *
     * @param folder path of the title folder
     * @param fs     file system to use for directory listing
     * @return anomaly report; anomalies list is empty if the folder is well-formed
     * @throws IOException if the file system raises an IO error
     */
    public static ScanTitleFolderAnomaliesTool.LocationReport scan(Path folder, VolumeFileSystem fs)
            throws IOException {
        if (!fs.exists(folder) || !fs.isDirectory(folder)) {
            return new ScanTitleFolderAnomaliesTool.LocationReport(
                    folder.toString(), List.of("missing_or_not_directory"),
                    List.of(), List.of(), List.of(), List.of());
        }

        List<String> anomalies    = new ArrayList<>();
        List<String> baseCovers   = new ArrayList<>();
        List<String> baseVideos   = new ArrayList<>();
        List<String> subfolders   = new ArrayList<>();
        List<ScanTitleFolderAnomaliesTool.MisfiledCover> misfiledCovers = new ArrayList<>();

        List<Path> children = fs.listDirectory(folder);
        if (children.isEmpty()) {
            anomalies.add("empty_folder");
        }
        for (Path child : children) {
            String name = fileName(child);
            if (fs.isDirectory(child)) {
                subfolders.add(name);
                List<String> covers = new ArrayList<>();
                for (Path inner : fs.listDirectory(child)) {
                    if (!fs.isDirectory(inner) && isCover(fileName(inner))) {
                        covers.add(fileName(inner));
                    }
                }
                if (!covers.isEmpty()) {
                    misfiledCovers.add(new ScanTitleFolderAnomaliesTool.MisfiledCover(name, covers));
                }
            } else {
                if (isCover(name)) baseCovers.add(name);
                else if (isVideo(name)) baseVideos.add(name);
            }
        }
        if (baseCovers.size() >= 2) anomalies.add("multiple_base_covers");
        if (baseCovers.isEmpty())   anomalies.add("no_base_cover");
        if (!baseVideos.isEmpty())  anomalies.add("videos_at_base");
        if (!misfiledCovers.isEmpty()) anomalies.add("covers_in_subfolder");
        if (subfolders.size() > 1)  anomalies.add("multiple_video_subfolders");

        return new ScanTitleFolderAnomaliesTool.LocationReport(
                folder.toString(), anomalies, baseCovers, baseVideos, subfolders, misfiledCovers);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static boolean isCover(String filename) {
        return hasExt(filename, COVER_EXTS);
    }

    private static boolean isVideo(String filename) {
        return hasExt(filename, VIDEO_EXTS);
    }

    private static boolean hasExt(String filename, Set<String> exts) {
        if (filename == null) return false;
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) return false;
        return exts.contains(filename.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    private static String fileName(Path p) {
        Path n = p.getFileName();
        return n == null ? p.toString() : n.toString();
    }
}
