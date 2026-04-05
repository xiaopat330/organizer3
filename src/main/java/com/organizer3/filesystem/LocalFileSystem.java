package com.organizer3.filesystem;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
}
