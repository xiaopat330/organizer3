package com.organizer3.command;

import com.organizer3.filesystem.VolumeFileSystem;
import com.organizer3.model.Actress;
import com.organizer3.repository.TitleLocationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.Jdbi;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
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

        List<Path> renamed = new ArrayList<>();
        List<LocationRename> skipped = new ArrayList<>();

        // Step 1: rename folders on mounted volume (filesystem first, then DB path)
        for (LocationRename rename : preview.renames()) {
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

        if (dry) {
            return new MergeResult(preview.castTitleCount(), preview.filingTitleCount(), renamed, skipped);
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

        return new MergeResult(preview.castTitleCount(), preview.filingTitleCount(), renamed, skipped);
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
