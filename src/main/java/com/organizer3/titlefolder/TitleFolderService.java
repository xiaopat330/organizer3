package com.organizer3.titlefolder;

import com.organizer3.config.volume.MediaConfig;
import com.organizer3.config.volume.NormalizeConfig;
import com.organizer3.filesystem.VolumeFileSystem;
import com.organizer3.model.Title;
import com.organizer3.model.Video;
import com.organizer3.repository.TitleRepository;
import com.organizer3.repository.VideoRepository;
import com.organizer3.trash.Trash;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.Jdbi;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Title-folder operations shared by MCP tools and (future) HTTP routes.
 *
 * <p>The service does not own any connection lifecycle. Callers supply a
 * {@link VolumeFileSystem} (and a {@link Trash} primitive that wraps it)
 * so that MCP can use its session-bound connection while web callers can
 * use the pooled {@code SmbConnectionFactory}. The service is a pure
 * logic layer over those handles plus the DB.
 *
 * <p>Operations are <strong>per-file</strong> — "trash one video" / "trash
 * one cover". The legacy MCP "trash all but keeper" semantics live in the
 * MCP-tool adapters, which iterate over the to-trash set and call into
 * this service one file at a time.
 *
 * @see com.organizer3.mcp.tools.AnalyzeTitleVideosTool — adapter
 * @see com.organizer3.mcp.tools.TrashDuplicateVideoTool — adapter
 * @see com.organizer3.mcp.tools.TrashDuplicateCoverTool — adapter
 */
@lombok.extern.slf4j.Slf4j
public class TitleFolderService {

    /** File extensions counted as cover images at a title folder. */
    public static final Set<String> COVER_EXTS = Set.of("jpg", "jpeg", "png", "webp");

    /** Durations within this window are treated as "same content". */
    static final int DUPLICATE_DURATION_TOLERANCE_SEC = 30;
    /** Minimum pairwise duration gap to call a multi-part set legitimate. */
    static final int SET_DURATION_SEPARATION_SEC = 120;

    private final TitleRepository titleRepo;
    private final VideoRepository videoRepo;
    private final Jdbi jdbi;
    private final MediaConfig mediaConfig;
    private final NormalizeConfig normalizeConfig;

    /** Backwards-compatible 3-arg constructor; uses {@link MediaConfig#DEFAULTS}. */
    public TitleFolderService(TitleRepository titleRepo, VideoRepository videoRepo, Jdbi jdbi) {
        this(titleRepo, videoRepo, jdbi, MediaConfig.DEFAULTS, null);
    }

    /** Full constructor — callers that need non-default extension classification inject MediaConfig. */
    public TitleFolderService(TitleRepository titleRepo, VideoRepository videoRepo,
                              Jdbi jdbi, MediaConfig mediaConfig) {
        this(titleRepo, videoRepo, jdbi, mediaConfig, null);
    }

    /** Full constructor with normalize config. */
    public TitleFolderService(TitleRepository titleRepo, VideoRepository videoRepo,
                              Jdbi jdbi, MediaConfig mediaConfig, NormalizeConfig normalizeConfig) {
        this.titleRepo       = titleRepo;
        this.videoRepo       = videoRepo;
        this.jdbi            = jdbi;
        this.mediaConfig     = mediaConfig != null ? mediaConfig : MediaConfig.DEFAULTS;
        this.normalizeConfig = normalizeConfig != null ? normalizeConfig : NormalizeConfig.EMPTY;
    }

    // ── Pure DB analysis ───────────────────────────────────────────────────

