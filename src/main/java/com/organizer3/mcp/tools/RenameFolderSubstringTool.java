package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.curation.CurationLog;
import com.organizer3.curation.CurationLogRecord;
import com.organizer3.filesystem.VolumeFileSystem;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;
import com.organizer3.model.TitleLocation;
import com.organizer3.repository.TitleLocationRepository;
import com.organizer3.shell.SessionContext;
import com.organizer3.smb.VolumeConnection;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Bulk-renames title folder basenames on the mounted volume by performing a
 * whole-word substring replacement of {@code oldText} → {@code newText} across
 * matching {@code title_locations} rows.
 *
 * <p>Whole-word match means: chars adjacent to the substring match in the basename
 * must be in the boundary set {@code {',', ' ', '(', ')'}}, or be start/end of the basename.
 * This prevents accidental token-internal corruption (e.g. "Sai" inside "Saigon").
 *
 * <p>Default {@code dryRun: true}. Each candidate is logged to the curation log.
 *
 * <p>If a title has multiple locations on the volume and both basenames match,
 * both rows are independently renamed.
 */
@Slf4j
public class RenameFolderSubstringTool implements Tool {

    /** Word-boundary chars surrounding the substring match. */
    private static final Set<Character> BOUNDARY = Set.of(',', ' ', '(', ')');

    private final SessionContext session;
    private final TitleLocationRepository locationRepo;
    private final CurationLog curationLog;

    public RenameFolderSubstringTool(SessionContext session,
                                     TitleLocationRepository locationRepo,
                                     CurationLog curationLog) {
        this.session      = session;
        this.locationRepo = locationRepo;
        this.curationLog  = curationLog;
    }

    @Override public String name() { return "rename_folder_substring"; }

    @Override
    public String description() {
        return "Bulk substring-rename of title folder basenames on the mounted volume "
             + "(whole-word match). Useful for post-merge typo fix-ups across many ensemble folders. "
             + "Default dryRun:true.";
    }

    @Override
    public JsonNode inputSchema() {
        return Schemas.object()
                .prop("volumeId",   "string",  "Volume id; must match the currently mounted volume.")
                .prop("oldText",    "string",  "Exact substring to find (whole-word in the folder basename).")
                .prop("newText",    "string",  "Replacement substring.")
                .prop("pathPrefix", "string",  "Optional volume-relative prefix to scope the search (e.g. '/archive').")
                .prop("dryRun",     "boolean", "If true (default), preview without renaming.", true)
                .require("volumeId", "oldText", "newText")
                .build();
    }

