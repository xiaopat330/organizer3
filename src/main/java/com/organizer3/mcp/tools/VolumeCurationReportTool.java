package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;
import com.organizer3.model.Actress;
import com.organizer3.model.ActressAlias;
import com.organizer3.notes.OrphanNoteFinder;
import com.organizer3.repository.ActressRepository;
import com.organizer3.shell.SessionContext;
import com.organizer3.smb.VolumeConnection;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.Jdbi;

import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * End-of-session dashboard aggregating Phase 3 discovery results into a single read-only call.
 *
 * <p>Sections:
 * <ol>
 *   <li>{@code misnamedParents} — actress folders whose parent path doesn't contain the canonical
 *       name (strict-mode B1 check).</li>
 *   <li>{@code driftedMultiActress} — title basenames whose parsed cast doesn't match DB credits
 *       (C3 logic).</li>
 *   <li>{@code fsOnlyTitles} — folders on disk with no DB row (C4 logic).</li>
 *   <li>{@code queueResidents} — title_locations whose path starts with {@code /queue/}.</li>
 *   <li>{@code duplicateBaseCodes} — titles sharing the same base_code, volume-scoped.</li>
 * </ol>
 *
 * <p>Each section reports {@code total} (full count, unbounded) and {@code results} capped at
 * {@code limit_per_section}, sorted most-severe-first within each section.
 *
 * <p>Read-only — no mutation, no curation log.
 */
@Slf4j
public class VolumeCurationReportTool implements Tool {

    private static final int DEFAULT_LIMIT = 200;

    private final SessionContext session;
    private final ActressRepository actressRepo;
    private final Jdbi jdbi;
    private final OrphanNoteFinder orphanNoteFinder;

    public VolumeCurationReportTool(SessionContext session,
                                     ActressRepository actressRepo,
                                     Jdbi jdbi) {
        this(session, actressRepo, jdbi, null);
    }

    public VolumeCurationReportTool(SessionContext session,
                                     ActressRepository actressRepo,
                                     Jdbi jdbi,
                                     OrphanNoteFinder orphanNoteFinder) {
        this.session          = session;
        this.actressRepo      = actressRepo;
        this.jdbi             = jdbi;
        this.orphanNoteFinder = orphanNoteFinder;
    }

    @Override public String name() { return "volume_curation_report"; }

    @Override
    public String description() {
        return "End-of-session dashboard. Aggregates misnamed actress parent folders, multi-actress "
             + "folder drift, FS-only titles, queue residents, and duplicate base codes into one "
             + "read-only call. Each section reports total + limit-capped results sorted most-severe-first. "
             + "Requires a mounted volume.";
    }

    @Override
    public JsonNode inputSchema() {
        return Schemas.object()
                .prop("volume_id", "string",
                        "Volume id. Defaults to the currently mounted volume.")
                .prop("limit_per_section", "integer",
                        "Maximum results per section. Default " + DEFAULT_LIMIT + ".", DEFAULT_LIMIT)
                .build();
    }

