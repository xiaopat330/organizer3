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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "Prep" phase for the global pool — turn raw video files dumped into a queue
 * partition (e.g. {@code unsorted/fresh}) into the skeleton of a Title folder
 * that a human operator can then curate (add actress, add cover, etc.).
 *
 * <p>For each video file directly at the partition root:
 * <ol>
 *   <li>Normalize the filename via {@link NormalizeConfig}'s removelist/replacelist
 *       (legacy junk-prefix strip list).</li>
 *   <li>Parse a product code at the start of the stripped name. If no code can be
 *       confidently parsed, the file is skipped — human will handle it.</li>
 *   <li>Compute a target folder name {@code (CODE[_SUFFIX…])} — the product code
 *       uppercased, with any {@code _X} non-encoding suffix tokens preserved
 *       (e.g. {@code _4K}, {@code _U}, {@code _a}), wrapped in parens.</li>
 *   <li>Compute a subfolder: {@code h265/} if the name contains an {@code h265}
 *       encoding hint, else {@code video/}.</li>
 *   <li>Move the file into {@code (CODE)/<subfolder>/<normalized-filename>},
 *       keeping the encoding hint (and any freeform tail) in the filename.</li>
 * </ol>
 *
 * <p>Collision rule: if the target folder already exists at the partition root,
 * skip with reason — human decides whether to merge.
 *
 * <p><b>Upper-case normalization.</b> Per the prep-spec discussion: the <em>code
 * region</em> (label + digits + underscore-suffix tokens) is forced to upper case
 * in both the folder name and the video filename. Everything after that region
 * (the encoding hint and any freeform tail like {@code -h265}, {@code .mkv})
 * retains whatever case it had after prefix stripping. This matches the worked
 * examples: {@code foo.com@ONED-999-h265.mp4} → folder {@code (ONED-999)},
 * file {@code ONED-999-h265.mp4} (lowercase {@code h265}, lowercase {@code .mp4}
 * preserved).
 *
 * <p><b>Encoding tokens</b> are <em>not</em> stripped by the normalize pre-pass
 * even if the configured removelist contains them — stripping {@code -h265} /
 * {@code -h264} would destroy information the folder name needs. The service
 * filters encoding entries out of the removelist before applying it.
 */
@Slf4j
public class FreshPrepService {

    /** Code region: optional leading digits, then label (must contain a letter), dash, 2–8 digits, optional _suffix tokens. */
    private static final Pattern CODE_REGION = Pattern.compile(
            "^([0-9]{0,6}[A-Za-z][A-Za-z0-9]{0,9})-(\\d{2,8})((?:_[A-Za-z0-9]+)*)");

    /**
     * Exception shape: codes that end with a recognized studio-label suffix rather than
     * starting with a label (e.g. {@code 041126_001-1PON}, {@code 050825_001-CARIB}).
     * These are treated as literal — no uppercasing, no canonicalization. The regex
     * runs against the encoding-stripped stem; the matched prefix (up through the
     * suffix label) is used verbatim as the folder body and in the filename.
     */
    private static final Pattern SUFFIX_LABEL_CODE = Pattern.compile(
            "(?i)^(.+-(?:1PON|CARIB))(?=$|[-_ .])");

    /** Encoding hint detection (subfolder decision) and preservation against removelist stripping. */
    private static final Pattern H265_HINT = Pattern.compile("(?i)(?<![A-Za-z0-9])h265(?![A-Za-z0-9])");

    /** Strips {@code -h265} from a stem so the folder-body regex can see suffixes that live after the hint. */
    private static final Pattern ENCODING_STRIP = Pattern.compile("(?i)-h265");

    private final NormalizeConfig normalize;
    private final MediaConfig media;

    public FreshPrepService(NormalizeConfig normalize, MediaConfig media) {
        this.normalize = normalize == null ? NormalizeConfig.EMPTY : normalize;
        this.media     = media     == null ? MediaConfig.DEFAULTS   : media;
    }

    /** Compute the plan for all video files at the immediate children of {@code partitionRoot}. */
    public Result plan(VolumeFileSystem fs, Path partitionRoot, int limit, int offset) throws IOException {
        return run(fs, partitionRoot, true, limit, offset);
    }

