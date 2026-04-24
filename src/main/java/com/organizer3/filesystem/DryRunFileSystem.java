package com.organizer3.filesystem;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Dry-run filesystem implementation.
 *
 * <p>Read operations ({@link #listDirectory}, {@link #walk}, {@link #exists},
 * {@link #isDirectory}) delegate to the real filesystem — the live directory tree must be
 * visible so that callers can plan operations correctly.
 *
 * <p>Write operations ({@link #move}, {@link #rename}, {@link #createDirectories}) log their
 * intended action to the provided {@link PrintWriter} and do nothing else. The filesystem
 * is never modified.
 *
 * <p>Note: because write ops are no-ops, a sequence of dependent operations may produce
 * output that does not reflect what would actually succeed (e.g., moving into a directory
 * that a prior dry-run step would have created). This is a known limitation of simulation
 * without state tracking — the output is still useful as a human-readable preview.
 *
 * <p>A new instance should be created per command invocation, using the command's
 * {@link PrintWriter}.
 */
public class DryRunFileSystem implements VolumeFileSystem {

    private final PrintWriter out;

    public DryRunFileSystem(PrintWriter out) {
        this.out = out;
    }

    // -------------------------------------------------------------------------
    // Read operations — delegate to real filesystem
    // -------------------------------------------------------------------------

    @Override
    public List<Path> listDirectory(Path path) throws IOException {
        try (Stream<Path> stream = Files.list(path)) {
            return stream.collect(Collectors.toList());
        }
    }

    @Override
    public List<Path> walk(Path root) throws IOException {
        try (Stream<Path> stream = Files.walk(root)) {
            return stream.collect(Collectors.toList());
        }
    }

    @Override
    public boolean exists(Path path) {
        return Files.exists(path);
    }

    @Override
    public boolean isDirectory(Path path) {
        return Files.isDirectory(path);
    }

    @Override
    public LocalDate getLastModifiedDate(Path path) throws IOException {
        return Files.getLastModifiedTime(path).toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
    }

    @Override
    public InputStream openFile(Path path) throws IOException {
        return Files.newInputStream(path);
    }

    @Override
    public long size(Path path) throws IOException {
        return Files.size(path);
    }

    // -------------------------------------------------------------------------
    // Write operations — log only
    // -------------------------------------------------------------------------

    @Override
    public void move(Path source, Path destination) {
        out.printf("[DRY RUN] move:  %s%n         ->  %s%n", source, destination);
    }

    @Override
    public void rename(Path path, String newName) {
        out.printf("[DRY RUN] rename: %s -> %s%n", path.getFileName(), newName);
    }

    @Override
    public void createDirectories(Path path) {
        out.printf("[DRY RUN] mkdir: %s%n", path);
    }

    @Override
    public void writeFile(Path path, byte[] contents) {
        out.printf("[DRY RUN] write: %s (%d bytes)%n", path, contents.length);
    }

    @Override
    public void delete(Path path) {
        out.printf("[DRY RUN] delete: %s%n", path);
    }

    @Override
    public FileTimestamps getTimestamps(Path path) throws IOException {
        BasicFileAttributes a = Files.readAttributes(path, BasicFileAttributes.class);
        Instant created  = a.creationTime()     != null ? a.creationTime().toInstant()     : null;
        Instant modified = a.lastModifiedTime() != null ? a.lastModifiedTime().toInstant() : null;
        Instant accessed = a.lastAccessTime()   != null ? a.lastAccessTime().toInstant()   : null;
        return new FileTimestamps(created, modified, accessed);
    }

    @Override
    public void setTimestamps(Path path, Instant created, Instant modified) {
        out.printf("[DRY RUN] setTimestamps: %s created=%s modified=%s%n", path, created, modified);
    }
}
