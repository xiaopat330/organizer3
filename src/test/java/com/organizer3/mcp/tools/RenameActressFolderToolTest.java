package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.organizer3.config.volume.VolumeConfig;
import com.organizer3.curation.CurationLog;
import com.organizer3.db.SchemaInitializer;
import com.organizer3.filesystem.FileTimestamps;
import com.organizer3.filesystem.VolumeFileSystem;
import com.organizer3.model.Actress;
import com.organizer3.model.ActressAlias;
import com.organizer3.repository.jdbi.JdbiActressRepository;
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

class RenameActressFolderToolTest {

    @TempDir
    Path logDir;

    private static final ObjectMapper M = new ObjectMapper();

    private Connection connection;
    private Jdbi jdbi;
    private JdbiActressRepository actressRepo;
    private SessionContext session;
    private FakeFs fs;
    private CurationLog curationLog;
    private RenameActressFolderTool tool;

    @BeforeEach
    void setUp() throws Exception {
        connection  = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi        = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        jdbi.useHandle(h -> {
            h.execute("INSERT INTO volumes (id, structure_type) VALUES ('s', 'conventional')");
            h.execute("INSERT INTO volumes (id, structure_type) VALUES ('r', 'conventional')");
        });
        actressRepo = new JdbiActressRepository(jdbi);

        fs          = new FakeFs();
        curationLog = new CurationLog(logDir);
        session     = new SessionContext();
        session.setMountedVolume(new VolumeConfig("s", "//host/s", "conventional", "host", null));
        session.setActiveConnection(new FakeConnection(fs));
        tool = new RenameActressFolderTool(session, actressRepo, jdbi, curationLog);
    }

    @AfterEach
    void tearDown() throws Exception { connection.close(); }

    // ── happy path ────────────────────────────────────────────────────────────

    @Test
    void dryRunReturnsPlanWithoutTouchingFs() {
        Actress a = seedActress("Shion Fujimoto", "Shien Fujimoto",
                "MIDE-001", "s", "/stars/minor/Shien Fujimoto/Shien Fujimoto (MIDE-001)");

        var result = (RenameActressFolderTool.Result) tool.call(args(a.getId(), true));
        assertEquals("dry-run", result.status());
        assertTrue(fs.renameCalls.isEmpty(), "dry-run must not touch FS");
        assertEquals(1, result.from().size());
        assertEquals("/stars/minor/Shien Fujimoto", result.from().get(0));
        assertEquals("/stars/minor/Shion Fujimoto", result.to().get(0));
        assertEquals(1, result.updatedPaths().size());
        assertEquals("/stars/minor/Shion Fujimoto/Shien Fujimoto (MIDE-001)", result.updatedPaths().get(0));
    }

    @Test
    void executesRenameAndUpdatesDbPaths() {
        Actress a = seedActress("Shion Fujimoto", "Shien Fujimoto",
                "MIDE-001", "s", "/stars/minor/Shien Fujimoto/Shien Fujimoto (MIDE-001)");

        var result = (RenameActressFolderTool.Result) tool.call(args(a.getId(), false));

        assertEquals("ok", result.status());
        assertEquals(1, fs.renameCalls.size());
        assertEquals(Path.of("/stars/minor/Shien Fujimoto"), fs.renameCalls.get(0).path());
        assertEquals("Shion Fujimoto", fs.renameCalls.get(0).newName());

        // DB path updated
        String dbPath = jdbi.withHandle(h ->
                h.createQuery("SELECT path FROM title_locations LIMIT 1").mapTo(String.class).one());
        assertEquals("/stars/minor/Shion Fujimoto/Shien Fujimoto (MIDE-001)", dbPath);
    }

    @Test
    void updatesMultipleTitleFoldersUnderActressFolder() {
        Actress a = actressRepo.save(Actress.builder()
                .canonicalName("Shion Fujimoto").tier(Actress.Tier.LIBRARY)
                .firstSeenAt(LocalDate.now()).build());
        actressRepo.saveAlias(new ActressAlias(a.getId(), "Shien Fujimoto"));
        // Two titles in the same actress folder
        seedTitleAt(a, "MIDE-001", "s", "/stars/minor/Shien Fujimoto/Shien Fujimoto (MIDE-001)");
        seedTitleAt(a, "MIDE-002", "s", "/stars/minor/Shien Fujimoto/Shien Fujimoto (MIDE-002)");

        var result = (RenameActressFolderTool.Result) tool.call(args(a.getId(), false));

        assertEquals("ok", result.status());
        assertEquals(2, result.updatedPaths().size());
        // Only ONE rename call at actress folder level
        assertEquals(1, fs.renameCalls.size());
    }

