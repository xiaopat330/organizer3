package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;
import com.organizer3.model.ActressAlias;
import com.organizer3.repository.ActressRepository;
import com.organizer3.shell.SessionContext;
import com.organizer3.smb.VolumeConnection;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.Jdbi;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Scans every title folder on a volume; parses the basename as a cast list, description, code,
 * and trailing tag; compares parsed cast positions against DB credits.
 *
 * <p>Surfaces misspelled positions, missing/extra cast members, non-standard separators, ambiguous
 * codes, and unparseable basenames. Severity is the weighted sum of issue weights.
 *
 * <p>Only title-shaped folders (directly containing video files) are considered. The parser is
 * shared with {@link FindFsOnlyTitlesTool} via {@link BasenameParser}.
 *
 * <p>Read-only — no mutation, no curation log.
 */
@Slf4j
public class FindMultiActressFolderDriftTool implements Tool {

    // ── Severity weights (locked-in from spec) ───────────────────────────────
    private static final double W_MISSPELLED_POSITION   = 1.0;
    private static final double W_MISSING_CAST_MEMBER   = 0.5;
    private static final double W_EXTRA_CAST_MEMBER     = 0.5;
    private static final double W_UNRESOLVABLE_NAME     = 0.8;
    private static final double W_CODE_MISMATCH         = 2.0;
    private static final double W_NON_STANDARD_SEP      = 0.1;
    private static final double W_AMBIGUOUS_CODE        = 0.5;
    private static final double W_UNPARSEABLE           = 1.5;

    private final SessionContext session;
    private final ActressRepository actressRepo;
    private final Jdbi jdbi;

    public FindMultiActressFolderDriftTool(SessionContext session,
                                            ActressRepository actressRepo,
                                            Jdbi jdbi) {
        this.session     = session;
        this.actressRepo = actressRepo;
        this.jdbi        = jdbi;
    }

    @Override public String name() { return "find_multi_actress_folder_drift"; }

    @Override
    public String description() {
        return "Scans every title folder on the mounted volume; parses each basename as a cast "
             + "list, description, code, and trailing tag; compares parsed cast positions against "
             + "DB credits. Surfaces misspelled positions, missing/extra cast members, "
             + "non-standard separators, ambiguous codes, and unparseable basenames. "
             + "Sorted by severity (descending).";
    }

    @Override
    public JsonNode inputSchema() {
        return Schemas.object()
                .prop("volume_id", "string", "Volume id. Defaults to the currently mounted volume.")
                .build();
    }

