package com.organizer3.sandbox.trash;

import com.organizer3.sandbox.SandboxTestBase;
import com.organizer3.trash.BatchResult;
import com.organizer3.trash.TrashService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@Tag("sandbox")
class TrashRestoreSandboxTest extends SandboxTestBase {

    private final TrashService svc = new TrashService();

    private SandboxTrashBuilder builder() {
        return new SandboxTrashBuilder(fs, trashRoot(), "vol-a");
    }

    @Test
    void restoreMovesItemToOriginalPath() throws Exception {
        Path sidecar = builder().withOriginalPath("/stars/foo/MIDE-1").asFolder().build();
        Path expectedOriginal = runDir.resolve("stars/foo/MIDE-1");
        Files.createDirectories(runDir.resolve("stars/foo"));

        BatchResult result = svc.restore(fs, trashRoot(), List.of(sidecar));

        assertEquals(1, result.successes());
        assertTrue(Files.exists(expectedOriginal), "Item must exist at original path after restore");
        assertFalse(Files.exists(trashRoot().resolve("stars/foo/MIDE-1")),
                "Item must no longer exist in trash");
    }

    @Test
    void restoreCreatesMissingParents() throws Exception {
        // Original parent /stars/foo/ was deleted after trashing
        Path sidecar = builder().withOriginalPath("/stars/foo/MIDE-2").asFolder().build();
        // Do NOT create the parent dirs in runDir

        BatchResult result = svc.restore(fs, trashRoot(), List.of(sidecar));

        assertEquals(1, result.successes());
        assertTrue(Files.exists(runDir.resolve("stars/foo/MIDE-2")),
                "Restore must recreate missing parent directories");
    }

    @Test
    void restoreDeletesSidecar() throws Exception {
        Path sidecar = builder().withOriginalPath("/items/MIDE-3").build();

        svc.restore(fs, trashRoot(), List.of(sidecar));

        assertFalse(Files.exists(sidecar), "Sidecar must be deleted after restore");
    }

    @Test
    void collisionWithExistingPathSkipped() throws Exception {
        Path sidecar = builder().withOriginalPath("/items/MIDE-4").build();
        // Pre-create the original path so restore would collide
        Path collisionPath = runDir.resolve("items/MIDE-4");
        Files.createDirectories(collisionPath);

        BatchResult result = svc.restore(fs, trashRoot(), List.of(sidecar));

        assertEquals(0, result.successes());
        assertEquals(1, result.failures().size());
        assertTrue(result.failures().get(0).reason().contains("already occupied"),
                "Failure reason must mention collision");
        // Item stays in trash
        assertTrue(Files.exists(trashRoot().resolve("items/MIDE-4")));
    }

    @Test
    void restoreCancelsScheduledDeletion() throws Exception {
        Path sidecar = builder().withOriginalPath("/items/MIDE-5")
                .withScheduledDeletionAt(Instant.parse("2026-05-03T10:00:00Z")).build();

        svc.restore(fs, trashRoot(), List.of(sidecar));

        // Sidecar deleted → item not eligible for sweep
        assertFalse(Files.exists(sidecar), "Scheduled sidecar must be deleted on restore");
    }

    @Test
    void restorePrunesEmptyAncestors() throws Exception {
        // Item at _trash/a/b/c/foo, no siblings
        Path sidecar = builder().withOriginalPath("/a/b/c/foo").asFolder().build();

        svc.restore(fs, trashRoot(), List.of(sidecar));

        assertFalse(Files.exists(trashRoot().resolve("a/b/c")), "_trash/a/b/c must be pruned");
        assertFalse(Files.exists(trashRoot().resolve("a/b")),   "_trash/a/b must be pruned");
        assertFalse(Files.exists(trashRoot().resolve("a")),     "_trash/a must be pruned");
        assertTrue(Files.exists(trashRoot()), "_trash/ itself must be preserved");
    }

    @Test
    void restorePreservesNonEmptyAncestors() throws Exception {
        // Item at _trash/a/b/c/foo, sibling at _trash/a/b/other
        Path sidecar = builder().withOriginalPath("/a/b/c/foo").asFolder().build();
        // Add a sibling at a/b level
        builder().withOriginalPath("/a/b/other").asFolder().build();

        svc.restore(fs, trashRoot(), List.of(sidecar));

        assertFalse(Files.exists(trashRoot().resolve("a/b/c")), "_trash/a/b/c must be pruned");
        assertTrue(Files.exists(trashRoot().resolve("a/b")),    "_trash/a/b must be preserved (has sibling)");
        assertTrue(Files.exists(trashRoot().resolve("a")),      "_trash/a must be preserved");
    }
}
