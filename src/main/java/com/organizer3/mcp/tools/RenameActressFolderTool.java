package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.curation.CurationLog;
import com.organizer3.curation.CurationLogRecord;
import com.organizer3.filesystem.VolumeFileSystem;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;
import com.organizer3.model.Actress;
import com.organizer3.repository.ActressRepository;
import com.organizer3.shell.SessionContext;
import com.organizer3.smb.VolumeConnection;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.Jdbi;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Renames the actress-level parent folder in-place on the mounted volume, eliminating the
 * {@code /_attention/} detour required by older tools.
 *
 * <p>Finds all title_locations for the actress on the mounted volume, groups them by their
 * parent folder (the actress folder), then renames any parent whose basename is not the
 * canonical name — either because it matches a known alias, or because it matches the optional
 * {@code fromName} override. Updates all affected {@code title_locations.path} rows.
 *
 * <p>Refuses if the actress folder is not found, if a destination folder already exists (collision),
 * or if the actress folder contains no DB-tracked titles on this volume.
 *
 * <p>All calls (ok/dry-run/failed) are appended to the curation log.
 * Default {@code dryRun: true}.
 */
@Slf4j
public class RenameActressFolderTool implements Tool {

    private final SessionContext session;
    private final ActressRepository actressRepo;
    private final Jdbi jdbi;
    private final CurationLog curationLog;

    public RenameActressFolderTool(SessionContext session,
                                    ActressRepository actressRepo,
                                    Jdbi jdbi,
                                    CurationLog curationLog) {
        this.session     = session;
        this.actressRepo = actressRepo;
        this.jdbi        = jdbi;
        this.curationLog = curationLog;
    }

    @Override public String name() { return "rename_actress_folder"; }

    @Override
    public String description() {
        return "Renames the actress parent folder in-place on the mounted volume "
             + "(e.g. /stars/minor/OldName/ → /stars/minor/CanonicalName/). "
             + "Eliminates the /_attention/ detour. Updates title_locations in DB. "
             + "Default dryRun:true.";
    }

    @Override
    public JsonNode inputSchema() {
        return Schemas.object()
                .prop("actress_id", "integer", "Actress id. Either this or 'name' is required.")
                .prop("name",       "string",  "Canonical name or alias to resolve. Either this or 'actress_id' is required.")
                .prop("fromName",   "string",  "Optional override: match folders with this basename even if not a registered alias.")
                .prop("dryRun",     "boolean", "If true (default), return the plan without renaming.", true)
                .build();
    }

