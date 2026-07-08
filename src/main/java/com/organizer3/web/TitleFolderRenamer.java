package com.organizer3.web;

import com.organizer3.filesystem.VolumeFileSystem;
import com.organizer3.repository.jdbi.JdbiUnsortedEditorRepository;
import com.organizer3.smb.SmbConnectionFactory;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.Jdbi;

import com.organizer3.repository.UnsortedEditorRepository.StagingLocation;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Shared helper that renames a title's staging folder to match the canonical pattern:
 * <pre>
 *   {PrimaryActress} - {Descriptor} ({code})   // when descriptor is non-blank
 *   {PrimaryActress} ({code})                  // otherwise
 * </pre>
 *
 * <p>Owns: target-name construction, {@link #sanitizeFolderName}, the SMB {@code rename},
 * and the DB path rewrite (via {@link JdbiUnsortedEditorRepository#renameFolderInDb},
 * which is load-bearing because it rewrites BOTH {@code title_locations.path} and
 * {@code videos.path} — see the {@code reference_videos_path_desync} memory note).
 *
 * <p>Used by {@link UnsortedEditorService} (no-draft save path).  Phase 2 will wire
 * this into {@code DraftPromotionService} (post-commit best-effort step).
 */
@Slf4j
public class TitleFolderRenamer {

    /** Return type of {@link #renameIfNeeded}. */
    public record RenameOutcome(String newPath, boolean renamed) {}

    private final SmbConnectionFactory smbFactory;
    private final JdbiUnsortedEditorRepository repo;
    private final Set<String> serviceableVolumeIds;

    public TitleFolderRenamer(SmbConnectionFactory smbFactory, Jdbi jdbi, Set<String> serviceableVolumeIds) {
        this.smbFactory = smbFactory;
        this.repo       = new JdbiUnsortedEditorRepository(jdbi);
        this.serviceableVolumeIds = serviceableVolumeIds;
    }

    /** Back-compat single-volume ctor — wraps the one volume in a serviceable set. */
    public TitleFolderRenamer(SmbConnectionFactory smbFactory, Jdbi jdbi, String volumeId) {
        this(smbFactory, jdbi, Set.of(volumeId));
    }

    /**
     * If the title's live staging folder name differs from the target pattern, performs
     * the SMB rename and updates BOTH {@code title_locations.path} and {@code videos.path}.
     *
     * <p>Semantics on failure:
     * <ul>
     *   <li>No live staging row for this title → no-op ({@code renamed=false, newPath=null}).
     *   <li>{@code primaryActressName} null/blank → no-op ({@code renamed=false, newPath=currentPath}).
     *   <li>Target matches current basename → no-op ({@code renamed=false, newPath=currentPath}).
     *   <li>Target folder already exists (collision) → throws {@link IllegalStateException}.
     *   <li>SMB / IO failure → throws {@link RuntimeException}.
     * </ul>
     *
     * @param titleId           the title to rename.
     * @param primaryActressName canonical name of the primary actress (may be null).
     * @param descriptor         optional middle segment (e.g. "Demosaiced"); null/blank → omitted.
     * @param code               the title code (e.g. "ABP-527").
     * @return the outcome, including the effective path and whether an actual rename occurred.
     */
    public RenameOutcome renameIfNeeded(long titleId, String primaryActressName,
                                        String descriptor, String code) {
        // Step 1 — resolve the live staging location (volume + path) among serviceable volumes.
        Optional<StagingLocation> locOpt = repo.findServiceableStagingLocation(titleId, serviceableVolumeIds);
        if (locOpt.isEmpty()) {
            return new RenameOutcome(null, false);
        }
        StagingLocation loc = locOpt.get();

        // Steps 2-6 — delegate to core (volume + path already resolved).
        return renameWithKnownPath(titleId, loc.volumeId(), primaryActressName, descriptor, code, loc.path());
    }

    /**
     * Multi-name overload of {@link #renameIfNeeded}: renames the staging folder using ALL
     * supplied ordered actress names. An empty list is a no-op.
     *
     * @param titleId    the title to rename.
     * @param names      ordered actress names (filing actress first); empty → no-op.
     * @param descriptor optional middle segment; null/blank → omitted.
     * @param code       the title code.
     * @return the outcome, including the effective path and whether an actual rename occurred.
     */
    public RenameOutcome renameIfNeeded(long titleId, List<String> names,
                                        String descriptor, String code) {
        Optional<StagingLocation> locOpt = repo.findServiceableStagingLocation(titleId, serviceableVolumeIds);
        if (locOpt.isEmpty()) {
            return new RenameOutcome(null, false);
        }
        StagingLocation loc = locOpt.get();
        return renameWithKnownPath(titleId, loc.volumeId(), names, descriptor, code, loc.path());
    }

    /**
     * Renames the title's staging folder while preserving whatever descriptor is already
     * embedded in the current folder name (e.g. "Demosaiced").
     *
     * <p>Resolves the current staging path once, extracts the descriptor via
     * {@link #extractDescriptor}, then delegates to the same rename logic as
     * {@link #renameIfNeeded}.
     *
     * <p>Called by {@code DraftPromotionService} post-commit (best-effort). On any failure
     * the caller is expected to log WARN and return {@code folderRenamed=false} — this method
     * propagates {@link IllegalStateException} on collision and {@link RuntimeException} on
     * SMB/IO failure, exactly as {@link #renameIfNeeded} does.
     *
     * @param titleId            the title to rename.
     * @param primaryActressName canonical name of the primary actress (may be null → no-op).
     * @param code               the title code (e.g. "ABP-527").
     * @return the outcome, including the effective path and whether an actual rename occurred.
     */
    public RenameOutcome renamePreservingDescriptor(long titleId, String primaryActressName,
                                                    String code) {
        List<String> names = (primaryActressName == null || primaryActressName.isBlank())
                ? List.of() : List.of(primaryActressName);
        return renamePreservingDescriptor(titleId, names, code);
    }

    /**
     * Multi-name overload of {@link #renamePreservingDescriptor}: renames the staging folder
     * using ALL supplied ordered actress names (joined by ", ") while preserving any descriptor
     * already embedded in the current folder name.
     *
     * <p>An empty list is a no-op ({@code renamed=false, newPath=currentPath}).
     *
     * @param titleId the title to rename.
     * @param names   ordered actress names (filing actress first); must be non-empty to rename.
     * @param code    the title code (e.g. "ADN-778").
     * @return the outcome, including the effective path and whether an actual rename occurred.
     */
    public RenameOutcome renamePreservingDescriptor(long titleId, List<String> names, String code) {
        // Resolve the live staging location (volume + path) among serviceable volumes.
        Optional<StagingLocation> locOpt = repo.findServiceableStagingLocation(titleId, serviceableVolumeIds);
        if (locOpt.isEmpty()) {
            return new RenameOutcome(null, false);
        }
        StagingLocation loc = locOpt.get();
        String currentPath = loc.path();

        // Parse the descriptor from the current folder name.
        String descriptor = extractDescriptor(basename(currentPath), code);

        // Delegate to the core rename logic (volume + path already resolved — avoid a second DB
        // call by reusing the currentPath we already have).
        return renameWithKnownPath(titleId, loc.volumeId(), names, descriptor, code, currentPath);
    }

    /**
     * Core rename logic that accepts an already-resolved {@code currentPath}.
     * Extracted so {@link #renamePreservingDescriptor} can reuse it without a second DB round-trip.
     */
    private RenameOutcome renameWithKnownPath(long titleId, String volumeId, String primaryActressName,
                                              String descriptor, String code,
                                              String currentPath) {
        List<String> names = (primaryActressName == null || primaryActressName.isBlank())
                ? List.of() : List.of(primaryActressName);
        return renameWithKnownPath(titleId, volumeId, names, descriptor, code, currentPath);
    }

    /**
     * Core rename logic (list-based) that accepts an already-resolved {@code currentPath}.
     * Called by callers that supply the full ordered cast list (for multi-name staging folders).
     * An empty list is a no-op ({@code renamed=false, newPath=currentPath}).
     */
    private RenameOutcome renameWithKnownPath(long titleId, String volumeId, List<String> names,
                                              String descriptor, String code,
                                              String currentPath) {
        // Require at least one name.
        if (names == null || names.isEmpty()) {
            return new RenameOutcome(currentPath, false);
        }

        // Build target name (shared construction — see targetFolderName).
        String targetName = targetFolderName(names, descriptor, code);

        // No-op if already correct.
        String currentName = basename(currentPath);
        if (targetName.equals(currentName)) {
            return new RenameOutcome(currentPath, false);
        }

        // SMB rename with collision check.
        String parent  = parentPath(currentPath);
        String newPath = parent.isEmpty() ? targetName : parent + "/" + targetName;

        try {
            // Route through withRetry: on a broken-pipe/transport error mid-rename the
            // factory evicts the stale pooled connection, reconnects, and retries the op
            // once. The op is made idempotent below so the retry can't false-collide.
            smbFactory.withRetry(volumeId, handle -> {
                VolumeFileSystem fs = handle.fileSystem();
                boolean curExists = fs.exists(Path.of(currentPath));
                boolean newExists = fs.exists(Path.of(newPath));
                if (!curExists && newExists) {
                    // A prior attempt already completed the rename (ack lost to the broken
                    // pipe). Treat as success — do NOT throw a false collision.
                    return null;
                }
                if (newExists && !newPath.equalsIgnoreCase(currentPath)) {
                    throw new IllegalStateException("Target folder already exists: " + newPath);
                }
                fs.rename(Path.of(currentPath), targetName);
                log.info("FS mutation [TitleFolderRenamer.renameFolder]: volume={} titleId={} from={} to={}",
                        volumeId, titleId, currentPath, newPath);
                return null;
            });
        } catch (IllegalStateException e) {
            throw e;  // collision — propagate as-is
        } catch (IOException e) {
            String rootCause = e.getCause() != null ? e.getCause().toString() : "(no cause)";
            log.warn("Folder rename failed for title {} ({} -> {}): {} / root: {}",
                    titleId, currentPath, newPath, e.getMessage(), rootCause);
            throw new RuntimeException("Folder rename failed: " + e.getMessage() + " / " + rootCause, e);
        }

        // Rewrite BOTH title_locations.path AND videos.path (load-bearing dual rewrite).
        repo.renameFolderInDb(titleId, volumeId, currentPath, newPath);
        return new RenameOutcome(newPath, true);
    }

    /**
     * Pulls the folder-name descriptor (e.g. "Demosaiced") out of a basename like
     * {@code "Nao Wakana - Demosaiced (ABP-527)"}. Returns empty string when there is no
     * {@code " - "} separator before the code. The prefix (actress / title stub) is discarded
     * — we only keep the text that would sit after the primary actress on a rewrite.
     *
     * <p>This is the single source of truth for descriptor extraction.
     * {@link UnsortedEditorService#extractDescriptor} delegates to this method.
     */
    public static String extractDescriptor(String folderName, String code) {
        if (folderName == null || code == null) return "";
        String suffix = " (" + code + ")";
        if (!folderName.endsWith(suffix)) return "";
        String prefix = folderName.substring(0, folderName.length() - suffix.length());
        int sep = prefix.indexOf(" - ");
        if (sep < 0) return "";
        return prefix.substring(sep + 3).trim();
    }

    /** Maximum basename length before the {@code Various (CODE)} overflow form is used. */
    public static final int MAX_FOLDER_NAME_LEN = 200;

    public static String targetFolderName(String primaryActressName, String descriptor, String code) {
        return targetFolderName(List.of(primaryActressName), descriptor, code);
    }

    /**
     * Builds the canonical target folder basename from an ordered list of actress names,
     * applying {@link #sanitizeFolderName}.
     *
     * <p>Names are joined with {@code ", "}. If the assembled basename (pre-sanitize) would exceed
     * {@link #MAX_FOLDER_NAME_LEN} characters, the overflow form {@code "Various (CODE)"} (or
     * {@code "Various - Desc (CODE)"} when descriptor is non-blank) is used instead.
     *
     * <p>Single-name input produces output byte-identical to
     * {@link #targetFolderName(String, String, String)}.
     *
     * @param names      ordered actress names; must be non-empty (caller guards this).
     * @param descriptor optional middle segment (e.g. "Demosaiced"); null/blank → omitted.
     * @param code       the title code (e.g. "ADN-778").
     * @return the sanitized target basename.
     */
    public static String targetFolderName(List<String> names, String descriptor, String code) {
        String desc = descriptor == null ? "" : descriptor.trim();
        String joined = String.join(", ", names);
        String base = desc.isEmpty()
                ? joined + " (" + code + ")"
                : joined + " - " + desc + " (" + code + ")";
        if (base.length() > MAX_FOLDER_NAME_LEN) {
            // Overflow: use "Various (CODE)" or "Various - Desc (CODE)"
            String overflow = desc.isEmpty()
                    ? "Various (" + code + ")"
                    : "Various - " + desc + " (" + code + ")";
            return sanitizeFolderName(overflow);
        }
        return sanitizeFolderName(base);
    }

    /**
     * Returns {@code true} when the given volume structure type uses multi-name staging folders
     * (i.e. all credited cast appear in the folder name). Currently only {@code "queue"} (the
     * staging/unsorted volume) uses multi-name naming; all other structure types use single-name.
     *
     * <p>Callers consult this predicate to decide whether to pass a multi-name list or a
     * single-name list to {@link #targetFolderName(List, String, String)}.
     *
     * @param structureType the {@code volumes.structure_type} value (e.g. {@code "queue"},
     *                      {@code "conventional"}, {@code "collections"}).
     * @return {@code true} for {@code "queue"} structure types; {@code false} for all others.
     */
    public static boolean usesMultiNameFolders(String structureType) {
        return "queue".equals(structureType);
    }

    // ── Static helpers (single source of truth; UnsortedEditorService delegates to these) ──

    /**
     * Strip filesystem-unsafe characters. Keeps letters, digits, spaces, parens, hyphens,
     * dots, ampersands, apostrophes. Replaces forbidden chars with space and collapses runs.
     */
    public static String sanitizeFolderName(String raw) {
        StringBuilder sb = new StringBuilder(raw.length());
        for (char c : raw.toCharArray()) {
            if (c == '/' || c == '\\' || c == ':' || c == '*' || c == '?' || c == '"'
                    || c == '<' || c == '>' || c == '|') {
                sb.append(' ');
            } else {
                sb.append(c);
            }
        }
        return sb.toString().replaceAll("\\s+", " ").trim();
    }

    public static String basename(String path) {
        int i = path.lastIndexOf('/');
        return i < 0 ? path : path.substring(i + 1);
    }

    static String parentPath(String path) {
        int i = path.lastIndexOf('/');
        return i < 0 ? "" : path.substring(0, i);
    }
}
