package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.config.volume.OrganizerConfig;
import com.organizer3.config.volume.PartitionDef;
import com.organizer3.config.volume.StructuredPartitionDef;
import com.organizer3.config.volume.VolumeConfig;
import com.organizer3.config.volume.VolumeStructureDef;
import com.organizer3.filesystem.VolumeFileSystem;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;
import com.organizer3.model.Actress;
import com.organizer3.model.ActressAlias;
import com.organizer3.repository.ActressRepository;
import com.organizer3.shell.SessionContext;
import com.organizer3.smb.VolumeConnection;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.Jdbi;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Folder-shaped discovery for fold-into-canonical workflows.
 *
 * <p>Walks actress-shaped folders on the mounted volume ({@code /stars/<tier>/<folder>/},
 * {@code /attention/<folder>/}) and scores each against the target actress by combining:
 * <ul>
 *   <li>Name similarity — Levenshtein-normalized score vs canonical + aliases.</li>
 *   <li>Credit overlap — fraction of titles inside the folder that the DB credits to
 *       the target actress.</li>
 * </ul>
 *
 * <p>Locked-in scoring:
 * <pre>
 *   nameSimilarity = max over (canonical + aliases) of:
 *       1 - levenshtein(folder.lower(), name.lower()) / max(len(a), len(b))
 *
 *   creditOverlap = dbCreditedTitleCount / titleCount   (0 if titleCount == 0)
 *
 *   if dbCreditedTitleCount < 2:
 *       score = nameSimilarity
 *   else:
 *       score = 0.6 * creditOverlap + 0.4 * nameSimilarity
 * </pre>
 *
 * <p>Only candidates with {@code score >= min_score} are returned, sorted descending.
 *
 * <p>Read-only — no mutation, no curation log.
 */
@Slf4j
public class FindActressFolderCandidatesTool implements Tool {

    private static final double DEFAULT_MIN_SCORE = 0.3;

    private final SessionContext session;
    private final ActressRepository actressRepo;
    private final Jdbi jdbi;
    private final OrganizerConfig config;

    public FindActressFolderCandidatesTool(SessionContext session,
                                            ActressRepository actressRepo,
                                            Jdbi jdbi,
                                            OrganizerConfig config) {
        this.session     = session;
        this.actressRepo = actressRepo;
        this.jdbi        = jdbi;
        this.config      = config;
    }

    @Override public String name() { return "find_actress_folder_candidates"; }

    @Override
    public String description() {
        return "Folder-shaped discovery for fold-into-canonical workflows. Walks actress-shaped "
             + "folders on the mounted volume and scores each against the target actress by "
             + "combining Levenshtein name similarity (vs canonical + aliases) with DB credit "
             + "overlap. 60/40 weighted when ≥2 credited titles, name-only otherwise. "
             + "Catches romanization-drift cases (e.g. Shelly Fuji) that similarity scan misses.";
    }

    @Override
    public JsonNode inputSchema() {
        return Schemas.object()
                .prop("actress_id", "integer", "Actress id to find folder candidates for.")
                .prop("volume_id",  "string",  "Volume id. Defaults to the currently mounted volume.")
                .prop("min_score",  "number",  "Minimum combined score (0.0–1.0) to include a candidate. Default 0.3.", DEFAULT_MIN_SCORE)
                .require("actress_id")
                .build();
    }

