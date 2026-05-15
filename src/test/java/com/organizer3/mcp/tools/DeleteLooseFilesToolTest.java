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

class DeleteLooseFilesToolTest {

    @TempDir
    Path logDir;

    private static final ObjectMapper M = new ObjectMapper();

    private Connection connection;
    private Jdbi jdbi;
    private SessionContext session;
    private FakeFs fs;
    private CurationLog curationLog;
    private DeleteLooseFilesTool tool;

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
        tool = new DeleteLooseFilesTool(session, curationLog, jdbi);
    }

    @AfterEach
    void tearDown() throws Exception { connection.close(); }

    // ── dry-run (default) ─────────────────────────────────────────────────────

    @Test
    void dryRunReturnsPlanWithoutDeleting() {
        Path folder  = Path.of("/attention/Actress (LABEL-001)");
        Path thumbs  = folder.resolve("Thumbs.db");
        Path video   = folder.resolve("LABEL-001.mkv");
        fs.directories.add(folder);
        fs.children.put(folder, List.of(thumbs, video));

        var result = (DeleteLooseFilesTool.Result) tool.call(args("s", folder.toString(), true));
        assertEquals("dry-run", result.status());
        assertTrue(result.dryRun());
        assertTrue(result.deleted().contains("Thumbs.db"));
        assertTrue(result.skipped().contains("LABEL-001.mkv"));
        assertTrue(fs.deleteCalls.isEmpty(), "dry-run must not delete anything");
    }

    @Test
    void dryRunEmptyFolderReturnsEmptyAfterTrue() {
        Path folder = Path.of("/attention/Clean");
        fs.directories.add(folder);
        // no children

        var result = (DeleteLooseFilesTool.Result) tool.call(args("s", folder.toString(), true));
        assertEquals("dry-run", result.status());
        assertTrue(result.emptyAfter());
    }

    // ── happy path: real delete ───────────────────────────────────────────────

    @Test
    void deletesThumbsDb() {
        Path folder = Path.of("/attention/Actress (VEC-682)");
        Path thumbs = folder.resolve("Thumbs.db");
        fs.directories.add(folder);
        fs.children.put(folder, List.of(thumbs));

        var result = (DeleteLooseFilesTool.Result) tool.call(args("s", folder.toString(), false));
        assertEquals("ok", result.status());
        assertTrue(result.deleted().contains("Thumbs.db"));
        assertTrue(fs.deleteCalls.contains(thumbs));
    }

    @Test
    void deletesDsStore() {
        Path folder = Path.of("/attention/Actress (FOCS-234)");
        Path ds     = folder.resolve(".DS_Store");
        fs.directories.add(folder);
        fs.children.put(folder, List.of(ds));

        var result = (DeleteLooseFilesTool.Result) tool.call(args("s", folder.toString(), false));
        assertEquals("ok", result.status());
        assertTrue(result.deleted().contains(".DS_Store"));
    }

    @Test
    void deletesReasonTxt() {
        Path folder = Path.of("/attention/Actress (LABEL-001)");
        Path reason = folder.resolve("REASON.txt");
        fs.directories.add(folder);
        fs.children.put(folder, List.of(reason));

        var result = (DeleteLooseFilesTool.Result) tool.call(args("s", folder.toString(), false));
        assertEquals("ok", result.status());
        assertTrue(result.deleted().contains("REASON.txt"));
    }

    @Test
    void deletesJpgFiles() {
        Path folder = Path.of("/attention/Actress (LABEL-001)");
        Path cover  = folder.resolve("cover.jpg");
        fs.directories.add(folder);
        fs.children.put(folder, List.of(cover));

        var result = (DeleteLooseFilesTool.Result) tool.call(args("s", folder.toString(), false));
        assertEquals("ok", result.status());
        assertTrue(result.deleted().contains("cover.jpg"));
    }

    @Test
    void deletesJpegAndPngFiles() {
        Path folder = Path.of("/attention/Actress (LABEL-001)");
        Path jpeg   = folder.resolve("front.jpeg");
        Path png    = folder.resolve("thumb.png");
        fs.directories.add(folder);
        fs.children.put(folder, List.of(jpeg, png));

        var result = (DeleteLooseFilesTool.Result) tool.call(args("s", folder.toString(), false));
        assertEquals("ok", result.status());
        assertTrue(result.deleted().contains("front.jpeg"));
        assertTrue(result.deleted().contains("thumb.png"));
    }

    @Test
    void skipsNonNoiseFiles() {
        Path folder = Path.of("/attention/Actress (LABEL-001)");
        Path video  = folder.resolve("LABEL-001.mkv");
        Path thumbs = folder.resolve("Thumbs.db");
        fs.directories.add(folder);
        fs.children.put(folder, List.of(video, thumbs));

        var result = (DeleteLooseFilesTool.Result) tool.call(args("s", folder.toString(), false));
        assertEquals("ok", result.status());
        assertFalse(result.deleted().contains("LABEL-001.mkv"));
        assertTrue(result.skipped().contains("LABEL-001.mkv"));
        assertFalse(result.emptyAfter()); // video remains
    }

    @Test
    void emptyAfterTrueWhenAllNoiseDeleted() {
        Path folder = Path.of("/attention/Actress (LABEL-001)");
        Path thumbs = folder.resolve("Thumbs.db");
        Path reason = folder.resolve("REASON.txt");
        fs.directories.add(folder);
        fs.children.put(folder, List.of(thumbs, reason));

        var result = (DeleteLooseFilesTool.Result) tool.call(args("s", folder.toString(), false));
        assertEquals("ok", result.status());
        assertTrue(result.emptyAfter());
    }

    @Test
    void caseInsensitiveMatchingForExtensions() {
        Path folder = Path.of("/attention/Actress (LABEL-001)");
        Path upper  = folder.resolve("COVER.JPG");
        Path mixed  = folder.resolve("thumb.Png");
        fs.directories.add(folder);
        fs.children.put(folder, List.of(upper, mixed));

        var result = (DeleteLooseFilesTool.Result) tool.call(args("s", folder.toString(), false));
        assertEquals("ok", result.status());
        assertTrue(result.deleted().contains("COVER.JPG"));
        assertTrue(result.deleted().contains("thumb.Png"));
    }

    @Test
    void caseInsensitiveMatchingForExactNames() {
        // REASON.TXT (uppercase) should match noise name thumbs.db (lowercase storage key)
        Path folder = Path.of("/attention/Actress (LABEL-001)");
        Path thumbs = folder.resolve("THUMBS.DB");
        fs.directories.add(folder);
        fs.children.put(folder, List.of(thumbs));

        var result = (DeleteLooseFilesTool.Result) tool.call(args("s", folder.toString(), false));
        assertEquals("ok", result.status());
        assertTrue(result.deleted().contains("THUMBS.DB"));
    }

    // ── path prefix allowlist ─────────────────────────────────────────────────

    @Test
    void allowsAttentionPrefix() {
        Path folder = Path.of("/attention/Actress (LABEL-001)");
        fs.directories.add(folder);
        var result = (DeleteLooseFilesTool.Result) tool.call(args("s", folder.toString(), false));
        assertEquals("ok", result.status());
    }

    @Test
    void allowsUnderscoreAttentionPrefix() {
        Path folder = Path.of("/_attention/Actress (LABEL-001)");
        fs.directories.add(folder);
        var result = (DeleteLooseFilesTool.Result) tool.call(args("s", folder.toString(), false));
        assertEquals("ok", result.status());
    }

    @Test
    void allowsQueuePrefix() {
        Path folder = Path.of("/queue/Old Name (LABEL-001)");
        fs.directories.add(folder);
        var result = (DeleteLooseFilesTool.Result) tool.call(args("s", folder.toString(), false));
        assertEquals("ok", result.status());
    }

    @Test
    void allowsStarsPopularDeep() {
        // /stars/popular/Actress Name/ — depth 3, not protected
        Path folder = Path.of("/stars/popular/Shion Fujimoto");
        fs.directories.add(folder);
        var result = (DeleteLooseFilesTool.Result) tool.call(args("s", folder.toString(), false));
        assertEquals("ok", result.status());
    }

    @Test
    void allowsSandboxPrefix() {
        Path folder = Path.of("/_sandbox/test-folder");
        fs.directories.add(folder);
        var result = (DeleteLooseFilesTool.Result) tool.call(args("s", folder.toString(), false));
        assertEquals("ok", result.status());
    }

    @Test
    void refusesDisallowedPrefix() {
        Path folder = Path.of("/browse/something");
        fs.directories.add(folder);
        var result = (DeleteLooseFilesTool.Result) tool.call(args("s", folder.toString(), false));
        assertEquals("refused", result.status());
        assertTrue(result.error().contains("not allowed"));
    }

    // ── tier-root protection ──────────────────────────────────────────────────

    @Test
    void refusesProtectedRoot_attention() {
        fs.directories.add(Path.of("/attention"));
        var result = (DeleteLooseFilesTool.Result) tool.call(args("s", "/attention", false));
        assertEquals("refused", result.status());
        assertTrue(result.error().contains("protected"));
    }

    @Test
    void refusesProtectedRoot_queue() {
        fs.directories.add(Path.of("/queue"));
        var result = (DeleteLooseFilesTool.Result) tool.call(args("s", "/queue", false));
        assertEquals("refused", result.status());
    }

    @Test
    void refusesProtectedRoot_starsItself() {
        fs.directories.add(Path.of("/stars"));
        var result = (DeleteLooseFilesTool.Result) tool.call(args("s", "/stars", false));
        assertEquals("refused", result.status());
    }

    @Test
    void refusesProtectedRoot_starsTier() {
        fs.directories.add(Path.of("/stars/popular"));
        var result = (DeleteLooseFilesTool.Result) tool.call(args("s", "/stars/popular", false));
        assertEquals("refused", result.status());
    }

    // ── general refusals ─────────────────────────────────────────────────────

    @Test
    void refusesNonExistentPath() {
        var result = (DeleteLooseFilesTool.Result) tool.call(args("s", "/attention/Ghost", false));
        assertEquals("refused", result.status());
        assertFalse(fs.deleteCalls.iterator().hasNext());
    }

    @Test
    void refusesNonDirectory() {
        fs.files.add(Path.of("/attention/something.mkv"));
        var result = (DeleteLooseFilesTool.Result) tool.call(args("s", "/attention/something.mkv", false));
        assertEquals("refused", result.status());
    }

    @Test
    void refusesPathTraversal() {
        var result = (DeleteLooseFilesTool.Result) tool.call(args("s", "/attention/../stars/evil", false));
        assertEquals("refused", result.status());
        assertTrue(result.error().contains(".."));
    }

    @Test
    void refusesVolumeMismatch() {
        fs.directories.add(Path.of("/attention/Folder"));
        var result = (DeleteLooseFilesTool.Result) tool.call(args("r", "/attention/Folder", false));
        assertEquals("refused", result.status());
    }

    @Test
    void refusesWhenNoVolumeMounted() {
        session.setMountedVolume(null);
        session.setActiveConnection(null);
        var result = (DeleteLooseFilesTool.Result) tool.call(args("s", "/attention/Folder", false));
        assertEquals("refused", result.status());
    }

    // ── partial status on IO failure ──────────────────────────────────────────

    @Test
    void partialStatusOnDeleteFailure() {
        Path folder = Path.of("/attention/Actress (LABEL-001)");
        Path thumbs = folder.resolve("Thumbs.db");
        fs.directories.add(folder);
        fs.children.put(folder, List.of(thumbs));
        fs.failOnDelete = true;

        var result = (DeleteLooseFilesTool.Result) tool.call(args("s", folder.toString(), false));
        assertEquals("partial", result.status());
        assertNotNull(result.error());
    }

    // ── curation log ─────────────────────────────────────────────────────────

    @Test
    void emitsCurationLogOnDryRun() throws Exception {
        Path folder = Path.of("/attention/Actress (LABEL-001)");
        fs.directories.add(folder);
        tool.call(args("s", folder.toString(), true));

        Path logFile = logDir.resolve("curation-log/s")
                .resolve(LocalDate.now() + ".jsonl");
        assertTrue(Files.exists(logFile));
        var parsed = new ObjectMapper().readTree(Files.readAllLines(logFile).get(0));
        assertEquals("delete_loose_files", parsed.get("tool").asText());
        assertEquals("dry-run", parsed.get("status").asText());
    }

    @Test
    void emitsCurationLogOnOk() throws Exception {
        Path folder = Path.of("/attention/Actress (LABEL-001)");
        Path thumbs = folder.resolve("Thumbs.db");
        fs.directories.add(folder);
        fs.children.put(folder, List.of(thumbs));
        tool.call(args("s", folder.toString(), false));

        Path logFile = logDir.resolve("curation-log/s")
                .resolve(LocalDate.now() + ".jsonl");
        assertTrue(Files.exists(logFile));
        var parsed = new ObjectMapper().readTree(Files.readAllLines(logFile).get(0));
        assertEquals("delete_loose_files", parsed.get("tool").asText());
        assertEquals("ok", parsed.get("status").asText());
    }

    @Test
    void emitsCurationLogOnRefusal() throws Exception {
        tool.call(args("s", "/attention/Ghost", false));

        Path logFile = logDir.resolve("curation-log/s")
                .resolve(LocalDate.now() + ".jsonl");
        assertTrue(Files.exists(logFile));
        var parsed = new ObjectMapper().readTree(Files.readAllLines(logFile).get(0));
        assertEquals("delete_loose_files", parsed.get("tool").asText());
        assertEquals("refused", parsed.get("status").asText());
    }

    // ── allowOrphanLocation cascade ──────────────────────────────────────────

    @Test
    void allowOrphanLocation_dropsRowWhenFolderBecomesEmpty() {
        Path folder = Path.of("/attention/Actress (LABEL-001)");
        Path thumbs = folder.resolve("Thumbs.db");
        fs.directories.add(folder);
        fs.children.put(folder, List.of(thumbs));
        seedLocation("s", folder.toString());

        var result = (DeleteLooseFilesTool.Result) tool.call(argsOrphan("s", folder.toString()));
        assertEquals("ok", result.status());
        assertTrue(result.emptyAfter());
        assertEquals(1, result.locationsDropped());

        Integer remaining = jdbi.withHandle(h -> h.createQuery(
                "SELECT COUNT(*) FROM title_locations WHERE path = ?")
                .bind(0, folder.toString())
                .mapTo(Integer.class).one());
        assertEquals(0, remaining);
    }

    @Test
    void allowOrphanLocation_surfacesOrphanedTitle() {
        Path folder = Path.of("/attention/Actress (LABEL-001)");
        Path thumbs = folder.resolve("Thumbs.db");
        fs.directories.add(folder);
        fs.children.put(folder, List.of(thumbs));
        seedLocation("s", folder.toString());

        var result = (DeleteLooseFilesTool.Result) tool.call(argsOrphan("s", folder.toString()));
        assertEquals(1, result.orphanedTitles().size());
        assertEquals("LABEL-001", result.orphanedTitles().get(0).titleCode());
    }

    @Test
    void allowOrphanLocation_keepsRowWhenFolderStillHasContent() {
        Path folder = Path.of("/attention/Actress (LABEL-001)");
        Path thumbs = folder.resolve("Thumbs.db");
        Path video  = folder.resolve("LABEL-001.mkv");
        fs.directories.add(folder);
        fs.children.put(folder, List.of(thumbs, video));
        seedLocation("s", folder.toString());

        var result = (DeleteLooseFilesTool.Result) tool.call(argsOrphan("s", folder.toString()));
        assertEquals("ok", result.status());
        assertFalse(result.emptyAfter()); // video remains
        assertEquals(0, result.locationsDropped());

        Integer remaining = jdbi.withHandle(h -> h.createQuery(
                "SELECT COUNT(*) FROM title_locations WHERE path = ?")
                .bind(0, folder.toString())
                .mapTo(Integer.class).one());
        assertEquals(1, remaining);
    }

    @Test
    void allowOrphanLocation_falsePreservesExistingBehavior() {
        Path folder = Path.of("/attention/Actress (LABEL-001)");
        Path thumbs = folder.resolve("Thumbs.db");
        fs.directories.add(folder);
        fs.children.put(folder, List.of(thumbs));
        seedLocation("s", folder.toString());

        // dryRun:false, allowOrphanLocation defaults to false
        var result = (DeleteLooseFilesTool.Result) tool.call(args("s", folder.toString(), false));
        assertEquals("ok", result.status());
        assertTrue(result.emptyAfter());
        assertEquals(0, result.locationsDropped());

        // row should still be there
        Integer remaining = jdbi.withHandle(h -> h.createQuery(
                "SELECT COUNT(*) FROM title_locations WHERE path = ?")
                .bind(0, folder.toString())
                .mapTo(Integer.class).one());
        assertEquals(1, remaining);
    }

    @Test
    void allowOrphanLocation_doesNotMatchOtherVolume() {
        Path folder = Path.of("/attention/Actress (LABEL-001)");
        Path thumbs = folder.resolve("Thumbs.db");
        fs.directories.add(folder);
        fs.children.put(folder, List.of(thumbs));
        // location is on volume 'r', not 's' — must not be touched
        seedLocation("r", folder.toString());

        var result = (DeleteLooseFilesTool.Result) tool.call(argsOrphan("s", folder.toString()));
        assertEquals("ok", result.status());
        assertEquals(0, result.locationsDropped());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void seedLocation(String volumeId, String path) {
        jdbi.useHandle(h -> {
            long titleId = h.createQuery(
                    "INSERT INTO titles (code, actress_id) VALUES ('LABEL-001', NULL) RETURNING id")
                    .mapTo(Long.class).one();
            h.execute("""
                    INSERT INTO title_locations (title_id, volume_id, partition_id, path, last_seen_at)
                    VALUES (?, ?, 'attention', ?, '2024-01-01')
                    """, titleId, volumeId, path);
        });
    }

    private static ObjectNode argsOrphan(String volumeId, String path) {
        ObjectNode n = M.createObjectNode();
        n.put("volumeId",            volumeId);
        n.put("path",                path);
        n.put("dryRun",              false);
        n.put("allowOrphanLocation", true);
        return n;
    }

    private static ObjectNode args(String volumeId, String path, boolean dryRun) {
        ObjectNode n = M.createObjectNode();
        n.put("volumeId", volumeId);
        n.put("path",     path);
        n.put("dryRun",   dryRun);
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
        final Set<Path>             directories = new HashSet<>();
        final Set<Path>             files       = new HashSet<>();
        final Map<Path, List<Path>> children    = new HashMap<>();
        final List<Path>            deleteCalls = new ArrayList<>();
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
