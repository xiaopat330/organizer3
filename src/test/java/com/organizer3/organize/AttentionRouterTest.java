package com.organizer3.organize;

import com.organizer3.filesystem.FileTimestamps;
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

class AttentionRouterTest {

    private InMemoryFS fs;
    private AttentionRouter router;

    @BeforeEach
    void setUp() {
        fs = new InMemoryFS();
        Clock fixed = Clock.fixed(Instant.parse("2026-04-17T04:12:00Z"), ZoneOffset.UTC);
        router = new AttentionRouter(fs, "a", fixed);
    }

    @Test
    void routesFolderToAttentionAndWritesSidecar() throws IOException {
        fs.mkdir("/stars/popular/Someone/Someone (MIDE-123)");

        AttentionRouter.Result r = router.route(
                Path.of("/stars/popular/Someone/Someone (MIDE-123)"),
                "actress-letter-mismatch",
                Map.of("expected-letter", "A", "actual-actress", "Nami Aino"),
                "Title was filed under actress id 4506 \"Aino Nami\" ...");

        assertEquals("/attention/Someone (MIDE-123)", r.attentionPath());
        assertTrue(fs.exists(Path.of("/attention/Someone (MIDE-123)")));
        assertFalse(fs.exists(Path.of("/stars/popular/Someone/Someone (MIDE-123)")));
        assertTrue(fs.exists(Path.of("/attention/Someone (MIDE-123)/REASON.txt")));
    }

    @Test
    void sidecarContentsHaveRequiredHeadersInOrder() throws IOException {
        fs.mkdir("/stars/a/A (X-1)");

        router.route(
                Path.of("/stars/a/A (X-1)"),
                "collision",
                Map.of("target", "/stars/minor/A/A (X-1)"),
                "already present");

        byte[] bytes = fs.fileContents(Path.of("/attention/A (X-1)/REASON.txt"));
        assertNotNull(bytes);
        String text = new String(bytes, StandardCharsets.UTF_8);
        assertTrue(text.startsWith("reason: collision\n"), text);
        assertTrue(text.contains("\nvolume: a\n"));
        assertTrue(text.contains("\noriginalPath: /stars/a/A (X-1)\n"));
        assertTrue(text.contains("\ntarget: /stars/minor/A/A (X-1)\n"));
        assertTrue(text.contains("\nmoved-at: 2026-04-17T04:12:00Z\n"));
        assertTrue(text.contains("\n\nalready present\n"));
    }

    @Test
    void refusesWhenAttentionTargetAlreadyExists() {
        fs.mkdir("/stars/popular/X/X (MIDE-123)");
        fs.mkdir("/attention/X (MIDE-123)");   // squatter

        assertThrows(IOException.class, () -> router.route(
                Path.of("/stars/popular/X/X (MIDE-123)"),
                "letter-mismatch",
                Map.of(),
                "reason body"));
    }

    @Test
    void refusesWhenSourceDoesNotExist() {
        assertThrows(IllegalArgumentException.class, () -> router.route(
                Path.of("/nope"),
                "letter-mismatch",
                Map.of(),
                "reason"));
    }

    @Test
    void refusesRelativePath() {
        assertThrows(IllegalArgumentException.class, () -> router.route(
                Path.of("some/relative"),
                "letter-mismatch",
                Map.of(),
                "reason"));
    }

    @Test
    void reservedHeaderKeysIgnoredFromCallerMap() throws IOException {
        fs.mkdir("/x/A (X-1)");
        router.route(
                Path.of("/x/A (X-1)"),
                "letter-mismatch",
                // all four are reserved and should be ignored
                Map.of("reason", "hijack", "volume", "z", "originalPath", "/hijack", "moved-at", "1970-01-01T00:00:00Z"),
                "body");
        String text = new String(fs.fileContents(Path.of("/attention/A (X-1)/REASON.txt")), StandardCharsets.UTF_8);
        assertTrue(text.contains("reason: letter-mismatch"));   // not hijack
        assertTrue(text.contains("volume: a"));                 // not z
        assertTrue(text.contains("originalPath: /x/A (X-1)"));
        assertTrue(text.contains("moved-at: 2026-04-17T04:12:00Z"));
        assertFalse(text.contains("hijack"));
    }

    // ── in-memory FS ────────────────────────────────────────────────────────

    static final class InMemoryFS implements VolumeFileSystem {
        private final Map<Path, Boolean> nodes = new HashMap<>();
        private final Map<Path, byte[]> contents = new HashMap<>();

        void mkdir(String p) {
            Path path = Path.of(p);
            Path cur = path;
            while (cur != null) { nodes.put(cur, true); cur = cur.getParent(); }
        }

        byte[] fileContents(Path p) { return contents.get(p); }

        @Override public List<Path> listDirectory(Path path) {
            List<Path> out = new ArrayList<>();
            String parent = path.toString();
            for (Path p : nodes.keySet()) {
                Path pp = p.getParent();
                if (pp != null && pp.toString().equals(parent)) out.add(p);
            }
            return out;
        }
        @Override public List<Path> walk(Path root) { throw new UnsupportedOperationException(); }
        @Override public boolean exists(Path path)  { return nodes.containsKey(path); }
        @Override public boolean isDirectory(Path path) { return Boolean.TRUE.equals(nodes.get(path)); }
        @Override public LocalDate getLastModifiedDate(Path path) { return null; }
        @Override public InputStream openFile(Path path) throws IOException { throw new IOException("n/a"); }
        @Override public long size(Path path) throws IOException { throw new IOException("n/a"); }
        @Override public void move(Path source, Path destination) {
            Boolean kind = nodes.remove(source);
            if (kind != null) nodes.put(destination, kind);
            byte[] body = contents.remove(source);
            if (body != null) contents.put(destination, body);
            // move children recursively for directories (so subtree is at new root)
            // For this test we don't populate children so no-op works.
        }
        @Override public void rename(Path path, String newName) { move(path, path.resolveSibling(newName)); }
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
            Path parent = path.getParent();
            while (parent != null && !parent.equals(Path.of("/"))) {
                nodes.put(parent, true);
                parent = parent.getParent();
            }
        }
        @Override public FileTimestamps getTimestamps(Path p) { return new FileTimestamps(null, null, null); }
        @Override public void setTimestamps(Path p, Instant c, Instant m) {}
    }
}