    /** Execute (non-dry-run) the plan. */
    public Result execute(VolumeFileSystem fs, Path partitionRoot, int limit, int offset) throws IOException {
        return run(fs, partitionRoot, false, limit, offset);
    }

    private Result run(VolumeFileSystem fs, Path partitionRoot, boolean dryRun, int limit, int offset) throws IOException {
        if (!fs.exists(partitionRoot) || !fs.isDirectory(partitionRoot)) {
            throw new IllegalArgumentException("Partition root does not exist or is not a directory: " + partitionRoot);
        }

        List<Path> candidates = new ArrayList<>();
        for (Path child : fs.listDirectory(partitionRoot)) {
            if (fs.isDirectory(child)) continue;
            String name = filename(child);
            if (name == null || name.startsWith(".") || name.equalsIgnoreCase("Thumbs.db")) continue;
            if (!isVideo(name)) continue;
            candidates.add(child);
        }
        candidates.sort((a, b) -> filename(a).compareToIgnoreCase(filename(b)));

        int from = Math.max(0, offset);
        int to   = (limit <= 0) ? candidates.size() : Math.min(candidates.size(), from + limit);
        List<Path> page = from >= candidates.size() ? List.of() : candidates.subList(from, to);

        List<Plan> planned = new ArrayList<>();
        List<Skip> skipped = new ArrayList<>();

        for (Path src : page) {
            String original = filename(src);
            Plan p = planOne(partitionRoot, original);
            if (p == null) {
                skipped.add(new Skip(original, "unparseable — no product code found in normalized filename"));
                continue;
            }
            if (fs.exists(Path.of(p.targetFolder()))) {
                skipped.add(new Skip(original, "target folder already exists: " + p.targetFolder()));
                continue;
            }
            planned.add(p);
        }

        if (dryRun) {
            return new Result(true, partitionRoot.toString(), candidates.size(), planned, skipped, List.of(), List.of());
        }

        List<Plan> moved  = new ArrayList<>();
        List<Plan> failed = new ArrayList<>();
        for (Plan p : planned) {
            try {
                fs.createDirectories(Path.of(p.targetSubfolder()));
                fs.move(Path.of(p.sourcePath()), Path.of(p.targetVideoPath()));
                log.info("FS mutation [FreshPrep.prep]: moved raw video into title folder — partitionRoot={} from={} to={}",
                        partitionRoot, p.sourcePath(), p.targetVideoPath());
                moved.add(p);
            } catch (IOException e) {
                log.warn("FS mutation [FreshPrep.prep] failed — from={} to={} error={}",
                        p.sourcePath(), p.targetVideoPath(), e.getMessage());
                failed.add(p);
                skipped.add(new Skip(filename(Path.of(p.sourcePath())), "move failed: " + e.getMessage()));
            }
        }
        return new Result(false, partitionRoot.toString(), candidates.size(), planned, skipped, moved, failed);
    }

