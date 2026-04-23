package com.organizer3.organize;

import com.organizer3.config.volume.MediaConfig;
import com.organizer3.config.volume.NormalizeConfig;
import com.organizer3.filesystem.VolumeFileSystem;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Phase 1 of the organize pipeline: rename a title's cover + single-video to the
 * canonical {@code {CODE}.{ext}} form. See {@code spec/PROPOSAL_ORGANIZE_PIPELINE.md} §3.1.
 *
 * <p>Scope (ships in step 3 of §9):
 * <ul>
 *   <li>Cover rename: if the title folder has exactly one cover file at its base,
 *       rename it to {@code {CODE}.{ext}} (keeping the original extension).</li>
 *   <li>Video rename: if there is exactly one video file anywhere in the title
 *       folder or its immediate child subfolders, rename it to {@code {CODE}.{ext}}.</li>
 * </ul>
 *
 * <p>Deferred: legacy {@code removelist}/{@code replacelist} token normalization on
 * multi-file cases. Will revisit after probe-backfill unblocks multi-file handling
 * per §6.2.
 */
@Slf4j
public class TitleNormalizerService {

    private final MediaConfig media;
    private final NormalizeConfig normalize;

    public TitleNormalizerService(MediaConfig media) {
        this(media, null);
    }

    public TitleNormalizerService(MediaConfig media, NormalizeConfig normalize) {
        this.media     = media      == null ? MediaConfig.DEFAULTS  : media;
        this.normalize = normalize  == null ? NormalizeConfig.EMPTY : normalize;
    }

    public Result apply(VolumeFileSystem fs, Path titleFolder, String titleCode, boolean dryRun) throws IOException {
        if (!fs.exists(titleFolder) || !fs.isDirectory(titleFolder)) {
            throw new IllegalArgumentException("Title folder does not exist or is not a directory: " + titleFolder);
        }
        if (titleCode == null || titleCode.isBlank()) {
            throw new IllegalArgumentException("titleCode is required");
        }

        List<Path> baseChildren = fs.listDirectory(titleFolder);
        List<Path> coversAtBase = new ArrayList<>();
        List<Path> videosAnywhere = new ArrayList<>();
        for (Path child : baseChildren) {
            if (fs.isDirectory(child)) {
                // one level deeper for video counting
                for (Path inner : fs.listDirectory(child)) {
                    if (fs.isDirectory(inner)) continue;
                    if (isVideo(filename(inner))) videosAnywhere.add(inner);
                }
            } else {
                String name = filename(child);
                if (isCover(name)) coversAtBase.add(child);
                if (isVideo(name)) videosAnywhere.add(child);
            }
        }

        List<Action> planned = new ArrayList<>();
        List<Skip> skipped = new ArrayList<>();

        // Cover: exactly one at base
        if (coversAtBase.size() == 1) {
            Path src = coversAtBase.get(0);
            String srcName = filename(src);
            String ext = extensionWithDot(srcName);
            String cleanStem = applyRemovelist(srcName.substring(0, srcName.length() - ext.length()));
            String target = titleCode + freeformSuffix(cleanStem, titleCode, ext) + ext;
            if (srcName.equalsIgnoreCase(target)) {
                skipped.add(new Skip("cover", srcName, "already canonical"));
            } else {
                planned.add(new Action("cover-rename", src.toString(), target));
            }
        } else if (coversAtBase.isEmpty()) {
            skipped.add(new Skip("cover", null, "no cover at base"));
        } else {
            skipped.add(new Skip("cover", null,
                    "multiple covers at base (" + coversAtBase.size() + ") — resolve with trash_duplicate_cover first"));
        }

        // Video: exactly one anywhere in title folder or one level deep
        if (videosAnywhere.size() == 1) {
            Path src = videosAnywhere.get(0);
            String srcName = filename(src);
            String ext = extensionWithDot(srcName);
            String cleanStem = applyRemovelist(srcName.substring(0, srcName.length() - ext.length()));
            String target = titleCode + freeformSuffix(cleanStem, titleCode, ext) + ext;
            if (srcName.equalsIgnoreCase(target)) {
                skipped.add(new Skip("video", srcName, "already canonical"));
            } else {
                planned.add(new Action("video-rename", src.toString(), target));
            }
        } else if (videosAnywhere.isEmpty()) {
            skipped.add(new Skip("video", null, "no videos found"));
        } else {
            skipped.add(new Skip("video", null,
                    "multi-file title (" + videosAnywhere.size() + " videos) — deferred until probe-backfill"));
        }

        if (dryRun) {
            return new Result(true, titleFolder.toString(), planned, List.of(), List.of(), skipped);
        }

        List<Action> applied = new ArrayList<>();
        List<Action> failed = new ArrayList<>();
        for (Action a : planned) {
            try {
                fs.rename(Path.of(a.from()), a.to());
                log.info("FS mutation [TitleNormalizer.normalize]: renamed — op={} titleFolder={} from={} to={}",
                        a.op(), titleFolder, a.from(), a.to());
                applied.add(a);
            } catch (IOException e) {
                log.warn("FS mutation [TitleNormalizer.normalize] failed — op={} from={} to={} error={}",
                        a.op(), a.from(), a.to(), e.getMessage());
                failed.add(new Action(a.op(), a.from(), a.to() + "  (error: " + e.getMessage() + ")"));
            }
        }

        return new Result(false, titleFolder.toString(), planned, applied, failed, skipped);
    }

