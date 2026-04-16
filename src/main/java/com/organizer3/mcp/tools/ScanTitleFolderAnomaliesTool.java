package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.filesystem.VolumeFileSystem;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;
import com.organizer3.model.Title;
import com.organizer3.model.TitleLocation;
import com.organizer3.repository.TitleLocationRepository;
import com.organizer3.repository.TitleRepository;
import com.organizer3.shell.SessionContext;
import com.organizer3.smb.VolumeConnection;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Per-title folder layout audit. Returns every deviation from the title-folder convention
 * for ONE title on the currently-mounted volume:
 *
 * <ul>
 *   <li>Multiple covers at the base folder</li>
 *   <li>Covers inside a video subfolder (misfiled)</li>
 *   <li>Video files sitting at the base folder instead of in a subfolder</li>
 *   <li>Empty folders</li>
 * </ul>
 *
 * <p>Use this on a single known-problematic title. For mass scans, pair
 * {@code find_multi_cover_titles} + {@code find_misfiled_covers}.
 */
public class ScanTitleFolderAnomaliesTool implements Tool {

    private static final Set<String> COVER_EXTS = Set.of("jpg", "jpeg", "png", "webp");
    private static final Set<String> VIDEO_EXTS = Set.of("mkv", "mp4", "avi", "wmv", "mov", "m4v", "ts", "mpg", "mpeg", "flv");

    private final SessionContext session;
    private final TitleRepository titleRepo;
    private final TitleLocationRepository locationRepo;

    public ScanTitleFolderAnomaliesTool(SessionContext session,
                                         TitleRepository titleRepo,
                                         TitleLocationRepository locationRepo) {
        this.session = session;
        this.titleRepo = titleRepo;
        this.locationRepo = locationRepo;
    }

    @Override public String name()        { return "scan_title_folder_anomalies"; }
    @Override public String description() {
        return "Audit one title's folder on the mounted volume against the layout convention "
             + "(cover at base, videos in subfolder). Returns every deviation found.";
    }

    @Override
    public JsonNode inputSchema() {
        return Schemas.object()
                .prop("code", "string", "Title code to inspect.")
                .require("code")
                .build();
    }

    @Override
    public Object call(JsonNode args) throws IOException {
        String code = Schemas.requireString(args, "code");

        String volumeId = session.getMountedVolumeId();
        if (volumeId == null) {
            throw new IllegalArgumentException(
                    "No volume is currently mounted. Mount one with the shell before using this tool.");
        }
        VolumeConnection conn = session.getActiveConnection();
        if (conn == null || !conn.isConnected()) {
            throw new IllegalArgumentException("Active connection is closed; re-mount the volume.");
        }
        VolumeFileSystem fs = conn.fileSystem();

        Title title = titleRepo.findByCode(code)
                .orElseThrow(() -> new IllegalArgumentException("No title with code " + code));
        List<TitleLocation> locations = locationRepo.findByTitle(title.getId()).stream()
                .filter(l -> l.getVolumeId().equals(volumeId))
                .toList();
        if (locations.isEmpty()) {
            throw new IllegalArgumentException(
                    "Title " + code + " has no location on the active volume '" + volumeId + "'.");
        }

        List<LocationReport> reports = new ArrayList<>();
        for (TitleLocation loc : locations) {
            Path folder = loc.getPath();
            if (!fs.exists(folder) || !fs.isDirectory(folder)) {
                reports.add(new LocationReport(folder.toString(), List.of("missing_or_not_directory"),
                        List.of(), List.of(), List.of(), List.of()));
                continue;
            }
            List<String> anomalies = new ArrayList<>();
            List<String> baseCovers = new ArrayList<>();
            List<String> baseVideos = new ArrayList<>();
            List<String> subfolders = new ArrayList<>();
            List<MisfiledCover> misfiledCovers = new ArrayList<>();

            List<Path> children = fs.listDirectory(folder);
            if (children.isEmpty()) {
                anomalies.add("empty_folder");
            }
            for (Path child : children) {
                String name = fileName(child);
                if (fs.isDirectory(child)) {
                    subfolders.add(name);
                    // Look for cover images misfiled in this subfolder
                    List<String> covers = new ArrayList<>();
                    for (Path inner : fs.listDirectory(child)) {
                        if (!fs.isDirectory(inner) && isCover(fileName(inner))) {
                            covers.add(fileName(inner));
                        }
                    }
                    if (!covers.isEmpty()) misfiledCovers.add(new MisfiledCover(name, covers));
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

            reports.add(new LocationReport(folder.toString(), anomalies,
                    baseCovers, baseVideos, subfolders, misfiledCovers));
        }

        return new Result(title.getId(), title.getCode(), volumeId, reports);
    }

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

    public record MisfiledCover(String subfolder, List<String> covers) {}
    public record LocationReport(String path, List<String> anomalies,
                                  List<String> baseCovers, List<String> baseVideos,
                                  List<String> subfolders, List<MisfiledCover> misfiledCovers) {}
    public record Result(long titleId, String titleCode, String volumeId, List<LocationReport> locations) {}
}