    @Override
    public Object call(JsonNode args) {
        String volumeIdArg = Schemas.optString(args, "volume_id", null);
        int limitPerSection = Math.max(1, Schemas.optInt(args, "limit_per_section", DEFAULT_LIMIT));

        String mountedVolumeId = session.getMountedVolumeId();
        String effectiveVolumeId = (volumeIdArg != null && !volumeIdArg.isBlank())
                ? volumeIdArg : mountedVolumeId;

        if (effectiveVolumeId == null) {
            throw new IllegalArgumentException("No volume mounted and no volume_id provided.");
        }
        if (!effectiveVolumeId.equals(mountedVolumeId)) {
            throw new IllegalArgumentException(
                    "volume_id '" + effectiveVolumeId + "' does not match mounted volume '"
                    + mountedVolumeId + "'.");
        }

        VolumeConnection conn = session.getActiveConnection();
        if (conn == null || !conn.isConnected()) {
            throw new IllegalArgumentException("No active volume connection. Mount the volume first.");
        }

        String generatedAt = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
        log.info("volume_curation_report starting volume={} limitPerSection={}",
                effectiveVolumeId, limitPerSection);

        MisnamedParentsSection misnamedParents =
                buildMisnamedParents(effectiveVolumeId, limitPerSection);
        DriftedMultiActressSection driftedMultiActress =
                buildDriftedMultiActress(effectiveVolumeId, limitPerSection);
        FsOnlyTitlesSection fsOnlyTitles =
                buildFsOnlyTitles(effectiveVolumeId, conn, limitPerSection);
        QueueResidentsSection queueResidents =
                buildQueueResidents(effectiveVolumeId, limitPerSection);
        DuplicateBaseCodesSection duplicateBaseCodes =
                buildDuplicateBaseCodes(effectiveVolumeId, limitPerSection);
        OrphanNotesSection orphanNotes = buildOrphanNotes();

        log.info("volume_curation_report done volume={} misnamedParents={} drifts={} fsOnly={} queue={} dupCodes={} orphanNotes={}",
                effectiveVolumeId,
                misnamedParents.total(),
                driftedMultiActress.total(),
                fsOnlyTitles.total(),
                queueResidents.total(),
                duplicateBaseCodes.total(),
                orphanNotes.count());

        return new Report(
                effectiveVolumeId,
                generatedAt,
                new Sections(misnamedParents, driftedMultiActress, fsOnlyTitles,
                             queueResidents, duplicateBaseCodes, orphanNotes));
    }

    // ── Section 1: misnamed actress parent folders ───────────────────────────

    private MisnamedParentsSection buildMisnamedParents(String volumeId, int limit) {
        // For each actress with on-volume titles, run strict B1 check.
        // B1 has no volume filter; we filter by volumeId post-call.
        // Pass max limit to B1 so cross-volume rows don't crowd out the on-volume ones.
        List<MisnamedParentRow> allRows = new ArrayList<>();

        List<Actress> actresses = actressRepo.findAll();
        for (Actress actress : actresses) {
            // Strict mode: only canonical-present paths (token-bounded) are clean.
            List<ActressAlias> aliases = actressRepo.findAliases(actress.getId());
            List<String> aliasNames = aliases.stream().map(ActressAlias::aliasName).toList();

            // Run strict misnamed check inline: same logic as FindMisnamedFoldersForActressTool
            // but limited to on-volume rows.
            List<FindMisnamedFoldersForActressTool.Row> misnamedOnVolume =
                    findMisnamedOnVolume(actress, aliasNames, volumeId);

            if (misnamedOnVolume.isEmpty()) continue;

            // Group by parent folder path (actress_id, parentFolderPath) → titleCount
            Map<String, Integer> parentCounts = new LinkedHashMap<>();
            for (FindMisnamedFoldersForActressTool.Row row : misnamedOnVolume) {
                String parent = parentOf(row.path());
                parentCounts.merge(parent, 1, (a, b) -> a + b);
            }

            for (Map.Entry<String, Integer> e : parentCounts.entrySet()) {
                allRows.add(new MisnamedParentRow(
                        actress.getId(),
                        actress.getCanonicalName(),
                        e.getKey(),
                        e.getValue()));
            }
        }

        // Sort by actress_id ascending (stable)
        allRows.sort((a, b) -> Long.compare(a.actressId(), b.actressId()));

        int total = allRows.size();
        List<MisnamedParentRow> results = allRows.size() > limit
                ? allRows.subList(0, limit)
                : allRows;
        return new MisnamedParentsSection(total, List.copyOf(results));
    }

