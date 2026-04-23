package com.organizer3.utilities.task.duplicates;

import com.organizer3.config.volume.OrganizerConfig;
import com.organizer3.config.volume.ServerConfig;
import com.organizer3.config.volume.VolumeConfig;
import com.organizer3.db.SchemaInitializer;
import com.organizer3.filesystem.FileTimestamps;
import com.organizer3.filesystem.VolumeFileSystem;
import com.organizer3.model.DuplicateDecision;
import com.organizer3.repository.jdbi.JdbiDuplicateDecisionRepository;
import com.organizer3.repository.jdbi.JdbiTitleLocationRepository;
import com.organizer3.smb.SmbConnectionFactory;
import com.organizer3.utilities.task.TaskIO;
import com.organizer3.utilities.task.TaskInputs;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExecuteDuplicateTrashTaskTest {

    private Connection connection;
    private Jdbi jdbi;
    private JdbiDuplicateDecisionRepository decisionRepo;
    private JdbiTitleLocationRepository locationRepo;

    private SmbConnectionFactory mockFactory;
    private SmbConnectionFactory.SmbShareHandle mockHandle;
    private InMemoryFS fs;

    private OrganizerConfig mockConfig;
    private Clock clock;
    private ExecuteDuplicateTrashTask task;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        jdbi.useHandle(h -> h.execute("INSERT INTO volumes (id, structure_type) VALUES ('vol-a', 'conventional')"));
        jdbi.useHandle(h -> h.execute("INSERT INTO volumes (id, structure_type) VALUES ('vol-b', 'conventional')"));

        decisionRepo = new JdbiDuplicateDecisionRepository(jdbi);
        locationRepo = new JdbiTitleLocationRepository(jdbi);

        mockFactory = mock(SmbConnectionFactory.class);
        mockHandle  = mock(SmbConnectionFactory.SmbShareHandle.class);
        fs = new InMemoryFS();
        when(mockFactory.open(anyString())).thenReturn(mockHandle);
        when(mockHandle.fileSystem()).thenReturn(fs);

        VolumeConfig volA = new VolumeConfig("vol-a", "//nas/jav_A", "conventional", "srv1", "g1");
        VolumeConfig volB = new VolumeConfig("vol-b", "//nas/jav_B", "conventional", "srv1", "g1");
        ServerConfig srv  = new ServerConfig("srv1", "user", "pass", null, "_trash", null);
        mockConfig = mock(OrganizerConfig.class);
        when(mockConfig.findById("vol-a")).thenReturn(Optional.of(volA));
        when(mockConfig.findById("vol-b")).thenReturn(Optional.of(volB));
        when(mockConfig.findServerById("srv1")).thenReturn(Optional.of(srv));

        clock = Clock.fixed(Instant.parse("2026-04-23T12:00:00Z"), ZoneOffset.UTC);
        task = new ExecuteDuplicateTrashTask(decisionRepo, locationRepo, mockConfig, mockFactory, jdbi, clock);
    }

    @AfterEach
    void tearDown() throws Exception { connection.close(); }

    // ── helpers ────────────────────────────────────────────────────────────────

    private long insertTitle(String code) {
        return jdbi.withHandle(h -> h.createQuery(
                "INSERT INTO titles (code, base_code) VALUES (:code, :code) RETURNING id")
                .bind("code", code)
                .mapTo(Long.class)
                .one());
    }

    private long insertLocation(long titleId, String volumeId, String path) {
        return jdbi.withHandle(h -> h.createQuery(
                """
                INSERT INTO title_locations (title_id, volume_id, path, partition_id, last_seen_at, added_date)
                VALUES (:tid, :vid, :path, 'default', '2026-01-01', '2026-01-01') RETURNING id
                """)
                .bind("tid", titleId)
                .bind("vid", volumeId)
                .bind("path", path)
                .mapTo(Long.class)
                .one());
    }

    private void upsertDecision(String titleCode, String volumeId, String nasPath, String decision) {
        decisionRepo.upsert(DuplicateDecision.builder()
                .titleCode(titleCode)
                .volumeId(volumeId)
                .nasPath(nasPath)
                .decision(decision)
                .createdAt(Instant.now().toString())
                .build());
    }

    /** nasPath = smbPath + share-relative path */
    private static String nasPath(String smbPath, String sharePath) {
        return smbPath + sharePath;
    }

    private static TaskInputs emptyInputs() {
        return new TaskInputs(Map.of());
    }

    // ── tests ──────────────────────────────────────────────────────────────────

    @Test
    void noPendingDecisions_endsEarlyWithOk() throws Exception {
        CapturingIO io = new CapturingIO();
        task.run(emptyInputs(), io);
        assertTrue(io.summary("plan").contains("No pending"));
    }

    @Test
    void trashDecision_movesFolderAndCleansUpDb() throws Exception {
        long titleId = insertTitle("ABP-001");
        insertLocation(titleId, "vol-a", "/stars/Actress/ABP-001");
        insertLocation(titleId, "vol-b", "/queue/ABP-001");
        fs.mkdir("/queue/ABP-001");

        String np = nasPath("//nas/jav_B", "/queue/ABP-001");
        upsertDecision("ABP-001", "vol-b", np, "TRASH");

        CapturingIO io = new CapturingIO();
        task.run(emptyInputs(), io);

        assertEquals("ok", io.status("execute"));

        // File moved to trash
        assertTrue(fs.exists(Path.of("/_trash/queue/ABP-001")));
        assertFalse(fs.exists(Path.of("/queue/ABP-001")));

        // Location row deleted
        List<com.organizer3.model.TitleLocation> remaining = locationRepo.findByTitle(titleId);
        assertEquals(1, remaining.size());
        assertEquals("vol-a", remaining.get(0).getVolumeId());

        // Decision marked executed
        assertTrue(decisionRepo.listPending().isEmpty());
    }

    @Test
    void onlyOneLocation_skipsToPreventOrphan() throws Exception {
        long titleId = insertTitle("ABP-002");
        insertLocation(titleId, "vol-a", "/stars/Actress/ABP-002");
        fs.mkdir("/stars/Actress/ABP-002");

        String np = nasPath("//nas/jav_A", "/stars/Actress/ABP-002");
        upsertDecision("ABP-002", "vol-a", np, "TRASH");

        CapturingIO io = new CapturingIO();
        task.run(emptyInputs(), io);

        assertTrue(io.logs("execute").stream().anyMatch(l -> l.contains("SKIP")));
        // No location deleted
        assertEquals(1, locationRepo.findByTitle(titleId).size());
        // File untouched
        assertFalse(fs.exists(Path.of("/_trash/stars/Actress/ABP-002")));
    }

    @Test
    void keepAndVariantDecisionsAreIgnored() throws Exception {
        long titleId = insertTitle("ABP-003");
        insertLocation(titleId, "vol-a", "/stars/Actress/ABP-003");
        insertLocation(titleId, "vol-b", "/queue/ABP-003");

        upsertDecision("ABP-003", "vol-a", nasPath("//nas/jav_A", "/stars/Actress/ABP-003"), "KEEP");
        upsertDecision("ABP-003", "vol-b", nasPath("//nas/jav_B", "/queue/ABP-003"), "VARIANT");

        CapturingIO io = new CapturingIO();
        task.run(emptyInputs(), io);

        assertEquals("ok", io.status("plan"));
        assertTrue(io.summary("plan").contains("No pending"), "KEEP/VARIANT should not count as pending TRASH");
    }

    @Test
    void actressKeyById_filtersDecisions() throws Exception {
        long aid = jdbi.withHandle(h -> h.createQuery(
                "INSERT INTO actresses (canonical_name, tier, first_seen_at) VALUES ('Ai Haneda', 'regular', '2026-01-01') RETURNING id")
                .mapTo(Long.class).one());

        long titleA = insertTitle("ABP-010");
        long titleB = insertTitle("ABP-011");
        insertLocation(titleA, "vol-a", "/s/ABP-010");
        insertLocation(titleA, "vol-b", "/q/ABP-010");
        insertLocation(titleB, "vol-a", "/s/ABP-011");
        insertLocation(titleB, "vol-b", "/q/ABP-011");

        jdbi.useHandle(h -> h.execute("INSERT INTO title_actresses (title_id, actress_id) VALUES (?, ?)", titleA, aid));

        fs.mkdir("/q/ABP-010");
        fs.mkdir("/q/ABP-011");

        upsertDecision("ABP-010", "vol-b", nasPath("//nas/jav_B", "/q/ABP-010"), "TRASH");
        upsertDecision("ABP-011", "vol-b", nasPath("//nas/jav_B", "/q/ABP-011"), "TRASH");

        CapturingIO io = new CapturingIO();
        task.run(TaskInputs.of("actressKey", "id:" + aid), io);

        // Only ABP-010 was attributed to actress aid
        assertTrue(io.summary("execute").contains("1 trashed"), "Only the actress's title should be trashed");
        assertTrue(fs.exists(Path.of("/_trash/q/ABP-010")));
        assertFalse(fs.exists(Path.of("/_trash/q/ABP-011")));
    }

    @Test
    void serverWithNoTrashFolder_recordsFailure() throws Exception {
        // Override config with a server that has no trash configured
        VolumeConfig volNoTrash = new VolumeConfig("vol-c", "//nas/jav_C", "conventional", "srv2", "g1");
        ServerConfig srvNoTrash = new ServerConfig("srv2", "u", "p", null, null, null);
        jdbi.useHandle(h -> h.execute("INSERT INTO volumes (id, structure_type) VALUES ('vol-c', 'conventional')"));

        OrganizerConfig cfgNoTrash = mock(OrganizerConfig.class);
        when(cfgNoTrash.findById("vol-c")).thenReturn(Optional.of(volNoTrash));
        when(cfgNoTrash.findServerById("srv2")).thenReturn(Optional.of(srvNoTrash));

        ExecuteDuplicateTrashTask taskNoTrash = new ExecuteDuplicateTrashTask(
                decisionRepo, locationRepo, cfgNoTrash, mockFactory, jdbi, clock);

        long titleId = insertTitle("ABP-099");
        insertLocation(titleId, "vol-c", "/s/ABP-099");
        insertLocation(titleId, "vol-c", "/q/ABP-099");
        upsertDecision("ABP-099", "vol-c", nasPath("//nas/jav_C", "/q/ABP-099"), "TRASH");

        CapturingIO io = new CapturingIO();
        taskNoTrash.run(emptyInputs(), io);

        assertEquals("failed", io.status("execute"));
        assertTrue(io.logs("execute").stream().anyMatch(l -> l.contains("FAIL") && l.contains("ABP-099")));
        // Decision not marked executed
        assertEquals(1, decisionRepo.listPending().size());
    }

    @Test
    void ioError_marksFailedButContinuesWithNextItem() throws Exception {
        long tidA = insertTitle("ABP-020");
        long tidB = insertTitle("ABP-021");
        insertLocation(tidA, "vol-a", "/s/ABP-020");
        insertLocation(tidA, "vol-b", "/q/ABP-020");
        insertLocation(tidB, "vol-a", "/s/ABP-021");
        insertLocation(tidB, "vol-b", "/q/ABP-021");

        // ABP-020 folder does not exist → FS move will fail
        fs.mkdir("/q/ABP-021");

        upsertDecision("ABP-020", "vol-b", nasPath("//nas/jav_B", "/q/ABP-020"), "TRASH");
        upsertDecision("ABP-021", "vol-b", nasPath("//nas/jav_B", "/q/ABP-021"), "TRASH");

        CapturingIO io = new CapturingIO();
        task.run(emptyInputs(), io);

        assertEquals("failed", io.status("execute"));
        String summary = io.summary("execute");
        assertTrue(summary.contains("1 trashed"), "ABP-021 should succeed");
        assertTrue(summary.contains("1 failed"), "ABP-020 should fail");
    }

    // ── minimal TaskIO capture ─────────────────────────────────────────────────

    static final class CapturingIO implements TaskIO {
        private final Map<String, String> statuses  = new HashMap<>();
        private final Map<String, String> summaries = new HashMap<>();
        private final Map<String, List<String>> logs = new HashMap<>();

        @Override public void phaseStart(String id, String label) { logs.put(id, new ArrayList<>()); }
        @Override public void phaseProgress(String id, int cur, int tot, String detail) {}
        @Override public void phaseLog(String id, String line) {
            logs.computeIfAbsent(id, k -> new ArrayList<>()).add(line);
        }
        @Override public void phaseEnd(String id, String status, String summary) {
            statuses.put(id, status);
            summaries.put(id, summary);
        }

        String status(String phase)  { return statuses.getOrDefault(phase, ""); }
        String summary(String phase) { return summaries.getOrDefault(phase, ""); }
        List<String> logs(String phase) { return logs.getOrDefault(phase, List.of()); }
    }

    // ── in-memory VolumeFileSystem ────────────────────────────────────────────

    static final class InMemoryFS implements VolumeFileSystem {
        private final Map<Path, Boolean> nodes    = new HashMap<>();  // true=dir
        private final Map<Path, byte[]>  contents = new HashMap<>();

        void mkdir(String p) {
            Path path = Path.of(p);
            Path cur = path;
            while (cur != null) { nodes.put(cur, true); cur = cur.getParent(); }
        }

        @Override public boolean exists(Path p)      { return nodes.containsKey(p); }
        @Override public boolean isDirectory(Path p) { return Boolean.TRUE.equals(nodes.get(p)); }

        @Override public void createDirectories(Path path) {
            Path cur = path;
            while (cur != null && !cur.equals(Path.of("/"))) {
                nodes.put(cur, true); cur = cur.getParent();
            }
        }

        @Override public void move(Path source, Path destination) throws IOException {
            Boolean kind = nodes.remove(source);
            if (kind == null) throw new IOException("move: source does not exist: " + source);
            nodes.put(destination, kind);
            byte[] body = contents.remove(source);
            if (body != null) contents.put(destination, body);
        }

        @Override public void rename(Path path, String newName) throws IOException { move(path, path.resolveSibling(newName)); }

        @Override public void writeFile(Path path, byte[] body) { nodes.put(path, false); contents.put(path, body); }

        @Override public List<Path> listDirectory(Path path) { throw new UnsupportedOperationException(); }
        @Override public List<Path> walk(Path root) { throw new UnsupportedOperationException(); }
        @Override public LocalDate getLastModifiedDate(Path path) { return null; }
        @Override public InputStream openFile(Path path) throws IOException { throw new IOException("n/a"); }
        @Override public long size(Path path) throws IOException { throw new IOException("n/a"); }
        @Override public FileTimestamps getTimestamps(Path p) { return new FileTimestamps(null, null, null); }
        @Override public void setTimestamps(Path p, Instant c, Instant m) {}
    }
}
