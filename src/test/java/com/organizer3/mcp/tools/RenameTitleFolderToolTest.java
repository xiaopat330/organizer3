package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.organizer3.config.volume.VolumeConfig;
import com.organizer3.curation.CurationLog;
import com.organizer3.db.SchemaInitializer;
import com.organizer3.filesystem.FileTimestamps;
import com.organizer3.filesystem.VolumeFileSystem;
import com.organizer3.repository.jdbi.JdbiTitleLocationRepository;
import com.organizer3.repository.jdbi.JdbiTitleRepository;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RenameTitleFolderToolTest {

    @TempDir
    Path logDir;

    private static final ObjectMapper M = new ObjectMapper();

    private Connection connection;
    private Jdbi jdbi;
    private JdbiTitleRepository titleRepo;
    private JdbiTitleLocationRepository locationRepo;
    private SessionContext session;
    private FakeFs fs;
    private CurationLog curationLog;
    private RenameTitleFolderTool tool;

    @BeforeEach
    void setUp() throws Exception {
        connection  = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi        = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        jdbi.useHandle(h -> {
            h.execute("INSERT INTO volumes (id, structure_type) VALUES ('s', 'conventional')");
            h.execute("INSERT INTO volumes (id, structure_type) VALUES ('r', 'conventional')");
        });
        locationRepo = new JdbiTitleLocationRepository(jdbi);
        titleRepo    = new JdbiTitleRepository(jdbi, locationRepo);

        fs          = new FakeFs();
        curationLog = new CurationLog(logDir);
        session     = new SessionContext();
        session.setMountedVolume(new VolumeConfig("s", "//host/s", "conventional", "host", null));
        session.setActiveConnection(new FakeConnection(fs));
        tool = new RenameTitleFolderTool(session, titleRepo, locationRepo, curationLog);
    }

    @AfterEach
    void tearDown() throws Exception { connection.close(); }

    // ── happy path ────────────────────────────────────────────────────────────

    @Test
    void dryRunReturnsPlanWithoutTouchingFs() {
        seedTitle("MIDE-123", "s", "/queue/Misuzu Kawana (MIDE-123)");
        var result = (RenameTitleFolderTool.Result) tool.call(args("MIDE-123", "Misuzu Kawana (MIDE-123)", true));
        assertEquals("dry-run", result.status());
        assertTrue(fs.renameCalls.isEmpty(), "dry-run must not touch FS");
    }

    @Test
    void executesRenameAndUpdatesDb() {
        seedTitle("MIDE-123", "s", "/queue/Old Name (MIDE-123)");
        var result = (RenameTitleFolderTool.Result) tool.call(args("MIDE-123", "New Name (MIDE-123)", false));

        assertEquals("ok", result.status());
        assertEquals("/queue/Old Name (MIDE-123)", result.from());
        assertEquals("/queue/New Name (MIDE-123)", result.to());
        assertNull(result.error());

        assertEquals(1, fs.renameCalls.size());
        assertEquals(Path.of("/queue/Old Name (MIDE-123)"), fs.renameCalls.get(0).path());
        assertEquals("New Name (MIDE-123)", fs.renameCalls.get(0).newName());

        // DB path should be updated
        String newPath = jdbi.withHandle(h ->
                h.createQuery("SELECT path FROM title_locations LIMIT 1").mapTo(String.class).one());
        assertEquals("/queue/New Name (MIDE-123)", newPath);
    }

    @Test
    void sameNameIsNoOp() {
        seedTitle("MIDE-123", "s", "/queue/Same Name (MIDE-123)");
        var result = (RenameTitleFolderTool.Result) tool.call(args("MIDE-123", "Same Name (MIDE-123)", false));
        assertEquals("ok", result.status());
        assertTrue(fs.renameCalls.isEmpty(), "same-name rename should not call FS");
    }

    @Test
    void emitsCurationLogOnOk() throws Exception {
        seedTitle("MIDE-123", "s", "/queue/Old (MIDE-123)");
        tool.call(args("MIDE-123", "New (MIDE-123)", false));

        Path logFile = logDir.resolve("curation-log/s")
                .resolve(LocalDate.now() + ".jsonl");
        assertTrue(Files.exists(logFile));
        var parsed = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(Files.readAllLines(logFile).get(0));
        assertEquals("rename_title_folder", parsed.get("tool").asText());
        assertEquals("ok", parsed.get("status").asText());
    }

    @Test
    void emitsCurationLogOnDryRun() throws Exception {
        seedTitle("MIDE-123", "s", "/queue/Old (MIDE-123)");
        tool.call(args("MIDE-123", "New (MIDE-123)", true));

        Path logFile = logDir.resolve("curation-log/s")
                .resolve(LocalDate.now() + ".jsonl");
        assertTrue(Files.exists(logFile));
        var parsed = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(Files.readAllLines(logFile).get(0));
        assertEquals("dry-run", parsed.get("status").asText());
    }

    // ── refusals ─────────────────────────────────────────────────────────────

    @Test
    void refusesUnknownTitleCode() {
        var result = (RenameTitleFolderTool.Result) tool.call(args("GHOST-001", "New Name", false));
        assertEquals("failed", result.status());
        assertNotNull(result.error());
    }

    @Test
    void refusesWhenNoLocationOnMountedVolume() {
        seedTitle("MIDE-123", "r", "/queue/Foo (MIDE-123)"); // on 'r', not 's'
        var result = (RenameTitleFolderTool.Result) tool.call(args("MIDE-123", "New (MIDE-123)", false));
        assertEquals("failed", result.status());
        assertTrue(result.error().contains("no live location") || result.error().contains("MIDE-123"));
    }

    @Test
    void refusesAmbiguousMultipleLocations() {
        // Seed two rows for same title on same volume
        long titleId = jdbi.withHandle(h ->
                h.createQuery("INSERT INTO titles (code) VALUES ('MIDE-500') RETURNING id")
                        .mapTo(Long.class).one());
        jdbi.useHandle(h -> {
            h.execute("INSERT INTO title_locations (title_id, volume_id, partition_id, path, last_seen_at) VALUES (?, 's', 'queue', '/queue/Loc1 (MIDE-500)', '2024-01-01')", titleId);
            h.execute("INSERT INTO title_locations (title_id, volume_id, partition_id, path, last_seen_at) VALUES (?, 's', 'queue', '/queue/Loc2 (MIDE-500)', '2024-01-01')", titleId);
        });
        var result = (RenameTitleFolderTool.Result) tool.call(args("MIDE-500", "New (MIDE-500)", false));
        assertEquals("failed", result.status());
        assertTrue(result.error().contains("ambiguous") || result.error().contains("2"));
    }

    @Test
    void refusesPathSeparatorInNewFolderName() {
        seedTitle("MIDE-123", "s", "/queue/Old (MIDE-123)");
        var result = (RenameTitleFolderTool.Result) tool.call(args("MIDE-123", "sub/dir", false));
        assertEquals("failed", result.status());
        assertTrue(result.error().contains("separator") || result.error().contains("basename"));
    }

    @Test
    void refusesBlankNewFolderName() {
        seedTitle("MIDE-123", "s", "/queue/Old (MIDE-123)");
        var result = (RenameTitleFolderTool.Result) tool.call(args("MIDE-123", "   ", false));
        assertEquals("failed", result.status());
    }

    @Test
    void refusesCollisionWithExistingPath() {
        seedTitle("MIDE-123", "s", "/queue/Old (MIDE-123)");
        fs.existingPaths.add(Path.of("/queue/New (MIDE-123)"));
        var result = (RenameTitleFolderTool.Result) tool.call(args("MIDE-123", "New (MIDE-123)", false));
        assertEquals("failed", result.status());
        assertTrue(result.error().contains("exists") || result.error().contains("destination"));
    }

    @Test
    void refusesWhenNoVolumeMounted() {
        session.setMountedVolume(null);
        session.setActiveConnection(null);
        var result = (RenameTitleFolderTool.Result) tool.call(args("MIDE-123", "New", false));
        assertEquals("failed", result.status());
    }

    @Test
    void returnsFailedOnIoException() {
        seedTitle("MIDE-123", "s", "/queue/Old (MIDE-123)");
        fs.failOnRename = true;
        var result = (RenameTitleFolderTool.Result) tool.call(args("MIDE-123", "New (MIDE-123)", false));
        assertEquals("failed", result.status());
        assertNotNull(result.error());
    }

    @Test
    void titleCodeIsNormalizedToUpperCase() {
        seedTitle("MIDE-123", "s", "/queue/Foo (MIDE-123)");
        // pass lowercase titleCode
        var result = (RenameTitleFolderTool.Result) tool.call(args("mide-123", "Bar (MIDE-123)", false));
        assertEquals("ok", result.status());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void seedTitle(String code, String volumeId, String path) {
        jdbi.useHandle(h -> {
            long titleId = h.createQuery("INSERT INTO titles (code) VALUES (?) RETURNING id")
                    .bind(0, code).mapTo(Long.class).one();
            h.execute("""
                    INSERT INTO title_locations (title_id, volume_id, partition_id, path, last_seen_at)
                    VALUES (?, ?, 'queue', ?, '2024-01-01')
                    """, titleId, volumeId, path);
        });
    }

    private static ObjectNode args(String titleCode, String newFolderName, boolean dryRun) {
        ObjectNode n = M.createObjectNode();
        n.put("titleCode",     titleCode);
        n.put("newFolderName", newFolderName);
        n.put("dryRun",        dryRun);
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
        record RenameCall(Path path, String newName) {}

        final List<RenameCall> renameCalls = new ArrayList<>();
        final Set<Path>        existingPaths = new HashSet<>();
        boolean failOnRename = false;

        @Override public boolean exists(Path path) { return existingPaths.contains(path); }
        @Override public boolean isDirectory(Path path) { return false; }

        @Override public void rename(Path path, String newName) throws IOException {
            if (failOnRename) throw new IOException("simulated rename failure");
            renameCalls.add(new RenameCall(path, newName));
        }

        @Override public List<Path> listDirectory(Path path) { return List.of(); }
        @Override public List<Path> walk(Path root) { return List.of(); }
        @Override public LocalDate getLastModifiedDate(Path path) { return null; }
        @Override public InputStream openFile(Path path) { throw new UnsupportedOperationException(); }
        @Override public long size(Path path) { return 0; }
        @Override public void move(Path source, Path destination) { throw new UnsupportedOperationException(); }
        @Override public void createDirectories(Path path) { throw new UnsupportedOperationException(); }
        @Override public void writeFile(Path path, byte[] contents) { throw new UnsupportedOperationException(); }
        @Override public FileTimestamps getTimestamps(Path path) { return null; }
        @Override public void setTimestamps(Path path, Instant created, Instant modified) {}
    }
}