    @Override
    public Object call(JsonNode args) {
        String volumeIdArg = Schemas.optString(args, "volume_id", null);

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

        log.info("find_multi_actress_folder_drift starting volume={}", effectiveVolumeId);

        // ── Build actress name lookup maps ────────────────────────────────────
        NameResolver resolver = buildNameResolver();

        // ── Walk the volume and collect title folders ────────────────────────
        // We re-use all title_locations for this volume to get the folder paths.
        // This avoids a slow full-tree FS walk; DB is the authoritative source of title folders.
        List<TitleFolder> titleFolders = loadTitleFolders(effectiveVolumeId);
        log.info("find_multi_actress_folder_drift processing {} title folders on volume={}",
                titleFolders.size(), effectiveVolumeId);

        List<DriftRecord> drifts = new ArrayList<>();

        for (TitleFolder tf : titleFolders) {
            String basename = tf.folderBasename();
            String dbCode   = tf.code();
            List<DbCredit> dbCredits = tf.credits();

            BasenameParser.ParseResult parsed = BasenameParser.parse(basename);

            List<String> issues   = new ArrayList<>(parsed.warnings()); // non-standard-sep, ambiguous-code
            double severity = 0.0;

            // Severity from parse warnings
            for (String w : parsed.warnings()) {
                severity += weightFor(w);
            }

            // If unparseable, emit record and move on
            if (!parsed.isParseable()) {
                drifts.add(new DriftRecord(
                        dbCode, tf.folderPath(),
                        new ParsedInfo(List.of(), null, null, null),
                        dbCredits, issues, severity));
                continue;
            }

            // Code mismatch check
            String parsedCode = parsed.code();
            if (dbCode != null && !dbCode.equalsIgnoreCase(parsedCode)) {
                issues.add("code-mismatch");
                severity += W_CODE_MISMATCH;
            }

            // Resolve parsed cast tokens
            List<CastEntry> castEntries = new ArrayList<>();
            for (int pos = 0; pos < parsed.castTokens().size(); pos++) {
                String raw = parsed.castTokens().get(pos);
                ResolvedActress resolved = resolver.resolve(raw);
                CastEntry entry;
                if (resolved != null) {
                    entry = new CastEntry(pos, raw, resolved.actressId(), resolved.via(), null, null);
                } else {
                    // Find closest actress for hint
                    Closest closest = resolver.findClosest(raw);
                    entry = new CastEntry(pos, raw, null, "unresolved",
                            closest != null ? closest.name() : null,
                            closest != null ? closest.distance() : null);
                    issues.add("unresolvable-name");
                    severity += W_UNRESOLVABLE_NAME;
                }
                castEntries.add(entry);
            }

            // Compare parsed cast vs DB credits (position-aware)
            // Only do this comparison when both sides have creditable data
            if (!dbCredits.isEmpty() || !castEntries.isEmpty()) {
                {
                    // Check misspelled-position: parsed token at position N resolves to actress X,
                    // but DB credits actress Y at the same position
                    for (CastEntry ce : castEntries) {
                        if (ce.resolvedActressId() == null) continue; // handled as unresolvable
                        if (ce.position() < dbCredits.size()) {
                            long dbActressId = dbCredits.get(ce.position()).actressId();
                            if (dbActressId != ce.resolvedActressId()) {
                                issues.add("misspelled-position");
                                severity += W_MISSPELLED_POSITION;
                            }
                        }
                    }

                    // missing-cast-member: DB has actress not present in basename
                    for (DbCredit dbCredit : dbCredits) {
                        boolean found = castEntries.stream()
                                .anyMatch(ce -> ce.resolvedActressId() != null
                                        && ce.resolvedActressId() == dbCredit.actressId());
                        if (!found) {
                            issues.add("missing-cast-member");
                            severity += W_MISSING_CAST_MEMBER;
                        }
                    }

                    // extra-cast-member: basename has actress not in DB credits
                    for (CastEntry ce : castEntries) {
                        if (ce.resolvedActressId() == null) continue; // already flagged as unresolvable
                        boolean foundInDb = dbCredits.stream()
                                .anyMatch(dc -> dc.actressId() == ce.resolvedActressId());
                        if (!foundInDb) {
                            issues.add("extra-cast-member");
                            severity += W_EXTRA_CAST_MEMBER;
                        }
                    }
                }
            }

            // Only include records with issues
            if (issues.isEmpty()) continue;

            // De-duplicate issues
            List<String> deduplicated = issues.stream().distinct().toList();
            drifts.add(new DriftRecord(
                    dbCode, tf.folderPath(),
                    new ParsedInfo(castEntries, parsed.description(), parsed.code(), parsed.trailingTag()),
                    dbCredits, deduplicated, severity));
        }

        // Sort descending by severity
        drifts.sort((a, b) -> Double.compare(b.severity(), a.severity()));

        log.info("find_multi_actress_folder_drift found {} drifts on volume={}", drifts.size(), effectiveVolumeId);
        return new Result(effectiveVolumeId, drifts);
    }

    // ── Name resolver ─────────────────────────────────────────────────────────

    private record ResolvedActress(long actressId, String via) {}
    private record Closest(String name, int distance) {}

    private static class NameResolver {
        // canonical name (lowercase) → actress id
        private final Map<String, Long> canonicalMap = new HashMap<>();
        // alias (lowercase) → actress id
        private final Map<String, Long> aliasMap     = new HashMap<>();
        // actress id → canonical name
        private final Map<Long, String> idToName     = new HashMap<>();

        void addActress(long id, String canonical, List<String> aliases) {
            idToName.put(id, canonical);
            canonicalMap.put(canonical.toLowerCase(Locale.ROOT), id);
            for (String alias : aliases) {
                if (alias != null && !alias.isBlank()) {
                    aliasMap.put(alias.toLowerCase(Locale.ROOT), id);
                }
            }
        }

        ResolvedActress resolve(String token) {
            if (token == null || token.isBlank()) return null;
            String lower = token.toLowerCase(Locale.ROOT);
            Long id = canonicalMap.get(lower);
            if (id != null) return new ResolvedActress(id, "canonical");
            id = aliasMap.get(lower);
            if (id != null) return new ResolvedActress(id, "alias");
            return null;
        }

        Closest findClosest(String token) {
            if (token == null || token.isBlank()) return null;
            String lowerToken = token.toLowerCase(Locale.ROOT);
            String bestName = null;
            int bestDist = Integer.MAX_VALUE;
            for (Map.Entry<String, Long> e : canonicalMap.entrySet()) {
                int d = levenshtein(lowerToken, e.getKey());
                if (d < bestDist) {
                    bestDist = d;
                    bestName = idToName.get(e.getValue());
                }
            }
            for (Map.Entry<String, Long> e : aliasMap.entrySet()) {
                int d = levenshtein(lowerToken, e.getKey());
                if (d < bestDist) {
                    bestDist = d;
                    bestName = idToName.get(e.getValue());
                }
            }
            return (bestName != null) ? new Closest(bestName, bestDist) : null;
        }
    }

    private NameResolver buildNameResolver() {
        NameResolver resolver = new NameResolver();
        for (var actress : actressRepo.findAll()) {
            List<String> aliasNames = actressRepo.findAliases(actress.getId()).stream()
                    .map(ActressAlias::aliasName).toList();
            resolver.addActress(actress.getId(), actress.getCanonicalName(), aliasNames);
        }
        return resolver;
    }

    // ── Title folder loading ──────────────────────────────────────────────────

