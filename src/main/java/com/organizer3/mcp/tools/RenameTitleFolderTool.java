package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.curation.CurationLog;
import com.organizer3.curation.CurationLogRecord;
import com.organizer3.filesystem.VolumeFileSystem;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;
import com.organizer3.model.Title;
import com.organizer3.model.TitleLocation;
import com.organizer3.repository.TitleLocationRepository;
import com.organizer3.repository.TitleRepository;
import com.organizer3.shell.SessionContext;
import com.organizer3.smb.VolumeConnection;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Force-renames a single title folder on the mounted volume to a caller-supplied basename.
 *
 * <p>This is independent of actress canonicalization rules — the caller provides the exact
 * new folder name. The existing parent directory is preserved; only the leaf folder name changes.
 *
 * <p>Requires exactly one live {@code title_locations} row on the currently mounted volume.
 * Refuses if the title has multiple locations on the current volume (ambiguous), if the
 * current path no longer exists, or if the new name would collide with an existing path.
 *
 * <p>On success, updates the {@code title_locations.path} record in the DB.
 * All calls (ok/dry-run/failed) are appended to the curation log.
 * Default {@code dryRun: true}.
 */
@Slf4j
public class RenameTitleFolderTool implements Tool {

    private final SessionContext session;
    private final TitleRepository titleRepo;
    private final TitleLocationRepository locationRepo;
    private final CurationLog curationLog;

    public RenameTitleFolderTool(SessionContext session,
                                  TitleRepository titleRepo,
                                  TitleLocationRepository locationRepo,
                                  CurationLog curationLog) {
        this.session      = session;
        this.titleRepo    = titleRepo;
        this.locationRepo = locationRepo;
        this.curationLog  = curationLog;
    }

    @Override public String name() { return "rename_title_folder"; }

    @Override
    public String description() {
        return "Renames a title folder on the mounted volume to a new basename. "
             + "The parent directory is preserved; only the leaf name changes. "
             + "Updates title_locations in the DB. Default dryRun:true.";
    }

    @Override
    public JsonNode inputSchema() {
        return Schemas.object()
                .prop("titleCode",     "string",  "Product code, e.g. 'MIDE-123'. Case-insensitive.")
                .prop("newFolderName", "string",  "New folder basename (no slashes).")
                .prop("fromPath",      "string",  "Optional disambiguator: volume-relative path of the specific location to rename (e.g. '/queue/Foo (BAR-123)'). Required when the title has >1 location on the mounted volume; if provided when only one location exists it must match.")
                .prop("dryRun",        "boolean", "If true (default), return the plan without renaming.", true)
                .require("titleCode", "newFolderName")
                .build();
    }

