package com.organizer3.command;

import com.organizer3.filesystem.VolumeFileSystem;
import com.organizer3.model.Actress;
import com.organizer3.model.ActressAlias;
import com.organizer3.repository.ActressRepository;
import com.organizer3.repository.TitleLocationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.Jdbi;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Merges a misspelled (suspect) actress record into the correct (canonical) one.
 *
 * <p>DB side: reassigns title_actresses rows, updates titles.actress_id, cleans dependent
 * tables, and deletes the suspect actress. Always runs to completion regardless of which
 * volume is mounted.
 *
 * <p>Filesystem side: renames affected title folders only on the currently mounted volume.
 * Locations on unmounted volumes are surfaced so the user can mount and sync them later.
 */
@Slf4j
@RequiredArgsConstructor
public class ActressMergeService {

    private final Jdbi jdbi;
    private final TitleLocationRepository locationRepo;
    private final ActressRepository actressRepo;

    // ── Public records ───────────────────────────────────────────────────────

    public record LocationRename(
            long locationId,
            String volumeId,
            String partitionId,
            Path currentPath,
            Path newPath
    ) {}

    public record MergePreview(
            Actress suspect,
            Actress canonical,
            int castTitleCount,
            int filingTitleCount,
            List<LocationRename> renames
    ) {}

    public record MergeResult(
            int castTitlesReassigned,
            int filingTitlesUpdated,
            List<Path> renamedPaths,
            List<LocationRename> skipped
    ) {}

    public record UnresolvedPath(long locationId, String volumeId, Path currentPath) {}

    public record RenamePlan(
            Actress actress,
            List<LocationRename> renames,
            List<UnresolvedPath> unresolved
    ) {}

    public record RenameResult(
            List<Path> renamedPaths,
            List<LocationRename> skipped,
            List<UnresolvedPath> unresolved
    ) {}

    // ── Preview ──────────────────────────────────────────────────────────────

    public MergePreview preview(Actress suspect, Actress canonical) {
        long suspectId = suspect.getId();

        int castCount = jdbi.withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM title_actresses WHERE actress_id = :id")
                        .bind("id", suspectId)
                        .mapTo(Integer.class)
                        .one());

        record FilingRow(long locId, String volumeId, String partitionId, String path) {}

