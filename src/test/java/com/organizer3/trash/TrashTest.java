package com.organizer3.trash;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.organizer3.filesystem.VolumeFileSystem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class TrashTest {

    private InMemoryFS fs;
    private Trash trash;

    @BeforeEach
    void setUp() {
        fs = new InMemoryFS();
        Clock fixed = Clock.fixed(Instant.parse("2026-04-16T10:00:00Z"), ZoneOffset.UTC);
        trash = new Trash(fs, "a", "_trash", fixed);
    }

    @Test
    void trashesNestedItem_mirroringParentTree() throws IOException {
        fs.mkdir("/stars/popular/MIDE-123");

        Trash.Result r = trash.trashItem(Path.of("/stars/popular/MIDE-123"), "title");

        assertEquals(Path.of("/_trash/stars/popular/MIDE-123"), r.trashedPath());
        assertEquals(Path.of("/_trash/stars/popular/MIDE-123.json"), r.sidecarPath());
        assertTrue(fs.exists(Path.of("/_trash/stars/popular")));
        assertTrue(fs.exists(Path.of("/_trash/stars/popular/MIDE-123")));
        assertFalse(fs.exists(Path.of("/stars/popular/MIDE-123")));
    }

    @Test
    void sidecarContainsRequiredMetadata() throws IOException {
        fs.mkdir("/stars/popular/MIDE-123");

        Trash.Result r = trash.trashItem(Path.of("/stars/popular/MIDE-123"), "Duplicate Triage — kept peer on volume vol-b");

        byte[] body = fs.fileContents(r.sidecarPath());
        assertNotNull(body);
        JsonNode json = new ObjectMapper().readTree(new String(body, StandardCharsets.UTF_8));
        // All four required fields per spec/PROPOSAL_TRASH.md §5
        assertEquals("/stars/popular/MIDE-123", json.get("originalPath").asText());
        assertEquals("2026-04-16T10:00:00Z", json.get("trashedAt").asText());
        assertEquals("a", json.get("volumeId").asText());
        assertEquals("Duplicate Triage — kept peer on volume vol-b", json.get("reason").asText());
        assertFalse(json.has("entityType"), "entityType field must not appear in sidecar");
        assertFalse(json.has("scheduledDeletionAt"), "scheduledDeletionAt must be absent on newly-trashed items");
    }

    @Test
    void secondTrashIntoSameParent_reusesExistingTrashDir() throws IOException {
        fs.mkdir("/stars/foo/ITEM-1");
        fs.mkdir("/stars/foo/ITEM-2");

        trash.trashItem(Path.of("/stars/foo/ITEM-1"), "title");
        trash.trashItem(Path.of("/stars/foo/ITEM-2"), "title");

        assertTrue(fs.exists(Path.of("/_trash/stars/foo/ITEM-1")));
        assertTrue(fs.exists(Path.of("/_trash/stars/foo/ITEM-2")));
    }

    @Test
    void trashesItemAtVolumeRoot() throws IOException {
        fs.file("/stray.txt");

        Trash.Result r = trash.trashItem(Path.of("/stray.txt"), "cover");

        assertEquals(Path.of("/_trash/stray.txt"), r.trashedPath());
        assertTrue(fs.exists(Path.of("/_trash/stray.txt")));
        assertFalse(fs.exists(Path.of("/stray.txt")));
    }

    @Test
    void rejectsTrashingVolumeRoot() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> trash.trashItem(Path.of("/"), "title"));
        assertTrue(ex.getMessage().toLowerCase().contains("root"));
    }

    @Test
    void rejectsTrashingItemAlreadyInTrash() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> trash.trashItem(Path.of("/_trash/foo/bar"), "title"));
        assertTrue(ex.getMessage().contains("already in"));
    }

    @Test
    void rejectsRelativePath() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> trash.trashItem(Path.of("stars/foo"), "title"));
        assertTrue(ex.getMessage().contains("absolute"));
    }

    @Test
    void mirrorUnderTrash_isKnownGood() {
        assertEquals(Path.of("/_trash/stars/popular"), trash.mirrorUnderTrash(Path.of("/stars/popular")));
        assertEquals(Path.of("/_trash"), trash.mirrorUnderTrash(Path.of("/")));
        assertEquals(Path.of("/_trash"), trash.mirrorUnderTrash(null));
    }

    // ── in-memory VolumeFileSystem for trash tests ──────────────────────────

    static final class InMemoryFS implements VolumeFileSystem {
        private final Map<Path, Boolean> nodes = new HashMap<>();       // true = dir, false = file
        private final Map<Path, byte[]> contents = new HashMap<>();

        void mkdir(String p) {
            Path path = Path.of(p);
            Path cur = path;
            while (cur != null) {
                nodes.put(cur, true);
                cur = cur.getParent();
            }
        }

        void file(String p) {
            Path path = Path.of(p);
            nodes.put(path, false);
            Path parent = path.getParent();
            while (parent != null) {
                nodes.put(parent, true);
                parent = parent.getParent();
            }
        }

        byte[] fileContents(Path p) { return contents.get(p); }

        @Override public List<Path> listDirectory(Path path) { throw new UnsupportedOperationException(); }
        @Override public List<Path> walk(Path root)          { throw new UnsupportedOperationException(); }
        @Override public boolean exists(Path path)           { return nodes.containsKey(path); }
        @Override public boolean isDirectory(Path path)      { return Boolean.TRUE.equals(nodes.get(path)); }
        @Override public LocalDate getLastModifiedDate(Path path) { return null; }
        @Override public InputStream openFile(Path path) throws IOException { throw new IOException("n/a"); }
        @Override public long size(Path path) throws IOException { throw new IOException("n/a"); }

        @Override public void move(Path source, Path destination) {
            Boolean kind = nodes.remove(source);
            if (kind != null) nodes.put(destination, kind);
            byte[] body = contents.remove(source);
            if (body != null) contents.put(destination, body);
        }

        @Override public void rename(Path path, String newName) {
            move(path, path.resolveSibling(newName));
        }

        @Override public void createDirectories(Path path) {
            Path cur = path;
            while (cur != null && !cur.equals(Path.of("/"))) {
                nodes.put(cur, true);
                cur = cur.getParent();
            }
        }

        @Override public void writeFile(Path path, byte[] body) {
            nodes.put(path, false);
            contents.put(path, body);
        }

        @Override public com.organizer3.filesystem.FileTimestamps getTimestamps(Path p) {
            return new com.organizer3.filesystem.FileTimestamps(null, null, null);
        }
        @Override public void setTimestamps(Path p, java.time.Instant c, java.time.Instant m) {}
    }
}
