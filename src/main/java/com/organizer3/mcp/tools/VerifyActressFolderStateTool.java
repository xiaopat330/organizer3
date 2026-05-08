package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.filesystem.VolumeFileSystem;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;
import com.organizer3.model.Actress;
import com.organizer3.model.ActressAlias;
import com.organizer3.model.TitleLocation;
import com.organizer3.repository.ActressRepository;
import com.organizer3.repository.TitleLocationRepository;
import com.organizer3.shell.SessionContext;
import com.organizer3.smb.VolumeConnection;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.Jdbi;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Pre-flight verification for an actress's folder state on the mounted volume.
 *
 * <p>Returns blockers (must be resolved before composites can proceed) and warnings
 * (composites may proceed but should surface).
 *
 * <p>Read-only — no mutation, no curation log, no dryRun.
 */
@Slf4j
public class VerifyActressFolderStateTool implements Tool {

    private final SessionContext session;
    private final ActressRepository actressRepo;
    private final TitleLocationRepository locationRepo;
    private final Jdbi jdbi;

    public VerifyActressFolderStateTool(SessionContext session,
                                         ActressRepository actressRepo,
                                         TitleLocationRepository locationRepo,
                                         Jdbi jdbi) {
        this.session      = session;
        this.actressRepo  = actressRepo;
        this.locationRepo = locationRepo;
        this.jdbi         = jdbi;
    }

    @Override public String name() { return "verify_actress_folder_state"; }

    @Override
    public String description() {
        return "Pre-flight verification for an actress's folder(s) on the mounted volume. "
             + "Returns blockers (sync drift, stale rows, foreign-actress folders, duplicate "
             + "locations) and warnings (parent-name mismatch, layout anomalies, unparseable "
             + "basenames). Read-only — no mutation.";
    }

    @Override
    public JsonNode inputSchema() {
        return Schemas.object()
                .prop("actress_id", "integer", "Actress id to verify.")
                .require("actress_id")
                .build();
    }

    @Override
    public Object call(JsonNode args) throws IOException {
        long actressId = Schemas.requireLong(args, "actress_id");

        String volumeId = session.getMountedVolumeId();
        if (volumeId == null) {
            throw new IllegalArgumentException("No volume is currently mounted.");
        }
        VolumeConnection conn = session.getActiveConnection();
        if (conn == null || !conn.isConnected()) {
            throw new IllegalArgumentException("Active connection is closed; re-mount the volume.");
        }
        VolumeFileSystem fs = conn.fileSystem();

        Actress actress = actressRepo.findById(actressId)
                .orElseThrow(() -> new IllegalArgumentException("No actress found for id=" + actressId));

        List<String> aliases = actressRepo.findAliases(actress.getId()).stream()
                .map(ActressAlias::aliasName).toList();

        String canonical = actress.getCanonicalName();
        // Accepted name set: canonical + all aliases (case-insensitive)
        Set<String> acceptedNamesLower = buildAcceptedNames(canonical, aliases);

        log.info("verify_actress_folder_state actressId={} name={} volume={}", actressId, canonical, volumeId);

        List<Issue> blockers = new ArrayList<>();
        List<Issue> warnings = new ArrayList<>();

        // ── Load all title_locations for this actress on this volume ─────────
        // Both live and stale
        List<TitleLocRow> allRows = loadTitleLocations(actressId, volumeId);

        if (allRows.isEmpty()) {
            // No titles on this volume — nothing to verify
            return new Result(actressId, canonical, blockers, warnings);
        }

        // ── Stale-location-row: rows with stale_since IS NOT NULL ────────────
        for (TitleLocRow row : allRows) {
            if (row.isStale()) {
                blockers.add(new Issue("stale-location-row", "title", row.code(),
                        row.path(),
                        "title_locations row is marked stale (stale_since IS NOT NULL) for title "
                        + row.code() + " at path " + row.path()));
            }
        }

        // Work only with live rows from here
        List<TitleLocRow> liveRows = allRows.stream()
                .filter(r -> !r.isStale())
                .toList();

        // ── Multiple-locations-on-volume: group by title code, check duplicates
        Map<String, List<TitleLocRow>> byCode = liveRows.stream()
                .collect(Collectors.groupingBy(TitleLocRow::code,
                        LinkedHashMap::new, Collectors.toList()));

        for (Map.Entry<String, List<TitleLocRow>> entry : byCode.entrySet()) {
            if (entry.getValue().size() > 1) {
                String paths = entry.getValue().stream()
                        .map(TitleLocRow::path).collect(Collectors.joining(", "));
                blockers.add(new Issue("multiple-locations-on-volume", "title",
                        entry.getKey(), null,
                        "Title " + entry.getKey() + " has " + entry.getValue().size()
                        + " live title_locations rows on volume " + volumeId + ": " + paths));
            }
        }

        // ── Group titles by parent folder (the actress folder) ───────────────
        Map<Path, List<TitleLocRow>> byParent = new LinkedHashMap<>();
        for (TitleLocRow row : liveRows) {
            Path titleFolder = Path.of(row.path());
            Path parent = titleFolder.getParent();
            if (parent == null) parent = Path.of("/");
            byParent.computeIfAbsent(parent, k -> new ArrayList<>()).add(row);
        }

        // ── Per-parent-folder checks ─────────────────────────────────────────
        for (Map.Entry<Path, List<TitleLocRow>> parentEntry : byParent.entrySet()) {
            Path parentFolder = parentEntry.getKey();
            List<TitleLocRow> titlesInParent = parentEntry.getValue();

            String parentBasename = parentFolder.getFileName() != null
                    ? parentFolder.getFileName().toString() : parentFolder.toString();

            // parent-folder-name-mismatch: parent basename not in canonical ∪ aliases
            if (!acceptedNamesLower.contains(parentBasename.toLowerCase(Locale.ROOT))) {
                warnings.add(new Issue("parent-folder-name-mismatch", "actress", null,
                        parentFolder.toString(),
                        "Parent folder '" + parentBasename
                        + "' does not match canonical '" + canonical
                        + "' or any alias: " + aliases));
            }

            // foreign-actress-folder: ALL titles in this parent are credited to OTHER actresses
            boolean allForeign = titlesInParent.stream().allMatch(TitleLocRow::isForeignActress);
            if (allForeign && !titlesInParent.isEmpty()) {
                String codes = titlesInParent.stream()
                        .map(TitleLocRow::code).collect(Collectors.joining(", "));
                blockers.add(new Issue("foreign-actress-folder", "actress", null,
                        parentFolder.toString(),
                        "All titles in parent folder '" + parentBasename
                        + "' are credited to other actresses (not actressId=" + actressId
                        + "): " + codes));
            }
        }

        // ── Per-title checks ──────────────────────────────────────────────────
        for (TitleLocRow row : liveRows) {
            Path folder = Path.of(row.path());
            String basename = folder.getFileName() != null
                    ? folder.getFileName().toString() : folder.toString();

            // sync-drift-folder-missing: live row but folder absent on disk
            if (!fs.exists(folder) || !fs.isDirectory(folder)) {
                blockers.add(new Issue("sync-drift-folder-missing", "title", row.code(),
                        row.path(),
                        "title_locations row exists but folder is absent on disk: " + folder));
                continue;
            }

            // Layout anomalies
            ScanTitleFolderAnomaliesTool.LocationReport report =
                    TitleFolderAnomalyScanner.scan(folder, fs);
            mapAnomalies(report.anomalies(), row.code(), row.path(), warnings);

            // Basename parse
            BasenameParser.ParseResult parsed = BasenameParser.parse(basename);
            if (!parsed.isParseable()) {
                warnings.add(new Issue("basename-unparseable", "title", row.code(),
                        row.path(),
                        "Basename could not be parsed: '" + basename + "'"));
            }
        }

        log.info("verify_actress_folder_state actressId={} blockers={} warnings={}",
                actressId, blockers.size(), warnings.size());
        return new Result(actressId, canonical, blockers, warnings);
    }

