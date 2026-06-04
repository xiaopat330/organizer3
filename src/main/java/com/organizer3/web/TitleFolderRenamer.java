package com.organizer3.web;

import com.organizer3.filesystem.VolumeFileSystem;
import com.organizer3.repository.jdbi.JdbiUnsortedEditorRepository;
import com.organizer3.smb.SmbConnectionFactory;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.Jdbi;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

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
    private final String volumeId;

    public TitleFolderRenamer(SmbConnectionFactory smbFactory, Jdbi jdbi, String volumeId) {
        this.smbFactory = smbFactory;
        this.repo       = new JdbiUnsortedEditorRepository(jdbi);
        this.volumeId   = volumeId;
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
        // Step 1 — resolve the live staging path.
        Optional<String> currentPathOpt = repo.findStagingPath(titleId, volumeId);
        if (currentPathOpt.isEmpty()) {
            return new RenameOutcome(null, false);
        }
        String currentPath = currentPathOpt.get();

        // Steps 2-6 — delegate to core (path already resolved).
        return renameWithKnownPath(titleId, primaryActressName, descriptor, code, currentPath);
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
        // Resolve the live staging path.
        Optional<String> currentPathOpt = repo.findStagingPath(titleId, volumeId);
        if (currentPathOpt.isEmpty()) {
            return new RenameOutcome(null, false);
        }
        String currentPath = currentPathOpt.get();

        // Parse the descriptor from the current folder name.
        String descriptor = extractDescriptor(basename(currentPath), code);

        // Delegate to the core rename logic (path already resolved — avoid a second DB call
        // by inlining the logic so we can reuse the currentPath we already have).
        return renameWithKnownPath(titleId, primaryActressName, descriptor, code,
                currentPath);
    }

    /**
     * Core rename logic that accepts an already-resolved {@code currentPath}.
     * Extracted so {@link #renamePreservingDescriptor} can reuse it without a second DB round-trip.
     */
    private RenameOutcome renameWithKnownPath(long titleId, String primaryActressName,
                                              String descriptor, String code,
                                              String currentPath) {
        // Require a primary name.
        if (primaryActressName == null || primaryActressName.isBlank()) {
            return new RenameOutcome(currentPath, false);
        }

        // Build target name (shared construction — see targetFolderName).
        String targetName = targetFolderName(primaryActressName, descriptor, code);

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

    /**
     * Builds the canonical target folder basename for a title, applying {@link #sanitizeFolderName}.
     * This is the single source of truth for target-name construction — both
     * {@link #renameWithKnownPath} (the live rename) and the promotion reconciler
     * ({@code PromotionFolderRenameReconciler}) use it so their no-op / needs-rename
     * detection agrees byte-for-byte with what the rename would actually produce.
     *
     * @param primaryActressName canonical name of the primary actress (must be non-blank).
     * @param descriptor         optional middle segment (e.g. "Demosaiced"); null/blank → omitted.
     * @param code               the title code (e.g. "ABP-527").
     * @return the sanitized target basename.
     */
    public static String targetFolderName(String primaryActressName, String descriptor, String code) {
        String desc = descriptor == null ? "" : descriptor.trim();
        String base = desc.isEmpty()
                ? primaryActressName + " (" + code + ")"
                : primaryActressName + " - " + desc + " (" + code + ")";
        return sanitizeFolderName(base);
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