    /**
     * Heuristic verdict + per-video metadata for a title's videos.
     * Pure DB read — no FS access. Throws if the title is unknown.
     */
    public AnalysisResult analyzeVideos(String titleCode) {
        Title title = titleRepo.findByCode(titleCode)
                .orElseThrow(() -> new IllegalArgumentException("No title with code " + titleCode));
        List<Video> videos = videoRepo.findByTitle(title.getId());
        List<VideoMetadata> rows = new ArrayList<>(videos.size());
        for (Video v : videos) {
            rows.add(new VideoMetadata(v.getId(), v.getFilename(), v.getVolumeId(),
                    v.getDurationSec(), v.getWidth(), v.getHeight(),
                    v.getVideoCodec(), v.getAudioCodec(), v.getContainer(),
                    v.getSizeBytes()));
        }
        String verdict = classifyVideos(videos);
        String explanation = explainVerdict(verdict);
        return new AnalysisResult(title.getId(), title.getCode(), videos.size(),
                verdict, explanation, rows);
    }

    static String classifyVideos(List<Video> videos) {
        if (videos.isEmpty())   return "no_videos";
        if (videos.size() == 1) return "single_video";
        for (Video v : videos) {
            if (v.getDurationSec() == null) return "insufficient_metadata";
        }
        long minDur = Long.MAX_VALUE, maxDur = Long.MIN_VALUE;
        for (Video v : videos) {
            long d = v.getDurationSec();
            if (d < minDur) minDur = d;
            if (d > maxDur) maxDur = d;
        }
        long spread = maxDur - minDur;
        if (spread <= DUPLICATE_DURATION_TOLERANCE_SEC) return "likely_duplicates";
        if (spread >= SET_DURATION_SEPARATION_SEC)      return "likely_set";
        return "ambiguous";
    }

    static String explainVerdict(String verdict) {
        return switch (verdict) {
            case "no_videos"             -> "Title has no videos indexed.";
            case "single_video"          -> "Only one video file — nothing to compare.";
            case "insufficient_metadata" -> "At least one video has no duration yet. Run 'probe videos' on the hosting volume.";
            case "likely_duplicates"     -> "All videos share a near-identical duration (within "
                                              + DUPLICATE_DURATION_TOLERANCE_SEC + "s). Same content, likely quality variants.";
            case "likely_set"            -> "Videos have clearly distinct durations (gap ≥ "
                                              + SET_DURATION_SEPARATION_SEC + "s). Consistent with a multi-part release.";
            case "ambiguous"             -> "Duration spread falls between the duplicate and set thresholds. Needs manual review.";
            default                      -> "";
        };
    }

    // ── Title-folder lookup (DB) ───────────────────────────────────────────

    /**
     * Resolve the live (non-stale) title-folder path on a given volume, if any.
     * Returns the first match in stable id order — multi-location titles will
     * have multiple rows but a single volume rarely hosts more than one.
     */
    public Optional<Path> findTitleFolder(String titleCode, String volumeId) {
        return jdbi.withHandle(h -> h.createQuery("""
                    SELECT tl.path FROM title_locations tl
                    JOIN titles t ON t.id = tl.title_id
                    WHERE tl.volume_id = :volumeId AND UPPER(t.code) = UPPER(:code)
                      AND tl.stale_since IS NULL
                    ORDER BY tl.id
                    LIMIT 1
                    """)
                .bind("volumeId", volumeId)
                .bind("code",     titleCode)
                .mapTo(String.class)
                .findFirst()
                .map(Path::of));
    }

    // ── Cover listing (FS) ────────────────────────────────────────────────

    /**
     * List cover-extension filenames at the given folder, base-level only.
     * Subfolders are not descended (per the canonical layout convention,
     * covers live at the title's base; misplaced covers are a §4.4 concern
     * for Phase 5's restructure flow).
     */
    public List<String> listCovers(VolumeFileSystem fs, Path folder) {
        List<String> out = new ArrayList<>();
        try {
            for (Path child : fs.listDirectory(folder)) {
                if (fs.isDirectory(child)) continue;
                Path name = child.getFileName();
                if (name == null) continue;
                String n = name.toString();
                if (isCover(n)) out.add(n);
            }
        } catch (IOException e) {
            throw new IllegalArgumentException(
                    "Failed to list cover candidates under " + folder + ": " + e.getMessage(), e);
        }
        return out;
    }

