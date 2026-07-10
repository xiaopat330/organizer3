package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.covers.CoverPath;
import com.organizer3.filesystem.VolumeFileSystem;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;
import com.organizer3.model.Title;
import com.organizer3.shell.SessionContext;
import com.organizer3.smb.VolumeConnection;
import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Repairs title folders on the mounted volume whose NAS cover image is missing or
 * zero-byte, by re-writing the intact local cover cache copy into the folder.
 *
 * <p>Background: a best-effort NAS cover write during promotion can create the cover
 * file but write zero bytes if the SMB session stalls mid-transfer (see
 * {@code CoverWriteService#saveToNasBestEffort}). The local cover cache under
 * {@code data/covers/<LABEL>/<baseCode>.jpg} is populated first and independently, so a
 * zero-byte (or altogether missing) NAS cover can usually be repaired by re-pushing the
 * local copy — no re-enrichment needed.
 *
 * <p>Enumerates live ({@code stale_since IS NULL}) title_locations on {@code volumeId},
 * ordered by title code for stable pagination, and for each:
 * <ul>
 *   <li>Resolves the local cover via {@link CoverPath#find}. Absent → {@code noLocalCover}
 *       (nothing to push).</li>
 *   <li>If the local cover itself is zero-byte → {@code localCoverEmpty} (nothing valid to
 *       push).</li>
 *   <li>Otherwise checks the NAS cover at {@code <folderPath>/<baseCode>.jpg}: present with
 *       {@code size > 0} → {@code ok}; absent → {@code missing}; present with size 0 →
 *       {@code zeroByte}. The latter two are candidates.</li>
 *   <li>In a dry run, candidates stay classified {@code missing}/{@code zeroByte}. In a real
 *       run, each candidate is pushed (local bytes written over the NAS path, which already
 *       exists as a folder) and terminally reclassified {@code pushed} or {@code failed} —
 *       {@code missing}/{@code zeroByte} counts are only populated in dry runs, so
 *       {@code counts} always sums to {@code scanned} in either mode.</li>
 * </ul>
 *
 * <p>A failure pushing one title's cover never aborts the batch — it is recorded and the
 * scan continues.
 *
 * <p>Caveats: only the {@code <baseCode>.jpg} filename is checked/written. A folder whose
 * valid NAS cover exists under a different extension (e.g. {@code .png}/{@code .webp}) will
 * be classified {@code missing}, and a real run would add a sibling {@code .jpg} rather than
 * detecting the existing cover — use {@code dryRun} first and inspect {@code state=missing}
 * candidates before committing. Also, unexpected I/O errors during the detection phase (not
 * just push failures) are folded into the {@code failed} count via the outer per-title guard,
 * so a nonzero {@code failed} does not always mean "push attempted and failed."
 */
public class BackfillFolderCoversTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(BackfillFolderCoversTool.class);

    private static final int DEFAULT_LIMIT  = 0; // 0 = all
    private static final int DEFAULT_OFFSET = 0;
    private static final int MAX_LISTED     = 500;

    private final SessionContext session;
    private final Jdbi jdbi;
    private final CoverPath coverPath;

    public BackfillFolderCoversTool(SessionContext session, Jdbi jdbi, CoverPath coverPath) {
        this.session = session;
        this.jdbi = jdbi;
        this.coverPath = coverPath;
    }

    @Override public String name() { return "backfill_folder_covers"; }

    @Override public String description() {
        return "Re-write the local cover into title folders on the mounted volume whose NAS cover "
             + "is missing or zero-byte. dryRun default.";
    }

    @Override
    public JsonNode inputSchema() {
        return Schemas.object()
                .prop("volumeId", "string",  "Must match the currently mounted volume id.")
                .prop("dryRun",   "boolean", "If true (default), detect and report only; do not write.", true)
                .prop("limit",    "integer", "Max titles to inspect, ordered by code. Default 0 (all).", DEFAULT_LIMIT)
                .prop("offset",   "integer", "Skip this many titles before scanning. Default 0.", DEFAULT_OFFSET)
                .prop("curatedOnly", "boolean", "If true, only process titles that have been promoted "
                        + "(title_locations.curated_at IS NOT NULL). Default false = all live titles.", false)
                .require("volumeId")
                .build();
    }

    @Override
    public Object call(JsonNode args) throws IOException {
        String volumeId = Schemas.requireString(args, "volumeId").trim();
        boolean dryRun  = Schemas.optBoolean(args, "dryRun", true);
        int limit       = Math.max(0, Schemas.optInt(args, "limit",  DEFAULT_LIMIT));
        int offset      = Math.max(0, Schemas.optInt(args, "offset", DEFAULT_OFFSET));
        boolean curatedOnly = Schemas.optBoolean(args, "curatedOnly", false);

        // ── Volume guard — mirrors ScanTitleFolderAnomaliesTool ─────────────────
        String mountedVolumeId = session.getMountedVolumeId();
        if (mountedVolumeId == null) {
            throw new IllegalArgumentException(
                    "No volume is currently mounted. Mount one with the shell before using this tool.");
        }
        if (!mountedVolumeId.equals(volumeId)) {
            throw new IllegalArgumentException("volumeId '" + volumeId + "' does not match the mounted volume '"
                    + mountedVolumeId + "'");
        }
        VolumeConnection conn = session.getActiveConnection();
        if (conn == null || !conn.isConnected()) {
            throw new IllegalArgumentException("Active connection is closed; re-mount the volume.");
        }
        VolumeFileSystem fs = conn.fileSystem();

        List<Row> rows = fetchRows(volumeId, limit, offset, curatedOnly);

        int ok = 0, missing = 0, zeroByte = 0, pushed = 0, failed = 0, noLocalCover = 0, localCoverEmpty = 0;
        List<Candidate> candidates = new ArrayList<>();
        List<Failure> failures = new ArrayList<>();

        for (Row row : rows) {
            try {
                Title minimalTitle = Title.builder().label(row.label()).baseCode(row.baseCode()).build();
                Optional<Path> localOpt = coverPath.find(minimalTitle);
                if (localOpt.isEmpty()) {
                    noLocalCover++;
                    continue;
                }
                Path localPath = localOpt.get();
                long localSize = Files.size(localPath);
                if (localSize == 0) {
                    localCoverEmpty++;
                    continue;
                }

                Path nasPath = Path.of(row.path(), row.baseCode() + ".jpg");
                boolean nasExists = fs.exists(nasPath);
                long nasSize = nasExists ? fs.size(nasPath) : 0;

                if (nasExists && nasSize > 0) {
                    ok++;
                    continue;
                }

                String detected = nasExists ? "zeroByte" : "missing";

                if (dryRun) {
                    if (detected.equals("missing")) missing++; else zeroByte++;
                    candidates.add(new Candidate(row.code(), row.path(), detected, localSize));
                } else {
                    try {
                        byte[] bytes = Files.readAllBytes(localPath);
                        fs.writeFile(nasPath, bytes);
                        pushed++;
                        candidates.add(new Candidate(row.code(), row.path(), "pushed", localSize));
                        log.info("backfill_folder_covers: pushed {} bytes to {} for {}",
                                bytes.length, nasPath, row.code());
                    } catch (Exception e) {
                        failed++;
                        candidates.add(new Candidate(row.code(), row.path(), "failed", localSize));
                        failures.add(new Failure(row.code(), e.getMessage()));
                        log.warn("backfill_folder_covers: push failed for {} at {}: {}",
                                row.code(), nasPath, e.getMessage());
                    }
                }
            } catch (Exception e) {
                // Never let one title's failure abort the batch.
                failed++;
                failures.add(new Failure(row.code(), e.getMessage()));
                log.warn("backfill_folder_covers: error scanning {}: {}", row.code(), e.getMessage());
            }
        }

        int candidatesOmitted = Math.max(0, candidates.size() - MAX_LISTED);
        if (candidatesOmitted > 0) candidates = candidates.subList(0, MAX_LISTED);
        int failuresOmitted = Math.max(0, failures.size() - MAX_LISTED);
        if (failuresOmitted > 0) failures = failures.subList(0, MAX_LISTED);

        Counts counts = new Counts(ok, missing, zeroByte, pushed, failed, noLocalCover, localCoverEmpty);
        return new Result(volumeId, dryRun, curatedOnly, rows.size(), counts,
                candidates, candidatesOmitted, failures, failuresOmitted);
    }

    private List<Row> fetchRows(String volumeId, int limit, int offset, boolean curatedOnly) {
        long effectiveLimit = limit > 0 ? limit : Long.MAX_VALUE;
        String curatedPredicate = curatedOnly ? "AND tl.curated_at IS NOT NULL" : "";
        String sql = """
                SELECT t.code AS code, t.base_code AS base_code, t.label AS label, tl.path AS path
                FROM title_locations tl
                JOIN titles t ON t.id = tl.title_id
                WHERE tl.volume_id = :volumeId
                  AND tl.stale_since IS NULL
                  %s
                ORDER BY t.code, tl.id
                LIMIT :limit OFFSET :offset
                """.formatted(curatedPredicate);
        return jdbi.withHandle(h -> h.createQuery(sql)
                .bind("volumeId", volumeId)
                .bind("limit", effectiveLimit)
                .bind("offset", offset)
                .map((rs, ctx) -> new Row(rs.getString("code"), rs.getString("base_code"),
                        rs.getString("label"), rs.getString("path")))
                .list());
    }

    private record Row(String code, String baseCode, String label, String path) {}

    public record Candidate(String code, String path, String state, long bytesToPush) {}
    public record Failure(String code, String error) {}
    public record Counts(int ok, int missing, int zeroByte, int pushed, int failed,
                          int noLocalCover, int localCoverEmpty) {}
    public record Result(String volumeId, boolean dryRun, boolean curatedOnly, int scanned, Counts counts,
                          List<Candidate> candidates, int candidatesOmitted,
                          List<Failure> failures, int failuresOmitted) {}
}