    @Test
    void handlesFromNameOverride() {
        // No alias registered; folder uses a name not in DB aliases
        Actress a = actressRepo.save(Actress.builder()
                .canonicalName("Yuzu Ogura").tier(Actress.Tier.POPULAR)
                .firstSeenAt(LocalDate.now()).build());
        seedTitleAt(a, "YUZ-001", "s", "/stars/popular/Yuko Ogura/Yuko Ogura (YUZ-001)");

        ObjectNode argsNode = M.createObjectNode();
        argsNode.put("actress_id", a.getId());
        argsNode.put("fromName", "Yuko Ogura");
        argsNode.put("dryRun", false);

        var result = (RenameActressFolderTool.Result) tool.call(argsNode);
        assertEquals("ok", result.status());
        assertEquals(1, fs.renameCalls.size());
        assertEquals(Path.of("/stars/popular/Yuko Ogura"), fs.renameCalls.get(0).path());
        assertEquals("Yuzu Ogura", fs.renameCalls.get(0).newName());
    }

    @Test
    void nothingToDoWhenAllFoldersAlreadyCanonical() {
        Actress a = seedActress("Shion Fujimoto", "Shien Fujimoto",
                "MIDE-001", "s", "/stars/minor/Shion Fujimoto/Shion Fujimoto (MIDE-001)");
        // Path already uses canonical name, so nothing to rename
        var result = (RenameActressFolderTool.Result) tool.call(args(a.getId(), false));
        assertEquals("nothing-to-do", result.status());
        assertTrue(fs.renameCalls.isEmpty());
    }

    @Test
    void emitsCurationLogOnDryRun() throws Exception {
        Actress a = seedActress("Shion Fujimoto", "Shien Fujimoto",
                "MIDE-001", "s", "/stars/minor/Shien Fujimoto/Shien Fujimoto (MIDE-001)");
        tool.call(args(a.getId(), true));

        Path logFile = logDir.resolve("curation-log/s")
                .resolve(LocalDate.now() + ".jsonl");
        assertTrue(Files.exists(logFile));
        var parsed = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(Files.readAllLines(logFile).get(0));
        assertEquals("rename_actress_folder", parsed.get("tool").asText());
        assertEquals("dry-run", parsed.get("status").asText());
    }

    @Test
    void emitsCurationLogOnOk() throws Exception {
        Actress a = seedActress("Shion Fujimoto", "Shien Fujimoto",
                "MIDE-001", "s", "/stars/minor/Shien Fujimoto/Shien Fujimoto (MIDE-001)");
        tool.call(args(a.getId(), false));

        Path logFile = logDir.resolve("curation-log/s")
                .resolve(LocalDate.now() + ".jsonl");
        var parsed = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(Files.readAllLines(logFile).get(0));
        assertEquals("ok", parsed.get("status").asText());
    }

    // ── refusals ─────────────────────────────────────────────────────────────

    @Test
    void refusesCollisionWithExistingDestination() {
        Actress a = seedActress("Shion Fujimoto", "Shien Fujimoto",
                "MIDE-001", "s", "/stars/minor/Shien Fujimoto/Shien Fujimoto (MIDE-001)");
        // Destination already exists
        fs.existingPaths.add(Path.of("/stars/minor/Shion Fujimoto"));

        var result = (RenameActressFolderTool.Result) tool.call(args(a.getId(), false));
        assertEquals("failed", result.status());
        assertTrue(fs.renameCalls.isEmpty());
    }

    @Test
    void refusesWhenNoVolumeMounted() {
        Actress a = seedActress("Shion Fujimoto", "Shien Fujimoto",
                "MIDE-001", "s", "/stars/minor/Shien Fujimoto/Shien Fujimoto (MIDE-001)");
        session.setMountedVolume(null);
        session.setActiveConnection(null);

        var result = (RenameActressFolderTool.Result) tool.call(args(a.getId(), false));
        assertEquals("failed", result.status());
    }

    @Test
    void skipsLocationsOnOtherVolume() {
        // Actress folder on 'r', not 's'
        Actress a = seedActress("Shion Fujimoto", "Shien Fujimoto",
                "MIDE-001", "r", "/stars/minor/Shien Fujimoto/Shien Fujimoto (MIDE-001)");
        var result = (RenameActressFolderTool.Result) tool.call(args(a.getId(), false));
        // Should be nothing-to-do for mounted volume 's'
        assertEquals("nothing-to-do", result.status());
    }

    @Test
    void rejectsWhenNoActressIdAndNoName() {
        ObjectNode argsNode = M.createObjectNode();
        argsNode.put("dryRun", true);
        assertThrows(IllegalArgumentException.class, () -> tool.call(argsNode));
    }

    @Test
    void resolvesActressByName() {
        Actress a = seedActress("Shion Fujimoto", "Shien Fujimoto",
                "MIDE-001", "s", "/stars/minor/Shien Fujimoto/Shien Fujimoto (MIDE-001)");
        ObjectNode argsNode = M.createObjectNode();
        argsNode.put("name", "Shion Fujimoto");
        argsNode.put("dryRun", true);
        var result = (RenameActressFolderTool.Result) tool.call(argsNode);
        assertEquals(a.getId(), result.actressId());
        assertEquals("dry-run", result.status());
    }