    /** True when the filename's extension is in {@link #COVER_EXTS}. */
    public static boolean isCover(String filename) {
        if (filename == null) return false;
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) return false;
        return COVER_EXTS.contains(filename.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    // ── Per-file trash operations ──────────────────────────────────────────

    /**
     * Trash one video and remove its DB row. The {@link Trash} primitive carries
     * its own {@code VolumeFileSystem}/volumeId/trash-folder context — this
     * service does not need the FS handle directly. Callers (MCP / HTTP route)
     * construct the Trash for the appropriate volume.
     *
     * <p>On IO failure the method does <strong>not</strong> throw; the outcome
     * record carries the error so the caller can aggregate a multi-file result.
     * The DB row is only deleted on FS-trash success.
     */
    public TrashOutcome trashVideo(Trash trash, Video video, String reason) {
        Path filePath = video.getPath();
        try {
            Trash.Result r = trash.trashItem(filePath, reason != null ? reason : "Duplicate video");
            videoRepo.delete(video.getId());
            return TrashOutcome.success(filePath, r.trashedPath());
        } catch (IOException e) {
            return TrashOutcome.failure(filePath, e.getMessage());
        }
    }

    /**
     * Trash one cover image at the title's base folder. Same outcome semantics
     * as {@link #trashVideo}: no throw, error captured in the result.
     */
    public TrashOutcome trashCover(Trash trash, Path folder, String coverFilename, String reason) {
        Path src = folder.resolve(coverFilename);
        try {
            Trash.Result r = trash.trashItem(src, reason != null ? reason : "Duplicate cover");
            return TrashOutcome.success(src, r.trashedPath());
        } catch (IOException e) {
            return TrashOutcome.failure(src, e.getMessage());
        }
    }

    // ── Folder contents listing (FS + DB merge) ───────────────────────────

    /**
     * Returns the merged FS-listed + DB-metadata view of a title's single folder.
     * Walks the base folder and one level of subdirectories (the canonical layout
     * places videos under {@code video/}, {@code h265/}, {@code 4K/}, etc.).
     *
     * <p>Each non-directory file is classified by its extension:
     * <ul>
     *   <li>video extension → {@link FolderContents#videos()}, joined to the DB row when available
     *   <li>cover extension → {@link FolderContents#covers()}, size from FS
     *   <li>otherwise → {@link FolderContents#otherFiles()} as a relative-to-folder path string
     * </ul>
     *
     * <p>If the folder does not exist or is not a directory, returns an empty {@link FolderContents}.
     *
     * <p>The filename→Video map is keyed by filename only (not path); for videos with the same
     * filename in two subdirectories of one folder, the last one seen wins — this is a degenerate
     * layout that §4.4 normalisation will surface and fix.
     */
    public FolderContents listContents(VolumeFileSystem fs, String titleCode,
                                       String volumeId, Path folder) {
        if (!fs.exists(folder) || !fs.isDirectory(folder)) {
            return new FolderContents(volumeId, folder.toString(), List.of(), List.of(), List.of());
        }

        // Build filename→Video map once (all videos for title regardless of volume; we're on a
        // single-location title so volume filtering is superfluous but harmless).
        Map<String, Video> videoByFilename = new HashMap<>();
        if (titleRepo != null) {
            Title title = titleRepo.findByCode(titleCode).orElse(null);
            if (title != null && videoRepo != null) {
                for (Video v : videoRepo.findByTitle(title.getId())) {
                    // Last write wins on filename collision (degenerate layout corner case).
                    videoByFilename.put(v.getFilename(), v);
                }
            }
        }

        Set<String> videoExts = Set.copyOf(
                mediaConfig.effectiveVideoExtensions().stream()
                        .map(e -> e.toLowerCase(Locale.ROOT)).toList());
        Set<String> coverExts = Set.copyOf(
                mediaConfig.effectiveCoverExtensions().stream()
                        .map(e -> e.toLowerCase(Locale.ROOT)).toList());

        List<FolderVideo> videos   = new ArrayList<>();
        List<FolderCover> covers   = new ArrayList<>();
        List<String>      others   = new ArrayList<>();

        List<Path> baseLevelChildren;
        try {
            baseLevelChildren = fs.listDirectory(folder);
        } catch (IOException e) {
            // If we can't list the folder at all, return empty rather than blowing up.
            return new FolderContents(volumeId, folder.toString(), List.of(), List.of(), List.of());
        }

        for (Path child : baseLevelChildren) {
            if (fs.isDirectory(child)) {
                // Walk one level into subdirectory — per canonical layout, videos live in
                // video/, h265/, 4K/ etc. We don't recurse further (Phase 5 concern).
                try {
                    for (Path sub : fs.listDirectory(child)) {
                        if (!fs.isDirectory(sub)) {
                            classifyFile(fs, sub, folder, videoExts, coverExts,
                                    videoByFilename, videos, covers, others);
                        }
                    }
                } catch (IOException ignored) {
                    // Unreadable subdirectory — skip, don't fail the whole listing.
                }
            } else {
                classifyFile(fs, child, folder, videoExts, coverExts,
                        videoByFilename, videos, covers, others);
            }
        }

        return new FolderContents(volumeId, folder.toString(), videos, covers, others);
    }

    private void classifyFile(VolumeFileSystem fs, Path file, Path folder,
                              Set<String> videoExts, Set<String> coverExts,
                              Map<String, Video> videoByFilename,
                              List<FolderVideo> videos, List<FolderCover> covers,
                              List<String> others) {
        Path nameOnly = file.getFileName();
        if (nameOnly == null) return;
        String filename = nameOnly.toString();
        String ext      = extensionOf(filename);
        // relativePath is forward-slash separated (folder uses Unix-style Path.of).
        String relativePath = folder.relativize(file).toString();

        if (videoExts.contains(ext)) {
            Long sizeBytes = sizeQuiet(fs, file);
            Video dbRow = videoByFilename.get(filename);
            if (dbRow != null) {
                videos.add(new FolderVideo(filename, relativePath, sizeBytes,
                        dbRow.getId(), dbRow.getDurationSec(),
                        dbRow.getWidth(), dbRow.getHeight(),
                        dbRow.getVideoCodec(), dbRow.getAudioCodec(), dbRow.getContainer()));
            } else {
                videos.add(new FolderVideo(filename, relativePath, sizeBytes,
                        null, null, null, null, null, null, null));
            }
        } else if (coverExts.contains(ext)) {
            Long sizeBytes = sizeQuiet(fs, file);
            covers.add(new FolderCover(filename, relativePath, sizeBytes));
        } else {
            others.add(relativePath);
        }
    }

    private static String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) return "";
        return filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static Long sizeQuiet(VolumeFileSystem fs, Path file) {
        try {
            return fs.size(file);
        } catch (IOException e) {
            return null;
        }
    }