    // ── Anomaly → warning mapping ─────────────────────────────────────────────

    private static void mapAnomalies(List<String> anomalies, String titleCode,
                                      String folderPath, List<Issue> warnings) {
        for (String anomaly : anomalies) {
            switch (anomaly) {
                case "multiple_base_covers" ->
                        warnings.add(new Issue("multi-cover-at-base", "title", titleCode,
                                folderPath, "Multiple cover images at the title folder base."));
                case "no_base_cover" ->
                        warnings.add(new Issue("no-base-cover", "title", titleCode,
                                folderPath, "No cover image found at the title folder base."));
                case "videos_at_base" ->
                        warnings.add(new Issue("videos-at-base", "title", titleCode,
                                folderPath, "Video files found at title folder base instead of a subfolder."));
                case "missing_or_not_directory" -> {} // handled as sync-drift-folder-missing
                default ->
                        warnings.add(new Issue("unexpected-child", "title", titleCode,
                                folderPath, "Unexpected folder layout issue: " + anomaly));
            }
        }
    }

    // ── DB helpers ─────────────────────────────────────────────────────────────

    /**
     * Loads title_locations rows for the given actress on the given volume,
     * joined with a flag indicating whether the title's primary actress is this actress.
     * "Foreign actress" = primary actress_id != actressId.
     */
    private List<TitleLocRow> loadTitleLocations(long actressId, String volumeId) {
        return jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT
                            t.code,
                            tl.path,
                            tl.stale_since,
                            CASE WHEN t.actress_id = :actressId THEN 0 ELSE 1 END AS is_foreign
                        FROM titles t
                        JOIN title_locations tl ON tl.title_id = t.id
                        WHERE t.actress_id = :actressId
                          AND tl.volume_id = :volumeId
                        ORDER BY tl.path
                        """)
                        .bind("actressId", actressId)
                        .bind("volumeId", volumeId)
                        .map((rs, ctx) -> new TitleLocRow(
                                rs.getString("code"),
                                rs.getString("path"),
                                rs.getString("stale_since") != null,
                                rs.getInt("is_foreign") == 1))
                        .list());
    }

    private record TitleLocRow(String code, String path, boolean isStale, boolean isForeignActress) {}

    // ── Name helpers ──────────────────────────────────────────────────────────

    private static Set<String> buildAcceptedNames(String canonical, List<String> aliases) {
        Set<String> names = new java.util.HashSet<>();
        names.add(canonical.toLowerCase(Locale.ROOT));
        for (String alias : aliases) {
            if (alias != null && !alias.isBlank()) {
                names.add(alias.toLowerCase(Locale.ROOT));
            }
        }
        return names;
    }

    // ── Output records ────────────────────────────────────────────────────────

    /**
     * An individual issue (blocker or warning) found during verification.
     *
     * @param kind      issue kind from the locked-in C5 taxonomy
     * @param scope     "actress" or "title"
     * @param titleCode title code this issue is about, or null for actress-scope issues
     * @param path      on-disk path associated with this issue, or null
     * @param detail    human-readable detail message
     */
    public record Issue(String kind, String scope, String titleCode, String path, String detail) {}

    public record Result(long actressId, String canonicalName,
                          List<Issue> blockers, List<Issue> warnings) {}
}
