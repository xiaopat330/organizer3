package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.organizer3.config.volume.VolumeConfig;
import com.organizer3.curation.CurationLog;
import com.organizer3.db.SchemaInitializer;
import com.organizer3.filesystem.FileTimestamps;
import com.organizer3.filesystem.VolumeFileSystem;
import com.organizer3.shell.SessionContext;
import com.organizer3.smb.VolumeConnection;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DeleteEmptyFolderToolTest {

    @TempDir
    Path logDir;

    private static final ObjectMapper M = new ObjectMapper();

    private Connection connection;
    private Jdbi jdbi;
    private SessionContext session;
    private FakeFs fs;
    private CurationLog curationLog;
    private DeleteEmptyFolderTool tool;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi       = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        jdbi.useHandle(h -> h.execute("INSERT INTO volumes (id, structure_type) VALUES ('s', 'conventional')"));

        fs          = new FakeFs();
        curationLog = new CurationLog(logDir);
        session     = new SessionContext();
        session.setMountedVolume(new VolumeConfig("s", "//host/s", "conventional", "host", null));
        session.setActiveConnection(new FakeConnection(fs));
        tool = new DeleteEmptyFolderTool(session, jdbi, curationLog);
    }

    @AfterEach
    void tearDown() throws Exception { connection.close(); }

    // ── happy path ────────────────────────────────────────────────────────────

    @Test
    void deletesEmptyFolder() {
        fs.directories.add(Path.of("/queue/Old Name (LABEL-001)"));
        var result = (DeleteEmptyFolderTool.Result) tool.call(args("s", "/queue/Old Name (LABEL-001)", true));
        assertEquals("ok", result.status());
        assertTrue(result.deleted());
        assertTrue(fs.deleteCalls.contains(Path.of("/queue/Old Name (LABEL-001)")));
    }

    @Test
    void deletesSidecarsBeforeFolder() {
        Path folder  = Path.of("/attention/Actress/Old Folder");
        Path sidecar = folder.resolve("REASON.txt");
        Path jsonSc  = folder.resolve("trash.json");

        fs.directories.add(folder);
        fs.children.put(folder, List.of(sidecar, jsonSc));

        var result = (DeleteEmptyFolderTool.Result) tool.call(args("s", folder.toString(), true));
        assertEquals("ok", result.status());

        // sidecars deleted before folder
        int sidecarIdx1 = fs.deleteCalls.indexOf(sidecar);
        int sidecarIdx2 = fs.deleteCalls.indexOf(jsonSc);
        int folderIdx   = fs.deleteCalls.indexOf(folder);
        assertTrue(sidecarIdx1 >= 0 && sidecarIdx1 < folderIdx);
        assertTrue(sidecarIdx2 >= 0 && sidecarIdx2 < folderIdx);
    }

    @Test
    void emitsCurationLogOnOk() throws Exception {
        fs.directories.add(Path.of("/queue/Empty"));
        tool.call(args("s", "/queue/Empty", true));

        Path logFile = logDir.resolve("curation-log/s")
                .resolve(java.time.LocalDate.now() + ".jsonl");
        assertTrue(Files.exists(logFile));
        var parsed = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(Files.readAllLines(logFile).get(0));
        assertEquals("delete_empty_folder", parsed.get("tool").asText());
        assertEquals("ok", parsed.get("status").asText());
    }

    // ── refusals ─────────────────────────────────────────────────────────────

    @Test
    void refusesNonExistentPath() {
        // fs.directories is empty — path doesn't exist
        var result = (DeleteEmptyFolderTool.Result) tool.call(args("s", "/queue/Ghost", true));
        assertEquals("refused", result.status());
        assertFalse(result.deleted());
    }

    @Test
    void refusesNonDirectory() {
        fs.files.add(Path.of("/queue/something.mkv"));
        var result = (DeleteEmptyFolderTool.Result) tool.call(args("s", "/queue/something.mkv", true));
        assertEquals("refused", result.status());
    }

    @Test
    void refusesNonEmptyFolderWhenHasRealFile() {
        Path folder = Path.of("/queue/Has Content");
        fs.directories.add(folder);
        fs.children.put(folder, List.of(folder.resolve("ABP-001.mkv")));

        var result = (DeleteEmptyFolderTool.Result) tool.call(args("s", "/queue/Has Content", true));
        assertEquals("refused", result.status());
        assertTrue(result.error().contains("ABP-001.mkv") || result.error().contains("empty"));
    }

    @Test
    void refusesSidecarWhenAllowSidecarsFalse() {
        Path folder  = Path.of("/queue/Has Sidecar");
        Path sidecar = folder.resolve("REASON.txt");
        fs.directories.add(folder);
        fs.children.put(folder, List.of(sidecar));

        var result = (DeleteEmptyFolderTool.Result) tool.call(argsNoSidecars("s", "/queue/Has Sidecar"));
        assertEquals("refused", result.status());
    }

    @Test
    void refusesProtectedRoot_stars() {
        fs.directories.add(Path.of("/stars"));
        var result = (DeleteEmptyFolderTool.Result) tool.call(args("s", "/stars", true));
        assertEquals("refused", result.status());
        assertTrue(result.error().contains("protected") || result.error().contains("tier"));
    }

    @Test
    void refusesProtectedRoot_starsMinor() {
        fs.directories.add(Path.of("/stars/minor"));
        var result = (DeleteEmptyFolderTool.Result) tool.call(args("s", "/stars/minor", true));
        assertEquals("refused", result.status());
    }

    @Test
    void allowsActressFolderUnderStarsTier() {
        // /stars/minor/Actress Name/ — depth 3, not protected
        Path folder = Path.of("/stars/minor/Old Actress");
        fs.directories.add(folder);
        var result = (DeleteEmptyFolderTool.Result) tool.call(args("s", "/stars/minor/Old Actress", true));
        assertEquals("ok", result.status());
    }

    @Test
    void refusesProtectedRoot_queue() {
        fs.directories.add(Path.of("/queue"));
        var result = (DeleteEmptyFolderTool.Result) tool.call(args("s", "/queue", true));
        assertEquals("refused", result.status());
    }

    @Test
    void refusesProtectedRoot_attention() {
        fs.directories.add(Path.of("/attention"));
        var result = (DeleteEmptyFolderTool.Result) tool.call(args("s", "/attention", true));
        assertEquals("refused", result.status());
    }

    @Test
    void refusesWhenTitleLocationsPrefixMatch() {
        // insert a live title_location with a path under our target folder
        seedLocation("s", "/queue/Old Name (LABEL-001)");
        fs.directories.add(Path.of("/queue/Old Name (LABEL-001)"));

        var result = (DeleteEmptyFolderTool.Result) tool.call(args("s", "/queue/Old Name (LABEL-001)", true));
        assertEquals("refused", result.status());
        assertTrue(result.error().contains("title_location") || result.error().contains("referenced"));
    }

    @Test
    void refusesPrefixMatchButNotOtherVolume() {
        // location on a different volume — should NOT block deletion
        seedLocation("r", "/queue/Old Name (LABEL-001)");
        fs.directories.add(Path.of("/queue/Old Name (LABEL-001)"));

        var result = (DeleteEmptyFolderTool.Result) tool.call(args("s", "/queue/Old Name (LABEL-001)", true));
        assertEquals("ok", result.status());
    }

    @Test
    void refusesPathTraversal() {
        var result = (DeleteEmptyFolderTool.Result) tool.call(args("s", "/queue/../stars/evil", true));
        assertEquals("refused", result.status());
        assertTrue(result.error().contains(".."));
    }

    @Test
    void refusesVolumeMismatch() {
        fs.directories.add(Path.of("/queue/Folder"));
        var result = (DeleteEmptyFolderTool.Result) tool.call(args("r", "/queue/Folder", true));
        assertEquals("refused", result.status());
    }

    @Test
    void refusesWhenNoVolumeMounted() {
        session.setMountedVolume(null);
        session.setActiveConnection(null);
        var result = (DeleteEmptyFolderTool.Result) tool.call(args("s", "/queue/Folder", true));
        assertEquals("refused", result.status());
    }

    @Test
    void emitsCurationLogOnRefusal() throws Exception {
        // path doesn't exist
        tool.call(args("s", "/queue/Ghost", true));

        Path logFile = logDir.resolve("curation-log/s")
                .resolve(java.time.LocalDate.now() + ".jsonl");
        assertTrue(Files.exists(logFile));
        var parsed = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(Files.readAllLines(logFile).get(0));
        assertEquals("delete_empty_folder", parsed.get("tool").asText());
        assertEquals("failed", parsed.get("status").asText());
    }

    @Test
    void returnsFailedOnIoException() {
        fs.directories.add(Path.of("/queue/Empty"));
        fs.failOnDelete = true;
        var result = (DeleteEmptyFolderTool.Result) tool.call(args("s", "/queue/Empty", true));
        assertEquals("failed", result.status());
        assertFalse(result.deleted());
    }

    // ── allowOrphanLocation ──────────────────────────────────────────────────

    @Test
    void allowOrphanLocation_dropsDanglingRowAndDeletesFolder() {
        seedLocation("s", "/queue/Old Name (LABEL-001)");
        Path folder = Path.of("/queue/Old Name (LABEL-001)");
        fs.directories.add(folder);

        var result = (DeleteEmptyFolderTool.Result) tool.call(argsOrphan("s", folder.toString()));
        assertEquals("ok", result.status());
        assertEquals(1, result.locationsDropped());

        // DB row is gone
        Integer remaining = jdbi.withHandle(h -> h.createQuery(
                "SELECT COUNT(*) FROM title_locations WHERE path = ?")
                .bind(0, folder.toString())
                .mapTo(Integer.class).one());
        assertEquals(0, remaining);
        assertTrue(fs.deleteCalls.contains(folder));
    }

    @Test
    void allowOrphanLocation_surfacesOrphanedTitle() {
        seedLocation("s", "/queue/Old Name (LABEL-001)");
        Path folder = Path.of("/queue/Old Name (LABEL-001)");
        fs.directories.add(folder);

        var result = (DeleteEmptyFolderTool.Result) tool.call(argsOrphan("s", folder.toString()));
        assertEquals("ok", result.status());
        assertEquals(1, result.orphanedTitles().size());
        assertEquals("LABEL-001", result.orphanedTitles().get(0).titleCode());
    }

    @Test
    void allowOrphanLocation_falseStillRefuses() {
        seedLocation("s", "/queue/Old Name (LABEL-001)");
        Path folder = Path.of("/queue/Old Name (LABEL-001)");
        fs.directories.add(folder);

        // explicit false — same as default; behaviour unchanged
        var result = (DeleteEmptyFolderTool.Result) tool.call(args("s", folder.toString(), true));
        assertEquals("refused", result.status());
    }

    // ── SQL guard: segment-aware prefix (no false-positives) ─────────────────

    @Test
    void prefixCheckDoesNotMatchSiblingFolder() {
        // /queue/Old Name is a sibling of /queue/Old Name Extra — not a prefix match
        seedLocation("s", "/queue/Old Name Extra (LABEL-002)");
        fs.directories.add(Path.of("/queue/Old Name"));

        var result = (DeleteEmptyFolderTool.Result) tool.call(args("s", "/queue/Old Name", true));
        assertEquals("ok", result.status()); // should NOT be refused by the extra-name row
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void seedLocation(String volumeId, String path) {
        jdbi.useHandle(h -> {
            long titleId = h.createQuery(
                    "INSERT INTO titles (code, actress_id) VALUES ('LABEL-001', NULL) RETURNING id")
                    .mapTo(Long.class).one();
            h.execute("""
                    INSERT INTO title_locations (title_id, volume_id, partition_id, path, last_seen_at)
                    VALUES (?, ?, 'queue', ?, '2024-01-01')
                    """, titleId, volumeId, path);
        });
    }

    private static ObjectNode args(String volumeId, String path, boolean allowSidecars) {
        ObjectNode n = M.createObjectNode();
        n.put("volumeId",      volumeId);
        n.put("path",          path);
        n.put("allowSidecars", allowSidecars);
        return n;
    }

    private static ObjectNode argsNoSidecars(String volumeId, String path) {
        return args(volumeId, path, false);
    }

    private static ObjectNode argsOrphan(String volumeId, String path) {
        ObjectNode n = args(volumeId, path, true);
        n.put("allowOrphanLocation", true);
        return n;
    }

    private static final class FakeConnection implements VolumeConnection {
        private final VolumeFileSystem fs;
        FakeConnection(VolumeFileSystem fs) { this.fs = fs; }
        @Override public VolumeFileSystem fileSystem() { return fs; }
        @Override public boolean isConnected() { return true; }
        @Override public void close() {}
    }

    static final class FakeFs implements VolumeFileSystem {
        final Set<Path>        directories = new HashSet<>();
        final Set<Path>        files       = new HashSet<>();
        final Map<Path, List<Path>> children = new HashMap<>();
        final List<Path>       deleteCalls = new ArrayList<>();
        boolean failOnDelete = false;

        @Override public boolean exists(Path path) {
            return directories.contains(path) || files.contains(path);
        }
        @Override public boolean isDirectory(Path path) { return directories.contains(path); }

        @Override public List<Path> listDirectory(Path path) throws IOException {
            return children.getOrDefault(path, List.of());
        }

        @Override public void delete(Path path) throws IOException {
            if (failOnDelete) throw new IOException("simulated delete failure");
            deleteCalls.add(path);
        }

        @Override public List<Path> walk(Path root) { return List.of(); }
        @Override public LocalDate getLastModifiedDate(Path path) { return null; }
        @Override public InputStream openFile(Path path) { throw new UnsupportedOperationException(); }
        @Override public long size(Path path) { return 0; }
        @Override public void move(Path source, Path destination) { throw new UnsupportedOperationException(); }
        @Override public void rename(Path path, String newName) { throw new UnsupportedOperationException(); }
        @Override public void createDirectories(Path path) { throw new UnsupportedOperationException(); }
        @Override public void writeFile(Path path, byte[] contents) { throw new UnsupportedOperationException(); }
        @Override public FileTimestamps getTimestamps(Path path) { return null; }
        @Override public void setTimestamps(Path path, Instant created, Instant modified) {}
    }
}
