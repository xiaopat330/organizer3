package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.filesystem.VolumeFileSystem;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;
import com.organizer3.model.Actress;
import com.organizer3.model.ActressAlias;
import com.organizer3.model.Title;
import com.organizer3.model.TitleLocation;
import com.organizer3.repository.ActressRepository;
import com.organizer3.repository.TitleLocationRepository;
import com.organizer3.repository.TitleRepository;
import com.organizer3.shell.SessionContext;
import com.organizer3.smb.VolumeConnection;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.Jdbi;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Pre-flight verification for a single title folder on the mounted volume.
 *
 * <p>Returns blockers (must be resolved before composites can proceed) and warnings
 * (composites may proceed but should surface).
 *
 * <p>Read-only — no mutation, no curation log, no dryRun.
 */
@Slf4j
public class VerifyTitleFolderStateTool implements Tool {

    private final SessionContext session;
    private final TitleRepository titleRepo;
    private final TitleLocationRepository locationRepo;
    private final ActressRepository actressRepo;
    private final Jdbi jdbi;

    public VerifyTitleFolderStateTool(SessionContext session,
                                       TitleRepository titleRepo,
                                       TitleLocationRepository locationRepo,
                                       ActressRepository actressRepo,
                                       Jdbi jdbi) {
        this.session     = session;
        this.titleRepo   = titleRepo;
        this.locationRepo = locationRepo;
        this.actressRepo = actressRepo;
        this.jdbi        = jdbi;
    }

    @Override public String name() { return "verify_title_folder_state"; }

    @Override
    public String description() {
        return "Pre-flight verification for a single title folder on the mounted volume. "
             + "Returns blockers (sync drift, duplicate locations, stale rows) and warnings "
             + "(layout anomalies, unparseable basename, credit mismatches). "
             + "Read-only — no mutation.";
    }

    @Override
    public JsonNode inputSchema() {
        return Schemas.object()
                .prop("titleCode", "string", "Title code to verify.")
                .require("titleCode")
                .build();
    }