    // ── Normalization plan (Phase 5) ──────────────────────────────────────

    /**
     * Produces a normalization plan: the set of moves (rename / path-move / both) needed
     * to bring the title folder to canonical layout — covers at base named {@code {CODE}.{ext}},
     * videos in the appropriate subfolder ({@code video/}, {@code h265/}, {@code 4K/}).
     *
     * <p>Each file in the folder (base + one level of subdirectories) gets one plan entry.
     * If {@code from == to} the entry is marked {@code alreadyCanonical=true} and requires no
     * action. If two videos would receive the same canonical name, both get {@code to=null} to
     * signal a conflict that the user must resolve by supplying an explicit name override.
     *
     * <p>{@code excludeRelPaths} is the set of relative paths (forward-slash) of files that
     * should be excluded from the plan — used by the frontend to filter out files with pending
     * trash stages.
     *
     * @param fs             live filesystem handle
     * @param titleCode      title code (e.g. {@code ABC-123})
     * @param folder         absolute path to the title's single location folder
     * @param excludeRelPaths relative paths to exclude (e.g. already-staged trash targets)
     * @return plan (may be {@link NormalizationPlan#alreadyNormalized()} when all entries
     *         are canonical)
     */
    public NormalizationPlan planNormalization(VolumeFileSystem fs, String titleCode,
                                               Path folder, Set<String> excludeRelPaths) {
        Set<String> videoExts = Set.copyOf(
                mediaConfig.effectiveVideoExtensions().stream()
                        .map(e -> e.toLowerCase(Locale.ROOT)).toList());
        Set<String> coverExts = Set.copyOf(
                mediaConfig.effectiveCoverExtensions().stream()
                        .map(e -> e.toLowerCase(Locale.ROOT)).toList());

        // Collect all files (base + one level deep), respecting excludes.
        record FileEntry(Path absPath, String relPath, boolean isVideo, boolean isCover) {}
        List<FileEntry> allFiles = new ArrayList<>();
        try {
            for (Path child : fs.listDirectory(folder)) {
                if (fs.isDirectory(child)) {
                    try {
                        for (Path sub : fs.listDirectory(child)) {
                            if (!fs.isDirectory(sub)) {
                                String rel = folder.relativize(sub).toString();
                                if (excludeRelPaths == null || !excludeRelPaths.contains(rel)) {
                                    String ext = extensionOf(sub.getFileName().toString());
                                    allFiles.add(new FileEntry(sub, rel, videoExts.contains(ext), coverExts.contains(ext)));
                                }
                            }
                        }
                    } catch (IOException ignored) { /* skip unreadable subfolder */ }
                } else {
                    String rel = folder.relativize(child).toString();
                    if (excludeRelPaths == null || !excludeRelPaths.contains(rel)) {
                        String ext = extensionOf(child.getFileName().toString());
                        allFiles.add(new FileEntry(child, rel, videoExts.contains(ext), coverExts.contains(ext)));
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalArgumentException(
                    "Cannot list title folder for normalization plan: " + folder + " — " + e.getMessage(), e);
        }

        // Separate into videos and covers.
        List<FileEntry> videos = allFiles.stream().filter(FileEntry::isVideo).toList();
        List<FileEntry> covers = allFiles.stream().filter(FileEntry::isCover).toList();

        // ── Plan video targets ────────────────────────────────────────────
        // Compute proposed target relative path for each video. On multi-video titles:
        // each video gets a best-guess canonical name from freeformSuffix logic.
        // If two videos resolve to the same canonical name → conflict (to=null).
        record VideoProposal(FileEntry entry, String proposedRel) {}
        List<VideoProposal> videoProposals = new ArrayList<>();
        for (FileEntry v : videos) {
            String filename = v.absPath().getFileName().toString();
            String extDot   = extensionWithDot(filename);
            String stem     = filename.substring(0, filename.length() - extDot.length());
            String cleanStem = applyRemovelist(stem);
            String freeform  = freeformSuffix(cleanStem, titleCode, extDot);
            String canonicalName = titleCode + freeform + extDot;
            // Pick subfolder for the canonical parent (based on canonical name, not current name).
            String subfolder    = pickSubfolder(canonicalName);
            String proposedRel  = subfolder + "/" + canonicalName;
            videoProposals.add(new VideoProposal(v, proposedRel));
        }

        // Detect conflicts: multiple videos → same canonical rel path.
        Map<String, Long> targetCount = new LinkedHashMap<>();
        for (VideoProposal vp : videoProposals) {
            targetCount.merge(vp.proposedRel(), 1L, Long::sum);
        }

        // ── Plan cover targets ────────────────────────────────────────────
        // First cover at base → {CODE}.{ext}. Additional covers or covers in subdirs
        // get best-guess names ({CODE}_alt.{ext}, {CODE}_alt2.{ext}, …).
        // In all cases the target is at the folder base.
        record CoverProposal(FileEntry entry, String proposedRel) {}
        List<CoverProposal> coverProposals = new ArrayList<>();
        int coverIndex = 0;
        for (FileEntry c : covers) {
            String filename = c.absPath().getFileName().toString();
            String extDot   = extensionWithDot(filename);
            String proposedName;
            if (coverIndex == 0) {
                proposedName = titleCode + extDot;
            } else {
                proposedName = titleCode + "_alt" + (coverIndex > 1 ? coverIndex : "") + extDot;
            }
            coverProposals.add(new CoverProposal(c, proposedName));
            coverIndex++;
        }

        // ── Build plan entries ────────────────────────────────────────────
        List<NormalizationPlanEntry> entries = new ArrayList<>();

        for (VideoProposal vp : videoProposals) {
            boolean conflict = targetCount.getOrDefault(vp.proposedRel(), 0L) > 1;
            String to = conflict ? null : vp.proposedRel();
            boolean canonical = to != null && normalizeRelPath(vp.entry().relPath()).equalsIgnoreCase(normalizeRelPath(to));
            entries.add(new NormalizationPlanEntry(vp.entry().relPath(), to, "video", conflict, canonical));
        }

        for (CoverProposal cp : coverProposals) {
            boolean canonical = normalizeRelPath(cp.entry().relPath()).equalsIgnoreCase(normalizeRelPath(cp.proposedRel()));
            entries.add(new NormalizationPlanEntry(cp.entry().relPath(), cp.proposedRel(), "cover", false, canonical));
        }

        boolean alreadyNormalized = entries.stream().allMatch(
                e -> !e.conflict() && e.alreadyCanonical());
        return new NormalizationPlan(titleCode, folder.toString(), entries, alreadyNormalized);
    }

    /** Normalize a relative path for case-insensitive comparison (forward-slash, lower). */
    private static String normalizeRelPath(String rel) {
        return rel == null ? "" : rel.replace('\\', '/').toLowerCase(Locale.ROOT);
    }

    /**
     * Executes the user-confirmed set of moves (renames, path-moves, or both).
     *
     * <p>Validates before mutating:
     * <ul>
     *   <li>All {@code from} paths must exist under {@code folder}.
     *   <li>No two {@code to} values may be the same (collision within the set).
     *   <li>No {@code to} value may point to a file that already exists and is NOT
     *       the source of another move in the same set (would overwrite an untouched file).
     *   <li>All {@code from} and {@code to} paths must resolve within {@code folder}
     *       (path-traversal guard).
     * </ul>
     *
     * <p>On validation failure throws {@link IllegalArgumentException} before any FS mutation.
     * On execution, moves are attempted in order; if any fails the method throws and later
     * moves are not attempted (caller should surface the error; partial state is visible on disk).
     *
     * @param fs     live filesystem handle
     * @param folder absolute title-folder path
     * @param moves  ordered list of {@code {from, to}} relative-path pairs
     * @throws IllegalArgumentException on pre-flight validation failure
     * @throws IOException              on FS move failure
     */
    public NormalizationOutcome executeNormalization(VolumeFileSystem fs, Path folder,
                                                     List<MovePair> moves) throws IOException {
        if (moves == null || moves.isEmpty()) {
            return new NormalizationOutcome(0, List.of());
        }

        Path normalFolder = folder.normalize();

        // ── Pre-flight validation ─────────────────────────────────────────
        // 1. All from-paths within folder, all exist.
        // 2. All to-paths within folder.
        // 3. No duplicate to-paths.
        // 4. No to-path hits an existing file NOT in the from-set.

        Set<String> fromPaths = new HashSet<>();
        Set<String> toPaths   = new HashSet<>();

        for (MovePair m : moves) {
            if (m.from() == null || m.from().isBlank()) {
                throw new IllegalArgumentException("Move 'from' must not be blank");
            }
            if (m.to() == null || m.to().isBlank()) {
                throw new IllegalArgumentException("Move 'to' must not be blank");
            }
            Path absFrom = normalFolder.resolve(m.from()).normalize();
            Path absTo   = normalFolder.resolve(m.to()).normalize();

            if (!absFrom.startsWith(normalFolder)) {
                throw new IllegalArgumentException("Path traversal in 'from': " + m.from());
            }
            if (!absTo.startsWith(normalFolder)) {
                throw new IllegalArgumentException("Path traversal in 'to': " + m.to());
            }
            fromPaths.add(absFrom.toString());
            if (!toPaths.add(absTo.toString())) {
                throw new IllegalArgumentException("Duplicate target path: " + m.to());
            }
        }

        // Verify existence and collision with untouched files.
        for (MovePair m : moves) {
            Path absFrom = normalFolder.resolve(m.from()).normalize();
            Path absTo   = normalFolder.resolve(m.to()).normalize();

            if (!fs.exists(absFrom)) {
                throw new IllegalArgumentException("Source file does not exist: " + m.from());
            }
            // If the target already exists and it's NOT the source of another move in this set,
            // it's an untouched file we'd overwrite — reject.
            if (fs.exists(absTo) && !fromPaths.contains(absTo.toString())) {
                throw new IllegalArgumentException(
                        "Target already exists and is not part of this move set: " + m.to());
            }
        }

        // ── Execute moves ─────────────────────────────────────────────────
        List<String> applied = new ArrayList<>();
        for (MovePair m : moves) {
            Path absFrom = normalFolder.resolve(m.from()).normalize();
            Path absTo   = normalFolder.resolve(m.to()).normalize();
            if (absFrom.equals(absTo)) continue;  // no-op (already canonical)

            fs.createDirectories(absTo.getParent());
            fs.move(absFrom, absTo);
            log.info("FS mutation [TitleFolderService.executeNormalization]: moved — from={} to={}",
                    absFrom, absTo);
            applied.add(m.from() + " → " + m.to());
        }

        return new NormalizationOutcome(applied.size(), applied);
    }

    // ── Helpers shared with plan logic ────────────────────────────────────

    /** Picks the canonical subfolder name from the video filename hint. */
    static String pickSubfolder(String filename) {
        if (filename == null) return "video";
        String lower = filename.toLowerCase(Locale.ROOT);
        if (lower.contains("-4k"))   return "4K";
        if (lower.contains("-h265")) return "h265";
        return "video";
    }

    /** Extension preserving original case, with dot (e.g. {@code ".MKV"}). */
    static String extensionWithDot(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot < 0) return "";
        return filename.substring(dot);
    }

    /**
     * Extracts the freeform suffix after the code part (e.g. {@code -h265} or {@code _4K}).
     * See {@code TitleNormalizerService.freeformSuffix} — duplicated here to avoid coupling.
     */
    static String freeformSuffix(String cleanStem, String titleCode, String extWithDot) {
        String stemLower = cleanStem.toLowerCase(Locale.ROOT);
        String codeLower = titleCode.toLowerCase(Locale.ROOT);
        if (stemLower.startsWith(codeLower)) {
            return cleanStem.substring(titleCode.length());
        }
        // Separator-normalized: treat - and _ as equivalent.
        if (stemLower.replace('-', '_').startsWith(codeLower.replace('-', '_'))) {
            return cleanStem.substring(titleCode.length());
        }
        return freeformSuffixFromFilename(cleanStem + extWithDot);
    }

    private static String freeformSuffixFromFilename(String filename) {
        int dot = filename.lastIndexOf('.');
        String stem = dot >= 0 ? filename.substring(0, dot) : filename;
        java.util.regex.Matcher m = Pattern
                .compile("^[0-9]{0,6}[A-Za-z][A-Za-z0-9]{0,9}-\\d+([-_].+)?$")
                .matcher(stem);
        if (m.matches()) {
            String suffix = m.group(1);
            return suffix != null ? suffix : "";
        }
        return "";
    }

    private String applyRemovelist(String stem) {
        for (String token : normalizeConfig.effectiveRemovelist()) {
            if (token == null || token.isEmpty()) continue;
            stem = Pattern.compile(Pattern.quote(token), Pattern.CASE_INSENSITIVE)
                          .matcher(stem).replaceAll("");
        }
        return stem;
    }

    // ── Result records ─────────────────────────────────────────────────────

    public record AnalysisResult(long titleId, String titleCode, int videoCount,
                                 String verdict, String explanation,
                                 List<VideoMetadata> videos) {}

    public record VideoMetadata(long videoId, String filename, String volumeId,
                                Long durationSec, Integer width, Integer height,
                                String videoCodec, String audioCodec, String container,
                                Long sizeBytes) {}

    /**
     * Outcome of a single trash operation. {@code success=true} means the file
     * was moved to the trash area (and, for videos, the DB row was deleted).
     */
    public record TrashOutcome(boolean success, Path source, Path trashedTo, String error) {
        public static TrashOutcome success(Path source, Path trashedTo) {
            return new TrashOutcome(true, source, trashedTo, null);
        }
        public static TrashOutcome failure(Path source, String error) {
            return new TrashOutcome(false, source, null, error);
        }
    }

    /** Merged FS + DB view of a title's single folder. */
    public record FolderContents(String volumeId, String folderPath,
                                 List<FolderVideo> videos,
                                 List<FolderCover> covers,
                                 List<String> otherFiles) {}

    /**
     * A video file found inside the title folder (base or one level of subdirectory).
     * DB-metadata fields are null when the file is on disk but not indexed.
     */
    public record FolderVideo(String filename, String relativePath, Long sizeBytes,
                              Long videoId, Long durationSec,
                              Integer width, Integer height,
                              String videoCodec, String audioCodec, String container) {}

    /** A cover-image file found at the title folder base. */
    public record FolderCover(String filename, String relativePath, Long sizeBytes) {}

    // ── Normalization records ──────────────────────────────────────────────

    /**
     * Full normalization plan for a title folder.
     *
     * @param titleCode         the title code the plan was computed for
     * @param folderPath        absolute path of the title folder
     * @param entries           one entry per file (canonical or needs moving)
     * @param alreadyNormalized true when all entries are already canonical and no moves are needed
     */
    public record NormalizationPlan(
            String titleCode,
            String folderPath,
            List<NormalizationPlanEntry> entries,
            boolean alreadyNormalized
    ) {}

    /**
     * One file in the normalization plan.
     *
     * @param from            current relative path (forward-slash, from folder root)
     * @param to              proposed canonical relative path, or {@code null} when there is a
     *                        naming conflict that the user must resolve with an explicit override
     * @param kind            {@code "video"} or {@code "cover"}
     * @param conflict        true when {@code to=null} due to a canonical-name collision
     * @param alreadyCanonical true when {@code from} and {@code to} are equivalent paths (no move needed)
     */
    public record NormalizationPlanEntry(
            String from,
            String to,
            String kind,
            boolean conflict,
            boolean alreadyCanonical
    ) {}

    /**
     * Outcome of {@link #executeNormalization}.
     *
     * @param movedCount number of files that were actually moved (no-ops excluded)
     * @param moved      human-readable "from → to" descriptions of applied moves
     */
    public record NormalizationOutcome(int movedCount, List<String> moved) {}

    /** A single from→to pair for {@link #executeNormalization}. */
    public record MovePair(String from, String to) {}
}
