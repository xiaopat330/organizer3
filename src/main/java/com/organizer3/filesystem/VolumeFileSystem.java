package com.organizer3.filesystem;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Facade for all filesystem operations within a mounted volume.
 *
 * <p>All operations are intra-volume — moves and renames never cross share boundaries,
 * which allows atomic operations throughout.
 *
 * <p>Two implementations:
 * <ul>
 *   <li>{@link LocalFileSystem} — delegates to {@code java.nio.file.Files}; used in armed mode
 *   <li>{@link DryRunFileSystem} — read ops execute normally; write ops log what would happen
 * </ul>
 */
public interface VolumeFileSystem {

    // -------------------------------------------------------------------------
    // Read operations — both implementations execute these against the real FS
    // -------------------------------------------------------------------------

    /**
     * Returns the immediate children of {@code path} (non-recursive).
     */
    List<Path> listDirectory(Path path) throws IOException;

    /**
     * Recursively returns all paths under {@code root}, depth-first, including {@code root} itself.
     */
    List<Path> walk(Path root) throws IOException;

    boolean exists(Path path);

    boolean isDirectory(Path path);

    /**
     * Returns the last-modified date of the file at {@code path}, or {@code null} if the
     * date cannot be determined.
     */
    LocalDate getLastModifiedDate(Path path) throws IOException;

    /**
     * Opens the file at {@code path} for reading and returns an {@link InputStream}.
     * The caller is responsible for closing the stream.
     */
    InputStream openFile(Path path) throws IOException;

    /**
     * Returns the size of the file at {@code path} in bytes. Behavior on directories
     * is implementation-defined; callers should only invoke on regular files.
     */
    long size(Path path) throws IOException;

    // -------------------------------------------------------------------------
    // Write operations
    // LocalFileSystem:    executes via java.nio.file.Files and logs to audit
    // DryRunFileSystem:   logs the intended operation; does NOT touch the filesystem
    // -------------------------------------------------------------------------

    /**
     * Moves {@code source} to {@code destination}. Both paths must be on the same volume.
     * Uses an atomic move where the filesystem supports it.
     */
    void move(Path source, Path destination) throws IOException;

    /**
     * Renames the file or directory at {@code path} to {@code newName},
     * keeping it in the same parent directory.
     */
    void rename(Path path, String newName) throws IOException;

    /**
     * Creates {@code path} and any missing parent directories (mkdir -p semantics).
     */
    void createDirectories(Path path) throws IOException;

    /**
     * Writes {@code contents} to {@code path}, creating or overwriting. The parent directory
     * must already exist. Atomicity is not guaranteed — on SMB this is a sequential write.
     */
    void writeFile(Path path, byte[] contents) throws IOException;

    /**
     * Deletes {@code path} from the filesystem.
     *
     * <p>If {@code path} is a directory, deletes it recursively (children first). If {@code path}
     * does not exist, the call is a no-op. Must not throw if the path is already gone.
     *
     * <p>On SMB, directories must be empty before the server accepts a delete request, so
     * implementations walk children first (leaf-first order).
     *
     * <p>Test-only fake implementations may leave this as the default (throws
     * {@link UnsupportedOperationException}) unless delete is exercised by their tests.
     */
    default void delete(Path path) throws IOException {
        throw new UnsupportedOperationException("delete not implemented for " + getClass().getSimpleName());
    }

    /**
     * Returns the file or folder's timestamps. Works for both files and directories.
     * Fields may be {@code null} if not supported by the underlying filesystem.
     */
    FileTimestamps getTimestamps(Path path) throws IOException;

    /**
     * Sets the file or folder's creation and/or modification time. A {@code null} arg leaves
     * that timestamp unchanged. Works for both files and directories.
     *
     * <p>Required SMB access: {@code FILE_WRITE_ATTRIBUTES} in addition to {@code FILE_READ_ATTRIBUTES}
     * so we can preserve the other fields. Access time is intentionally not exposed —
     * it's usually OS-maintained and rarely useful to set from app code.
     */
    void setTimestamps(Path path, Instant created, Instant modified) throws IOException;
}
