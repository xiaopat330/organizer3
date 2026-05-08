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
import java.util.ArrayList;
import java.util.List;

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
            reports.add(TitleFolderAnomalyScanner.scan(loc.getPath(), fs));
        }

        return new Result(title.getId(), title.getCode(), volumeId, reports);
    }

    public record MisfiledCover(String subfolder, List<String> covers) {}
    public record LocationReport(String path, List<String> anomalies,
                                  List<String> baseCovers, List<String> baseVideos,
                                  List<String> subfolders, List<MisfiledCover> misfiledCovers) {}
    public record Result(long titleId, String titleCode, String volumeId, List<LocationReport> locations) {}
}