    @Override
    public Object call(JsonNode args) {
        String titleCode    = Schemas.requireString(args, "titleCode").trim().toUpperCase();
        String newFolderNameRaw = Schemas.optString(args, "newFolderName", "").trim();
        String fromPathRaw  = Schemas.optString(args, "fromPath", null);
        String fromPath     = (fromPathRaw == null || fromPathRaw.isBlank()) ? null : fromPathRaw.trim();
        boolean dryRun      = Schemas.optBoolean(args, "dryRun", true);
        // defer blank validation so we can return a Result instead of throwing
        String newFolderName = newFolderNameRaw;

        java.util.LinkedHashMap<String, Object> inputs = new java.util.LinkedHashMap<>();
        inputs.put("titleCode",     titleCode);
        inputs.put("newFolderName", newFolderName);
        if (fromPath != null) inputs.put("fromPath", fromPath);
        inputs.put("dryRun",        dryRun);

        // ── Validate newFolderName ──────────────────────────────────────────
        if (newFolderName.contains("/") || newFolderName.contains("\\")) {
            return failed(null, inputs, dryRun, "newFolderName must be a basename with no path separators");
        }
        if (newFolderName.isBlank()) {
            return failed(null, inputs, dryRun, "newFolderName must not be blank");
        }

        String mountedVolumeId = session.getMountedVolumeId();
        VolumeConnection conn  = session.getActiveConnection();
        VolumeFileSystem fs    = (conn != null && conn.isConnected()) ? conn.fileSystem() : null;

        if (mountedVolumeId == null) {
            return failed(null, inputs, dryRun, "no volume mounted");
        }

        // ── Resolve title ───────────────────────────────────────────────────
        Optional<Title> titleOpt = titleRepo.findByCode(titleCode);
        if (titleOpt.isEmpty()) {
            return failed(mountedVolumeId, inputs, dryRun,
                    "no title found with code '" + titleCode + "'");
        }
        Title title = titleOpt.get();

        // ── Find the location on the mounted volume ─────────────────────────
        List<TitleLocation> locations = locationRepo.findByTitle(title.getId())
                .stream()
                .filter(l -> mountedVolumeId.equals(l.getVolumeId()))
                .toList();

        if (locations.isEmpty()) {
            return failed(mountedVolumeId, inputs, dryRun,
                    "title '" + titleCode + "' has no live location on volume '" + mountedVolumeId + "'");
        }

        TitleLocation location;
        if (fromPath != null) {
            // Caller specified the exact location — must match one row regardless of count.
            List<TitleLocation> matches = locations.stream()
                    .filter(l -> l.getPath().toString().equals(fromPath))
                    .toList();
            if (matches.isEmpty()) {
                String available = locations.stream()
                        .map(l -> l.getPath().toString())
                        .reduce((a, b) -> a + ", " + b).orElse("");
                return failed(mountedVolumeId, inputs, dryRun,
                        "fromPath '" + fromPath + "' does not match any location of title '"
                        + titleCode + "' on volume '" + mountedVolumeId + "' (available: " + available + ")");
            }
            location = matches.get(0);
        } else if (locations.size() > 1) {
            return failed(mountedVolumeId, inputs, dryRun,
                    "title '" + titleCode + "' has " + locations.size()
                    + " locations on volume '" + mountedVolumeId + "' — ambiguous (pass fromPath to disambiguate)");
        } else {
            location = locations.get(0);
        }
        Path currentPath = location.getPath();
        Path parent      = currentPath.getParent();
        Path newPath     = (parent != null) ? parent.resolve(newFolderName) : Path.of(newFolderName);

        Map<String, Object> plan = Map.of(
                "from", currentPath.toString(),
                "to",   newPath.toString()
        );

        // ── Return plan on dry run or no FS ────────────────────────────────
        if (dryRun || fs == null) {
            String status = (fs == null && !dryRun) ? "no-volume-mounted" : "dry-run";
            CurationLogRecord rec = new CurationLogRecord(
                    Instant.now(), name(), "mcp", sessionId(), inputs, plan, null, null,
                    status, List.of());
            curationLog.append(mountedVolumeId, rec);
            return new Result(currentPath.toString(), newPath.toString(), status, null);
        }

        // ── Check for same-name no-op ───────────────────────────────────────
        if (currentPath.getFileName().toString().equals(newFolderName)) {
            CurationLogRecord rec = new CurationLogRecord(
                    Instant.now(), name(), "mcp", sessionId(), inputs, plan, null,
                    Map.of("from", currentPath.toString(), "to", newPath.toString()),
                    "ok", List.of());
            curationLog.append(mountedVolumeId, rec);
            return new Result(currentPath.toString(), newPath.toString(), "ok", null);
        }

        // ── Collision guard ─────────────────────────────────────────────────
        if (fs.exists(newPath)) {
            return failed(mountedVolumeId, inputs, dryRun,
                    "destination already exists: " + newPath);
        }

        // ── Execute ─────────────────────────────────────────────────────────
        try {
            fs.rename(currentPath, newFolderName);
            locationRepo.updatePathAndPartition(location.getId(), newPath, location.getPartitionId());

            log.info("rename_title_folder volume={} from={} to={}", mountedVolumeId, currentPath, newPath);
            CurationLogRecord rec = new CurationLogRecord(
                    Instant.now(), name(), "mcp", sessionId(), inputs, plan,
                    Map.of("path", currentPath.toString()),
                    Map.of("path", newPath.toString()),
                    "ok", List.of());
            curationLog.append(mountedVolumeId, rec);
            return new Result(currentPath.toString(), newPath.toString(), "ok", null);

        } catch (IOException e) {
            log.warn("rename_title_folder failed volume={} from={}: {}", mountedVolumeId, currentPath, e.getMessage());
            CurationLogRecord rec = new CurationLogRecord(
                    Instant.now(), name(), "mcp", sessionId(), inputs, plan, null, null,
                    "failed", List.of(e.getMessage()));
            curationLog.append(mountedVolumeId, rec);
            return new Result(currentPath.toString(), newPath.toString(), "failed", e.getMessage());
        }
    }

    private Result failed(String volumeId, Map<String, Object> inputs, boolean dryRun, String reason) {
        log.info("rename_title_folder refused/failed volume={} reason={}", volumeId, reason);
        String logVolume = volumeId != null ? volumeId : "unknown";
        CurationLogRecord rec = new CurationLogRecord(
                Instant.now(), name(), "mcp", sessionId(), inputs, null, null, null,
                "failed", List.of(reason));
        curationLog.append(logVolume, rec);
        return new Result(
                inputs.getOrDefault("titleCode", "").toString(),
                null, "failed", reason);
    }

    private String sessionId() { return "mcp-" + Thread.currentThread().getName(); }

    public record Result(String from, String to, String status, String error) {}
}