    private boolean isCover(String filename) {
        return matchesAnyExtension(filename, media.effectiveCoverExtensions());
    }

    private boolean isVideo(String filename) {
        return matchesAnyExtension(filename, media.effectiveVideoExtensions());
    }

    private static boolean matchesAnyExtension(String filename, List<String> extensions) {
        if (filename == null) return false;
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) return false;
        String ext = filename.substring(dot + 1).toLowerCase(Locale.ROOT);
        for (String e : extensions) if (e.equalsIgnoreCase(ext)) return true;
        return false;
    }

    /**
     * Extracts the freeform suffix given the cleaned stem, the known titleCode, and the extension.
     *
     * <p>Prefers a direct prefix-strip: if the cleaned stem starts with titleCode (case-insensitive),
     * the freeform is everything after it — this handles codes that already include a {@code _4K}
     * token (e.g. titleCode {@code MIKR-055_4K}, stem {@code MIKR-055_4K-h265} → {@code -h265}).
     *
     * <p>Second attempt: separator-normalized match, treating {@code -} and {@code _} as equivalent.
     * Handles cases where the titleCode freeform uses {@code _4K} but the file uses {@code -4k}
     * (e.g. titleCode {@code START-488_4K}, stem {@code start-488-4k} → freeform {@code ""}).
     * Since both separators are one character, the length-based index into the original stem is exact.
     *
     * <p>Falls back to the regex-based {@link #freeformSuffix(String)} when the stem does not start
     * with the titleCode — handles the numeric-prefix case where the DB-stored code has the leading
     * digits stripped (e.g. DB code {@code MIUM-1355}, filename {@code 300MIUM-1355-h265.mkv}).
     */
    private static String freeformSuffix(String cleanStem, String titleCode, String ext) {
        String stemLower = cleanStem.toLowerCase(Locale.ROOT);
        String codeLower = titleCode.toLowerCase(Locale.ROOT);
        if (stemLower.startsWith(codeLower)) {
            return cleanStem.substring(titleCode.length());
        }
        // Separator-normalized: treat - and _ as equivalent (e.g. START-488_4K vs start-488-4k)
        if (normSep(stemLower).startsWith(normSep(codeLower))) {
            return cleanStem.substring(titleCode.length());
        }
        return freeformSuffix(cleanStem + ext);
    }

    private static String normSep(String s) {
        return s.replace('-', '_');
    }

    /**
     * Extracts the freeform suffix from a video/cover filename, preserving the separator.
     * Input should be the cleaned filename (removelist already applied).
     * Format: {@code {label}-{seq}[-_]{freeform}.ext} — returns {@code [-_]{freeform}} or {@code ""}.
     * Label may start with up to 6 digits (e.g. {@code 300MIUM}).
     * Example: {@code "ONED-999-h265.mkv"} → {@code "-h265"},  {@code "300MIUM-999_4K.mkv"} → {@code "_4K"}.
     */
    static String freeformSuffix(String filename) {
        int dot = filename.lastIndexOf('.');
        String stem = dot >= 0 ? filename.substring(0, dot) : filename;
        // Match optional leading digits + label letters + dash + seq digits, then capture freeform
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^[0-9]{0,6}[A-Za-z][A-Za-z0-9]{0,9}-\\d+([-_].+)?$")
                .matcher(stem);
        if (m.matches()) {
            String suffix = m.group(1);
            return suffix != null ? suffix : "";
        }
        return "";
    }

    private String applyRemovelist(String stem) {
        for (String token : normalize.effectiveRemovelist()) {
            if (token == null || token.isEmpty()) continue;
            stem = Pattern.compile(Pattern.quote(token), Pattern.CASE_INSENSITIVE)
                          .matcher(stem).replaceAll("");
        }
        return stem;
    }

    private static String extensionWithDot(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot < 0) return "";
        // preserve original-case extension so e.g. "MIDE-123.JPG" stays ".JPG" on rename
        return filename.substring(dot);
    }

    private static String filename(Path p) {
        Path n = p.getFileName();
        return n == null ? p.toString() : n.toString();
    }

    // ── result shapes ──────────────────────────────────────────────────────

    public record Action(String op, String from, String to) {}

    public record Skip(String kind, String filename, String reason) {}

    public record Result(
            boolean dryRun,
            String titleFolder,
            List<Action> planned,
            List<Action> applied,
            List<Action> failed,
            List<Skip> skipped
    ) {}
}