    private record DbCredit(long actressId, String canonicalName, int position) {}

    private record TitleFolder(String code, String folderPath, String folderBasename,
                                List<DbCredit> credits) {}

    /**
     * Load all title folders for the given volume from the DB.
     * For each title, load all DB credits (primary actress + title_actresses),
     * ordered by position (primary=0, then by actress_id for determinism).
     */
    private List<TitleFolder> loadTitleFolders(String volumeId) {
        // Collect all title_locations for this volume
        record LocRow(long titleId, String code, String path) {}
        List<LocRow> locs = jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT t.id, t.code, tl.path
                        FROM titles t
                        JOIN title_locations tl ON tl.title_id = t.id
                        WHERE tl.volume_id = :volumeId
                          AND tl.stale_since IS NULL
                        ORDER BY tl.path
                        """)
                        .bind("volumeId", volumeId)
                        .map((rs, ctx) -> new LocRow(
                                rs.getLong("id"),
                                rs.getString("code"),
                                rs.getString("path")))
                        .list());

        List<TitleFolder> result = new ArrayList<>();
        for (LocRow loc : locs) {
            List<DbCredit> credits = loadCredits(loc.titleId());
            Path folderPath = Path.of(loc.path());
            String basename = folderPath.getFileName() != null
                    ? folderPath.getFileName().toString() : loc.path();
            result.add(new TitleFolder(loc.code(), loc.path(), basename, credits));
        }
        return result;
    }

    /**
     * Load all DB credits for a title, ordered by position (primary actress=0,
     * then additional actresses from title_actresses ordered by actress_id).
     * Returns canonical name alongside actress_id.
     */
    private List<DbCredit> loadCredits(long titleId) {
        // Primary actress
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

        // Additional actresses from title_actresses (multi-actress)
        record ExtraRow(long actressId, String canonicalName) {}
        List<ExtraRow> extras = jdbi.withHandle(h -> {
            String excludeIds = primary.isEmpty() ? "" : " AND ta.actress_id != " + primary.get(0).actressId();
            return h.createQuery(
                    "SELECT ta.actress_id, a.canonical_name " +
                    "FROM title_actresses ta " +
                    "JOIN actresses a ON a.id = ta.actress_id " +
                    "WHERE ta.title_id = :titleId" + excludeIds + " " +
                    "ORDER BY ta.actress_id")
                    .bind("titleId", titleId)
                    .map((rs, ctx) -> new ExtraRow(rs.getLong("actress_id"), rs.getString("canonical_name")))
                    .list();
        });

        List<DbCredit> credits = new ArrayList<>();
        for (PrimaryRow p : primary) {
            if (p.actressId() != null) {
                credits.add(new DbCredit(p.actressId(), p.canonicalName(), 0));
            }
        }
        for (int i = 0; i < extras.size(); i++) {
            credits.add(new DbCredit(extras.get(i).actressId(), extras.get(i).canonicalName(), credits.size()));
        }
        return credits;
    }

    // ── Levenshtein ──────────────────────────────────────────────────────────

    static int levenshtein(String a, String b) {
        int n = a.length(), m = b.length();
        if (n == 0) return m;
        if (m == 0) return n;
        int[] prev = new int[m + 1];
        int[] curr = new int[m + 1];
        for (int j = 0; j <= m; j++) prev[j] = j;
        for (int i = 1; i <= n; i++) {
            curr[0] = i;
            char ca = a.charAt(i - 1);
            for (int j = 1; j <= m; j++) {
                int cost = (ca == b.charAt(j - 1)) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev; prev = curr; curr = tmp;
        }
        return prev[m];
    }

    private static double weightFor(String issue) {
        return switch (issue) {
            case "misspelled-position"   -> W_MISSPELLED_POSITION;
            case "missing-cast-member"   -> W_MISSING_CAST_MEMBER;
            case "extra-cast-member"     -> W_EXTRA_CAST_MEMBER;
            case "unresolvable-name"     -> W_UNRESOLVABLE_NAME;
            case "code-mismatch"         -> W_CODE_MISMATCH;
            case "non-standard-separator" -> W_NON_STANDARD_SEP;
            case "ambiguous-code"        -> W_AMBIGUOUS_CODE;
            case "unparseable-basename"  -> W_UNPARSEABLE;
            default                      -> 0.0;
        };
    }

    // ── Output records ────────────────────────────────────────────────────────

    public record CastEntry(
            int position,
            String raw,
            Long resolvedActressId,
            String resolvedVia,          // "canonical" | "alias" | "unresolved"
            String closestActress,
            Integer closestDistance
    ) {}

    public record ParsedInfo(
            List<CastEntry> cast,
            String description,
            String code,
            String trailingTag
    ) {}

    public record DriftRecord(
            String titleCode,
            String folderPath,
            ParsedInfo parsed,
            List<DbCredit> dbCredits,
            List<String> issues,
            double severity
    ) {}

    public record Result(String volumeId, List<DriftRecord> drifts) {}
}