    /**
     * Run the strict-mode misnamed-folder check for one actress, filtered to one volume.
     * Uses the same strict-pattern logic as {@link FindMisnamedFoldersForActressTool}.
     */
    private List<FindMisnamedFoldersForActressTool.Row> findMisnamedOnVolume(
            Actress actress, List<String> aliasNames, String volumeId) {

        var strictPattern = FindMisnamedFoldersForActressTool.buildBoundaryPattern(
                actress.getCanonicalName());

        return jdbi.withHandle(h -> {
            List<FindMisnamedFoldersForActressTool.Row> rows = new ArrayList<>();
            h.createQuery("""
                    SELECT t.code, tl.volume_id, tl.path
                    FROM titles t
                    JOIN title_locations tl ON tl.title_id = t.id
                    WHERE t.actress_id = :aid
                      AND tl.volume_id = :volumeId
                      AND tl.stale_since IS NULL
                    ORDER BY t.code
                    LIMIT 5000
                    """)
                    .bind("aid", actress.getId())
                    .bind("volumeId", volumeId)
                    .map((rs, ctx) -> {
                        String path = rs.getString("path");
                        // Reuse alias-match helper logic inline
                        String matchedAlias = findMatchedAlias(path, aliasNames);
                        return new FindMisnamedFoldersForActressTool.Row(
                                rs.getString("code"),
                                rs.getString("volume_id"),
                                path,
                                matchedAlias);
                    })
                    .filter(row -> !strictPattern.matcher(row.path()).find())
                    .forEach(rows::add);
            return rows;
        });
    }

    private static String findMatchedAlias(String path, List<String> aliases) {
        if (path == null) return null;
        String lower = path.toLowerCase(java.util.Locale.ROOT);
        for (String alias : aliases) {
            if (alias != null && !alias.isBlank()
                    && lower.contains(alias.toLowerCase(java.util.Locale.ROOT))) {
                return alias;
            }
        }
        return null;
    }

    private static String parentOf(String path) {
        if (path == null) return "";
        Path p = Path.of(path);
        Path parent = p.getParent();
        return parent != null ? parent.toString() : "";
    }

    // ── Section 2: drifted multi-actress folders ─────────────────────────────

    private DriftedMultiActressSection buildDriftedMultiActress(String volumeId, int limit) {
        // Instantiate the C3 tool and delegate to its logic via call().
        // The tool returns FindMultiActressFolderDriftTool.Result — map to report shape.
        FindMultiActressFolderDriftTool driftTool =
                new FindMultiActressFolderDriftTool(session, actressRepo, jdbi);

        com.fasterxml.jackson.databind.node.ObjectNode driftArgs =
                new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        driftArgs.put("volume_id", volumeId);

        FindMultiActressFolderDriftTool.Result driftResult =
                (FindMultiActressFolderDriftTool.Result) driftTool.call(driftArgs);

        // Already sorted by severity descending inside the tool.
        List<DriftedMultiActressRow> allRows = driftResult.drifts().stream()
                .map(d -> new DriftedMultiActressRow(
                        d.titleCode(),
                        d.folderPath(),
                        d.severity(),
                        List.copyOf(d.issues())))
                .toList();

        int total = allRows.size();
        List<DriftedMultiActressRow> results = allRows.size() > limit
                ? allRows.subList(0, limit)
                : allRows;
        return new DriftedMultiActressSection(total, List.copyOf(results));
    }

    // ── Section 3: FS-only titles ────────────────────────────────────────────

    private FsOnlyTitlesSection buildFsOnlyTitles(String volumeId, VolumeConnection conn, int limit) {
        FindFsOnlyTitlesTool fsOnlyTool = new FindFsOnlyTitlesTool(session, jdbi);

        com.fasterxml.jackson.databind.node.ObjectNode fsArgs =
                new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        fsArgs.put("volume_id", volumeId);

        FindFsOnlyTitlesTool.Result fsResult =
                (FindFsOnlyTitlesTool.Result) fsOnlyTool.call(fsArgs);

        List<FsOnlyTitleRow> allRows = fsResult.results().stream()
                .map(r -> new FsOnlyTitleRow(
                        r.folderPath(),
                        r.parsedCode(),
                        r.sizeBytes(),
                        r.childFileCount()))
                .toList();

        int total = allRows.size();
        List<FsOnlyTitleRow> results = allRows.size() > limit
                ? allRows.subList(0, limit)
                : allRows;
        return new FsOnlyTitlesSection(total, List.copyOf(results));
    }

