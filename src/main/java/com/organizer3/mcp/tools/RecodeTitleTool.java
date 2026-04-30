package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.filesystem.VolumeFileSystem;
import com.organizer3.javdb.enrichment.EnrichmentHistoryRepository;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;
import com.organizer3.model.Title;
import com.organizer3.model.TitleLocation;
import com.organizer3.repository.TitleLocationRepository;
import com.organizer3.repository.TitleRepository;
import com.organizer3.shell.SessionContext;
import com.organizer3.smb.VolumeConnection;
import com.organizer3.sync.TitleCodeParser;
import com.organizer3.sync.TitleCodeParser.ParsedCode;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.Jdbi;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Update a title's code (e.g. {@code ABC-001} → {@code ABC-002}) while preserving all enrichment.
 *
 * <p>Operations (in order):
 * <ol>
 *   <li>Validate {@code new_code} parses cleanly and doesn't collide with another title row.</li>
 *   <li>Snapshot enrichment history with {@code reason='recode'} (within the transaction).</li>
 *   <li>Update {@code titles.code}, {@code base_code}, {@code label}, {@code seq_num}.</li>
 *   <li>Update every {@code title_locations.path} row by replacing the old code suffix.</li>
 *   <li>Optionally rename on-disk folders.</li>
 * </ol>
 *
 * <p>DB changes are committed in a single transaction before disk renames. If disk renames fail
 * after DB commit, the DB state is rolled back and {@code partialFailure=true} is reported.
 *
 * <p>Refuses if {@code new_code} destination folder already exists on disk (to prevent clobbering)
 * or if any title_locations are on volumes other than the currently mounted one when
 * {@code rename_disk=true}.
 *
 * <p>Gated on {@code mcp.allowMutations}.
 */
@Slf4j
public class RecodeTitleTool implements Tool {

    private final Jdbi jdbi;
    private final SessionContext session;
    private final TitleRepository titleRepo;
    private final TitleLocationRepository locationRepo;
    private final EnrichmentHistoryRepository enrichmentHistory;
    private final TitleCodeParser codeParser;

    public RecodeTitleTool(Jdbi jdbi, SessionContext session, TitleRepository titleRepo,
                           TitleLocationRepository locationRepo,
                           EnrichmentHistoryRepository enrichmentHistory,
                           TitleCodeParser codeParser) {
        this.jdbi = jdbi;
        this.session = session;
        this.titleRepo = titleRepo;
        this.locationRepo = locationRepo;
        this.enrichmentHistory = enrichmentHistory;
        this.codeParser = codeParser;
    }

    @Override public String name()        { return "recode_title"; }
    @Override public String description() {
        return "Update a title's code (e.g. ABC-001 → ABC-002) while preserving all enrichment. "
             + "Snapshots enrichment history, updates DB fields, optionally renames on-disk folders. "
             + "Defaults to dryRun:true.";
    }

    @Override
    public JsonNode inputSchema() {
        return Schemas.object()
                .prop("title_id",    "integer", "Title id to recode.")
                .prop("new_code",    "string",  "New code, e.g. 'ABC-002'. Must parse as a valid JAV code.")
                .prop("dry_run",     "boolean", "If true (default), return the plan without committing.", true)
                .prop("rename_disk", "boolean", "If true (default), rename on-disk folders after DB commit. Requires a mounted volume.", true)
                .require("title_id", "new_code")
                .build();
    }

