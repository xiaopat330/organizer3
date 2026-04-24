package com.organizer3.sandbox.trash;

import com.organizer3.filesystem.LocalFileSystem;
import com.organizer3.filesystem.VolumeFileSystem;
import com.organizer3.sandbox.SandboxTestBase;
import com.organizer3.trash.SweepReport;
import com.organizer3.trash.TrashService;
import com.organizer3.trash.TrashSidecar;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

import static org.junit.jupiter.api.Assertions.*;

@Tag("sandbox")
class TrashSweepSandboxTest extends SandboxTestBase {

    private final TrashService svc = new TrashService();
    private static final Instant NOW = Instant.parse("2026-04-23T10:00:00Z");
    private static final Instant PAST = NOW.minusSeconds(24 * 3600);   // yesterday
    private static final Instant FUTURE = NOW.plusSeconds(24 * 3600);  // tomorrow

    private SandboxTrashBuilder builder() {
        return new SandboxTrashBuilder(fs, trashRoot(), "vol-a");
    }

    @Test
    void deletesItemsPastScheduledTime() throws Exception {
        Path sidecar = builder().withOriginalPath("/items/MIDE-1")
                .withScheduledDeletionAt(PAST).build();
        Path itemPath = trashRoot().resolve("items/MIDE-1");

        SweepReport report = svc.sweepExpired(fs, "vol-a", trashRoot(), NOW);

        assertEquals(1, report.deleted());
        assertEquals(0, report.errors());
        assertFalse(Files.exists(itemPath),   "Item must be deleted");
        assertFalse(Files.exists(sidecar),    "Sidecar must be deleted");
    }

    @Test
    void skipsItemsWithFutureSchedule() throws Exception {
        Path sidecar = builder().withOriginalPath("/items/MIDE-2")
                .withScheduledDeletionAt(FUTURE).build();

        SweepReport report = svc.sweepExpired(fs, "vol-a", trashRoot(), NOW);

        assertEquals(0, report.deleted());
        assertEquals(1, report.skipped());
        assertTrue(Files.exists(sidecar), "Sidecar must remain — not yet due");
    }

    @Test
    void skipsItemsWithoutSchedule() throws Exception {
        Path sidecar = builder().withOriginalPath("/items/MIDE-3").build(); // no schedule

        SweepReport report = svc.sweepExpired(fs, "vol-a", trashRoot(), NOW);

        assertEquals(0, report.deleted());
        assertEquals(1, report.skipped());
        assertTrue(Files.exists(sidecar));
    }

    @Test
    void sweepOnEmptyTrashIsNoop() throws Exception {
        SweepReport report = svc.sweepExpired(fs, "vol-a", trashRoot(), NOW);

        assertEquals(0, report.deleted());
        assertEquals(0, report.errors());
    }

    @Test
    void foldersDeletedRecursively() throws Exception {
        // Trashed folder with nested files
        Path sidecar = builder().withOriginalPath("/stars/MIDE-4")
                .withScheduledDeletionAt(PAST).asFolder().build();
        Path folderPath = trashRoot().resolve("stars/MIDE-4");
        // Add nested content inside the trashed folder
        Files.createDirectories(folderPath.resolve("video/h265"));
        Files.write(folderPath.resolve("video/h265/movie.mkv"), new byte[]{0x00});

        SweepReport report = svc.sweepExpired(fs, "vol-a", trashRoot(), NOW);

        assertEquals(1, report.deleted());
        assertFalse(Files.exists(folderPath), "Trashed folder and all contents must be deleted");
        assertFalse(Files.exists(sidecar));
    }

    @Test
    void sweepPrunesEmptyAncestors() throws Exception {
        Path sidecar = builder().withOriginalPath("/a/b/c/foo")
                .withScheduledDeletionAt(PAST).build();

        svc.sweepExpired(fs, "vol-a", trashRoot(), NOW);

        assertFalse(Files.exists(trashRoot().resolve("a/b/c")), "_trash/a/b/c must be pruned");
        assertFalse(Files.exists(trashRoot().resolve("a/b")),   "_trash/a/b must be pruned");
        assertFalse(Files.exists(trashRoot().resolve("a")),     "_trash/a must be pruned");
        assertTrue(Files.exists(trashRoot()), "_trash/ itself must be preserved");
    }

