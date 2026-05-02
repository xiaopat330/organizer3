package com.organizer3.javdb.draft;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.Optional;

/**
 * Manages the per-draft cover image scratch area under
 * {@code <dataDir>/_sandbox/draft_covers/}.
 *
 * <p>Each draft title gets at most one scratch cover file named
 * {@code <draftTitleId>.jpg}. The directory is created lazily on first write.
 *
 * <p>Writes are atomic: bytes are staged to a {@code .tmp} file then
 * renamed with {@link StandardCopyOption#ATOMIC_MOVE} so a crash during
 * write can never leave a partial file visible to readers.
 *
 * <p>See spec/PROPOSAL_DRAFT_MODE.md §7.
 */
public class DraftCoverScratchStore {

    private final Path scratchDir;

    /** Creates a store rooted at {@code <dataDir>/_sandbox/draft_covers}. */
    public DraftCoverScratchStore(Path dataDir) {
        this.scratchDir = dataDir.resolve("_sandbox").resolve("draft_covers");
    }

    /**
     * Writes {@code bytes} to the scratch cover file for {@code draftTitleId}.
     *
     * <p>The target directory is created if it does not exist. The write is
     * performed atomically (stage then rename).
     *
     * @throws IOException if the directory cannot be created or the write fails.
     */
    public void write(long draftTitleId, byte[] bytes) throws IOException {
        Files.createDirectories(scratchDir);
        Path target = coverPath(draftTitleId);
        Path tmp    = scratchDir.resolve(draftTitleId + ".jpg.tmp");
        Files.write(tmp, bytes);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /**
     * Reads the scratch cover bytes for {@code draftTitleId}, if present.
     *
     * @return the file bytes, or empty if no cover has been stored.
     * @throws IOException if reading fails.
     */
    public Optional<byte[]> read(long draftTitleId) throws IOException {
        Path p = coverPath(draftTitleId);
        if (!Files.exists(p)) return Optional.empty();
        return Optional.of(Files.readAllBytes(p));
    }

    /**
     * Opens an {@link InputStream} for the scratch cover of {@code draftTitleId}.
     *
     * @return a stream, or empty if no cover has been stored.
     * @throws IOException if opening the stream fails.
     */
    public Optional<InputStream> openStream(long draftTitleId) throws IOException {
        Path p = coverPath(draftTitleId);
        if (!Files.exists(p)) return Optional.empty();
        return Optional.of(Files.newInputStream(p));
    }

    /**
     * Deletes the scratch cover for {@code draftTitleId}. No-op if the file
     * does not exist.
     *
     * @throws IOException if deletion fails.
     */
    public void delete(long draftTitleId) throws IOException {
        Files.deleteIfExists(coverPath(draftTitleId));
    }

    /** Returns {@code true} if a scratch cover exists for {@code draftTitleId}. */
    public boolean exists(long draftTitleId) {
        return Files.exists(coverPath(draftTitleId));
    }

    /** Returns the absolute path for the cover file (whether it exists or not). */
    public Path coverPath(long draftTitleId) {
        return scratchDir.resolve(draftTitleId + ".jpg");
    }

    /**
     * Deletes scratch cover files whose draft id is not in {@code liveIds}.
     *
     * <p>Any file whose name cannot be parsed as a long integer (e.g., {@code .tmp}
     * leftovers from a crash) is skipped silently — only files matching
     * {@code <numeric-id>.jpg} are considered.
     *
     * <p>If the scratch directory does not yet exist (nothing has been written since
     * startup), returns 0 immediately without throwing.
     *
     * @param liveIds the set of draft title ids whose files must NOT be deleted.
     * @return the number of orphan files deleted.
     * @throws IOException if a file deletion fails.
     */
    public int reapOrphans(Collection<Long> liveIds) throws IOException {
        if (!Files.exists(scratchDir)) return 0;

        int count = 0;
        try (var stream = Files.list(scratchDir)) {
            for (Path file : (Iterable<Path>) stream::iterator) {
                String name = file.getFileName().toString();
                if (!name.endsWith(".jpg")) continue;
                String stem = name.substring(0, name.length() - 4);
                long id;
                try {
                    id = Long.parseLong(stem);
                } catch (NumberFormatException e) {
                    continue; // not a draft cover file — skip
                }
                if (!liveIds.contains(id)) {
                    Files.deleteIfExists(file);
                    count++;
                }
            }
        }
        return count;
    }
}
