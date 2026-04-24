package com.organizer3.sandbox.trash;

import com.organizer3.sandbox.SandboxTestBase;
import com.organizer3.trash.BatchResult;
import com.organizer3.trash.TrashService;
import com.organizer3.trash.TrashSidecar;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@Tag("sandbox")
class TrashScheduleSandboxTest extends SandboxTestBase {

    private final TrashService svc = new TrashService();
    private static final Instant NOW = Instant.parse("2026-04-23T10:00:00Z");
    private static final Instant PLUS_10D = NOW.plusSeconds(10L * 24 * 3600);

    private SandboxTrashBuilder builder() {
        return new SandboxTrashBuilder(fs, trashRoot(), "vol-a");
    }

    @Test
    void schedulesUnscheduledItem() throws Exception {
        Path sidecar = builder().withOriginalPath("/items/MIDE-1").build();

        BatchResult result = svc.scheduleForDeletion(fs, List.of(sidecar), PLUS_10D);

        assertEquals(1, result.successes());
        assertTrue(result.failures().isEmpty());

        TrashSidecar sc = TrashSidecar.read(fs, sidecar);
        assertNotNull(sc.scheduledDeletionAt());
        Instant scheduled = Instant.parse(sc.scheduledDeletionAt());
        // Should equal PLUS_10D (±1s tolerance for any timestamp formatting)
        assertTrue(Math.abs(scheduled.getEpochSecond() - PLUS_10D.getEpochSecond()) <= 1,
                "scheduledDeletionAt should equal now+10d");
    }

    @Test
    void reschedulesItemResetsClock() throws Exception {
        // Item already scheduled 3 days ago (clock: now-3d+10d = now+7d)
        Instant oldSchedule = NOW.minusSeconds(3L * 24 * 3600).plusSeconds(10L * 24 * 3600);
        Path sidecar = builder().withOriginalPath("/items/MIDE-2")
                .withScheduledDeletionAt(oldSchedule).build();

        BatchResult result = svc.scheduleForDeletion(fs, List.of(sidecar), PLUS_10D);

        assertEquals(1, result.successes());
        TrashSidecar sc = TrashSidecar.read(fs, sidecar);
        Instant rescheduled = Instant.parse(sc.scheduledDeletionAt());
        // Clock should reset to now+10d, not the old value
        assertTrue(rescheduled.isAfter(oldSchedule),
                "Re-schedule must reset the clock to now+10d, not keep the old value");
        assertTrue(Math.abs(rescheduled.getEpochSecond() - PLUS_10D.getEpochSecond()) <= 1);
    }

    @Test
    void partialFailureReported() throws Exception {
        Path validSidecar = builder().withOriginalPath("/items/MIDE-3").build();
        Path invalidSidecar = trashRoot().resolve("nonexistent/MIDE-999.json"); // does not exist

        BatchResult result = svc.scheduleForDeletion(fs, List.of(validSidecar, invalidSidecar), PLUS_10D);

        assertEquals(1, result.successes());
        assertEquals(1, result.failures().size());
        assertEquals(invalidSidecar, result.failures().get(0).sidecarPath());

        // Valid one was scheduled
        TrashSidecar sc = TrashSidecar.read(fs, validSidecar);
        assertNotNull(sc.scheduledDeletionAt());
    }
}
