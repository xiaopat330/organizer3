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

        // Step 2 — require a primary name.
        if (primaryActressName == null || primaryActressName.isBlank()) {
            return new RenameOutcome(currentPath, false);
        }

        // Step 3 — build target name.
        String desc = descriptor == null ? "" : descriptor.trim();
        String base = desc.isEmpty()
                ? primaryActressName + " (" + code + ")"
                : primaryActressName + " - " + desc + " (" + code + ")";
        String targetName = sanitizeFolderName(base);

        // Step 4 — no-op if already correct.
        String currentName = basename(currentPath);
        if (targetName.equals(currentName)) {
            return new RenameOutcome(currentPath, false);
        }

        // Step 5 — SMB rename with collision check.
        String parent  = parentPath(currentPath);
        String newPath = parent.isEmpty() ? targetName : parent + "/" + targetName;

        try (SmbConnectionFactory.SmbShareHandle handle = smbFactory.open(volumeId)) {
            VolumeFileSystem fs = handle.fileSystem();
            if (fs.exists(Path.of(newPath)) && !newPath.equalsIgnoreCase(currentPath)) {
                throw new IllegalStateException("Target folder already exists: " + newPath);
            }
            fs.rename(Path.of(currentPath), targetName);
            log.info("FS mutation [TitleFolderRenamer.renameFolder]: volume={} titleId={} from={} to={}",
                    volumeId, titleId, currentPath, newPath);
        } catch (IllegalStateException e) {
            throw e;  // collision — propagate as-is
        } catch (IOException e) {
            String rootCause = e.getCause() != null ? e.getCause().toString() : "(no cause)";
            log.warn("Folder rename failed for title {} ({} -> {}): {} / root: {}",
                    titleId, currentPath, newPath, e.getMessage(), rootCause);
            throw new RuntimeException("Folder rename failed: " + e.getMessage() + " / " + rootCause, e);
        }

        // Step 6 — rewrite BOTH title_locations.path AND videos.path (load-bearing dual rewrite).
        repo.renameFolderInDb(titleId, volumeId, currentPath, newPath);
        return new RenameOutcome(newPath, true);
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

    static String basename(String path) {
        int i = path.lastIndexOf('/');
        return i < 0 ? path : path.substring(i + 1);
    }

    static String parentPath(String path) {
        int i = path.lastIndexOf('/');
        return i < 0 ? "" : path.substring(0, i);
    }
}