    @Override
    public Object call(JsonNode args) {
        long idArg      = Schemas.optLong(args, "actress_id", -1);
        String nameArg  = Schemas.optString(args, "name", null);
        String fromName = Schemas.optString(args, "fromName", null);
        boolean dryRun  = Schemas.optBoolean(args, "dryRun", true);

        if (idArg < 0 && (nameArg == null || nameArg.isBlank())) {
            throw new IllegalArgumentException("Must provide either 'actress_id' or 'name'");
        }

        Actress actress = (idArg >= 0
                ? actressRepo.findById(idArg)
                : actressRepo.resolveByName(nameArg))
                .orElseThrow(() -> new IllegalArgumentException(
                        "No actress found for " + (idArg >= 0 ? "id=" + idArg : "name=" + nameArg)));

        String mountedVolumeId = session.getMountedVolumeId();
        VolumeConnection conn  = session.getActiveConnection();
        VolumeFileSystem fs    = (conn != null && conn.isConnected()) ? conn.fileSystem() : null;

        Map<String, Object> inputs = Map.of(
                "actressId", actress.getId(),
                "canonical", actress.getCanonicalName(),
                "fromName",  fromName != null ? fromName : "",
                "dryRun",    dryRun
        );

        if (mountedVolumeId == null) {
            return Result.error(actress.getId(), actress.getCanonicalName(), mountedVolumeId,
                    "no volume mounted", inputs, curationLog, name(), sessionId());
        }

        // ── Find actress folders to rename ──────────────────────────────────
        List<FolderToRename> toRename = findActressFoldersToRename(
                actress, mountedVolumeId, fromName);

        if (toRename.isEmpty()) {
            String status = "nothing-to-do";
            CurationLogRecord rec = new CurationLogRecord(
                    Instant.now(), name(), "mcp", sessionId(), inputs,
                    Map.of("foldersToRename", 0), null, null, status, List.of());
            curationLog.append(mountedVolumeId, rec);
            return new Result(actress.getId(), actress.getCanonicalName(), dryRun,
                    mountedVolumeId, List.of(), List.of(), List.of(), status);
        }

        // ── Check for collisions (before executing) ─────────────────────────
        if (fs != null) {
            for (FolderToRename r : toRename) {
                if (fs.exists(r.newActressFolder())) {
                    return Result.error(actress.getId(), actress.getCanonicalName(), mountedVolumeId,
                            "destination already exists: " + r.newActressFolder(), inputs, curationLog, name(), sessionId());
                }
            }
        }

        // ── Build plan summary ──────────────────────────────────────────────
        List<Map<String, Object>> planList = toRename.stream()
                .map(r -> Map.<String, Object>of(
                        "from", r.actressFolder().toString(),
                        "to",   r.newActressFolder().toString(),
                        "titlesInFolder", r.locations().size()))
                .toList();

        // ── Dry-run ─────────────────────────────────────────────────────────
        if (dryRun || fs == null) {
            String status = (fs == null && !dryRun) ? "no-volume-mounted" : "dry-run";
            CurationLogRecord rec = new CurationLogRecord(
                    Instant.now(), name(), "mcp", sessionId(), inputs,
                    Map.of("folders", planList), null, null, status, List.of());
            curationLog.append(mountedVolumeId, rec);

            List<String> updatedPaths = toRename.stream()
                    .flatMap(r -> r.locations().stream().map(l -> l.newPath().toString()))
                    .toList();
            return new Result(actress.getId(), actress.getCanonicalName(), true,
                    mountedVolumeId,
                    toRename.stream().map(r -> r.actressFolder().toString()).toList(),
                    toRename.stream().map(r -> r.newActressFolder().toString()).toList(),
                    updatedPaths, status);
        }

        // ── Execute ─────────────────────────────────────────────────────────
        List<String> renamedFrom = new ArrayList<>();
        List<String> renamedTo   = new ArrayList<>();
        List<String> updatedPaths = new ArrayList<>();
        List<Object> errors       = new ArrayList<>();

        for (FolderToRename r : toRename) {
            try {
                fs.rename(r.actressFolder(), actress.getCanonicalName());
                // Update all title_location paths
                jdbi.useTransaction(h -> {
                    for (LocationRow loc : r.locations()) {
                        h.createUpdate("""
                                UPDATE title_locations
                                SET path = :newPath
                                WHERE id = :id
                                """)
                                .bind("newPath", loc.newPath().toString())
                                .bind("id", loc.locationId())
                                .execute();
                    }
                });
                renamedFrom.add(r.actressFolder().toString());
                renamedTo.add(r.newActressFolder().toString());
                for (LocationRow loc : r.locations()) {
                    updatedPaths.add(loc.newPath().toString());
                }
                log.info("rename_actress_folder volume={} from={} to={} paths={}",
                        mountedVolumeId, r.actressFolder(), r.newActressFolder(), r.locations().size());
            } catch (IOException e) {
                log.warn("rename_actress_folder failed volume={} from={}: {}",
                        mountedVolumeId, r.actressFolder(), e.getMessage());
                errors.add(r.actressFolder() + ": " + e.getMessage());
            }
        }

        String status = errors.isEmpty() ? "ok" : (renamedFrom.isEmpty() ? "failed" : "partial");
        CurationLogRecord rec = new CurationLogRecord(
                Instant.now(), name(), "mcp", sessionId(), inputs,
                Map.of("folders", planList),
                Map.of("from", renamedFrom),
                Map.of("to", renamedTo, "updatedPaths", updatedPaths),
                status, errors);
        curationLog.append(mountedVolumeId, rec);

        return new Result(actress.getId(), actress.getCanonicalName(), false,
                mountedVolumeId, renamedFrom, renamedTo, updatedPaths, status);
    }

