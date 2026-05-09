package com.organizer3.titlefolder;

import com.organizer3.config.volume.MediaConfig;
import com.organizer3.filesystem.VolumeFileSystem;
import com.organizer3.model.Title;
import com.organizer3.model.Video;
import com.organizer3.repository.TitleRepository;
import com.organizer3.repository.VideoRepository;
import com.organizer3.trash.Trash;
import org.jdbi.v3.core.Jdbi;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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

    /** Backwards-compatible 3-arg constructor; uses {@link MediaConfig#DEFAULTS}. */
    public TitleFolderService(TitleRepository titleRepo, VideoRepository videoRepo, Jdbi jdbi) {
        this(titleRepo, videoRepo, jdbi, MediaConfig.DEFAULTS);
    }

    /** Full constructor — callers that need non-default extension classification inject MediaConfig. */
    public TitleFolderService(TitleRepository titleRepo, VideoRepository videoRepo,
                              Jdbi jdbi, MediaConfig mediaConfig) {
        this.titleRepo   = titleRepo;
        this.videoRepo   = videoRepo;
        this.jdbi        = jdbi;
        this.mediaConfig = mediaConfig != null ? mediaConfig : MediaConfig.DEFAULTS;
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
}