    @Override
    public Object call(JsonNode args) {
        long titleId     = Schemas.requireLong(args, "title_id");
        String newCode   = Schemas.requireString(args, "new_code").strip().toUpperCase();
        boolean dryRun   = Schemas.optBoolean(args, "dry_run", true);
        boolean renameDisk = Schemas.optBoolean(args, "rename_disk", true);

        Title title = titleRepo.findById(titleId).orElseThrow(
                () -> new IllegalArgumentException("No title with id=" + titleId));

        String oldCode = title.getCode();
        if (oldCode.equalsIgnoreCase(newCode)) {
            throw new IllegalArgumentException("new_code is the same as the current code (case-insensitive)");
        }

        // Validate and parse new_code
        ParsedCode parsed = codeParser.parse(newCode);
        if (parsed.label() == null) {
            throw new IllegalArgumentException("'" + newCode + "' is not a valid JAV code (expected LABEL-NUMBER format)");
        }
        // Normalize to uppercase
        String normalizedCode = parsed.code();

        // Check collision with another title
        titleRepo.findByCode(normalizedCode).ifPresent(existing -> {
            if (existing.getId() != titleId) {
                throw new IllegalArgumentException(
                        "code '" + normalizedCode + "' is already used by title id=" + existing.getId());
            }
        });

        // Load all locations
        List<TitleLocation> locations = locationRepo.findByTitle(titleId);

        String mountedVolumeId = session.getMountedVolumeId();
        VolumeConnection conn  = session.getActiveConnection();
        VolumeFileSystem fs    = (conn != null && conn.isConnected()) ? conn.fileSystem() : null;

        // Build path rename plan
        List<LocationRecode> plan = new ArrayList<>();
        List<String> unmountedVolumes = new ArrayList<>();
        for (TitleLocation loc : locations) {
            Path newPath = computeNewPath(loc.getPath(), oldCode, normalizedCode);
            plan.add(new LocationRecode(loc.getId(), loc.getVolumeId(), loc.getPath(), newPath));
            if (!loc.getVolumeId().equals(mountedVolumeId)) {
                unmountedVolumes.add(loc.getVolumeId());
            }
        }

        if (renameDisk && !unmountedVolumes.isEmpty()) {
            throw new IllegalArgumentException(
                    "Title has locations on unmounted volume(s): " + unmountedVolumes
                    + ". Mount the volume first or pass rename_disk:false to skip disk renames.");
        }

        // Check destination doesn't already exist on disk (even in dry_run, guard is useful)
        if (renameDisk && fs != null) {
            for (LocationRecode r : plan) {
                if (!r.volumeId().equals(mountedVolumeId)) continue;
                if (fs.exists(r.newPath())) {
                    throw new IllegalArgumentException(
                            "Destination folder already exists: " + r.newPath()
                            + ". Cannot recode without clobbering.");
                }
            }
        }

        log.info("RecodeTitle: title={} '{}' → '{}' dryRun={} renameDisk={} locations={}",
                titleId, oldCode, normalizedCode, dryRun, renameDisk, plan.size());

        if (dryRun) {
            return buildResult(titleId, oldCode, normalizedCode, parsed, true, false,
                    mountedVolumeId, plan, List.of(), null);
        }

        // ── DB transaction: snapshot + update code + update paths ─────────────
        jdbi.useTransaction(h -> {
            enrichmentHistory.appendIfExists(titleId, "recode", h);
            titleRepo.updateCode(titleId, normalizedCode, parsed.baseCode(),
                    parsed.label(), parsed.seqNum(), h);
            for (LocationRecode r : plan) {
                locationRepo.updatePath(r.locationId(), r.newPath(), h);
            }
            log.info("RecodeTitle: DB commit — code updated '{}' → '{}', {} location path(s) updated",
                    oldCode, normalizedCode, plan.size());
        });

        if (!renameDisk) {
            return buildResult(titleId, oldCode, normalizedCode, parsed, false, false,
                    mountedVolumeId, plan, List.of(), null);
        }

        // ── Disk renames ──────────────────────────────────────────────────────
        List<LocationRecode> renamed = new ArrayList<>();
        List<LocationRecode> failed = new ArrayList<>();
        for (LocationRecode r : plan) {
            if (!r.volumeId().equals(mountedVolumeId)) continue;
            try {
                fs.rename(r.oldPath(), r.newPath().getFileName().toString());
                renamed.add(r);
            } catch (IOException e) {
                log.error("RecodeTitle: disk rename failed for {} → {}: {}", r.oldPath(), r.newPath(), e.getMessage());
                failed.add(r);
            }
        }

        if (!failed.isEmpty()) {
            log.error("RecodeTitle: {} disk rename(s) failed after DB commit — attempting DB rollback", failed.size());
            try {
                jdbi.useTransaction(h -> {
                    titleRepo.updateCode(titleId, oldCode, title.getBaseCode(),
                            title.getLabel(), title.getSeqNum(), h);
                    for (TitleLocation loc : locations) {
                        locationRepo.updatePath(loc.getId(), loc.getPath(), h);
                    }
                });
                log.info("RecodeTitle: DB rollback succeeded");
                // Also undo successful disk renames
                for (LocationRecode r : renamed) {
                    try { fs.rename(r.newPath(), r.oldPath().getFileName().toString()); } catch (IOException ignored) {}
                }
                throw new IllegalStateException(
                        "Disk rename failed for " + failed.size() + " path(s) (DB rolled back)");
            } catch (IllegalStateException rethrow) {
                throw rethrow;
            } catch (Exception rollbackEx) {
                log.error("RecodeTitle: DB rollback FAILED — title id={} code is now '{}' in DB but disk renames partially failed. Manual intervention required.",
                        titleId, normalizedCode, rollbackEx);
                return buildResult(titleId, oldCode, normalizedCode, parsed, false, true,
                        mountedVolumeId, plan, failed,
                        "DB rollback failed after disk error: " + rollbackEx.getMessage());
            }
        }

        log.info("RecodeTitle: completed — {} disk rename(s) done", renamed.size());
        return buildResult(titleId, oldCode, normalizedCode, parsed, false, false,
                mountedVolumeId, plan, List.of(), null);
    }

