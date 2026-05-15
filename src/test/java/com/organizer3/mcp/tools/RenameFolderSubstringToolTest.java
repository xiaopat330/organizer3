package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.organizer3.config.volume.VolumeConfig;
import com.organizer3.curation.CurationLog;
import com.organizer3.db.SchemaInitializer;
import com.organizer3.filesystem.FileTimestamps;
import com.organizer3.filesystem.VolumeFileSystem;
import com.organizer3.repository.jdbi.JdbiTitleLocationRepository;
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

class RenameFolderSubstringToolTest {

    @TempDir
    Path logDir;

    private static final ObjectMapper M = new ObjectMapper();

    private Connection connection;
    private Jdbi jdbi;
    private JdbiTitleLocationRepository locationRepo;
    private SessionContext session;
    private FakeFs fs;
    private CurationLog curationLog;
    private RenameFolderSubstringTool tool;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi       = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        jdbi.useHandle(h -> {
            h.execute("INSERT INTO volumes (id, structure_type) VALUES ('s', 'conventional')");
            h.execute("INSERT INTO volumes (id, structure_type) VALUES ('r', 'conventional')");
        });
        locationRepo = new JdbiTitleLocationRepository(jdbi);
        fs           = new FakeFs();
        curationLog  = new CurationLog(logDir);
        session      = new SessionContext();
        session.setMountedVolume(new VolumeConfig("s", "//host/s", "conventional", "host", null));
        session.setActiveConnection(new FakeConnection(fs));
        tool = new RenameFolderSubstringTool(session, locationRepo, curationLog);
    }

    @AfterEach
    void tearDown() throws Exception { connection.close(); }

    // ── 1. dryRun finds matches, no mutation ──────────────────────────────

    @Test
    void dryRun_findsMatches_doesNotMutate() {
        seedTitle("AAA-1", "s", "queue", "/queue/Foo Bar (AAA-1)");
        seedTitle("AAA-2", "s", "queue", "/queue/Z, Foo Bar, Y (AAA-2)");
        seedTitle("AAA-3", "s", "queue", "/queue/Q (AAA-3) Foo Bar");
        var r = (RenameFolderSubstringTool.Result) tool.call(args("s", "Foo Bar", "Foo Baz", true));
        // exactly 3 candidates — the third basename "Q (AAA-3) Foo Bar" has Foo Bar at end → whole-word OK
        assertEquals(3, r.totalCandidates());
        assertEquals(0, r.renamed());
        assertTrue(fs.renameCalls.isEmpty(), "dry-run must not touch FS");
        // DB unchanged
        List<String> paths = listPaths();
        assertTrue(paths.contains("/queue/Foo Bar (AAA-1)"));
        assertTrue(paths.contains("/queue/Z, Foo Bar, Y (AAA-2)"));
    }

    // ── 2. start of basename match ────────────────────────────────────────

    @Test
    void wholeWordMatch_atStartOfBasename() {
        seedTitle("CODE-1", "s", "queue", "/queue/Foo Bar (CODE-1)");
        var r = (RenameFolderSubstringTool.Result) tool.call(args("s", "Foo Bar", "Foo Baz", false));
        assertEquals(1, r.totalCandidates());
        assertEquals(1, r.renamed());
        assertEquals("/queue/Foo Baz (CODE-1)",
                jdbi.withHandle(h -> h.createQuery("SELECT path FROM title_locations").mapTo(String.class).one()));
    }

    // ── 3. middle with commas ─────────────────────────────────────────────

    @Test
    void wholeWordMatch_inMiddleWithCommas() {
        seedTitle("CODE-2", "s", "queue", "/queue/A, Foo Bar, B (CODE-2)");
        var r = (RenameFolderSubstringTool.Result) tool.call(args("s", "Foo Bar", "Foo Baz", false));
        assertEquals(1, r.totalCandidates());
        assertEquals(1, r.renamed());
        assertEquals("/queue/A, Foo Baz, B (CODE-2)",
                jdbi.withHandle(h -> h.createQuery("SELECT path FROM title_locations").mapTo(String.class).one()));
    }

    // ── 4. end before paren ───────────────────────────────────────────────

    @Test
    void wholeWordMatch_atEndBeforeParen() {
        seedTitle("CODE-3", "s", "queue", "/queue/A, Foo Bar (CODE-3)");
        var r = (RenameFolderSubstringTool.Result) tool.call(args("s", "Foo Bar", "Foo Baz", false));
        assertEquals(1, r.totalCandidates());
        assertEquals(1, r.renamed());
        assertEquals("/queue/A, Foo Baz (CODE-3)",
                jdbi.withHandle(h -> h.createQuery("SELECT path FROM title_locations").mapTo(String.class).one()));
    }

    // ── 5. not a whole-word: prefix of longer token ───────────────────────

    @Test
    void notWholeWord_partialPrefix() {
        seedTitle("CODE-4", "s", "queue", "/queue/Foo Bart (CODE-4)");
        var r = (RenameFolderSubstringTool.Result) tool.call(args("s", "Foo Bar", "Foo Baz", false));
        assertEquals(0, r.totalCandidates(), "Foo Bar followed by 't' is not whole-word");
        assertEquals(1, r.skippedNonWordBoundary());
        assertTrue(fs.renameCalls.isEmpty());
        assertEquals("/queue/Foo Bart (CODE-4)",
                jdbi.withHandle(h -> h.createQuery("SELECT path FROM title_locations").mapTo(String.class).one()));
    }

    // ── 6. not a whole-word: suffix of longer token ───────────────────────

    @Test
    void notWholeWord_partialSuffix() {
        // 'Foo Bar' appears at index 1, preceded by 'x' — not a word boundary
        seedTitle("CODE-5", "s", "queue", "/queue/xFoo Bar (CODE-5)");
        var r = (RenameFolderSubstringTool.Result) tool.call(args("s", "Foo Bar", "Foo Baz", false));
        assertEquals(0, r.totalCandidates(), "Foo Bar preceded by 'x' is not whole-word");
        assertEquals(1, r.skippedNonWordBoundary());
        assertTrue(fs.renameCalls.isEmpty());
    }

    // ── 7. pathPrefix scopes search ───────────────────────────────────────

    @Test
    void pathPrefixScopesSearch() {
        seedTitle("Q-1", "s", "queue",   "/queue/Foo Bar (Q-1)");
        seedTitle("A-1", "s", "archive", "/archive/Foo Bar (A-1)");
        ObjectNode a = args("s", "Foo Bar", "Foo Baz", false);
        a.put("pathPrefix", "/queue");
        var r = (RenameFolderSubstringTool.Result) tool.call(a);
        assertEquals(1, r.totalCandidates(), "only /queue should match");
        assertEquals(1, r.renamed());
        // /archive row unchanged
        List<String> paths = listPaths();
        assertTrue(paths.contains("/queue/Foo Baz (Q-1)"));
        assertTrue(paths.contains("/archive/Foo Bar (A-1)"));
    }

    // ── 8. DB + FS updated on commit ──────────────────────────────────────

    @Test
    void dbAndFsUpdated_onCommit() {
        seedTitle("OK-1", "s", "queue", "/queue/Old Name (OK-1)");
        var r = (RenameFolderSubstringTool.Result) tool.call(args("s", "Old Name", "New Name", false));
        assertEquals(1, r.renamed());
        assertEquals(1, fs.renameCalls.size());
        assertEquals(Path.of("/queue/Old Name (OK-1)"), fs.renameCalls.get(0).path());
        assertEquals("New Name (OK-1)", fs.renameCalls.get(0).newName());
        assertEquals("/queue/New Name (OK-1)",
                jdbi.withHandle(h -> h.createQuery("SELECT path FROM title_locations").mapTo(String.class).one()));
    }

    // ── 9. curation log recorded (using as "path history" analogue) ──────

    @Test
    void pathHistoryRecorded() throws Exception {
        seedTitle("OK-2", "s", "queue", "/queue/Old (OK-2)");
        tool.call(args("s", "Old", "New", false));
        Path logFile = logDir.resolve("curation-log/s").resolve(LocalDate.now() + ".jsonl");
        assertTrue(Files.exists(logFile), "curation log file should be written");
        var parsed = M.readTree(Files.readAllLines(logFile).get(0));
        assertEquals("rename_folder_substring", parsed.get("tool").asText());
        assertEquals("ok", parsed.get("status").asText());
        assertEquals("/queue/Old (OK-2)", parsed.get("before").get("path").asText());
        assertEquals("/queue/New (OK-2)", parsed.get("after").get("path").asText());
    }

    // ── 10. volumeId must match mounted volume ────────────────────────────

    @Test
    void volumeMustBeMounted() {
        seedTitle("R-1", "r", "queue", "/queue/Foo Bar (R-1)");
        // mounted volume is 's', but we ask for 'r'
        var r = (RenameFolderSubstringTool.Result) tool.call(args("r", "Foo Bar", "Foo Baz", false));
        assertEquals(0, r.totalCandidates());
        assertEquals(1, r.errors().size());
        assertTrue(r.errors().get(0).contains("does not match mounted volume"));
        assertTrue(fs.renameCalls.isEmpty());
    }

    // ── extra: multiple matches across two locations of one title ─────────

    @Test
    void multipleLocations_bothRenamed() {
        long titleId = jdbi.withHandle(h ->
                h.createQuery("INSERT INTO titles (code) VALUES ('MIDE-700') RETURNING id")
                        .mapTo(Long.class).one());
        jdbi.useHandle(h -> {
            h.execute("INSERT INTO title_locations (title_id, volume_id, partition_id, path, last_seen_at) VALUES (?, 's', 'queue', '/queue/Foo Bar A (MIDE-700)', '2024-01-01')", titleId);
            h.execute("INSERT INTO title_locations (title_id, volume_id, partition_id, path, last_seen_at) VALUES (?, 's', 'queue', '/queue/Foo Bar B (MIDE-700)', '2024-01-01')", titleId);
        });
        var r = (RenameFolderSubstringTool.Result) tool.call(args("s", "Foo Bar", "Foo Baz", false));
        assertEquals(2, r.totalCandidates());
        assertEquals(2, r.renamed());
    }

    // ── extra: word-boundary helper sanity ────────────────────────────────

    @Test
    void wholeWordHelper_replacesAllOccurrences() {
        var r = RenameFolderSubstringTool.replaceWholeWord(
                "Foo Bar, Baz, Foo Bar (X-1)", "Foo Bar", "Foo Baz");
        assertEquals("Foo Baz, Baz, Foo Baz (X-1)", r.replaced());
        assertEquals(2, r.matchCount());
    }

    @Test
    void wholeWordHelper_skipsTokenInternal() {
        var r = RenameFolderSubstringTool.replaceWholeWord("Saigon, Sai Hatsumi", "Sai", "Saki");
        // 'Sai' at start is followed by 'g' → not boundary; 'Sai' before ' Hatsumi' IS boundary
        assertEquals("Saigon, Saki Hatsumi", r.replaced());
        assertEquals(1, r.matchCount());
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private void seedTitle(String code, String volumeId, String partitionId, String path) {
        jdbi.useHandle(h -> {
            long titleId = h.createQuery("INSERT INTO titles (code) VALUES (?) RETURNING id")
                    .bind(0, code).mapTo(Long.class).one();
            h.execute("""
                    INSERT INTO title_locations (title_id, volume_id, partition_id, path, last_seen_at)
                    VALUES (?, ?, ?, ?, '2024-01-01')
                    """, titleId, volumeId, partitionId, path);
        });
    }

    private List<String> listPaths() {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT path FROM title_locations ORDER BY path").mapTo(String.class).list());
    }

    private static ObjectNode args(String volumeId, String oldText, String newText, boolean dryRun) {
        ObjectNode n = M.createObjectNode();
        n.put("volumeId", volumeId);
        n.put("oldText",  oldText);
        n.put("newText",  newText);
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
        record RenameCall(Path path, String newName) {}

        final List<RenameCall> renameCalls = new ArrayList<>();
        final Set<Path> existingPaths = new HashSet<>();

        @Override public boolean exists(Path path) { return existingPaths.contains(path); }
        @Override public boolean isDirectory(Path path) { return false; }
        @Override public void rename(Path path, String newName) throws IOException {
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
