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
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for VolumeCurationReportTool (E1).
 *
 * <p>Uses real in-memory SQLite via JDBI and a fake FS (MapFs) for the FS-only section.
 */
class VolumeCurationReportToolTest {

    private static final ObjectMapper M = new ObjectMapper();

    private Connection connection;
    private Jdbi jdbi;
    private JdbiActressRepository actressRepo;
    private JdbiTitleRepository titleRepo;
    private JdbiTitleLocationRepository locationRepo;
    private SessionContext session;
    private MapFs fs;
    private VolumeCurationReportTool tool;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi       = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        jdbi.useHandle(h ->
                h.execute("INSERT INTO volumes (id, structure_type) VALUES ('vol', 'conventional')"));
        actressRepo  = new JdbiActressRepository(jdbi);
        locationRepo = new JdbiTitleLocationRepository(jdbi);
        titleRepo    = new JdbiTitleRepository(jdbi, locationRepo);

        fs      = new MapFs();
        session = new SessionContext();
        session.setMountedVolume(new VolumeConfig("vol", "//host/vol", "conventional", "host", null));
        session.setActiveConnection(new FakeConn(fs));
        tool = new VolumeCurationReportTool(session, actressRepo, jdbi);
    }

    @AfterEach
    void tearDown() throws Exception { connection.close(); }

    // ── Empty volume → all sections have total=0, results=[] ─────────────────

    @Test
    void emptyVolumeAllSectionsEmpty() throws Exception {
        var report = callDefault();
        assertEquals("vol", report.mountedVolumeId());
        assertNotNull(report.generatedAt());
        var s = report.sections();
        assertEquals(0, s.misnamedParents().total());
        assertTrue(s.misnamedParents().results().isEmpty());
        assertEquals(0, s.driftedMultiActress().total());
        assertTrue(s.driftedMultiActress().results().isEmpty());
        assertEquals(0, s.fsOnlyTitles().total());
        assertTrue(s.fsOnlyTitles().results().isEmpty());
        assertEquals(0, s.queueResidents().total());
        assertTrue(s.queueResidents().results().isEmpty());
        assertEquals(0, s.duplicateBaseCodes().total());
        assertTrue(s.duplicateBaseCodes().results().isEmpty());
    }

    // ── generatedAt is set and parseable as ISO-8601 ──────────────────────────

    @Test
    void generatedAtIsIso8601() throws Exception {
        var report = callDefault();
        assertNotNull(report.generatedAt());
        assertFalse(report.generatedAt().isBlank());
        // Should parse without throwing
        Instant parsed = DateTimeFormatter.ISO_INSTANT.parse(report.generatedAt(), Instant::from);
        assertNotNull(parsed);
    }

    // ── volume_id=null defaults to mounted volume ─────────────────────────────

    @Test
    void volumeIdNullDefaultsToMounted() throws Exception {
        var report = callDefault(); // no volume_id arg → defaults
        assertEquals("vol", report.mountedVolumeId());
    }

    // ── volume_id mismatch → IllegalArgumentException ─────────────────────────

    @Test
    void volumeIdMismatchThrows() {
        ObjectNode args = M.createObjectNode();
        args.put("volume_id", "other-volume");
        assertThrows(IllegalArgumentException.class, () -> tool.call(args));
    }

    // ── No volume mounted → IllegalArgumentException ─────────────────────────

    @Test
    void noVolumeMountedThrows() {
        session.setMountedVolume(null);
        session.setActiveConnection(null);
        assertThrows(IllegalArgumentException.class, () -> tool.call(M.createObjectNode()));
    }

    // ── Section misnamedParents: one misnamed actress ─────────────────────────

    @Test
    void misnamedParentsFindsActressWithWrongFolderName() throws Exception {
        // Actress "Shion Fujimoto" but title lives under /stars/library/Shien Fujimoto/
        long aid = actressRepo.save(actress("Shion Fujimoto")).getId();
        long tid = titleRepo.save(title("ABP-001", "ABP-00001", aid)).getId();
        // Path does NOT contain "Shion Fujimoto" in a token-bounded way
        locationRepo.save(loc(tid, "vol", "/stars/library/Shien Fujimoto/Shien Fujimoto (ABP-001)"));

        var report = callDefault();
        var section = report.sections().misnamedParents();
        assertEquals(1, section.total());
        assertEquals(1, section.results().size());
        var row = section.results().get(0);
        assertEquals(aid, row.actressId());
        assertEquals("Shion Fujimoto", row.canonicalName());
        assertEquals("/stars/library/Shien Fujimoto", row.parentFolderPath());
        assertEquals(1, row.titleCount());
    }

    // ── Section misnamedParents: correctly named → not surfaced ──────────────

    @Test
    void misnamedParentsSkipsCorrectlyNamedFolder() throws Exception {
        long aid = actressRepo.save(actress("Yua Mikami")).getId();
        long tid = titleRepo.save(title("SOD-001", "SOD-00001", aid)).getId();
        locationRepo.save(loc(tid, "vol", "/stars/library/Yua Mikami/Yua Mikami (SOD-001)"));

        var report = callDefault();
        assertEquals(0, report.sections().misnamedParents().total());
    }

    // ── Section misnamedParents: sorted by actress_id ascending ──────────────

    @Test
    void misnamedParentsSortedByActressId() throws Exception {
        // Create two actresses with misnamed folders; actress_id order should be preserved
        long a2 = actressRepo.save(actress("Actress Two")).getId();
        long a1 = actressRepo.save(actress("Actress One")).getId();
        // Note: a2 < a1 because a2 was inserted first — sorting ensures a2 comes first

        long t1 = titleRepo.save(title("XYZ-001", "XYZ-00001", a1)).getId();
        long t2 = titleRepo.save(title("XYZ-002", "XYZ-00002", a2)).getId();

        locationRepo.save(loc(t1, "vol", "/stars/library/Wrong Name One/Wrong Name One (XYZ-001)"));
        locationRepo.save(loc(t2, "vol", "/stars/library/Wrong Name Two/Wrong Name Two (XYZ-002)"));

        var report = callDefault();
        var rows = report.sections().misnamedParents().results();
        assertEquals(2, rows.size());
        // Sorted ascending by actress_id — a2 was inserted first so has lower id
        assertTrue(rows.get(0).actressId() < rows.get(1).actressId(),
                "Results should be sorted by actress_id ascending");
    }

    // ── Section misnamedParents: limit_per_section truncation ────────────────

    @Test
    void misnamedParentsLimitTruncation() throws Exception {
        // Seed 5 misnamed parents with limit_per_section=3 → results.size=3, total=5
        for (int i = 1; i <= 5; i++) {
            long aid = actressRepo.save(actress("Actress " + i)).getId();
            long tid = titleRepo.save(title("LIM-00" + i, "LIM-000" + i, aid)).getId();
            locationRepo.save(loc(tid, "vol",
                    "/stars/library/Wrong Name " + i + "/Wrong Name " + i + " (LIM-00" + i + ")"));
        }

        ObjectNode args = M.createObjectNode();
        args.put("limit_per_section", 3);
        var report = (VolumeCurationReportTool.Report) tool.call(args);
        var section = report.sections().misnamedParents();
        assertEquals(5, section.total(), "total should be full count");
        assertEquals(3, section.results().size(), "results should be capped at limit");
    }

    // ── Section driftedMultiActress: one drift surfaced ───────────────────────

    @Test
    void driftedMultiActressFindsOneDrift() throws Exception {
        long a1 = actressRepo.save(actress("Alice")).getId();
        long a2 = actressRepo.save(actress("Bob")).getId();
        long tid = titleRepo.save(title("DUAL-001", "DUAL-00001", a1)).getId();
        jdbi.useHandle(h -> h.execute(
                "INSERT INTO title_actresses (title_id, actress_id) VALUES (?, ?)", tid, a2));
        // Missing Bob in basename → missing-cast-member
        locationRepo.save(loc(tid, "vol", "/stars/library/Alice/Alice (DUAL-001)"));

        var report = callDefault();
        var section = report.sections().driftedMultiActress();
        assertEquals(1, section.total());
        assertEquals(1, section.results().size());
        var row = section.results().get(0);
        assertEquals("DUAL-001", row.titleCode());
        assertTrue(row.issues().contains("missing-cast-member"));
    }

    // ── Section driftedMultiActress: sorted by severity descending ────────────

    @Test
    void driftedMultiActressSortedBySeverityDescending() throws Exception {
        long a1 = actressRepo.save(actress("Cast")).getId();
        // Title 1: unparseable → severity 1.5
        long t1 = titleRepo.save(title("SV1-001", "SV1-00001", a1)).getId();
        locationRepo.save(loc(t1, "vol", "/stars/library/Cast/no-code-here"));
        // Title 2: non-standard-sep only → severity 0.1
        long t2 = titleRepo.save(title("SV2-001", "SV2-00001", a1)).getId();
        locationRepo.save(loc(t2, "vol", "/stars/library/Cast/Cast & Bob (SV2-001)"));

        var report = callDefault();
        var rows = report.sections().driftedMultiActress().results();
        assertEquals(2, rows.size());
        assertTrue(rows.get(0).severity() >= rows.get(1).severity(),
                "Drifts should be sorted by severity descending");
    }

    // ── Section fsOnlyTitles: one FS-only folder surfaced ─────────────────────

    @Test
    void fsOnlyTitlesFindsOneFolder() throws Exception {
        // No DB row; title-shaped folder on disk
        fs.addDir("/queue/Some Actress (NEW-001)");
        fs.addFile("/queue/Some Actress (NEW-001)/video.mkv", 1_000_000L);

        var report = callDefault();
        var section = report.sections().fsOnlyTitles();
        assertEquals(1, section.total());
        assertEquals(1, section.results().size());
        var row = section.results().get(0);
        assertEquals("/queue/Some Actress (NEW-001)", row.folderPath());
        assertEquals("NEW-001", row.parsedCode());
        assertEquals(1_000_000L, row.sizeBytes());
        assertEquals(1, row.childFileCount());
    }

    // ── Section queueResidents: one queue resident surfaced ───────────────────

    @Test
    void queueResidentsFindsOneTitleInQueue() throws Exception {
        long aid = actressRepo.save(actress("Queue Star")).getId();
        long tid = titleRepo.save(title("QUEUE-001", "QUEUE-00001", aid)).getId();
        locationRepo.save(loc(tid, "vol", "/queue/Queue Star (QUEUE-001)"));

        var report = callDefault();
        var section = report.sections().queueResidents();
        assertEquals(1, section.total());
        var row = section.results().get(0);
        assertEquals("QUEUE-001", row.titleCode());
        assertEquals("/queue/Queue Star (QUEUE-001)", row.folderPath());
        assertTrue(row.dbCanonicalActresses().contains("Queue Star"));
    }

    // ── Section queueResidents: non-queue title NOT surfaced ─────────────────

    @Test
    void queueResidentsSkipsNonQueueTitles() throws Exception {
        long aid = actressRepo.save(actress("Stars Actress")).getId();
        long tid = titleRepo.save(title("STARS-001", "STARS-00001", aid)).getId();
        locationRepo.save(loc(tid, "vol", "/stars/library/Stars Actress/Stars Actress (STARS-001)"));

        var report = callDefault();
        assertEquals(0, report.sections().queueResidents().total());
    }

    // ── Section duplicateBaseCodes: two titles sharing base_code on volume ────

    @Test
    void duplicateBaseCodesGroupsTitlesOnVolume() throws Exception {
        long aid = actressRepo.save(actress("Some Actress")).getId();
        long t1 = titleRepo.save(title("ABP-001",  "ABP-00001", aid)).getId();
        long t2 = titleRepo.save(title("ABP-0001", "ABP-00001", aid)).getId();
        locationRepo.save(loc(t1, "vol", "/stars/library/Some Actress/Some Actress (ABP-001)"));
        locationRepo.save(loc(t2, "vol", "/stars/library/Some Actress/Some Actress (ABP-0001)"));

        var report = callDefault();
        var section = report.sections().duplicateBaseCodes();
        assertEquals(1, section.total());
        var row = section.results().get(0);
        assertEquals("ABP-00001", row.baseCode());
        assertEquals(2, row.titles().size());
    }

    // ── Section duplicateBaseCodes: title not on volume → not counted ─────────

    @Test
    void duplicateBaseCodesExcludesTitlesNotOnVolume() throws Exception {
        // Two titles share a base_code but only one is on "vol"; the other is on "other-vol"
        jdbi.useHandle(h ->
                h.execute("INSERT INTO volumes (id, structure_type) VALUES ('other-vol', 'conventional')"));
        long aid = actressRepo.save(actress("Cross Vol Actress")).getId();
        long t1 = titleRepo.save(title("CVP-001", "CVP-00001", aid)).getId();
        long t2 = titleRepo.save(title("CVP-0001", "CVP-00001", aid)).getId();
        locationRepo.save(loc(t1, "vol",       "/stars/library/CVP/CVP-001 (CVP-001)"));
        locationRepo.save(loc(t2, "other-vol", "/stars/library/CVP/CVP-0001 (CVP-0001)"));

        var report = callDefault();
        // Only one title on "vol" for this base_code → no group (needs 2 on same volume)
        assertEquals(0, report.sections().duplicateBaseCodes().total());
    }

    // ── Each section: volume with one of each issue ───────────────────────────

    @Test
    void allSectionsOneIssueEach() throws Exception {
        // misnamedParents: actress with misnamed folder (canonical "Canon Actress" absent from path).
        // Note: the wrong basename will also produce a C3 drift (unresolvable name), so
        // driftedMultiActress may total > 1; we assert >= 1 per section.
        long a1 = actressRepo.save(actress("Canon Actress")).getId();
        long t1 = titleRepo.save(title("MIS-001", "MIS-00001", a1)).getId();
        locationRepo.save(loc(t1, "vol", "/stars/library/Wrong Canon/Wrong Canon (MIS-001)"));

        // driftedMultiActress: title with missing cast member
        long a2 = actressRepo.save(actress("Alice")).getId();
        long a3 = actressRepo.save(actress("Bob")).getId();
        long t2 = titleRepo.save(title("DRIFT-001", "DRIFT-00001", a2)).getId();
        jdbi.useHandle(h -> h.execute(
                "INSERT INTO title_actresses (title_id, actress_id) VALUES (?, ?)", t2, a3));
        locationRepo.save(loc(t2, "vol", "/stars/library/Alice/Alice (DRIFT-001)"));

        // fsOnlyTitles: folder on disk with no DB row
        fs.addDir("/queue/Unknown (FSONLY-001)");
        fs.addFile("/queue/Unknown (FSONLY-001)/video.mkv", 500_000L);

        // queueResidents: title in /queue/
        long a4 = actressRepo.save(actress("Queue Star")).getId();
        long t4 = titleRepo.save(title("QUEUE-001", "QUEUE-00001", a4)).getId();
        locationRepo.save(loc(t4, "vol", "/queue/Queue Star (QUEUE-001)"));

        // duplicateBaseCodes: two titles sharing base_code on this volume
        long a5 = actressRepo.save(actress("Dup Actress")).getId();
        long t5 = titleRepo.save(title("DUP-001",  "DUP-00001", a5)).getId();
        long t6 = titleRepo.save(title("DUP-0001", "DUP-00001", a5)).getId();
        locationRepo.save(loc(t5, "vol", "/stars/library/Dup Actress/Dup Actress (DUP-001)"));
        locationRepo.save(loc(t6, "vol", "/stars/library/Dup Actress/Dup Actress (DUP-0001)"));

        var report = callDefault();
        var s = report.sections();
        // Each section should surface at least 1 issue. Some may surface more due to cross-section
        // overlap (e.g. a misnamed-parent path also produces a driftedMultiActress unresolvable-name).
        assertTrue(s.misnamedParents().total() >= 1, "misnamedParents total >= 1");
        assertTrue(s.driftedMultiActress().total() >= 1, "driftedMultiActress total >= 1");
        assertEquals(1, s.fsOnlyTitles().total(), "fsOnlyTitles total");
        assertTrue(s.queueResidents().total() >= 1, "queueResidents total >= 1");
        assertEquals(1, s.duplicateBaseCodes().total(), "duplicateBaseCodes total");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private VolumeCurationReportTool.Report callDefault() throws Exception {
        return (VolumeCurationReportTool.Report) tool.call(M.createObjectNode());
    }

    private static Actress actress(String name) {
        return Actress.builder()
                .canonicalName(name)
                .tier(Actress.Tier.LIBRARY)
                .firstSeenAt(LocalDate.of(2024, 1, 1))
                .build();
    }

    private static Title title(String code, String baseCode, long actressId) {
        return Title.builder()
                .code(code)
                .baseCode(baseCode)
                .label(code.split("-")[0])
                .seqNum(1)
                .actressId(actressId)
                .build();
    }

    private static TitleLocation loc(long titleId, String volumeId, String path) {
        return TitleLocation.builder()
                .titleId(titleId)
                .volumeId(volumeId)
                .partitionId("p")
                .path(Path.of(path))
                .lastSeenAt(LocalDate.now())
                .build();
    }

    // ── fake filesystem (reused from FindFsOnlyTitlesToolTest pattern) ────────

    static final class MapFs implements VolumeFileSystem {
        private final Map<String, List<Path>> childMap = new HashMap<>();
        private final java.util.Set<String>   dirs     = new java.util.HashSet<>();
        private final Map<String, Long>        files    = new HashMap<>();

        void addDir(String path) {
            Path p = Path.of(path);
            registerAncestors(p);
            dirs.add(path);
            Path parent = p.getParent();
            if (parent != null) {
                List<Path> siblings = childMap.computeIfAbsent(parent.toString(), k -> new ArrayList<>());
                if (!siblings.contains(p)) siblings.add(p);
            }
            childMap.computeIfAbsent(path, k -> new ArrayList<>());
        }

        void addFile(String path, long size) {
            files.put(path, size);
            Path p = Path.of(path);
            Path parent = p.getParent();
            if (parent != null) {
                List<Path> siblings = childMap.computeIfAbsent(parent.toString(), k -> new ArrayList<>());
                if (!siblings.contains(p)) siblings.add(p);
            }
        }

        private void registerAncestors(Path p) {
            Path parent = p.getParent();
            if (parent == null) return;
            String parentStr = parent.toString();
            if (!dirs.contains(parentStr)) {
                dirs.add(parentStr);
                childMap.computeIfAbsent(parentStr, k -> new ArrayList<>());
                Path grandparent = parent.getParent();
                if (grandparent != null) {
                    List<Path> gpChildren = childMap.computeIfAbsent(grandparent.toString(), k -> new ArrayList<>());
                    if (!gpChildren.contains(parent)) gpChildren.add(parent);
                }
                registerAncestors(parent);
            }
        }

        @Override public List<Path> listDirectory(Path path) {
            return childMap.getOrDefault(path.toString(), List.of());
        }
        @Override public List<Path> walk(Path root) { return List.of(); }
        @Override public boolean exists(Path path) {
            return dirs.contains(path.toString()) || files.containsKey(path.toString());
        }
        @Override public boolean isDirectory(Path path) { return dirs.contains(path.toString()); }
        @Override public long size(Path path) {
            Long s = files.get(path.toString());
            return s != null ? s : 0L;
        }
        @Override public LocalDate getLastModifiedDate(Path path) { return null; }
        @Override public InputStream openFile(Path path) { throw new UnsupportedOperationException(); }
        @Override public void move(Path source, Path destination) { throw new UnsupportedOperationException(); }
        @Override public void rename(Path path, String newName) { throw new UnsupportedOperationException(); }
        @Override public void createDirectories(Path path) { throw new UnsupportedOperationException(); }
        @Override public void writeFile(Path path, byte[] contents) { throw new UnsupportedOperationException(); }
        @Override public FileTimestamps getTimestamps(Path path) { return null; }
        @Override public void setTimestamps(Path path, Instant created, Instant modified) {}
    }

    private static final class FakeConn implements VolumeConnection {
        private final VolumeFileSystem fs;
        FakeConn(VolumeFileSystem fs) { this.fs = fs; }
        @Override public VolumeFileSystem fileSystem() { return fs; }
        @Override public boolean isConnected() { return true; }
        @Override public void close() {}
    }
}