    static Path computeNewPath(Path currentPath, String oldCode, String newCode) {
        // Folder name ends with the code; replace the suffix
        String folderName = currentPath.getFileName().toString();
        if (!folderName.toUpperCase().endsWith(oldCode.toUpperCase())) {
            // Fallback: replace last occurrence of old code (case-insensitive)
            String lc = folderName.toUpperCase();
            int idx = lc.lastIndexOf(oldCode.toUpperCase());
            if (idx < 0) return currentPath.resolveSibling(folderName + "-RECODE-ERROR");
            String newFolder = folderName.substring(0, idx) + newCode + folderName.substring(idx + oldCode.length());
            return currentPath.resolveSibling(newFolder);
        }
        String newFolder = folderName.substring(0, folderName.length() - oldCode.length()) + newCode;
        return currentPath.resolveSibling(newFolder);
    }

    private static Result buildResult(long titleId, String oldCode, String newCode, ParsedCode parsed,
                                      boolean dryRun, boolean partialFailure, String mountedVolumeId,
                                      List<LocationRecode> plan, List<LocationRecode> failed,
                                      String errorMessage) {
        List<LocationRecodeRow> rows = plan.stream()
                .map(r -> new LocationRecodeRow(r.locationId(), r.volumeId(),
                        r.oldPath().toString(), r.newPath().toString()))
                .toList();
        List<String> failedPaths = failed.stream().map(r -> r.oldPath().toString()).toList();
        return new Result(titleId, oldCode, newCode, parsed.baseCode(), parsed.label(), parsed.seqNum(),
                dryRun, partialFailure, mountedVolumeId, rows, failedPaths, errorMessage);
    }

    record LocationRecode(long locationId, String volumeId, Path oldPath, Path newPath) {}

    public record LocationRecodeRow(long locationId, String volumeId, String oldPath, String newPath) {}
    public record Result(
            long titleId,
            String oldCode,
            String newCode,
            String newBaseCode,
            String newLabel,
            int newSeqNum,
            boolean dryRun,
            boolean partialFailure,
            String mountedVolumeId,
            List<LocationRecodeRow> locations,
            List<String> failedPaths,
            String errorMessage
    ) {}
}