    // ── Section 4: queue residents ───────────────────────────────────────────

    private QueueResidentsSection buildQueueResidents(String volumeId, int limit) {
        // Find title_locations on this volume whose path starts with /queue/
        record LocRow(long titleId, String titleCode, String path) {}
        List<LocRow> locs = jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT t.id, t.code, tl.path
                        FROM title_locations tl
                        JOIN titles t ON t.id = tl.title_id
                        WHERE tl.volume_id = :volumeId
                          AND tl.stale_since IS NULL
                          AND (tl.path LIKE '/queue/%' OR tl.path = '/queue')
                        ORDER BY t.code
                        """)
                        .bind("volumeId", volumeId)
                        .map((rs, ctx) -> new LocRow(
                                rs.getLong("id"),
                                rs.getString("code"),
                                rs.getString("path")))
                        .list());

        List<QueueResidentRow> allRows = new ArrayList<>();
        for (LocRow loc : locs) {
            List<String> actresses = loadCanonicalActresses(loc.titleId());
            allRows.add(new QueueResidentRow(loc.titleCode(), loc.path(), actresses));
        }

        int total = allRows.size();
        List<QueueResidentRow> results = allRows.size() > limit
                ? allRows.subList(0, limit)
                : allRows;
        return new QueueResidentsSection(total, List.copyOf(results));
    }

    private List<String> loadCanonicalActresses(long titleId) {
        // Primary actress
        List<String> names = new ArrayList<>(jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT a.canonical_name
                        FROM titles t
                        JOIN actresses a ON a.id = t.actress_id
                        WHERE t.id = :titleId AND t.actress_id IS NOT NULL
                        """)
                        .bind("titleId", titleId)
                        .mapTo(String.class)
                        .list()));

        // Additional actresses
        names.addAll(jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT a.canonical_name
                        FROM title_actresses ta
                        JOIN actresses a ON a.id = ta.actress_id
                        WHERE ta.title_id = :titleId
                        ORDER BY ta.actress_id
                        """)
                        .bind("titleId", titleId)
                        .mapTo(String.class)
                        .list()));

        return List.copyOf(names);
    }

    // ── Section 5: duplicate base codes (volume-scoped) ──────────────────────

    private DuplicateBaseCodesSection buildDuplicateBaseCodes(String volumeId, int limit) {
        // Find all titles on this volume that share a base_code with another title on this volume.
        // We want (titleCode, paths) pairs — one row per title code, with all on-volume paths.
        record TitleLocRow(String code, String baseCode, String path) {}

        List<TitleLocRow> rows = jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT t.code, t.base_code, tl.path
                        FROM titles t
                        JOIN title_locations tl ON tl.title_id = t.id
                        WHERE tl.volume_id = :volumeId
                          AND tl.stale_since IS NULL
                          AND t.base_code IS NOT NULL
                          AND t.base_code IN (
                              SELECT t2.base_code
                              FROM titles t2
                              JOIN title_locations tl2 ON tl2.title_id = t2.id
                              WHERE tl2.volume_id = :volumeId
                                AND tl2.stale_since IS NULL
                                AND t2.base_code IS NOT NULL
                              GROUP BY t2.base_code
                              HAVING COUNT(DISTINCT t2.id) > 1
                          )
                        ORDER BY t.base_code, t.code, tl.path
                        """)
                        .bind("volumeId", volumeId)
                        .map((rs, ctx) -> new TitleLocRow(
                                rs.getString("code"),
                                rs.getString("base_code"),
                                rs.getString("path")))
                        .list());

        // Group by titleCode → paths
        Map<String, List<String>> codeToPathsMap = new LinkedHashMap<>();
        Map<String, String> codeToBaseCode = new LinkedHashMap<>();
        for (TitleLocRow row : rows) {
            codeToPathsMap.computeIfAbsent(row.code(), k -> new ArrayList<>()).add(row.path());
            codeToBaseCode.put(row.code(), row.baseCode());
        }

        // Group by base_code → list of titleCodes with paths
        Map<String, List<DupCodeTitleEntry>> baseCodeGroups = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : codeToPathsMap.entrySet()) {
            String code = e.getKey();
            String baseCode = codeToBaseCode.get(code);
            baseCodeGroups.computeIfAbsent(baseCode, k -> new ArrayList<>())
                    .add(new DupCodeTitleEntry(code, List.copyOf(e.getValue())));
        }

        List<DuplicateBaseCodeRow> allRows = new ArrayList<>();
        for (Map.Entry<String, List<DupCodeTitleEntry>> e : baseCodeGroups.entrySet()) {
            if (e.getValue().size() >= 2) {
                allRows.add(new DuplicateBaseCodeRow(e.getKey(), List.copyOf(e.getValue())));
            }
        }

        int total = allRows.size();
        List<DuplicateBaseCodeRow> results = allRows.size() > limit
                ? allRows.subList(0, limit)
                : allRows;
        return new DuplicateBaseCodesSection(total, List.copyOf(results));
    }

    // ── Section 6: orphan notes ──────────────────────────────────────────────

    private OrphanNotesSection buildOrphanNotes() {
        if (orphanNoteFinder == null) {
            return new OrphanNotesSection(0, List.of());
        }
        List<OrphanNoteFinder.OrphanNote> orphans = orphanNoteFinder.findAll();
        int count = orphans.size();
        List<OrphanNotesPeekRow> peek = orphans.stream()
                .limit(5)
                .map(o -> new OrphanNotesPeekRow(o.entityType().wireValue(), o.entityId(), o.body()))
                .toList();
        return new OrphanNotesSection(count, peek);
    }

    // ── Output records ────────────────────────────────────────────────────────

    public record MisnamedParentRow(
            long actressId,
            String canonicalName,
            String parentFolderPath,
            int titleCount
    ) {}

    public record MisnamedParentsSection(int total, List<MisnamedParentRow> results) {}

    public record DriftedMultiActressRow(
            String titleCode,
            String folderPath,
            double severity,
            List<String> issues
    ) {}

    public record DriftedMultiActressSection(int total, List<DriftedMultiActressRow> results) {}

    public record FsOnlyTitleRow(
            String folderPath,
            String parsedCode,
            long sizeBytes,
            int childFileCount
    ) {}

    public record FsOnlyTitlesSection(int total, List<FsOnlyTitleRow> results) {}

    public record QueueResidentRow(
            String titleCode,
            String folderPath,
            List<String> dbCanonicalActresses
    ) {}

    public record QueueResidentsSection(int total, List<QueueResidentRow> results) {}

    public record DupCodeTitleEntry(String titleCode, List<String> paths) {}

    public record DuplicateBaseCodeRow(String baseCode, List<DupCodeTitleEntry> titles) {}

    public record DuplicateBaseCodesSection(int total, List<DuplicateBaseCodeRow> results) {}

    public record OrphanNotesPeekRow(String entityType, String entityId, String body) {}

    public record OrphanNotesSection(int count, List<OrphanNotesPeekRow> peek) {}

    public record Sections(
            MisnamedParentsSection misnamedParents,
            DriftedMultiActressSection driftedMultiActress,
            FsOnlyTitlesSection fsOnlyTitles,
            QueueResidentsSection queueResidents,
            DuplicateBaseCodesSection duplicateBaseCodes,
            OrphanNotesSection orphanNotes
    ) {}

    public record Report(
            String mountedVolumeId,
            String generatedAt,
            Sections sections
    ) {}
}
