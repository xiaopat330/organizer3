package com.organizer3.trash;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.organizer3.filesystem.LocalFileSystem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class TrashSidecarTest {

    @TempDir Path tempDir;

    private final LocalFileSystem fs = new LocalFileSystem();
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void roundTrip_withoutSchedule() throws Exception {
        TrashSidecar original = new TrashSidecar(
                "/stars/popular/MIDE-123",
                "2026-04-23T10:00:00Z",
                "a",
                "Duplicate Triage — kept peer on volume vol-b",
                null
        );
        Path sidecar = tempDir.resolve("MIDE-123.json");

        original.write(fs, sidecar);
        TrashSidecar read = TrashSidecar.read(fs, sidecar);

        assertEquals(original.originalPath(), read.originalPath());
        assertEquals(original.trashedAt(), read.trashedAt());
        assertEquals(original.volumeId(), read.volumeId());
        assertEquals(original.reason(), read.reason());
        assertNull(read.scheduledDeletionAt());
    }

    @Test
    void roundTrip_withSchedule() throws Exception {
        TrashSidecar original = new TrashSidecar(
                "/stars/foo/MIDE-456",
                "2026-04-23T10:00:00Z",
                "bg",
                "Duplicate Triage",
                "2026-05-03T10:00:00Z"
        );
        Path sidecar = tempDir.resolve("MIDE-456.json");

        original.write(fs, sidecar);
        TrashSidecar read = TrashSidecar.read(fs, sidecar);

        assertEquals("2026-05-03T10:00:00Z", read.scheduledDeletionAt());
    }

    @Test
    void scheduledDeletionAt_absentFromJson_whenNull() throws Exception {
        TrashSidecar sc = new TrashSidecar("/p", "2026-04-23T10:00:00Z", "a", "r", null);
        Path sidecar = tempDir.resolve("item.json");
        sc.write(fs, sidecar);

        JsonNode node = json.readTree(sidecar.toFile());
        assertFalse(node.has("scheduledDeletionAt"), "scheduledDeletionAt must be omitted, not null");
    }

    @Test
    void withScheduledDeletionAt_setsField() {
        TrashSidecar sc = new TrashSidecar("/p", "2026-04-23T10:00:00Z", "a", "r", null);
        Instant t = Instant.parse("2026-05-03T10:00:00Z");

        TrashSidecar scheduled = sc.withScheduledDeletionAt(t);

        assertEquals("2026-05-03T10:00:00Z", scheduled.scheduledDeletionAt());
        // original unchanged
        assertNull(sc.scheduledDeletionAt());
    }

    @Test
    void withScheduledDeletionAt_null_clearsField() {
        TrashSidecar sc = new TrashSidecar("/p", "2026-04-23T10:00:00Z", "a", "r", "2026-05-03T10:00:00Z");

        TrashSidecar cleared = sc.withScheduledDeletionAt(null);

        assertNull(cleared.scheduledDeletionAt());
    }

    @Test
    void unknownFields_ignoredOnRead() throws Exception {
        // Write a sidecar with an unknown field (simulates a future sidecar version)
        String json = """
                {
                  "originalPath": "/stars/foo/MIDE-789",
                  "trashedAt": "2026-04-23T10:00:00Z",
                  "volumeId": "a",
                  "reason": "test",
                  "lastDeletionError": "some future field",
                  "unknownField": 42
                }
                """;
        Path sidecar = tempDir.resolve("MIDE-789.json");
        fs.writeFile(sidecar, json.getBytes());

        TrashSidecar read = TrashSidecar.read(fs, sidecar);

        assertEquals("/stars/foo/MIDE-789", read.originalPath());
        assertNull(read.scheduledDeletionAt());
    }
}
