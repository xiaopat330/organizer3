package com.organizer3.sandbox;

import com.organizer3.filesystem.FileTimestamps;
import com.organizer3.filesystem.VolumeFileSystem;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * A {@link VolumeFileSystem} wrapper that maps absolute volume paths (e.g. {@code /stars/library/...})
 * through a sandbox base directory on the NAS (e.g. {@code /_sandbox/tests/SortSandboxTest-abc123/}).
 *
 * <p>This is the same pattern as {@code RebasingFS} in {@code TitleSorterServiceTest}, but
 * delegating to the real SMB filesystem rather than a local temp dir. It allows
 * {@link com.organizer3.organize.TitleSorterService} — which hardcodes absolute paths
 * like {@code /stars/{tier}/{actress}/} — to operate safely within a sandbox subtree
 * without touching the real NAS library structure.
 *
 * <p>All absolute paths passed by the service (e.g. {@code /queue/Title}, {@code /stars/library/Actress/Title})
 * are rebased to {@code base/queue/Title}, {@code base/stars/library/Actress/Title}, etc.
 *
 * <p>Note on {@code listDirectory}: returned paths are in the original volume namespace
 * (not the rebased NAS namespace) so callers can use them transparently.
 */
public class SmbRebasingFS implements VolumeFileSystem {

    private final VolumeFileSystem delegate;
    private final Path base;

    public SmbRebasingFS(VolumeFileSystem delegate, Path base) {
        this.delegate = delegate;
        this.base = base;
    }

    private Path rebase(Path p) {
        String s = p.toString();
        if (s.startsWith("/")) s = s.substring(1);
        return base.resolve(s);
    }

    @Override
    public List<Path> listDirectory(Path p) throws IOException {
        List<Path> out = new ArrayList<>();
        for (Path real : delegate.listDirectory(rebase(p))) {
            out.add(p.resolve(real.getFileName().toString()));
        }
        return out;
    }

    @Override public List<Path> walk(Path p) throws IOException { return delegate.walk(rebase(p)); }
    @Override public boolean exists(Path p) { return delegate.exists(rebase(p)); }
    @Override public boolean isDirectory(Path p) { return delegate.isDirectory(rebase(p)); }
    @Override public LocalDate getLastModifiedDate(Path p) throws IOException { return delegate.getLastModifiedDate(rebase(p)); }
    @Override public InputStream openFile(Path p) throws IOException { return delegate.openFile(rebase(p)); }
    @Override public long size(Path p) throws IOException { return delegate.size(rebase(p)); }
    @Override public void move(Path s, Path d) throws IOException { delegate.move(rebase(s), rebase(d)); }
    @Override public void rename(Path p, String n) throws IOException { delegate.rename(rebase(p), n); }
    @Override public void createDirectories(Path p) throws IOException { delegate.createDirectories(rebase(p)); }
    @Override public void writeFile(Path p, byte[] b) throws IOException { delegate.writeFile(rebase(p), b); }
    @Override public FileTimestamps getTimestamps(Path p) throws IOException { return delegate.getTimestamps(rebase(p)); }
    @Override public void setTimestamps(Path p, Instant c, Instant m) throws IOException { delegate.setTimestamps(rebase(p), c, m); }
}