    @Override
    public Object call(JsonNode args) throws IOException {
        String titleCode = Schemas.requireString(args, "titleCode");

        String volumeId = session.getMountedVolumeId();
        if (volumeId == null) {
            throw new IllegalArgumentException("No volume is currently mounted.");
        }
        VolumeConnection conn = session.getActiveConnection();
        if (conn == null || !conn.isConnected()) {
            throw new IllegalArgumentException("Active connection is closed; re-mount the volume.");
        }
        VolumeFileSystem fs = conn.fileSystem();

        log.info("verify_title_folder_state titleCode={} volume={}", titleCode, volumeId);

        Title title = titleRepo.findByCode(titleCode)
                .orElseThrow(() -> new IllegalArgumentException("No title with code " + titleCode));

        // All locations for this title across volumes — include stale rows so we can flag them
        List<TitleLocation> allLocations = locationRepo.findByTitle(title.getId(), true);

        // Split by volume: live (stale_since IS NULL) vs stale (stale_since IS NOT NULL)
        List<TitleLocation> liveOnVolume  = allLocations.stream()
                .filter(l -> l.getVolumeId().equals(volumeId) && !l.isStale())
                .toList();
        List<TitleLocation> staleOnVolume = allLocations.stream()
                .filter(l -> l.getVolumeId().equals(volumeId) && l.isStale())
                .toList();

        List<Issue> blockers = new ArrayList<>();
        List<Issue> warnings = new ArrayList<>();

        // ── Stale-location-row: DB has stale rows on this volume ──────────────
        for (TitleLocation stale : staleOnVolume) {
            blockers.add(new Issue("stale-location-row", "title", titleCode,
                    stale.getPath().toString(),
                    "title_locations row is marked stale (stale_since IS NOT NULL) at path "
                    + stale.getPath()));
        }

        // If no live rows, check whether it's just plain missing
        if (liveOnVolume.isEmpty() && staleOnVolume.isEmpty()) {
            // No rows at all on this volume — nothing to verify
            return new Result(titleCode, blockers, warnings);
        }

        // ── Multiple-locations-on-volume: more than one live row ──────────────
        if (liveOnVolume.size() > 1) {
            blockers.add(new Issue("multiple-locations-on-volume", "title", titleCode,
                    null,
                    "Title has " + liveOnVolume.size() + " live title_locations rows on volume "
                    + volumeId + ": " + liveOnVolume.stream()
                            .map(l -> l.getPath().toString()).collect(Collectors.joining(", "))));
        }

        // ── Per-live-location checks ──────────────────────────────────────────
        // Load DB credits once
        List<DbCredit> dbCredits = loadCredits(title.getId());

        for (TitleLocation loc : liveOnVolume) {
            Path folder = loc.getPath();
            String basename = folder.getFileName() != null
                    ? folder.getFileName().toString() : folder.toString();

            // Sync-drift-folder-missing: live row but on-disk folder absent
            if (!fs.exists(folder) || !fs.isDirectory(folder)) {
                blockers.add(new Issue("sync-drift-folder-missing", "title", titleCode,
                        folder.toString(),
                        "title_locations row exists but folder is absent on disk: " + folder));
                continue; // No point scanning a missing folder
            }

            // Layout anomalies via shared scanner
            ScanTitleFolderAnomaliesTool.LocationReport report =
                    TitleFolderAnomalyScanner.scan(folder, fs);
            mapAnomalies(report.anomalies(), titleCode, folder.toString(), warnings);

            // Basename parse check
            BasenameParser.ParseResult parsed = BasenameParser.parse(basename);
            if (!parsed.isParseable()) {
                warnings.add(new Issue("basename-unparseable", "title", titleCode,
                        folder.toString(),
                        "Basename could not be parsed: '" + basename + "'"));
            } else {
                // db-credit-mismatch: any resolved cast token is an actress NOT in DB credits
                checkDbCreditMismatch(parsed, dbCredits, titleCode, folder.toString(), warnings);
            }
        }

        log.info("verify_title_folder_state titleCode={} blockers={} warnings={}",
                titleCode, blockers.size(), warnings.size());
        return new Result(titleCode, blockers, warnings);
    }

    // ── DB credit mismatch check ──────────────────────────────────────────────

    private void checkDbCreditMismatch(BasenameParser.ParseResult parsed,
                                        List<DbCredit> dbCredits,
                                        String titleCode,
                                        String folderPath,
                                        List<Issue> warnings) {
        if (parsed.castTokens().isEmpty() || dbCredits.isEmpty()) return;

        // Build a set of all DB actress ids
        Set<Long> dbActressIds = dbCredits.stream()
                .map(DbCredit::actressId)
                .collect(Collectors.toSet());

        // Resolve cast tokens via the name resolver
        NameResolver resolver = buildNameResolver();
        for (String token : parsed.castTokens()) {
            Long resolvedId = resolver.resolve(token);
            if (resolvedId != null && !dbActressIds.contains(resolvedId)) {
                String resolvedName = resolver.nameFor(resolvedId);
                warnings.add(new Issue("db-credit-mismatch", "title", titleCode,
                        folderPath,
                        "Basename cast token '" + token + "' resolves to actress '"
                        + resolvedName + "' (id=" + resolvedId
                        + ") which is not in DB credits for title " + titleCode));
            }
        }
    }

    // ── Anomaly → warning mapping ─────────────────────────────────────────────

