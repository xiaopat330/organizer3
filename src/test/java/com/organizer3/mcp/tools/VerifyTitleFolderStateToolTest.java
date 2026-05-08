package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.organizer3.config.volume.VolumeConfig;
import com.organizer3.db.SchemaInitializer;
import com.organizer3.filesystem.FileTimestamps;
import com.organizer3.filesystem.VolumeFileSystem;
import com.organizer3.model.Actress;
import com.organizer3.model.Title;
import com.organizer3.model.TitleLocation;
import com.organizer3.repository.jdbi.JdbiActressRepository;
import com.organizer3.repository.jdbi.JdbiTitleLocationRepository;
import com.organizer3.repository.jdbi.JdbiTitleRepository;
import com.organizer3.shell.SessionContext;
import com.organizer3.smb.VolumeConnection;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for VerifyTitleFolderStateTool (C5).
 */
class VerifyTitleFolderStateToolTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static final String VOL = "vol";

    private Connection connection;
    private Jdbi jdbi;
    private JdbiActressRepository actressRepo;
    private JdbiTitleRepository titleRepo;
    private JdbiTitleLocationRepository locationRepo;
    private SessionContext session;
    private FakeVolumeFS fs;
    private VerifyTitleFolderStateTool tool;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        jdbi.useHandle(h -> h.execute("INSERT INTO volumes (id, structure_type) VALUES ('" + VOL + "', 'conventional')"));
        actressRepo  = new JdbiActressRepository(jdbi);
        locationRepo = new JdbiTitleLocationRepository(jdbi);
        titleRepo    = new JdbiTitleRepository(jdbi, locationRepo);

        fs = new FakeVolumeFS();
        session = new SessionContext();
        session.setMountedVolume(new VolumeConfig(VOL, "//host/vol", "conventional", "host", null));
        session.setActiveConnection(new FakeConn(fs));

        tool = new VerifyTitleFolderStateTool(session, titleRepo, locationRepo, actressRepo, jdbi);
    }

    @AfterEach
    void tearDown() throws Exception { connection.close(); }

    // ── Clean title → empty blockers and warnings ─────────────────────────────

    @Test
    void cleanTitleReturnsEmpty() throws Exception {
        long aid = seedActress("Nami Aino");
        long tid = seedTitle("ABP-001", aid);
        seedLocation(tid, "/stars/Nami Aino/Nami Aino (ABP-001)", false);
        fs.mkdir("/stars/Nami Aino/Nami Aino (ABP-001)");
        fs.file("/stars/Nami Aino/Nami Aino (ABP-001)/cover.jpg");
        fs.mkdir("/stars/Nami Aino/Nami Aino (ABP-001)/video");
        fs.file("/stars/Nami Aino/Nami Aino (ABP-001)/video/ABP-001.mkv");

        var result = (VerifyTitleFolderStateTool.Result) tool.call(args("ABP-001"));

        assertTrue(result.blockers().isEmpty(), "Expected no blockers but got: " + result.blockers());
        assertTrue(result.warnings().isEmpty(), "Expected no warnings but got: " + result.warnings());
    }

    // ── Sync drift: live row, folder missing → blocker ────────────────────────

    @Test
    void syncDriftFolderMissingEmitsBlocker() throws Exception {
        long aid = seedActress("Nami Aino");
        long tid = seedTitle("ABP-002", aid);
        seedLocation(tid, "/stars/Nami Aino/Nami Aino (ABP-002)", false);
        // Deliberately DO NOT create the folder on disk

        var result = (VerifyTitleFolderStateTool.Result) tool.call(args("ABP-002"));

        boolean hasDrift = result.blockers().stream()
                .anyMatch(b -> "sync-drift-folder-missing".equals(b.kind()));
        assertTrue(hasDrift, "Expected sync-drift-folder-missing blocker");
    }

    // ── Stale location row → blocker ──────────────────────────────────────────

    @Test
    void staleLocationRowEmitsBlocker() throws Exception {
        long aid = seedActress("Nami Aino");
        long tid = seedTitle("ABP-003", aid);
        seedLocation(tid, "/stars/Nami Aino/Nami Aino (ABP-003)", true); // stale

        var result = (VerifyTitleFolderStateTool.Result) tool.call(args("ABP-003"));

        boolean hasStale = result.blockers().stream()
                .anyMatch(b -> "stale-location-row".equals(b.kind()));
        assertTrue(hasStale, "Expected stale-location-row blocker");
    }

    // ── Multiple title_locations rows for same title → blocker ───────────────

    @Test
    void multipleLocationsOnVolumeEmitsBlocker() throws Exception {
        long aid = seedActress("Nami Aino");
        long tid = seedTitle("ABP-004", aid);
        seedLocation(tid, "/stars/Nami Aino/Nami Aino (ABP-004)", false);
        seedLocation(tid, "/stars/Nami Aino/Nami Aino (ABP-004) copy", false);

        var result = (VerifyTitleFolderStateTool.Result) tool.call(args("ABP-004"));

        boolean hasMultiLoc = result.blockers().stream()
                .anyMatch(b -> "multiple-locations-on-volume".equals(b.kind()));
        assertTrue(hasMultiLoc, "Expected multiple-locations-on-volume blocker");
    }

    // ── Multi-cover-at-base → warning ─────────────────────────────────────────

    @Test
    void multiCoverAtBaseEmitsWarning() throws Exception {
        long aid = seedActress("Nami Aino");
        long tid = seedTitle("ABP-005", aid);
        seedLocation(tid, "/stars/Nami Aino/Nami Aino (ABP-005)", false);
        fs.mkdir("/stars/Nami Aino/Nami Aino (ABP-005)");
        fs.file("/stars/Nami Aino/Nami Aino (ABP-005)/cover.jpg");
        fs.file("/stars/Nami Aino/Nami Aino (ABP-005)/cover-big.jpg");

        var result = (VerifyTitleFolderStateTool.Result) tool.call(args("ABP-005"));

        assertTrue(result.blockers().isEmpty());
        boolean hasMultiCover = result.warnings().stream()
                .anyMatch(w -> "multi-cover-at-base".equals(w.kind()));
        assertTrue(hasMultiCover, "Expected multi-cover-at-base warning");
    }

    // ── DB credit mismatch: basename has actress X, DB credits actress Y → warning

    @Test
    void dbCreditMismatchEmitsWarning() throws Exception {
        long aidAlice = seedActress("Alice");
        long aidBob   = seedActress("Bob");

        // Title is credited to Bob in DB, but basename says Alice is the actress
        long tid = seedTitle("MIS-001", aidBob);
        // Location: basename says Alice; DB says Bob is actress
        seedLocation(tid, "/stars/Bob/Alice (MIS-001)", false);
        fs.mkdir("/stars/Bob/Alice (MIS-001)");
        fs.file("/stars/Bob/Alice (MIS-001)/cover.jpg");

        var result = (VerifyTitleFolderStateTool.Result) tool.call(args("MIS-001"));

        boolean hasMismatch = result.warnings().stream()
                .anyMatch(w -> "db-credit-mismatch".equals(w.kind()));
        assertTrue(hasMismatch, "Expected db-credit-mismatch warning when Alice resolves but Bob is in DB");
    }

    // ── Basename with multi-actress, one matches DB → no mismatch for valid one

    @Test
    void multiActressTitleBothInDbNoDriftWarning() throws Exception {
        long a1 = seedActress("Alice");
        long a2 = seedActress("Bob");
        // Multi-actress title: both Alice and Bob in DB credits
        long tid = seedTitle("DUAL-001", a1);
        jdbi.useHandle(h -> h.execute(
                "INSERT INTO title_actresses (title_id, actress_id) VALUES (?, ?)", tid, a2));
        seedLocation(tid, "/stars/Alice/Alice, Bob (DUAL-001)", false);
        fs.mkdir("/stars/Alice/Alice, Bob (DUAL-001)");
        fs.file("/stars/Alice/Alice, Bob (DUAL-001)/cover.jpg");

        var result = (VerifyTitleFolderStateTool.Result) tool.call(args("DUAL-001"));

        boolean hasMismatch = result.warnings().stream()
                .anyMatch(w -> "db-credit-mismatch".equals(w.kind()));
        assertFalse(hasMismatch, "Both Alice and Bob in DB credits; no mismatch expected");
    }

    // ── Unparseable basename → warning ────────────────────────────────────────

    @Test
    void unparseableBasenameEmitsWarning() throws Exception {
        long aid = seedActress("Alice");
        long tid = seedTitle("BAD-001", aid);
        // No (CODE) in basename
        seedLocation(tid, "/stars/Alice/no-code-folder", false);
        fs.mkdir("/stars/Alice/no-code-folder");
        fs.file("/stars/Alice/no-code-folder/cover.jpg");

        var result = (VerifyTitleFolderStateTool.Result) tool.call(args("BAD-001"));

        boolean hasUnparseable = result.warnings().stream()
                .anyMatch(w -> "basename-unparseable".equals(w.kind()));
        assertTrue(hasUnparseable, "Expected basename-unparseable warning");
    }

    // ── Unknown title code → throws ───────────────────────────────────────────

    @Test
    void unknownTitleCodeThrows() {
        assertThrows(IllegalArgumentException.class, () -> tool.call(args("DOES-NOT-EXIST")));
    }

    // ── No volume mounted → throws ────────────────────────────────────────────

    @Test
    void noVolumeMountedThrows() {
        session.setMountedVolume(null);
        long aid = seedActress("Alice");
        seedTitle("X-001", aid);
        assertThrows(IllegalArgumentException.class, () -> tool.call(args("X-001")));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private long seedActress(String name) {
        return actressRepo.save(Actress.builder()
                .canonicalName(name)
                .tier(Actress.Tier.LIBRARY)
                .firstSeenAt(LocalDate.of(2024, 1, 1))
                .build()).getId();
    }

    private long seedTitle(String code, long actressId) {
        return titleRepo.save(Title.builder()
                .code(code).baseCode(code).label(code.split("-")[0]).seqNum(1)
                .actressId(actressId).build()).getId();
    }

    private void seedLocation(long titleId, String path, boolean stale) {
        TitleLocation loc = TitleLocation.builder()
                .titleId(titleId).volumeId(VOL).partitionId("p")
                .path(Path.of(path)).lastSeenAt(LocalDate.now()).build();
        TitleLocation saved = locationRepo.save(loc);
        if (stale && saved.getId() != null) {
            // save() always clears stale_since; stamp it directly via SQL using ISO 8601 so
            // the repository mapper's Instant.parse() can deserialize it.
            jdbi.useHandle(h -> h.execute(
                    "UPDATE title_locations SET stale_since = ? WHERE id = ?",
                    java.time.Instant.now().toString(), saved.getId()));
        }
    }

    private static ObjectNode args(String titleCode) {
        ObjectNode n = M.createObjectNode();
        n.put("titleCode", titleCode);
        return n;
    }

    // ── In-memory VolumeFileSystem ────────────────────────────────────────────

    private static final class FakeVolumeFS implements VolumeFileSystem {
        private final Map<Path, Boolean> nodes = new HashMap<>();

        void mkdir(String p) { nodes.put(Path.of(p), true); }
        void file(String p)  { nodes.put(Path.of(p), false); }

        @Override public List<Path> listDirectory(Path path) {
            List<Path> out = new ArrayList<>();
            String parent = path.toString();
            for (Path p : nodes.keySet()) {
                Path pp = p.getParent();
                if (pp != null && pp.toString().equals(parent)) out.add(p);
            }
            return out;
        }
        @Override public List<Path> walk(Path root)               { throw new UnsupportedOperationException(); }
        @Override public boolean exists(Path path)                { return nodes.containsKey(path); }
        @Override public boolean isDirectory(Path path)           { return Boolean.TRUE.equals(nodes.get(path)); }
        @Override public LocalDate getLastModifiedDate(Path path) { return null; }
        @Override public InputStream openFile(Path path) throws IOException { throw new IOException("n/a"); }
        @Override public long size(Path path) throws IOException  { throw new IOException("n/a"); }
        @Override public void move(Path s, Path d)                {}
        @Override public void rename(Path p, String n)            {}
        @Override public void createDirectories(Path p)           {}
        @Override public void writeFile(Path p, byte[] b)         {}
        @Override public FileTimestamps getTimestamps(Path p)     { return new FileTimestamps(null, null, null); }
        @Override public void setTimestamps(Path p, Instant c, Instant m) {}
    }

    private static final class FakeConn implements VolumeConnection {
        private final VolumeFileSystem fs;
        FakeConn(VolumeFileSystem fs) { this.fs = fs; }
        @Override public VolumeFileSystem fileSystem() { return fs; }
        @Override public boolean isConnected()         { return true; }
        @Override public void close()                  {}
    }
}