    @Override
    public Object call(JsonNode args) {
        long actressId   = Schemas.requireLong(args, "actress_id");
        String volumeIdArg = Schemas.optString(args, "volume_id", null);
        double minScore  = Schemas.optDouble(args, "min_score", DEFAULT_MIN_SCORE);

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
        VolumeFileSystem fs = conn.fileSystem();

        Actress actress = actressRepo.findById(actressId)
                .orElseThrow(() -> new IllegalArgumentException("No actress found for id=" + actressId));

        List<String> aliases = actressRepo.findAliases(actress.getId()).stream()
                .map(ActressAlias::aliasName)
                .toList();

        String canonical = actress.getCanonicalName();
        log.info("find_actress_folder_candidates actressId={} name={} volume={}",
                actressId, canonical, effectiveVolumeId);

        // ── Build name list: canonical + aliases ──────────────────────────────
        List<String> allNames = new ArrayList<>();
        allNames.add(canonical);
        allNames.addAll(aliases);

        // ── Enumerate actress-shaped folders on the volume ────────────────────
        List<FolderEntry> folders = enumerateActressFolders(fs, effectiveVolumeId);
        log.info("find_actress_folder_candidates found {} actress folders on volume={}",
                folders.size(), effectiveVolumeId);

        // ── Score each folder ────────────────────────────────────────────────
        List<Candidate> candidates = new ArrayList<>();
        for (FolderEntry folder : folders) {
            String folderBasename = folder.basename();

            // Compute name similarity
            double nameSimilarity = computeNameSimilarity(folderBasename, allNames);

            // Load titles in this folder and count DB-credited ones
            TitleStats stats = computeTitleStats(fs, folder.path(), actressId, effectiveVolumeId);
            int titleCount = stats.totalCount();
            int dbCreditedCount = stats.creditedCount();

            double creditOverlap = (titleCount == 0) ? 0.0
                    : (double) dbCreditedCount / titleCount;

            double score;
            if (dbCreditedCount < 2) {
                score = nameSimilarity;
            } else {
                score = 0.6 * creditOverlap + 0.4 * nameSimilarity;
            }

            if (score < minScore) continue;

            boolean mixedContent = dbCreditedCount > 0 && dbCreditedCount < titleCount;

            candidates.add(new Candidate(
                    folder.path().toString(),
                    titleCount,
                    round3(nameSimilarity),
                    round3(creditOverlap),
                    dbCreditedCount,
                    stats.sampleCodes(),
                    round3(score),
                    mixedContent));
        }

        // Sort descending by score
        candidates.sort((a, b) -> Double.compare(b.score(), a.score()));

        return new Result(actress.getId(), canonical, aliases, candidates);
    }

    // ── Folder enumeration ────────────────────────────────────────────────────

    /**
     * Enumerate actress-shaped folders: /stars/<tier>/<basename>/ and /attention/<basename>/.
     * Uses the volume structure definition when available to determine tier paths.
     * Falls back to hardcoded conventional layout when config can't provide the structure.
     */
    private List<FolderEntry> enumerateActressFolders(VolumeFileSystem fs, String volumeId) {
        List<FolderEntry> entries = new ArrayList<>();
        VolumeConfig vol = config.findById(volumeId).orElse(null);

        List<Path> tierRoots = new ArrayList<>();

        if (vol != null) {
            VolumeStructureDef def = config.findStructureById(vol.structureType()).orElse(null);
            if (def != null && def.structuredPartition() != null) {
                StructuredPartitionDef stars = def.structuredPartition();
                for (PartitionDef sub : stars.partitions()) {
                    tierRoots.add(Path.of("/", stars.path(), sub.path()));
                }
            }
        }

        // Attention folder: always walked regardless of structure type
        tierRoots.add(Path.of("/attention"));

        for (Path tierRoot : tierRoots) {
            try {
                if (!fs.exists(tierRoot)) continue;
                List<Path> subdirs = fs.listDirectory(tierRoot);
                for (Path subdir : subdirs) {
                    if (!fs.isDirectory(subdir)) continue;
                    String basename = subdir.getFileName() != null
                            ? subdir.getFileName().toString() : "";
                    if (!basename.isEmpty()) {
                        entries.add(new FolderEntry(subdir, basename));
                    }
                }
            } catch (IOException e) {
                log.warn("find_actress_folder_candidates: error listing {}: {}", tierRoot, e.getMessage());
            }
        }

        return entries;
    }

    // ── Title stats: count titles in a folder, how many are DB-credited ────────

    private record TitleStats(int totalCount, int creditedCount, List<String> sampleCodes) {}