        List<FilingRow> rows = jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT tl.id, tl.volume_id, tl.partition_id, tl.path
                        FROM titles t
                        JOIN title_locations tl ON tl.title_id = t.id
                        WHERE t.actress_id = :suspectId
                        """)
                        .bind("suspectId", suspectId)
                        .map((rs, ctx) -> new FilingRow(
                                rs.getLong("id"),
                                rs.getString("volume_id"),
                                rs.getString("partition_id"),
                                rs.getString("path")))
                        .list());

        int filingCount = jdbi.withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM titles WHERE actress_id = :id")
                        .bind("id", suspectId)
                        .mapTo(Integer.class)
                        .one());

        List<LocationRename> renames = new ArrayList<>();
        for (FilingRow row : rows) {
            Path current = Path.of(row.path());
            Path newPath = computeNewPath(current, suspect.getCanonicalName(), canonical.getCanonicalName());
            if (newPath != null) {
                renames.add(new LocationRename(row.locId(), row.volumeId(), row.partitionId(), current, newPath));
            }
        }

        return new MergePreview(suspect, canonical, castCount, filingCount, renames);
    }

    // ── Execute ──────────────────────────────────────────────────────────────

    public MergeResult execute(MergePreview preview, String mountedVolumeId,
                               VolumeFileSystem fs, boolean dry) throws IOException {
        long suspectId = preview.suspect().getId();
        long canonicalId = preview.canonical().getId();

        // Step 1: rename folders on mounted volume (filesystem first, then DB path)
        FsRenameOutcome rn = performFsRenames(preview.renames(), mountedVolumeId, fs, dry);

        if (dry) {
            return new MergeResult(preview.castTitleCount(), preview.filingTitleCount(), rn.renamed, rn.skipped);
        }

        // Step 2: DB merge in a single transaction
        jdbi.useTransaction(h -> {
            // Reassign title_actresses: insert new rows for canonical, then delete suspect rows
            h.createUpdate("""
                    INSERT OR IGNORE INTO title_actresses (title_id, actress_id)
                    SELECT title_id, :canonicalId FROM title_actresses WHERE actress_id = :suspectId
                    """)
                    .bind("canonicalId", canonicalId)
                    .bind("suspectId", suspectId)
                    .execute();

            int castRows = h.createUpdate("DELETE FROM title_actresses WHERE actress_id = :suspectId")
                    .bind("suspectId", suspectId)
                    .execute();
            log.info("Reassigned {} title_actresses rows", castRows);

            // Update filing actress FK on titles
            int filingRows = h.createUpdate("UPDATE titles SET actress_id = :canonicalId WHERE actress_id = :suspectId")
                    .bind("canonicalId", canonicalId)
                    .bind("suspectId", suspectId)
                    .execute();
            log.info("Updated {} titles.actress_id rows", filingRows);

            // Clean dependent tables that don't cascade
            h.createUpdate("DELETE FROM actress_aliases WHERE actress_id = :suspectId")
                    .bind("suspectId", suspectId).execute();
            h.createUpdate("DELETE FROM javdb_actress_staging WHERE actress_id = :suspectId")
                    .bind("suspectId", suspectId).execute();
            h.createUpdate("DELETE FROM javdb_enrichment_queue WHERE actress_id = :suspectId")
                    .bind("suspectId", suspectId).execute();

            // Delete suspect actress (actress_companies cascades)
            h.createUpdate("DELETE FROM actresses WHERE id = :suspectId")
                    .bind("suspectId", suspectId)
                    .execute();
        });

        return new MergeResult(preview.castTitleCount(), preview.filingTitleCount(), rn.renamed, rn.skipped);
    }

    // ── Rename-only (post-merge cleanup) ─────────────────────────────────────

    /**
     * Build the rename plan for one actress by scanning her filing title_locations
     * for folder names that start with any of her aliases (or canonical name) but
     * don't already use the canonical name.
     *
     * <p>Locations whose folder name doesn't match any known alias appear in
     * {@link RenamePlan#unresolved()} for manual inspection.
     */
    public RenamePlan planRenamesFor(Actress actress) {
        long actressId = actress.getId();
        String canonical = actress.getCanonicalName();

        // Aliases sorted longest-first so multi-token aliases match before shorter prefixes
        List<String> aliasNames = new ArrayList<>();
        for (ActressAlias a : actressRepo.findAliases(actressId)) {
            aliasNames.add(a.aliasName());
        }
        aliasNames.sort(Comparator.comparingInt(String::length).reversed());

        record FilingRow(long locId, String volumeId, String partitionId, String path) {}

        List<FilingRow> rows = jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT tl.id, tl.volume_id, tl.partition_id, tl.path
                        FROM titles t
                        JOIN title_locations tl ON tl.title_id = t.id
                        WHERE t.actress_id = :actressId
                          AND instr(LOWER(tl.path), LOWER(:canonical)) = 0
                        """)
                        .bind("actressId", actressId)
                        .bind("canonical", canonical)
                        .map((rs, ctx) -> new FilingRow(
                                rs.getLong("id"),
                                rs.getString("volume_id"),
                                rs.getString("partition_id"),
                                rs.getString("path")))
                        .list());

        List<LocationRename> renames = new ArrayList<>();
        List<UnresolvedPath> unresolved = new ArrayList<>();
        for (FilingRow row : rows) {
            Path current = Path.of(row.path());
            Path newPath = null;
            for (String alias : aliasNames) {
                newPath = computeNewPath(current, alias, canonical);
                if (newPath != null) break;
            }
            if (newPath != null) {
                renames.add(new LocationRename(row.locId(), row.volumeId(), row.partitionId(), current, newPath));
            } else {
                unresolved.add(new UnresolvedPath(row.locId(), row.volumeId(), current));
            }
        }

        return new RenamePlan(actress, renames, unresolved);
    }

    /**
     * Execute (or preview) folder renames for one actress, without touching the DB merge
     * tables. Use this after a {@code merge_actresses} call when only the filesystem
     * still reflects the old name.
     */
    public RenameResult renameOnly(RenamePlan plan, String mountedVolumeId,
                                   VolumeFileSystem fs, boolean dry) throws IOException {
        FsRenameOutcome rn = performFsRenames(plan.renames(), mountedVolumeId, fs, dry);
        return new RenameResult(rn.renamed, rn.skipped, plan.unresolved());
    }

    // ── Shared FS rename loop ────────────────────────────────────────────────

    private record FsRenameOutcome(List<Path> renamed, List<LocationRename> skipped) {}

    private FsRenameOutcome performFsRenames(List<LocationRename> renames, String mountedVolumeId,
                                             VolumeFileSystem fs, boolean dry) throws IOException {
        List<Path> renamed = new ArrayList<>();
        List<LocationRename> skipped = new ArrayList<>();
        for (LocationRename rename : renames) {
            boolean onMountedVolume = rename.volumeId().equals(mountedVolumeId) && fs != null;
            if (!onMountedVolume) {
                skipped.add(rename);
                continue;
            }
            if (dry) {
                renamed.add(rename.newPath());
            } else {
                try {
                    fs.rename(rename.currentPath(), rename.newPath().getFileName().toString());
                    locationRepo.updatePathAndPartition(rename.locationId(), rename.newPath(), rename.partitionId());
                    renamed.add(rename.newPath());
                    log.info("Renamed folder: {} → {}", rename.currentPath(), rename.newPath());
                } catch (IOException e) {
                    log.warn("Failed to rename {}: {}", rename.currentPath(), e.getMessage());
                    skipped.add(rename);
                }
            }
        }
        return new FsRenameOutcome(renamed, skipped);
    }

    // ── Path computation ─────────────────────────────────────────────────────

    /**
     * Replaces the suspect actress name prefix in the last path segment with the canonical name.
     * Matches only if the segment starts with {@code suspectName} followed by a space or end.
     * Returns {@code null} if the segment doesn't match (no rename needed or not recognized).
     */
    static Path computeNewPath(Path currentPath, String suspectName, String canonicalName) {
        String folderName = currentPath.getFileName().toString();
        if (!folderName.startsWith(suspectName + " ") && !folderName.equals(suspectName)) {
            return null;
        }
        String newFolderName = canonicalName + folderName.substring(suspectName.length());
        Path parent = currentPath.getParent();
        return parent != null ? parent.resolve(newFolderName) : Path.of(newFolderName);
    }
}