    @Override
    public Object call(JsonNode args) {
        String volumeId   = Schemas.requireString(args, "volumeId").trim();
        String oldText    = Schemas.requireString(args, "oldText");
        String newText    = Schemas.optString(args, "newText", "");
        String pathPrefix = Schemas.optString(args, "pathPrefix", null);
        if (pathPrefix != null) {
            pathPrefix = pathPrefix.trim();
            if (pathPrefix.isEmpty()) pathPrefix = null;
        }
        boolean dryRun = Schemas.optBoolean(args, "dryRun", true);

        LinkedHashMap<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("volumeId",   volumeId);
        inputs.put("oldText",    oldText);
        inputs.put("newText",    newText);
        if (pathPrefix != null) inputs.put("pathPrefix", pathPrefix);
        inputs.put("dryRun",     dryRun);

        // ── Volume guards ──────────────────────────────────────────────────
        String mountedVolumeId = session.getMountedVolumeId();
        VolumeConnection conn  = session.getActiveConnection();
        VolumeFileSystem fs    = (conn != null && conn.isConnected()) ? conn.fileSystem() : null;

        if (mountedVolumeId == null) {
            return errorResult(null, inputs, "no volume mounted");
        }
        if (!mountedVolumeId.equals(volumeId)) {
            return errorResult(mountedVolumeId, inputs,
                    "volumeId '" + volumeId + "' does not match mounted volume '" + mountedVolumeId + "'");
        }
        if (oldText.isBlank()) {
            return errorResult(mountedVolumeId, inputs, "oldText must not be blank");
        }
        if (newText.contains("/") || newText.contains("\\")) {
            return errorResult(mountedVolumeId, inputs, "newText must not contain path separators");
        }

        // ── Scan locations ─────────────────────────────────────────────────
        List<TitleLocation> all = locationRepo.findByVolume(volumeId);
        final String prefix = pathPrefix;

        List<Operation> ops = new ArrayList<>();
        int skippedNonWordBoundary = 0;
        int skippedAmbiguous = 0;
        List<String> errors = new ArrayList<>();

        for (TitleLocation loc : all) {
            Path p = loc.getPath();
            String full = p.toString();
            if (prefix != null && !full.startsWith(prefix)) continue;

            Path parent = p.getParent();
            String basename = p.getFileName() == null ? "" : p.getFileName().toString();
            if (basename.isEmpty() || !basename.contains(oldText)) continue;

            BoundaryResult br = replaceWholeWord(basename, oldText, newText);
            if (br.matchCount == 0) {
                // contains() said yes, but no whole-word match — skip with warning
                skippedNonWordBoundary++;
                continue;
            }

            String newBasename = br.replaced;
            Path newPath = (parent != null) ? parent.resolve(newBasename) : Path.of(newBasename);
            ops.add(new Operation(loc, full, newPath.toString(), newBasename, br.matchCount));
        }

        // ── Dry run: just emit summary ─────────────────────────────────────
        if (dryRun || fs == null) {
            String status = (fs == null && !dryRun) ? "no-volume-mounted" : "dry-run";
            for (Operation op : ops) {
                Map<String, Object> plan = Map.of("from", op.fromPath, "to", op.toPath);
                CurationLogRecord rec = new CurationLogRecord(
                        Instant.now(), name(), "mcp", sessionId(), inputs, plan, null, null,
                        status, List.of());
                curationLog.append(mountedVolumeId, rec);
            }
            return new Result(ops.size(), 0, skippedNonWordBoundary, skippedAmbiguous, errors, summarize(ops, status));
        }

        // ── Execute ────────────────────────────────────────────────────────
        int renamed = 0;
        List<Map<String, Object>> operations = new ArrayList<>();
        for (Operation op : ops) {
            Map<String, Object> plan = Map.of("from", op.fromPath, "to", op.toPath);
            Path fromPath = op.location.getPath();
            Path toPath   = Path.of(op.toPath);

            // No-op (shouldn't happen if oldText != newText for a matched basename)
            if (fromPath.toString().equals(op.toPath)) {
                CurationLogRecord rec = new CurationLogRecord(
                        Instant.now(), name(), "mcp", sessionId(), inputs, plan,
                        Map.of("path", op.fromPath), Map.of("path", op.toPath),
                        "ok", List.of());
                curationLog.append(mountedVolumeId, rec);
                operations.add(Map.of("from", op.fromPath, "to", op.toPath, "status", "ok"));
                renamed++;
                continue;
            }

            if (fs.exists(toPath)) {
                String msg = "destination already exists: " + op.toPath;
                errors.add(op.fromPath + " -> " + msg);
                CurationLogRecord rec = new CurationLogRecord(
                        Instant.now(), name(), "mcp", sessionId(), inputs, plan, null, null,
                        "failed", List.of(msg));
                curationLog.append(mountedVolumeId, rec);
                operations.add(Map.of("from", op.fromPath, "to", op.toPath, "status", "failed", "error", msg));
                continue;
            }

            try {
                fs.rename(fromPath, op.newBasename);
                locationRepo.updatePathAndPartition(op.location.getId(), toPath, op.location.getPartitionId());
                renamed++;
                log.info("rename_folder_substring volume={} from={} to={}", mountedVolumeId, op.fromPath, op.toPath);
                CurationLogRecord rec = new CurationLogRecord(
                        Instant.now(), name(), "mcp", sessionId(), inputs, plan,
                        Map.of("path", op.fromPath), Map.of("path", op.toPath),
                        "ok", List.of());
                curationLog.append(mountedVolumeId, rec);
                operations.add(Map.of("from", op.fromPath, "to", op.toPath, "status", "ok"));
            } catch (IOException e) {
                String msg = e.getMessage();
                errors.add(op.fromPath + " -> " + msg);
                log.warn("rename_folder_substring failed volume={} from={}: {}", mountedVolumeId, op.fromPath, msg);
                CurationLogRecord rec = new CurationLogRecord(
                        Instant.now(), name(), "mcp", sessionId(), inputs, plan, null, null,
                        "failed", List.of(msg));
                curationLog.append(mountedVolumeId, rec);
                operations.add(Map.of("from", op.fromPath, "to", op.toPath, "status", "failed", "error", msg));
            }
        }

        return new Result(ops.size(), renamed, skippedNonWordBoundary, skippedAmbiguous, errors, operations);
    }

    private List<Map<String, Object>> summarize(List<Operation> ops, String status) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Operation op : ops) {
            out.add(Map.of("from", op.fromPath, "to", op.toPath, "status", status));
        }
        return out;
    }

    private Result errorResult(String volumeId, Map<String, Object> inputs, String reason) {
        log.info("rename_folder_substring refused volume={} reason={}", volumeId, reason);
        String logVolume = volumeId != null ? volumeId : "unknown";
        CurationLogRecord rec = new CurationLogRecord(
                Instant.now(), name(), "mcp", sessionId(), inputs, null, null, null,
                "failed", List.of(reason));
        curationLog.append(logVolume, rec);
        return new Result(0, 0, 0, 0, List.of(reason), List.of());
    }

    /**
     * Replace every whole-word occurrence of {@code needle} in {@code haystack} with {@code replacement}.
     * Boundary chars are {@link #BOUNDARY}, plus start/end of string.
     */
    static BoundaryResult replaceWholeWord(String haystack, String needle, String replacement) {
        if (needle.isEmpty()) return new BoundaryResult(haystack, 0);
        StringBuilder out = new StringBuilder();
        int i = 0;
        int matches = 0;
        int n = needle.length();
        while (i <= haystack.length() - n) {
            int idx = haystack.indexOf(needle, i);
            if (idx < 0) break;
            boolean leftOk  = (idx == 0) || BOUNDARY.contains(haystack.charAt(idx - 1));
            boolean rightOk = (idx + n == haystack.length()) || BOUNDARY.contains(haystack.charAt(idx + n));
            if (leftOk && rightOk) {
                out.append(haystack, i, idx);
                out.append(replacement);
                i = idx + n;
                matches++;
            } else {
                // not a whole-word match; advance one char past this occurrence start
                out.append(haystack, i, idx + 1);
                i = idx + 1;
            }
        }
        out.append(haystack, i, haystack.length());
        return new BoundaryResult(out.toString(), matches);
    }

    private String sessionId() { return "mcp-" + Thread.currentThread().getName(); }

    record BoundaryResult(String replaced, int matchCount) {}

    private record Operation(TitleLocation location, String fromPath, String toPath, String newBasename, int matchCount) {}

    public record Result(
            int totalCandidates,
            int renamed,
            int skippedNonWordBoundary,
            int skippedAmbiguous,
            List<String> errors,
            List<Map<String, Object>> operations
    ) {}
}
