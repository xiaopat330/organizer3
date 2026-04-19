package com.organizer3.filesystem;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Live filesystem implementation backed by {@code java.nio.file.Files}.
 *
 * <p>All volumes are accessed via OS-level SMB mounts ({@code mount_smbfs}), so standard
 * NIO paths work for everything — no third-party SMB library is needed.
 *
 * <p>Move and rename use {@link StandardCopyOption#ATOMIC_MOVE}. This is safe because all
 * operations are intra-volume (same share), and POSIX rename — which macOS SMB mounts support —
 * is atomic by definition.
 */
public class LocalFileSystem implements VolumeFileSystem {

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

    @Override
    public void move(Path source, Path destination) throws IOException {
        Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
    }

    @Override
    public void rename(Path path, String newName) throws IOException {
        Files.move(path, path.resolveSibling(newName), StandardCopyOption.ATOMIC_MOVE);
    }

    @Override
    public void createDirectories(Path path) throws IOException {
        Files.createDirectories(path);
    }

    @Override
    public void writeFile(Path path, byte[] contents) throws IOException {
        Files.write(path, contents);
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
    public void setTimestamps(Path path, Instant created, Instant modified) throws IOException {
        BasicFileAttributeView view = Files.getFileAttributeView(path, BasicFileAttributeView.class);
        FileTime mod = modified != null ? FileTime.from(modified) : null;
        FileTime cre = created  != null ? FileTime.from(created)  : null;
        // Signature: setTimes(lastModified, lastAccess, creation). null means "leave unchanged".
        view.setTimes(mod, null, cre);
    }
}