    /**
     * Walk the actress folder, collect title subfolders, count them, and check how many
     * have DB-credited entries for the target actress on this volume.
     */
    private TitleStats computeTitleStats(VolumeFileSystem fs, Path actressFolder,
                                          long actressId, String volumeId) {
        List<Path> titleFolders;
        try {
            titleFolders = fs.listDirectory(actressFolder).stream()
                    .filter(fs::isDirectory)
                    .toList();
        } catch (IOException e) {
            return new TitleStats(0, 0, List.of());
        }

        if (titleFolders.isEmpty()) return new TitleStats(0, 0, List.of());

        // Parse codes from folder basenames
        Set<String> parsedCodes = new HashSet<>();
        for (Path folder : titleFolders) {
            String basename = folder.getFileName() != null ? folder.getFileName().toString() : "";
            String code = BasenameParser.extractCode(basename);
            if (code != null) {
                parsedCodes.add(code.toUpperCase());
            }
        }

        if (parsedCodes.isEmpty()) {
            return new TitleStats(titleFolders.size(), 0, List.of());
        }

        // Query DB: which of these codes are credited to actressId on this volume?
        final int batchSize = 500;
        List<String> codeList = new ArrayList<>(parsedCodes);
        List<String> creditedCodes = new ArrayList<>();
        for (int i = 0; i < codeList.size(); i += batchSize) {
            List<String> batch = codeList.subList(i, Math.min(i + batchSize, codeList.size()));
            List<String> batchResult = jdbi.withHandle(h -> {
                String placeholders = batch.stream()
                        .map(c -> "?")
                        .reduce((a, b) -> a + "," + b)
                        .orElse("?");
                var query = h.createQuery(
                        "SELECT UPPER(t.code) FROM titles t " +
                        "JOIN title_locations tl ON tl.title_id = t.id " +
                        "WHERE t.actress_id = ? " +
                        "  AND tl.volume_id = ? " +
                        "  AND tl.stale_since IS NULL " +
                        "  AND UPPER(t.code) IN (" + placeholders + ")")
                        .bind(0, actressId)
                        .bind(1, volumeId);
                for (int j = 0; j < batch.size(); j++) {
                    query = query.bind(j + 2, batch.get(j));
                }
                return query.mapTo(String.class).list();
            });
            creditedCodes.addAll(batchResult);
        }

        // Sample: up to 5 credited codes; fall back to all codes if none credited
        List<String> sample = (creditedCodes.isEmpty() ? codeList : creditedCodes)
                .stream().limit(5).toList();

        return new TitleStats(titleFolders.size(), creditedCodes.size(), sample);
    }

    // ── Levenshtein name similarity ───────────────────────────────────────────

    /**
     * Returns the best (highest) normalized similarity score between
     * {@code folderBasename} and any of the candidate names.
     * Score = 1 - levenshtein / max(len(a), len(b)).
     */
    static double computeNameSimilarity(String folderBasename, List<String> candidateNames) {
        if (candidateNames.isEmpty()) return 0.0;
        String aLower = folderBasename.toLowerCase(Locale.ROOT);
        double best = 0.0;
        for (String name : candidateNames) {
            if (name == null || name.isBlank()) continue;
            String bLower = name.toLowerCase(Locale.ROOT);
            int maxLen = Math.max(aLower.length(), bLower.length());
            if (maxLen == 0) continue;
            int dist = levenshtein(aLower, bLower);
            double sim = 1.0 - (double) dist / maxLen;
            if (sim > best) best = sim;
        }
        return best;
    }

    /**
     * Full Levenshtein (no threshold). Returns edit distance.
     */
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

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    // ── internal model ────────────────────────────────────────────────────────

    private record FolderEntry(Path path, String basename) {}

    // ── output records ────────────────────────────────────────────────────────

    public record Candidate(
            String folderPath,
            int titleCount,
            double nameSimilarity,
            double creditOverlap,
            int dbCreditedTitleCount,
            List<String> sampleTitleCodes,
            double score,
            boolean mixedContent
    ) {}

    public record Result(
            long actressId,
            String canonicalName,
            List<String> aliases,
            List<Candidate> candidates
    ) {}
}
