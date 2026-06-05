package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.config.volume.LibraryConfig;
import com.organizer3.curation.CurationLog;
import com.organizer3.curation.CurationLogRecord;
import com.organizer3.filesystem.VolumeFileSystem;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;
import com.organizer3.model.Actress;
import com.organizer3.model.Title;
import com.organizer3.model.TitleLocation;
import com.organizer3.repository.ActressRepository;
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
 * Intra-volume re-parent: moves a title folder from its current location to a new parent
 * on the same mounted volume.
 *
 * <p>Destination can be specified as:
 * <ul>
 *   <li>{@code toActressId}: resolves to {@code /stars/<tier>/<canonicalName>/}</li>
 *   <li>{@code toAbsolutePath}: explicit volume-relative parent path (mutually exclusive)</li>
 * </ul>
 *
 * <p>Auto-creates the destination parent directory if it doesn't exist.
 * Updates the {@code title_locations} row after the move.
 * Refuses if the destination title folder already exists (collision).
 *
 * <p>Requires exactly one live location on the mounted volume.
 * All calls (ok/dry-run/failed) are appended to the curation log.
 * Default {@code dryRun: true}.
 */
@Slf4j
public class MoveTitleFolderTool implements Tool {

    private final SessionContext session;
    private final TitleRepository titleRepo;
    private final TitleLocationRepository locationRepo;
    private final ActressRepository actressRepo;
    private final LibraryConfig libraryConfig;
    private final CurationLog curationLog;

    public MoveTitleFolderTool(SessionContext session,
                                TitleRepository titleRepo,
                                TitleLocationRepository locationRepo,
                                ActressRepository actressRepo,
                                LibraryConfig libraryConfig,
                                CurationLog curationLog) {
        this.session      = session;
        this.titleRepo    = titleRepo;
        this.locationRepo = locationRepo;
        this.actressRepo  = actressRepo;
        this.libraryConfig = libraryConfig;
        this.curationLog  = curationLog;
    }

    @Override public String name() { return "move_title_folder"; }

    @Override
    public String description() {
        return "Moves a title folder to a new parent on the mounted volume. "
             + "Destination: toActressId resolves to /stars/<tier>/<canonicalName>/, "
             + "or toAbsolutePath for an explicit parent path (mutually exclusive). "
             + "Auto-creates destination parent. Updates title_locations in DB. "
             + "Default dryRun:true.";
    }

    @Override
    public JsonNode inputSchema() {
        return Schemas.object()
                .prop("titleCode",      "string",  "Product code of the title (e.g. 'MIDE-123'). Case-insensitive.")
                .prop("toActressId",    "integer", "Destination: actress id — resolves to /stars/<tier>/<name>/. Mutually exclusive with toAbsolutePath.")
                .prop("toAbsolutePath", "string",  "Destination: explicit volume-relative parent path. Mutually exclusive with toActressId.")
                .prop("fromPath",       "string",  "Optional disambiguator: volume-relative path of the specific location to move (e.g. '/queue/Foo (BAR-123)'). Required when the title has >1 location on the mounted volume; if provided when only one location exists it must match.")
                .prop("dryRun",         "boolean", "If true (default), return the plan without moving.", true)
                .require("titleCode")
                .build();
    }