    /**
     * Maps {@link TitleFolderAnomalyScanner} anomaly codes to the C5 warning taxonomy.
     * Other anomaly codes (empty_folder, covers_in_subfolder, multiple_video_subfolders,
     * missing_or_not_directory) are emitted as unexpected-child warnings.
     */
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
                                folderPath, "Video files found at the title folder base instead of in a subfolder."));
                case "missing_or_not_directory" -> {} // handled as sync-drift-folder-missing above
                default ->
                        warnings.add(new Issue("unexpected-child", "title", titleCode,
                                folderPath, "Unexpected folder layout issue: " + anomaly));
            }
        }
    }

    // ── DB helpers ─────────────────────────────────────────────────────────────

    private record DbCredit(long actressId, String canonicalName) {}

    private List<DbCredit> loadCredits(long titleId) {
        record PrimaryRow(Long actressId, String canonicalName) {}
        List<PrimaryRow> primary = jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT t.actress_id, a.canonical_name
                        FROM titles t
                        LEFT JOIN actresses a ON a.id = t.actress_id
                        WHERE t.id = :titleId AND t.actress_id IS NOT NULL
                        """)
                        .bind("titleId", titleId)
                        .map((rs, ctx) -> new PrimaryRow(rs.getLong("actress_id"), rs.getString("canonical_name")))
                        .list());

        record ExtraRow(long actressId, String canonicalName) {}
        String excludeClause = primary.isEmpty() ? "" : " AND ta.actress_id != " + primary.get(0).actressId();
        List<ExtraRow> extras = jdbi.withHandle(h ->
                h.createQuery("SELECT ta.actress_id, a.canonical_name "
                        + "FROM title_actresses ta "
                        + "JOIN actresses a ON a.id = ta.actress_id "
                        + "WHERE ta.title_id = :titleId" + excludeClause
                        + " ORDER BY ta.actress_id")
                        .bind("titleId", titleId)
                        .map((rs, ctx) -> new ExtraRow(rs.getLong("actress_id"), rs.getString("canonical_name")))
                        .list());

        List<DbCredit> result = new ArrayList<>();
        for (PrimaryRow p : primary) {
            result.add(new DbCredit(p.actressId(), p.canonicalName()));
        }
        for (ExtraRow e : extras) {
            result.add(new DbCredit(e.actressId(), e.canonicalName()));
        }
        return result;
    }

    // ── Name resolver ─────────────────────────────────────────────────────────

    private NameResolver buildNameResolver() {
        NameResolver resolver = new NameResolver();
        for (Actress actress : actressRepo.findAll()) {
            List<String> aliasNames = actressRepo.findAliases(actress.getId()).stream()
                    .map(ActressAlias::aliasName).toList();
            resolver.add(actress.getId(), actress.getCanonicalName(), aliasNames);
        }
        return resolver;
    }

    private static class NameResolver {
        private final Map<String, Long> byName = new HashMap<>();   // lowercase name/alias → id
        private final Map<Long, String> idToName = new HashMap<>(); // id → canonical name

        void add(long id, String canonical, List<String> aliases) {
            idToName.put(id, canonical);
            byName.put(canonical.toLowerCase(Locale.ROOT), id);
            for (String alias : aliases) {
                if (alias != null && !alias.isBlank()) {
                    byName.put(alias.toLowerCase(Locale.ROOT), id);
                }
            }
        }

        Long resolve(String token) {
            if (token == null || token.isBlank()) return null;
            return byName.get(token.toLowerCase(Locale.ROOT));
        }

        String nameFor(long id) {
            return idToName.getOrDefault(id, "id=" + id);
        }
    }

    // ── Output records ────────────────────────────────────────────────────────

    /**
     * An individual issue (blocker or warning) found during verification.
     *
     * @param kind      issue kind from the locked-in C5 taxonomy
     * @param scope     always "title" for this tool
     * @param titleCode title code this issue is about (may be null for actress-scope issues)
     * @param path      on-disk path associated with this issue, or null
     * @param detail    human-readable detail message
     */
    public record Issue(String kind, String scope, String titleCode, String path, String detail) {}

    public record Result(String titleCode, List<Issue> blockers, List<Issue> warnings) {}
}
