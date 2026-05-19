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

    /**
     * A {@link LocationRename} that was skipped during fs execution, with a human-readable
     * reason. See {@link SkipReason} for the canonical reason strings.
     */
    public record SkippedRename(LocationRename rename, String reason) {
        public String volumeId()    { return rename.volumeId(); }
        public Path   currentPath() { return rename.currentPath(); }
        public Path   newPath()     { return rename.newPath(); }
    }

    /** Canonical reason strings attached to {@link SkippedRename}. */
    public static final class SkipReason {
        public static final String VOLUME_NOT_MOUNTED = "volume not mounted on session";
        public static String fsRenameFailed(String detail) { return "fs rename failed: " + detail; }
        private SkipReason() {}
    }

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
            List<SkippedRename> skipped
    ) {}

    public record UnresolvedPath(long locationId, String volumeId, Path currentPath) {}

    public record RenamePlan(
            Actress actress,
            List<LocationRename> renames,
            List<UnresolvedPath> unresolved
    ) {}

    public record RenameResult(
            List<Path> renamedPaths,
            List<SkippedRename> skipped,
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
                          AND tl.stale_since IS NULL
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
        return planRenamesFor(actress, null);
    }

    public RenamePlan planRenamesFor(Actress actress, String fromName) {
        long actressId = actress.getId();
        String canonical = actress.getCanonicalName();

        // Aliases sorted longest-first so multi-token aliases match before shorter prefixes
        List<String> aliasNames = new ArrayList<>();
        for (ActressAlias a : actressRepo.findAliases(actressId)) {
            aliasNames.add(a.aliasName());
        }
        if (fromName != null && !fromName.isBlank()) {
            aliasNames.add(fromName);
        }
        aliasNames.sort(Comparator.comparingInt(String::length).reversed());

        record FilingRow(long locId, String volumeId, String partitionId, String path) {}

        List<FilingRow> rows = jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT tl.id, tl.volume_id, tl.partition_id, tl.path
                        FROM titles t
                        JOIN title_locations tl ON tl.title_id = t.id
                        WHERE t.actress_id = :actressId
                          AND tl.stale_since IS NULL
                        """)
                        .bind("actressId", actressId)
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
            // Skip rows whose LEAF folder is already canonical. Use the same bounded-name check
            // so mid-position canonical occurrences (e.g. "Yuki, Sora Arakawa (CODE)") are also
            // recognized and don't surface as spurious unresolvable entries.
            String leaf = current.getFileName() != null ? current.getFileName().toString() : "";
            boolean leafAlreadyCanonical = findBoundedName(leaf, canonical) >= 0
                    || leaf.equalsIgnoreCase(canonical);
            if (leafAlreadyCanonical) {
                continue;
            }

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

    private record FsRenameOutcome(List<Path> renamed, List<SkippedRename> skipped) {}

    private FsRenameOutcome performFsRenames(List<LocationRename> renames, String mountedVolumeId,
                                             VolumeFileSystem fs, boolean dry) throws IOException {
        List<Path> renamed = new ArrayList<>();
        List<SkippedRename> skipped = new ArrayList<>();
        for (LocationRename rename : renames) {
            boolean onMountedVolume = rename.volumeId().equals(mountedVolumeId) && fs != null;
            if (!onMountedVolume) {
                log.info("Skipping rename {} → {}: volume '{}' is not mounted (session mounted='{}').",
                        rename.currentPath(), rename.newPath(), rename.volumeId(), mountedVolumeId);
                skipped.add(new SkippedRename(rename, SkipReason.VOLUME_NOT_MOUNTED));
                continue;
            }
            if (dry) {
                renamed.add(rename.newPath());
            } else {
                try {
                    fs.rename(rename.currentPath(), rename.newPath().getFileName().toString());
                    locationRepo.updatePathAndPartition(rename.locationId(), rename.newPath(),
                            deriveShortPartitionId(rename.newPath(), rename.partitionId()));
                    renamed.add(rename.newPath());
                    log.info("Renamed folder: {} → {}", rename.currentPath(), rename.newPath());
                } catch (IOException e) {
                    log.warn("Failed to rename {}: {}", rename.currentPath(), e.getMessage());
                    skipped.add(new SkippedRename(rename, SkipReason.fsRenameFailed(e.getMessage())));
                }
            }
        }
        return new FsRenameOutcome(renamed, skipped);
    }

    // ── Actress-folder move to attention ────────────────────────────────────

    public record LocationEntry(long locationId, String partitionId, Path currentPath, Path newPath) {}

    public record ActressFolderEntry(
            Path actressFolder,
            String oldName,
            List<LocationEntry> locations
    ) {}

    public record ActressFolderPlan(
            Actress actress,
            List<ActressFolderEntry> entries
    ) {}

    /**
     * Finds actress-level folders on {@code volumeId} whose basename matches one of the actress's
     * aliases (not the canonical name), and computes the target path for each contained
     * title_location under {@code /attention/<canonicalName>/}.
     *
     * <p>Returns an empty plan (no entries) when all folders already use the canonical name.
     */
    public ActressFolderPlan planActressFolderMoveFor(Actress actress, String volumeId) {
        return planActressFolderMoveFor(actress, volumeId, false);
    }

    /**
     * Variant of {@link #planActressFolderMoveFor(Actress, String)} that optionally includes
     * actress-level folders whose basename already matches the canonical name. Use this when
     * deliberately curating a correctly-named actress folder to {@code /attention/} for
     * inspection.
     */
    public ActressFolderPlan planActressFolderMoveFor(Actress actress, String volumeId, boolean includeCanonical) {
        if (volumeId == null) return new ActressFolderPlan(actress, List.of());

        String canonical = actress.getCanonicalName();
        String canonicalLower = canonical.toLowerCase();

        List<String> aliasNames = new ArrayList<>();
        for (ActressAlias a : actressRepo.findAliases(actress.getId())) {
            aliasNames.add(a.aliasName().toLowerCase());
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

        // Group rows by parent folder; include only parents whose basename is an alias
        java.util.LinkedHashMap<Path, List<Row>> byParent = new java.util.LinkedHashMap<>();
        for (Row row : rows) {
            Path parent = Path.of(row.path()).getParent();
            if (parent == null) continue;
            String parentBasename = parent.getFileName().toString().toLowerCase();
            boolean matches = aliasNames.contains(parentBasename)
                    || (includeCanonical && canonicalLower.equals(parentBasename));
            if (matches) {
                byParent.computeIfAbsent(parent, k -> new ArrayList<>()).add(row);
            }
        }

        Path attentionBase = Path.of("/attention", canonical);
        List<ActressFolderEntry> entries = new ArrayList<>();
        for (java.util.Map.Entry<Path, List<Row>> e : byParent.entrySet()) {
            Path actressFolder = e.getKey();
            String oldName = actressFolder.getFileName().toString();
            List<LocationEntry> locs = new ArrayList<>();
            for (Row row : e.getValue()) {
                Path current = Path.of(row.path());
                Path newPath = attentionBase.resolve(actressFolder.relativize(current));
                locs.add(new LocationEntry(row.locId(), row.partId(), current, newPath));
            }
            entries.add(new ActressFolderEntry(actressFolder, oldName, locs));
        }

        return new ActressFolderPlan(actress, entries);
    }

    /**
     * Updates all title_location paths recorded in the plan to their {@code newPath}
     * and sets {@code partition_id = "attention"}. Call this after a successful
     * {@link com.organizer3.organize.AttentionRouter#route} to keep the DB in sync.
     */
    public void applyActressFolderMove(ActressFolderPlan plan) {
        jdbi.useTransaction(h -> {
            for (ActressFolderEntry entry : plan.entries()) {
                for (LocationEntry loc : entry.locations()) {
                    h.createUpdate("""
                            UPDATE title_locations
                            SET path = :path, partition_id = 'attention'
                            WHERE id = :id
                            -- W:both: targets a specific row by id (live or stale); a stale row
                            -- that gets an updated path here may later be re-observed and cleared.
                            """)
                            .bind("path", loc.newPath().toString())
                            .bind("id", loc.locationId())
                            .execute();
                }
            }
        });
    }

    // ── Actress-folder move from attention (reverse) ──────────────────────────

    /**
     * Plan for moving an actress folder from {@code /attention/<canonicalName>/} back to
     * {@code /stars/<tier>/<canonicalName>/}.
     */
    public record AttentionExitPlan(
            Actress actress,
            String tier,
            Path source,
            Path destination,
            List<LocationEntry> locations
    ) {}

    /**
     * Builds the plan for moving {@code /attention/<canonicalName>/} to
     * {@code /stars/<tier>/<canonicalName>/}.
     *
     * <p>Does not touch the filesystem. Returns a plan whose {@code locations} list contains
     * all title_location rows (on {@code volumeId}) whose path starts with the source prefix.
     *
     * @param actress   resolved actress
     * @param volumeId  currently mounted volume; if null returns a plan with empty locations
     */
    public AttentionExitPlan planMoveActressFolderFromAttention(Actress actress, String volumeId) {
        String canonical = actress.getCanonicalName();
        String tier = actress.getTier().name().toLowerCase();
        Path source = Path.of("/attention", canonical);
        Path destination = Path.of("/stars", tier, canonical);

        if (volumeId == null) {
            return new AttentionExitPlan(actress, tier, source, destination, List.of());
        }

        String sourcePrefix = source + "/";

        record Row(long locId, String partId, String path) {}
        List<Row> rows = jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT tl.id, tl.partition_id, tl.path
                        FROM titles t
                        JOIN title_locations tl ON tl.title_id = t.id
                        WHERE t.actress_id = :actressId AND tl.volume_id = :volumeId
                          AND (tl.path = :src OR tl.path LIKE :prefix)
                        """)
                        .bind("actressId", actress.getId())
                        .bind("volumeId", volumeId)
                        .bind("src", source.toString())
                        .bind("prefix", sourcePrefix + "%")
                        .map((rs, ctx) -> new Row(rs.getLong("id"), rs.getString("partition_id"), rs.getString("path")))
                        .list());

        List<LocationEntry> locs = new ArrayList<>();
        for (Row row : rows) {
            Path current = Path.of(row.path());
            // Rebase: replace /attention/<canonical>/... with /stars/<tier>/<canonical>/...
            Path relative = source.relativize(current);
            Path newPath = destination.resolve(relative);
            locs.add(new LocationEntry(row.locId(), row.partId(), current, newPath));
        }

        return new AttentionExitPlan(actress, tier, source, destination, locs);
    }

    /**
     * Updates all title_location paths in the plan to their {@code newPath} and sets
     * {@code partition_id} to the tier. Call after a successful FS move.
     */
    public void applyMoveActressFolderFromAttention(AttentionExitPlan plan) {
        String tier = plan.tier();
        jdbi.useTransaction(h -> {
            for (LocationEntry loc : plan.locations()) {
                h.createUpdate("""
                        UPDATE title_locations
                        SET path = :path, partition_id = :tier, stale_since = NULL
                        WHERE id = :id
                        """)
                        .bind("path", loc.newPath().toString())
                        .bind("tier", tier)
                        .bind("id", loc.locationId())
                        .execute();
            }
        });
    }

    // ── Path computation ─────────────────────────────────────────────────────

    /**
     * Returns the short-form partition_id for a new path.
     *
     * <p>For {@code /stars/<tier>/...} paths, the partition is the tier name only (e.g.
     * {@code "popular"}, not {@code "stars/popular"}). This matches the short forms expected
     * by {@code ActressClassifierService.TIER_ORDER} and stored by
     * {@code applyMoveActressFolderFromAttention}.
     *
     * <p>For all other paths, the top-level folder name is used (e.g. {@code "queue"},
     * {@code "attention"}).
     *
     * <p>{@code fallback} is returned if the path has no segments.
     */
    public static String deriveShortPartitionId(Path newPath, String fallback) {
        if (newPath == null || newPath.getNameCount() == 0) return fallback;
        String top = newPath.getName(0).toString();
        if ("stars".equals(top) && newPath.getNameCount() >= 2) {
            return newPath.getName(1).toString(); // tier only, not "stars/<tier>"
        }
        return top;
    }

    /**
     * Replaces the suspect actress name in the last path segment with the canonical name.
     *
     * <p>Matches the name in any position (lead, mid, trailing) provided it is delimited by
     * actress-list separators:
     * <ul>
     *   <li>Left boundary: start-of-string, or {@code ", "} (comma-space) immediately before</li>
     *   <li>Right boundary: end-of-string, or {@code ", "} immediately after, or {@code " ("}
     *       (space + open-paren, the code suffix pattern) immediately after</li>
     * </ul>
     *
     * <p>Returns {@code null} if the segment doesn't contain a bounded match.
     */
    static Path computeNewPath(Path currentPath, String suspectName, String canonicalName) {
        String folderName = currentPath.getFileName().toString();
        int idx = findBoundedName(folderName, suspectName);
        if (idx < 0) return null;
        String newFolderName = folderName.substring(0, idx) + canonicalName
                + folderName.substring(idx + suspectName.length());
        Path parent = currentPath.getParent();
        return parent != null ? parent.resolve(newFolderName) : Path.of(newFolderName);
    }

    /**
     * Returns the start index of {@code name} inside {@code text} if it occurs at a valid
     * actress-list boundary, or {@code -1} if no such occurrence exists.
     *
     * <p>Valid left boundary: index 0, or the two characters before are {@code ", "}.
     * Valid right boundary: end-of-string, or a space character (e.g. before a code suffix
     * or extra token in a lead-position single-actress folder), or the next two characters
     * are {@code ", "} or {@code " ("}.
     *
     * <p>Allowing a trailing space preserves backward-compatible matching for lead-position
     * single-actress folders like {@code "Rin Hatchimitsu X (CODE-001)"} while still
     * rejecting sub-name matches like {@code "Aki"} inside {@code "Akina"} (where the char
     * after "Aki" is {@code 'n'}, not a space or separator).
     */
    static int findBoundedName(String text, String name) {
        int nameLen = name.length();
        int searchFrom = 0;
        while (searchFrom <= text.length() - nameLen) {
            int idx = text.indexOf(name, searchFrom);
            if (idx < 0) break;
            // Left boundary check
            boolean leftOk = (idx == 0) || (idx >= 2 && text.charAt(idx - 2) == ','
                    && text.charAt(idx - 1) == ' ');
            // Right boundary check
            int after = idx + nameLen;
            boolean rightOk = (after == text.length())
                    || text.charAt(after) == ' '
                    || (after + 1 < text.length() && text.charAt(after) == ','
                            && text.charAt(after + 1) == ' ');
            if (leftOk && rightOk) return idx;
            searchFrom = idx + 1;
        }
        return -1;
    }
}