    @Override
    public Object call(JsonNode args) {
        String titleCode    = Schemas.requireString(args, "titleCode").trim().toUpperCase();
        long toActressId    = Schemas.optLong(args, "toActressId", -1);
        String toAbsPath    = Schemas.optString(args, "toAbsolutePath", null);
        String fromPathRaw  = Schemas.optString(args, "fromPath", null);
        String fromPath     = (fromPathRaw == null || fromPathRaw.isBlank()) ? null : fromPathRaw.trim();
        boolean dryRun      = Schemas.optBoolean(args, "dryRun", true);

        // ── Mutual exclusion ────────────────────────────────────────────────
        if (toActressId >= 0 && toAbsPath != null && !toAbsPath.isBlank()) {
            return failed(null, Map.of("titleCode", titleCode, "dryRun", dryRun),
                    "toActressId and toAbsolutePath are mutually exclusive");
        }
        if (toActressId < 0 && (toAbsPath == null || toAbsPath.isBlank())) {
            return failed(null, Map.of("titleCode", titleCode, "dryRun", dryRun),
                    "Must provide either toActressId or toAbsolutePath");
        }

        String mountedVolumeId = session.getMountedVolumeId();
        VolumeConnection conn  = session.getActiveConnection();
        VolumeFileSystem fs    = (conn != null && conn.isConnected()) ? conn.fileSystem() : null;

        if (mountedVolumeId == null) {
            return failed(null, Map.of("titleCode", titleCode, "dryRun", dryRun),
                    "no volume mounted");
        }

        // ── Resolve title ───────────────────────────────────────────────────
        Optional<Title> titleOpt = titleRepo.findByCode(titleCode);
        if (titleOpt.isEmpty()) {
            return failed(mountedVolumeId, Map.of("titleCode", titleCode, "dryRun", dryRun),
                    "no title found with code '" + titleCode + "'");
        }
        Title title = titleOpt.get();

        // ── Find location on mounted volume ─────────────────────────────────
        List<TitleLocation> locations = locationRepo.findByTitle(title.getId())
                .stream()
                .filter(l -> mountedVolumeId.equals(l.getVolumeId()))
                .toList();

        if (locations.isEmpty()) {
            return failed(mountedVolumeId, Map.of("titleCode", titleCode, "dryRun", dryRun),
                    "title '" + titleCode + "' has no live location on volume '" + mountedVolumeId + "'");
        }

        TitleLocation location;
        if (fromPath != null) {
            List<TitleLocation> matches = locations.stream()
                    .filter(l -> l.getPath().toString().equals(fromPath))
                    .toList();
            if (matches.isEmpty()) {
                String available = locations.stream()
                        .map(l -> l.getPath().toString())
                        .reduce((a, b) -> a + ", " + b).orElse("");
                return failed(mountedVolumeId, buildInputs(titleCode, toActressId, toAbsPath, fromPath, dryRun),
                        "fromPath '" + fromPath + "' does not match any location of title '"
                        + titleCode + "' on volume '" + mountedVolumeId + "' (available: " + available + ")");
            }
            location = matches.get(0);
        } else if (locations.size() > 1) {
            String available = locations.stream()
                    .map(l -> l.getPath().toString())
                    .reduce((a, b) -> a + ", " + b).orElse("");
            return failed(mountedVolumeId, buildInputs(titleCode, toActressId, toAbsPath, fromPath, dryRun),
                    "title '" + titleCode + "' has " + locations.size()
                    + " locations on volume '" + mountedVolumeId + "' — ambiguous (pass fromPath to disambiguate; candidates: "
                    + available + ")");
        } else {
            location = locations.get(0);
        }
        Path currentPath = location.getPath();
        String folderBasename = currentPath.getFileName().toString();

        // ── Resolve destination parent ──────────────────────────────────────
        Path destParent;
        String destDescription;
        if (toActressId >= 0) {
            Actress actress = actressRepo.findById(toActressId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "No actress found for id=" + toActressId));
            int titleCount = titleRepo.countByActress(actress.getId());
            String tier    = libraryConfig.tierFor(titleCount);
            destParent     = Path.of("/stars", tier, actress.getCanonicalName());
            destDescription = "actress=" + actress.getCanonicalName() + " tier=" + tier;
        } else {
            destParent      = Path.of(toAbsPath);
            destDescription = "explicit=" + toAbsPath;
        }

        Path targetPath = destParent.resolve(folderBasename);

        Map<String, Object> inputs = buildInputs(titleCode, toActressId, toAbsPath, fromPath, dryRun);
        Map<String, Object> plan   = Map.of(
                "from", currentPath.toString(),
                "to",   targetPath.toString(),
                "destParent", destParent.toString()
        );