    /**
     * Compute the {@link Plan} for a single raw filename. Returns {@code null} if the
     * filename cannot be parsed (no product code found). Collision with an existing
     * target folder is not checked here — that's the caller's responsibility since it
     * requires a filesystem.
     */
    Plan planOne(Path partitionRoot, String rawFilename) {
        String ext  = extensionWithDot(rawFilename);          // ".mkv"
        String stem = rawFilename.substring(0, rawFilename.length() - ext.length());

        String stripped = applyRemovelist(stem);
        stripped = applyReplacelist(stripped);

        // Exception branch: suffix-label codes (1PON/CARIB style) are kept literal.
        // Match against the encoding-stripped stem so the trailing -h265 doesn't hide the label.
        String encodingFreeForSuffix = ENCODING_STRIP.matcher(stripped).replaceAll("");
        Matcher ms = SUFFIX_LABEL_CODE.matcher(encodingFreeForSuffix);
        if (ms.find()) {
            String literalCode = ms.group(1);
            String subName = H265_HINT.matcher(stripped).find() ? "h265" : "video";
            Path folder    = partitionRoot.resolve("(" + literalCode + ")");
            Path subfolder = folder.resolve(subName);
            Path target    = subfolder.resolve(stripped + ext);
            Path source    = partitionRoot.resolve(rawFilename);
            return new Plan(
                    source.toString(), folder.toString(), subfolder.toString(),
                    target.toString(), literalCode, subName);
        }

        // Video filename parsing — against the intact stripped stem (encoding hint preserved).
        Matcher mv = CODE_REGION.matcher(stripped);
        if (!mv.find()) return null;
        String videoCodeRegion =
                mv.group(1).toUpperCase(Locale.ROOT) + "-"
                + mv.group(2)
                + (mv.group(3) == null ? "" : mv.group(3).toUpperCase(Locale.ROOT));
        String remainder = stripped.substring(mv.end());
        String videoStem = videoCodeRegion + remainder;

        // Folder-body parsing — against an encoding-stripped copy so suffixes that live
        // after the encoding token (e.g. "ONED-999-h265_4K") collapse adjacent to the code.
        String encodingFree = ENCODING_STRIP.matcher(stripped).replaceAll("");
        Matcher mf = CODE_REGION.matcher(encodingFree);
        if (!mf.find()) return null;
        String folderBody =
                mf.group(1).toUpperCase(Locale.ROOT) + "-"
                + mf.group(2)
                + (mf.group(3) == null ? "" : mf.group(3).toUpperCase(Locale.ROOT));

        String subfolderName = H265_HINT.matcher(stripped).find() ? "h265" : "video";

        Path folder    = partitionRoot.resolve("(" + folderBody + ")");
        Path subfolder = folder.resolve(subfolderName);
        Path target    = subfolder.resolve(videoStem + ext);
        Path source    = partitionRoot.resolve(rawFilename);

        return new Plan(
                source.toString(),
                folder.toString(),
                subfolder.toString(),
                target.toString(),
                folderBody,
                subfolderName);
    }

    private String applyRemovelist(String s) {
        for (String token : normalize.effectiveRemovelist()) {
            if (token == null || token.isEmpty()) continue;
            if (isEncodingTokenToPreserve(token)) continue;   // never strip encoding hints here
            // case-insensitive literal replace
            s = replaceAllLiteralCI(s, token, "");
        }
        return s;
    }

    private String applyReplacelist(String s) {
        for (NormalizeConfig.Replace r : normalize.effectiveReplacelist()) {
            if (r == null || r.from() == null || r.from().isEmpty()) continue;
            if (isEncodingTokenToPreserve(r.from())) continue;
            s = replaceAllLiteralCI(s, r.from(), r.to() == null ? "" : r.to());
        }
        return s;
    }

    private static boolean isEncodingTokenToPreserve(String token) {
        String t = token.toLowerCase(Locale.ROOT);
        return t.contains("h265") || t.contains("h264");
    }

    private static String replaceAllLiteralCI(String haystack, String needle, String replacement) {
        String quoted = Pattern.quote(needle);
        return Pattern.compile(quoted, Pattern.CASE_INSENSITIVE).matcher(haystack).replaceAll(Matcher.quoteReplacement(replacement));
    }

    private boolean isVideo(String filename) {
        if (filename == null) return false;
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) return false;
        String ext = filename.substring(dot + 1).toLowerCase(Locale.ROOT);
        for (String e : media.effectiveVideoExtensions()) if (e.equalsIgnoreCase(ext)) return true;
        return false;
    }

    private static String extensionWithDot(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot < 0) return "";
        return filename.substring(dot);
    }

    private static String filename(Path p) {
        Path n = p.getFileName();
        return n == null ? p.toString() : n.toString();
    }

    // ── result shapes ──────────────────────────────────────────────────────

    /** One planned unit: source video → destination folder + subfolder + filename. */
    public record Plan(
            String sourcePath,
            String targetFolder,
            String targetSubfolder,
            String targetVideoPath,
            String code,
            String subfolderName
    ) {}

    public record Skip(String filename, String reason) {}

    public record Result(
            boolean dryRun,
            String partitionRoot,
            int totalVideosAtRoot,
            List<Plan> planned,
            List<Skip> skipped,
            List<Plan> moved,
            List<Plan> failed
    ) {}
}