    @Test
    void sweepRemovesOrphanSidecar() throws Exception {
        // Sidecar exists but item does not (deleted externally from NAS)
        Files.createDirectories(trashRoot().resolve("a"));
        Path orphanSidecar = trashRoot().resolve("a/foo.json");
        new TrashSidecar("/a/foo", "2026-04-22T10:00:00Z", "vol-a", "test",
                null, null, null).write(fs, orphanSidecar);
        // No /a/foo item

        SweepReport report = svc.sweepExpired(fs, "vol-a", trashRoot(), NOW);

        assertEquals(1, report.deleted());
        assertFalse(Files.exists(orphanSidecar), "Orphan sidecar must be deleted");
        assertFalse(Files.exists(trashRoot().resolve("a")), "_trash/a must be pruned after orphan sidecar removal");
    }

    @Test
    void deletionFailureRecordedOnSidecar() throws Exception {
        Path sidecar = builder().withOriginalPath("/items/MIDE-5")
                .withScheduledDeletionAt(PAST).build();
        Path itemPath = trashRoot().resolve("items/MIDE-5");

        // Fault-injecting FS: fails delete for MIDE-5 specifically
        FaultInjectingFS faultFS = new FaultInjectingFS(fs, Set.of(itemPath));

        SweepReport report = svc.sweepExpired(faultFS, "vol-a", trashRoot(), NOW);

        assertEquals(0, report.deleted());
        assertEquals(1, report.errors());

        // Sidecar still present with failure fields set
        assertTrue(Files.exists(sidecar), "Sidecar must remain after failed deletion");
        TrashSidecar sc = TrashSidecar.read(fs, sidecar);
        assertNotNull(sc.lastDeletionAttempt(), "lastDeletionAttempt must be set");
        assertNotNull(sc.lastDeletionError(),   "lastDeletionError must be set");
    }

    @Test
    void nextSweepRetriesAfterFailure() throws Exception {
        Path sidecar = builder().withOriginalPath("/items/MIDE-6")
                .withScheduledDeletionAt(PAST).build();
        Path itemPath = trashRoot().resolve("items/MIDE-6");

        // First sweep: fails
        FaultInjectingFS faultFS = new FaultInjectingFS(fs, Set.of(itemPath));
        svc.sweepExpired(faultFS, "vol-a", trashRoot(), NOW);

        // Second sweep: normal FS succeeds
        SweepReport report = svc.sweepExpired(fs, "vol-a", trashRoot(), NOW);

        assertEquals(1, report.deleted());
        assertEquals(0, report.errors());
        assertFalse(Files.exists(itemPath), "Item must be deleted on retry");
        assertFalse(Files.exists(sidecar),  "Sidecar must be deleted on retry");
    }

    // -------------------------------------------------------------------------
    // Fault-injecting FS wrapper — delegates to LocalFileSystem, throws on delete
    // for a configured set of paths.
    // -------------------------------------------------------------------------

    static class FaultInjectingFS implements VolumeFileSystem {

        private final LocalFileSystem delegate;
        private final Set<Path> failOnDelete;

        FaultInjectingFS(LocalFileSystem delegate, Set<Path> failOnDelete) {
            this.delegate = delegate;
            this.failOnDelete = new CopyOnWriteArraySet<>(failOnDelete);
        }

        @Override
        public void delete(Path path) throws IOException {
            if (failOnDelete.contains(path)) {
                throw new IOException("Simulated delete failure for: " + path);
            }
            delegate.delete(path);
        }

        // Delegate all other operations unchanged
        @Override public java.util.List<Path> listDirectory(Path p) throws IOException { return delegate.listDirectory(p); }
        @Override public java.util.List<Path> walk(Path r) throws IOException { return delegate.walk(r); }
        @Override public boolean exists(Path p) { return delegate.exists(p); }
        @Override public boolean isDirectory(Path p) { return delegate.isDirectory(p); }
        @Override public java.time.LocalDate getLastModifiedDate(Path p) throws IOException { return delegate.getLastModifiedDate(p); }
        @Override public java.io.InputStream openFile(Path p) throws IOException { return delegate.openFile(p); }
        @Override public long size(Path p) throws IOException { return delegate.size(p); }
        @Override public void move(Path s, Path d) throws IOException { delegate.move(s, d); }
        @Override public void rename(Path p, String n) throws IOException { delegate.rename(p, n); }
        @Override public void createDirectories(Path p) throws IOException { delegate.createDirectories(p); }
        @Override public void writeFile(Path p, byte[] b) throws IOException { delegate.writeFile(p, b); }
        @Override public com.organizer3.filesystem.FileTimestamps getTimestamps(Path p) throws IOException { return delegate.getTimestamps(p); }
        @Override public void setTimestamps(Path p, Instant c, Instant m) throws IOException { delegate.setTimestamps(p, c, m); }
    }
}
