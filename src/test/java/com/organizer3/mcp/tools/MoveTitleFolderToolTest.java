package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.organizer3.config.volume.LibraryConfig;
import com.organizer3.config.volume.VolumeConfig;
import com.organizer3.curation.CurationLog;
import com.organizer3.db.SchemaInitializer;
import com.organizer3.filesystem.FileTimestamps;
import com.organizer3.filesystem.VolumeFileSystem;
import com.organizer3.model.Actress;
import com.organizer3.repository.jdbi.JdbiActressRepository;
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

class MoveTitleFolderToolTest {

    @TempDir
    Path logDir;

    private static final ObjectMapper M = new ObjectMapper();

    private Connection connection;
    private Jdbi jdbi;
    private JdbiTitleRepository titleRepo;
    private JdbiTitleLocationRepository locationRepo;
    private JdbiActressRepository actressRepo;
    private SessionContext session;
    private FakeFs fs;
    private CurationLog curationLog;
    private MoveTitleFolderTool tool;

    // LibraryConfig: star=2, minor=5, popular=20, superstar=50, goddess=100
    private static final LibraryConfig LIBRARY_CFG = new LibraryConfig(2, 5, 20, 50, 100);

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
        actressRepo  = new JdbiActressRepository(jdbi);

        fs          = new FakeFs();
        curationLog = new CurationLog(logDir);
        session     = new SessionContext();
        session.setMountedVolume(new VolumeConfig("s", "//host/s", "conventional", "host", null));
        session.setActiveConnection(new FakeConnection(fs));
        tool = new MoveTitleFolderTool(session, titleRepo, locationRepo, actressRepo, LIBRARY_CFG, curationLog);
    }

    @AfterEach
    void tearDown() throws Exception { connection.close(); }

    // ── toActressId path ──────────────────────────────────────────────────────

    @Test
    void dryRunReturnsPlanWithoutTouchingFs() {
        Actress a = seedActress("Rin Hachimitsu", 3); // 3 titles → tier library (2<=3<5)
        seedTitle("MIDE-001", a, "s", "/queue/Rin Hachimitsu (MIDE-001)");

        var result = (MoveTitleFolderTool.Result) tool.call(argsActress("MIDE-001", a.getId(), true));
        assertEquals("dry-run", result.status());
        assertEquals("/queue/Rin Hachimitsu (MIDE-001)", result.from());
        assertEquals("/stars/library/Rin Hachimitsu/Rin Hachimitsu (MIDE-001)", result.to());
        assertTrue(fs.moveCalls.isEmpty(), "dry-run must not touch FS");
    }

    @Test
    void movesToActressTierFolder() {
        Actress a = seedActress("Rin Hachimitsu", 7); // 7 titles → tier minor (5<=7<20)
        seedTitle("MIDE-001", a, "s", "/queue/Rin Hachimitsu (MIDE-001)");

        var result = (MoveTitleFolderTool.Result) tool.call(argsActress("MIDE-001", a.getId(), false));
        assertEquals("ok", result.status());
        assertEquals("/stars/minor/Rin Hachimitsu/Rin Hachimitsu (MIDE-001)", result.to());
        assertEquals(1, fs.moveCalls.size());
        assertEquals(Path.of("/queue/Rin Hachimitsu (MIDE-001)"), fs.moveCalls.get(0).from());
        assertEquals(Path.of("/stars/minor/Rin Hachimitsu/Rin Hachimitsu (MIDE-001)"), fs.moveCalls.get(0).to());
        assertTrue(fs.createDirCalls.contains(Path.of("/stars/minor/Rin Hachimitsu")));
    }

    @Test
    void updatesDbPathAndPartition() {
        Actress a = seedActress("Rin Hachimitsu", 7);
        seedTitle("MIDE-001", a, "s", "/queue/Rin Hachimitsu (MIDE-001)");

        tool.call(argsActress("MIDE-001", a.getId(), false));

        String newPath = jdbi.withHandle(h ->
                h.createQuery("SELECT path FROM title_locations LIMIT 1").mapTo(String.class).one());
        assertEquals("/stars/minor/Rin Hachimitsu/Rin Hachimitsu (MIDE-001)", newPath);

        String newPartition = jdbi.withHandle(h ->
                h.createQuery("SELECT partition_id FROM title_locations LIMIT 1").mapTo(String.class).one());
        assertEquals("minor", newPartition);
    }

    // ── toAbsolutePath ────────────────────────────────────────────────────────

    @Test
    void movesToExplicitAbsolutePath() {
        seedTitle("MIDE-001", null, "s", "/queue/Foo (MIDE-001)");
        var result = (MoveTitleFolderTool.Result) tool.call(argsAbsPath("MIDE-001", "/attention/Review", false));
        assertEquals("ok", result.status());
        assertEquals("/attention/Review/Foo (MIDE-001)", result.to());
    }

    @Test
    void absolutePathDryRun() {
        seedTitle("MIDE-001", null, "s", "/queue/Foo (MIDE-001)");
        var result = (MoveTitleFolderTool.Result) tool.call(argsAbsPath("MIDE-001", "/attention/Review", true));
        assertEquals("dry-run", result.status());
        assertTrue(fs.moveCalls.isEmpty());
    }

    @Test
    void absolutePathSetsPartitionIdFromTopLevel() {
        seedTitle("MIDE-001", null, "s", "/queue/Foo (MIDE-001)");
        tool.call(argsAbsPath("MIDE-001", "/attention/Review", false));

        String partition = jdbi.withHandle(h ->
                h.createQuery("SELECT partition_id FROM title_locations LIMIT 1").mapTo(String.class).one());
        assertEquals("attention", partition);
    }

    // ── curation log ─────────────────────────────────────────────────────────

    @Test
    void emitsCurationLogOnOk() throws Exception {
        Actress a = seedActress("Rin Hachimitsu", 7);
        seedTitle("MIDE-001", a, "s", "/queue/Rin Hachimitsu (MIDE-001)");
        tool.call(argsActress("MIDE-001", a.getId(), false));

        Path logFile = logDir.resolve("curation-log/s")
                .resolve(LocalDate.now() + ".jsonl");
        assertTrue(Files.exists(logFile));
        var parsed = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(Files.readAllLines(logFile).get(0));
        assertEquals("move_title_folder", parsed.get("tool").asText());
        assertEquals("ok", parsed.get("status").asText());
    }

    @Test
    void emitsCurationLogOnDryRun() throws Exception {
        Actress a = seedActress("Rin Hachimitsu", 7);
        seedTitle("MIDE-001", a, "s", "/queue/Rin Hachimitsu (MIDE-001)");
        tool.call(argsActress("MIDE-001", a.getId(), true));

        Path logFile = logDir.resolve("curation-log/s")
                .resolve(LocalDate.now() + ".jsonl");
        var parsed = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(Files.readAllLines(logFile).get(0));
        assertEquals("dry-run", parsed.get("status").asText());
    }

    // ── refusals ─────────────────────────────────────────────────────────────

    @Test
    void refusesBothActressAndAbsPath() {
        var result = (MoveTitleFolderTool.Result) tool.call(argsActressAndAbsPath("MIDE-001", 1L, "/queue", true));
        assertEquals("failed", result.status());
        assertTrue(result.error().contains("mutually exclusive"));
    }

    @Test
    void refusesNeitherActressNorAbsPath() {
        ObjectNode n = M.createObjectNode();
        n.put("titleCode", "MIDE-001");
        n.put("dryRun", true);
        var result = (MoveTitleFolderTool.Result) tool.call(n);
        assertEquals("failed", result.status());
    }

    @Test
    void refusesUnknownTitle() {
        var result = (MoveTitleFolderTool.Result) tool.call(argsAbsPath("GHOST-999", "/queue", false));
        assertEquals("failed", result.status());
    }

    @Test
    void refusesNoLocationOnMountedVolume() {
        seedTitle("MIDE-001", null, "r", "/queue/Foo (MIDE-001)"); // on r, not s
        var result = (MoveTitleFolderTool.Result) tool.call(argsAbsPath("MIDE-001", "/queue", false));
        assertEquals("failed", result.status());
    }

    @Test
    void refusesAmbiguousMultipleLocations() {
        jdbi.useHandle(h -> {
            long titleId = h.createQuery("INSERT INTO titles (code) VALUES ('MIDE-500') RETURNING id")
                    .mapTo(Long.class).one();
            h.execute("INSERT INTO title_locations (title_id, volume_id, partition_id, path, last_seen_at) VALUES (?, 's', 'queue', '/queue/A (MIDE-500)', '2024-01-01')", titleId);
            h.execute("INSERT INTO title_locations (title_id, volume_id, partition_id, path, last_seen_at) VALUES (?, 's', 'queue', '/queue/B (MIDE-500)', '2024-01-01')", titleId);
        });
        var result = (MoveTitleFolderTool.Result) tool.call(argsAbsPath("MIDE-500", "/queue", false));
        assertEquals("failed", result.status());
    }

    @Test
    void refusesCollisionAtDestination() {
        seedTitle("MIDE-001", null, "s", "/queue/Foo (MIDE-001)");
        fs.existingPaths.add(Path.of("/attention/Review/Foo (MIDE-001)"));
        var result = (MoveTitleFolderTool.Result) tool.call(argsAbsPath("MIDE-001", "/attention/Review", false));
        assertEquals("failed", result.status());
        assertTrue(result.error().contains("exists") || result.error().contains("destination"));
    }

    @Test
    void refusesWhenNoVolumeMounted() {
        session.setMountedVolume(null);
        session.setActiveConnection(null);
        var result = (MoveTitleFolderTool.Result) tool.call(argsAbsPath("MIDE-001", "/queue", false));
        assertEquals("failed", result.status());
    }

    @Test
    void returnsFailedOnIoException() {
        seedTitle("MIDE-001", null, "s", "/queue/Foo (MIDE-001)");
        fs.failOnMove = true;
        var result = (MoveTitleFolderTool.Result) tool.call(argsAbsPath("MIDE-001", "/attention/Review", false));
        assertEquals("failed", result.status());
    }

    @Test
    void samePathIsNoOp() {
        seedTitle("MIDE-001", null, "s", "/queue/Foo (MIDE-001)");
        var result = (MoveTitleFolderTool.Result) tool.call(argsAbsPath("MIDE-001", "/queue", false));
        assertEquals("ok", result.status());
        assertTrue(fs.moveCalls.isEmpty(), "same-path move should be a no-op");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Actress seedActress(String canonical, int extraTitles) {
        Actress a = actressRepo.save(Actress.builder()
                .canonicalName(canonical).tier(Actress.Tier.LIBRARY)
                .firstSeenAt(LocalDate.now()).build());
        // Seed enough title rows to make countByActress return extraTitles
        for (int i = 0; i < extraTitles; i++) {
            final int fi = i;
            jdbi.useHandle(h -> h.execute(
                    "INSERT INTO titles (code, actress_id) VALUES ('EXTRA-" + String.format("%03d", fi) + "', ?)",
                    a.getId()));
        }
        return a;
    }

    private void seedTitle(String code, Actress actress, String volumeId, String path) {
        jdbi.useHandle(h -> {
            long actressId = actress != null ? actress.getId() : 0;
            String sql = actress != null
                    ? "INSERT INTO titles (code, actress_id) VALUES (?, ?) RETURNING id"
                    : "INSERT INTO titles (code) VALUES (?) RETURNING id";
            long titleId = actress != null
                    ? h.createQuery(sql).bind(0, code).bind(1, actressId).mapTo(Long.class).one()
                    : h.createQuery(sql).bind(0, code).mapTo(Long.class).one();
            h.execute("""
                    INSERT INTO title_locations (title_id, volume_id, partition_id, path, last_seen_at)
                    VALUES (?, ?, 'queue', ?, '2024-01-01')
                    """, titleId, volumeId, path);
        });
    }

    private static ObjectNode argsActress(String titleCode, long actressId, boolean dryRun) {
        ObjectNode n = M.createObjectNode();
        n.put("titleCode",  titleCode);
        n.put("toActressId", actressId);
        n.put("dryRun",     dryRun);
        return n;
    }

    private static ObjectNode argsAbsPath(String titleCode, String absPath, boolean dryRun) {
        ObjectNode n = M.createObjectNode();
        n.put("titleCode",      titleCode);
        n.put("toAbsolutePath", absPath);
        n.put("dryRun",         dryRun);
        return n;
    }

    private static ObjectNode argsActressAndAbsPath(String titleCode, long actressId, String absPath, boolean dryRun) {
        ObjectNode n = M.createObjectNode();
        n.put("titleCode",      titleCode);
        n.put("toActressId",    actressId);
        n.put("toAbsolutePath", absPath);
        n.put("dryRun",         dryRun);
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
        record MoveCall(Path from, Path to) {}

        final List<MoveCall> moveCalls      = new ArrayList<>();
        final List<Path>     createDirCalls = new ArrayList<>();
        final Set<Path>      existingPaths  = new HashSet<>();
        boolean failOnMove = false;

        @Override public boolean exists(Path path) { return existingPaths.contains(path); }
        @Override public boolean isDirectory(Path path) { return false; }

        @Override public void move(Path source, Path destination) throws IOException {
            if (failOnMove) throw new IOException("simulated move failure");
            moveCalls.add(new MoveCall(source, destination));
        }

        @Override public void createDirectories(Path path) { createDirCalls.add(path); }

        @Override public List<Path> listDirectory(Path path) { return List.of(); }
        @Override public List<Path> walk(Path root) { return List.of(); }
        @Override public LocalDate getLastModifiedDate(Path path) { return null; }
        @Override public InputStream openFile(Path path) { throw new UnsupportedOperationException(); }
        @Override public long size(Path path) { return 0; }
        @Override public void rename(Path path, String newName) { throw new UnsupportedOperationException(); }
        @Override public void writeFile(Path path, byte[] contents) { throw new UnsupportedOperationException(); }
        @Override public FileTimestamps getTimestamps(Path path) { return null; }
        @Override public void setTimestamps(Path path, Instant created, Instant modified) {}
    }
}