        // ── Same path no-op ─────────────────────────────────────────────────
        if (currentPath.equals(targetPath)) {
            CurationLogRecord rec = new CurationLogRecord(
                    Instant.now(), name(), "mcp", sessionId(), inputs, plan, null,
                    Map.of("from", currentPath.toString(), "to", targetPath.toString()),
                    "ok", List.of());
            curationLog.append(mountedVolumeId, rec);
            return new Result(currentPath.toString(), targetPath.toString(), "ok", null);
        }

        // ── Dry-run ─────────────────────────────────────────────────────────
        if (dryRun || fs == null) {
            String status = (fs == null && !dryRun) ? "no-volume-mounted" : "dry-run";
            CurationLogRecord rec = new CurationLogRecord(
                    Instant.now(), name(), "mcp", sessionId(), inputs, plan, null, null, status, List.of());
            curationLog.append(mountedVolumeId, rec);
            return new Result(currentPath.toString(), targetPath.toString(), status, null);
        }

        // ── Collision check ─────────────────────────────────────────────────
        if (fs.exists(targetPath)) {
            return failed(mountedVolumeId, inputs,
                    "destination already exists: " + targetPath);
        }

        // ── Execute ─────────────────────────────────────────────────────────
        try {
            fs.createDirectories(destParent);
            fs.move(currentPath, targetPath);

            String newPartitionId = derivePartitionId(destParent);
            locationRepo.updatePathPartitionAndVideos(
                    location.getId(), title.getId(), mountedVolumeId,
                    currentPath.toString(), targetPath.toString(), newPartitionId);

            log.info("move_title_folder volume={} from={} to={} dest={}",
                    mountedVolumeId, currentPath, targetPath, destDescription);
            CurationLogRecord rec = new CurationLogRecord(
                    Instant.now(), name(), "mcp", sessionId(), inputs, plan,
                    Map.of("path", currentPath.toString()),
                    Map.of("path", targetPath.toString()),
                    "ok", List.of());
            curationLog.append(mountedVolumeId, rec);
            return new Result(currentPath.toString(), targetPath.toString(), "ok", null);

        } catch (IOException e) {
            log.warn("move_title_folder failed volume={} from={} to={}: {}",
                    mountedVolumeId, currentPath, targetPath, e.getMessage());
            CurationLogRecord rec = new CurationLogRecord(
                    Instant.now(), name(), "mcp", sessionId(), inputs, plan, null, null,
                    "failed", List.of(e.getMessage()));
            curationLog.append(mountedVolumeId, rec);
            return new Result(currentPath.toString(), targetPath.toString(), "failed", e.getMessage());
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /**
     * Derives a partition_id from the destination parent path.
     * For {@code /stars/<tier>/...} paths, the partition is the tier name.
     * For other paths (queue, attention, etc.), use the top-level folder name.
     */
    private String derivePartitionId(Path destParent) {
        if (destParent.getNameCount() >= 2) {
            String top = destParent.getName(0).toString();
            if ("stars".equals(top)) {
                return destParent.getName(1).toString(); // tier
            }
        }
        return destParent.getName(0).toString();
    }

    private Map<String, Object> buildInputs(String titleCode, long toActressId,
                                             String toAbsPath, String fromPath, boolean dryRun) {
        var m = new java.util.LinkedHashMap<String, Object>();
        m.put("titleCode", titleCode);
        if (toActressId >= 0) m.put("toActressId", toActressId);
        if (toAbsPath != null) m.put("toAbsolutePath", toAbsPath);
        if (fromPath != null) m.put("fromPath", fromPath);
        m.put("dryRun", dryRun);
        return java.util.Collections.unmodifiableMap(m);
    }

    private Result failed(String volumeId, Map<String, Object> inputs, String reason) {
        log.info("move_title_folder failed/refused volume={} reason={}", volumeId, reason);
        String logVolume = volumeId != null ? volumeId : "unknown";
        CurationLogRecord rec = new CurationLogRecord(
                Instant.now(), name(), "mcp", sessionId(), inputs, null, null, null,
                "failed", List.of(reason));
        curationLog.append(logVolume, rec);
        return new Result(null, null, "failed", reason);
    }

    private String sessionId() { return "mcp-" + Thread.currentThread().getName(); }

    public record Result(String from, String to, String status, String error) {}
}