    // ── internal model ──────────────────────────────────────────────────────

    record LocationRow(long locationId, String partitionId, Path currentPath, Path newPath) {}

    record FolderToRename(
            Path actressFolder,      // current: e.g. /stars/minor/Old Name
            Path newActressFolder,   // target:  e.g. /stars/minor/Canonical Name
            List<LocationRow> locations
    ) {}

    private List<FolderToRename> findActressFoldersToRename(
            Actress actress, String volumeId, String fromNameOverride) {

        String canonical = actress.getCanonicalName();

        // Collect match names: registered aliases + optional fromName override
        List<String> matchNames = new ArrayList<>();
        for (var alias : actressRepo.findAliases(actress.getId())) {
            matchNames.add(alias.aliasName().toLowerCase());
        }
        if (fromNameOverride != null && !fromNameOverride.isBlank()) {
            matchNames.add(fromNameOverride.toLowerCase());
        }

        record Row(long locId, String partId, String path) {}
        List<Row> rows = jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT tl.id, tl.partition_id, tl.path
                        FROM titles t
                        JOIN title_locations tl ON tl.title_id = t.id
                        WHERE t.actress_id = :actressId AND tl.volume_id = :volumeId
                          AND tl.stale_since IS NULL
                        """)
                        .bind("actressId", actress.getId())
                        .bind("volumeId", volumeId)
                        .map((rs, ctx) -> new Row(rs.getLong("id"), rs.getString("partition_id"), rs.getString("path")))
                        .list());

        // Group by actress-folder (parent of title folder), keep only non-canonical names
        LinkedHashMap<Path, List<Row>> byActressFolder = new LinkedHashMap<>();
        for (Row row : rows) {
            Path titleFolder   = Path.of(row.path());
            Path actressFolder = titleFolder.getParent();
            if (actressFolder == null) continue;

            String folderName = actressFolder.getFileName().toString();
            // Skip if already canonical
            if (folderName.equals(canonical)) continue;
            // Only process if name matches an alias or the fromName override
            if (!matchNames.isEmpty() && !matchNames.contains(folderName.toLowerCase())) continue;
            // If no match names at all (actress has no aliases, no fromName), skip
            if (matchNames.isEmpty()) continue;

            byActressFolder.computeIfAbsent(actressFolder, k -> new ArrayList<>()).add(row);
        }

        List<FolderToRename> result = new ArrayList<>();
        for (var entry : byActressFolder.entrySet()) {
            Path actressFolder    = entry.getKey();
            Path parentOfActress  = actressFolder.getParent();
            Path newActressFolder = (parentOfActress != null)
                    ? parentOfActress.resolve(canonical)
                    : Path.of(canonical);

            List<LocationRow> locs = entry.getValue().stream().map(row -> {
                Path currentPath = Path.of(row.path());
                // Replace the actress folder segment with canonical name
                Path relToActress = actressFolder.relativize(currentPath);
                Path newPath = newActressFolder.resolve(relToActress);
                return new LocationRow(row.locId(), row.partId(), currentPath, newPath);
            }).toList();

            result.add(new FolderToRename(actressFolder, newActressFolder, locs));
        }
        return result;
    }

    private String sessionId() { return "mcp-" + Thread.currentThread().getName(); }

    public record Result(
            long actressId,
            String canonicalName,
            boolean dryRun,
            String mountedVolumeId,
            List<String> from,
            List<String> to,
            List<String> updatedPaths,
            String status
    ) {
        static Result error(long actressId, String canonical, String volumeId,
                            String reason, Map<String, Object> inputs,
                            CurationLog curationLog, String toolName, String sessionId) {
            CurationLogRecord rec = new CurationLogRecord(
                    Instant.now(), toolName, "mcp", sessionId, inputs, null, null, null,
                    "failed", List.of(reason));
            curationLog.append(volumeId != null ? volumeId : "unknown", rec);
            return new Result(actressId, canonical, false, volumeId,
                    List.of(), List.of(), List.of(), "failed");
        }
    }
}
