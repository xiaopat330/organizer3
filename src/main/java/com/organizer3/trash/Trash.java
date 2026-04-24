package com.organizer3.trash;

import com.organizer3.filesystem.VolumeFileSystem;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.format.DateTimeFormatter;

/**
 * Trash primitive — a volume-aware, per-volume move-to-trash mechanism.
 *
 * <p>Per {@code spec/PROPOSAL_TRASH.md}: moves an item to a {@code _trash/} folder at the
 * volume's share root, mirroring the original directory tree inside trash, and writes a
 * JSON sidecar describing what was trashed and when. The move is atomic (single SMB rename),
 * so trashing is instantaneous regardless of size.
 *
 * <p><b>Constraints:</b>
 * <ul>
 *   <li>Intra-volume only — the trash folder lives on the same share as the item.
 *   <li>Sidecar loss is acceptable — if the metadata write fails after a successful move,
 *       the item is still in trash and the operation is considered successful.
 *   <li>No restore or permanent-delete semantics — the app never empties the trash.
 * </ul>
 */
@Slf4j
public class Trash {

    private final VolumeFileSystem fs;
    private final String volumeId;
    private final String trashFolder;  // e.g. "_trash"
    private final Clock clock;

    public Trash(VolumeFileSystem fs, String volumeId, String trashFolder, Clock clock) {
        if (fs == null) throw new IllegalArgumentException("fs is required");
        if (volumeId == null || volumeId.isBlank()) throw new IllegalArgumentException("volumeId is required");
        if (trashFolder == null || trashFolder.isBlank()) throw new IllegalArgumentException("trashFolder is required");
        this.fs = fs;
        this.volumeId = volumeId;
        this.trashFolder = trashFolder;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    /**
     * Moves {@code itemPath} into the volume's trash folder, preserving its directory tree,
     * and writes a JSON sidecar next to the trashed item.
     *
     * @param itemPath  share-relative absolute path of the item to trash (e.g. {@code /stars/popular/MIDE-123})
     * @param reason    human-readable explanation of why the item was trashed (e.g. {@code "Duplicate Triage — kept peer on volume vol-a"})
     * @return the resulting paths inside the trash folder
     */
    public Result trashItem(Path itemPath, String reason) throws IOException {
        if (itemPath == null) throw new IllegalArgumentException("itemPath is required");
        if (!itemPath.isAbsolute()) {
            throw new IllegalArgumentException("itemPath must be absolute (rooted at /): " + itemPath);
        }
        if (itemPath.equals(Path.of("/"))) {
            throw new IllegalArgumentException("Cannot trash volume root");
        }
        Path trashRoot = Path.of("/", trashFolder);
        if (itemPath.startsWith(trashRoot)) {
            throw new IllegalArgumentException("Item is already in " + trashFolder + ": " + itemPath);
        }

        Path originalParent = itemPath.getParent();           // e.g. /stars/popular
        Path trashParent = mirrorUnderTrash(originalParent);  // e.g. /_trash/stars/popular
        fs.createDirectories(trashParent);

        Path trashed = trashParent.resolve(itemPath.getFileName().toString());
        fs.move(itemPath, trashed);
        log.info("FS mutation [Trash.trash]: moved to trash — volume={} reason={} from={} to={}",
                volumeId, reason, itemPath, trashed);

        Path sidecar = trashParent.resolve(itemPath.getFileName().toString() + ".json");
        try {
            TrashSidecar sc = new TrashSidecar(
                    itemPath.toString(),
                    DateTimeFormatter.ISO_INSTANT.format(clock.instant()),
                    volumeId,
                    reason == null ? "unknown" : reason,
                    null, null, null
            );
            sc.write(fs, sidecar);
        } catch (IOException e) {
            log.warn("Trash sidecar write failed (best-effort) for {}: {}", sidecar, e.getMessage());
        }
        return new Result(trashed, sidecar);
    }

    /** Maps {@code /stars/popular} to {@code /<trashFolder>/stars/popular}. Handles root. */
    Path mirrorUnderTrash(Path originalParent) {
        Path trashRoot = Path.of("/", trashFolder);
        if (originalParent == null || originalParent.equals(Path.of("/"))) {
            return trashRoot;
        }
        String stripped = originalParent.toString();
        if (stripped.startsWith("/")) stripped = stripped.substring(1);
        return trashRoot.resolve(stripped);
    }

    public record Result(Path trashedPath, Path sidecarPath) {}
}