    /**
     * Bug 2 regression: rename_actress_folder must write the short-form partition_id
     * (e.g. "minor") not the path-style form ("stars/minor") that sync stores.
     * Before the fix the UPDATE only set path, leaving partition_id unchanged.
     */
    @Test
    void normalisesPartitionIdToShortFormOnRename() {
        // Seed with path-style partition_id as sync would produce
        Actress a = actressRepo.save(Actress.builder()
                .canonicalName("Shion Fujimoto").tier(Actress.Tier.MINOR)
                .firstSeenAt(LocalDate.now()).build());
        actressRepo.saveAlias(new ActressAlias(a.getId(), "Shien Fujimoto"));
        jdbi.useHandle(h -> {
            long titleId = h.createQuery(
                    "INSERT INTO titles (code, actress_id) VALUES (?, ?) RETURNING id")
                    .bind(0, "MIDE-001").bind(1, a.getId()).mapTo(Long.class).one();
            h.execute("""
                    INSERT INTO title_locations (title_id, volume_id, partition_id, path, last_seen_at)
                    VALUES (?, 's', 'stars/minor', '/stars/minor/Shien Fujimoto/Shien Fujimoto (MIDE-001)', '2024-01-01')
                    """, titleId);
        });

        var result = (RenameActressFolderTool.Result) tool.call(args(a.getId(), false));
        assertEquals("ok", result.status());

        String partition = jdbi.withHandle(h ->
                h.createQuery("SELECT partition_id FROM title_locations LIMIT 1")
                        .mapTo(String.class).one());
        assertEquals("minor", partition,
                "partition_id must be normalised to short form (not 'stars/minor')");
    }

    /**
     * Regression: actress titles living at the volume root (no per-actress parent folder)
     * must yield a clean no-op rather than throwing NPE on {@code getFileName()}.
     */
    @Test
    void rootLevelTitleLocationsYieldNoOpInsteadOfNpe() {
        Actress a = actressRepo.save(Actress.builder()
                .canonicalName("Shion Fujimoto").tier(Actress.Tier.LIBRARY)
                .firstSeenAt(LocalDate.now()).build());
        actressRepo.saveAlias(new ActressAlias(a.getId(), "Shien Fujimoto"));
        // Title sits at volume root — parent path has no filename segment
        seedTitleAt(a, "MIDE-001", "s", "/Shien Fujimoto (MIDE-001)");

        var result = assertDoesNotThrow(() ->
                (RenameActressFolderTool.Result) tool.call(args(a.getId(), false)));
        assertEquals("nothing-to-do", result.status());
        assertTrue(fs.renameCalls.isEmpty());
        assertTrue(result.from().isEmpty());
        assertTrue(result.to().isEmpty());
        assertTrue(result.updatedPaths().isEmpty());
    }

    @Test
    void returnsPartialOnIoException() {
        Actress a = actressRepo.save(Actress.builder()
                .canonicalName("Shion Fujimoto").tier(Actress.Tier.LIBRARY)
                .firstSeenAt(LocalDate.now()).build());
        actressRepo.saveAlias(new ActressAlias(a.getId(), "Shien Fujimoto"));
        seedTitleAt(a, "MIDE-001", "s", "/stars/minor/Shien Fujimoto/Shien Fujimoto (MIDE-001)");

        fs.failOnRename = true;
        var result = (RenameActressFolderTool.Result) tool.call(args(a.getId(), false));
        assertEquals("failed", result.status()); // all failed (only 1 folder)
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Actress seedActress(String canonical, String alias, String titleCode,
                                 String volumeId, String path) {
        Actress a = actressRepo.save(Actress.builder()
                .canonicalName(canonical).tier(Actress.Tier.LIBRARY)
                .firstSeenAt(LocalDate.now()).build());
        actressRepo.saveAlias(new ActressAlias(a.getId(), alias));
        seedTitleAt(a, titleCode, volumeId, path);
        return a;
    }

    private void seedTitleAt(Actress actress, String code, String volumeId, String path) {
        jdbi.useHandle(h -> {
            long titleId = h.createQuery(
                    "INSERT INTO titles (code, actress_id) VALUES (?, ?) RETURNING id")
                    .bind(0, code).bind(1, actress.getId()).mapTo(Long.class).one();
            h.execute("""
                    INSERT INTO title_locations (title_id, volume_id, partition_id, path, last_seen_at)
                    VALUES (?, ?, 'minor', ?, '2024-01-01')
                    """, titleId, volumeId, path);
        });
    }

    private static ObjectNode args(long actressId, boolean dryRun) {
        ObjectNode n = M.createObjectNode();
        n.put("actress_id", actressId);
        n.put("dryRun", dryRun);
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
