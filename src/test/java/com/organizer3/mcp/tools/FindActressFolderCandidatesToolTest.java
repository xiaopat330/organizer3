package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.organizer3.config.volume.OrganizerConfig;
import com.organizer3.config.volume.PartitionDef;
import com.organizer3.config.volume.ServerConfig;
import com.organizer3.config.volume.StructuredPartitionDef;
import com.organizer3.config.volume.VolumeConfig;
import com.organizer3.config.volume.VolumeStructureDef;
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

class FindActressFolderCandidatesToolTest {

    private static final ObjectMapper M = new ObjectMapper();

    private Connection connection;
    private Jdbi jdbi;
    private JdbiActressRepository actressRepo;
    private JdbiTitleRepository titleRepo;
    private JdbiTitleLocationRepository locationRepo;
    private SessionContext session;
    private MapFs fs;
    private OrganizerConfig config;
    private FindActressFolderCandidatesTool tool;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi       = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        jdbi.useHandle(h ->
                h.execute("INSERT INTO volumes (id, structure_type) VALUES ('s', 'conventional')"));
        actressRepo  = new JdbiActressRepository(jdbi);
        locationRepo = new JdbiTitleLocationRepository(jdbi);
        titleRepo    = new JdbiTitleRepository(jdbi, locationRepo);

        fs     = new MapFs();
        config = makeConfig("s");
        session = new SessionContext();
        session.setMountedVolume(new VolumeConfig("s", "//host/s", "conventional", "host", null));
        session.setActiveConnection(new FakeConn(fs));
        tool = new FindActressFolderCandidatesTool(session, actressRepo, jdbi, config);
    }

    @AfterEach
    void tearDown() throws Exception { connection.close(); }

    // ── strong-name / strong-credit → high score ──────────────────────────────

    @Test
    void canonicalFolderWithAllCreditedTitlesScoresHigh() throws Exception {
        // Actress: "Shelly Fuji"
        long aid = actressRepo.save(actress("Shelly Fuji")).getId();
        // DB: 3 titles for this actress on volume 's'
        for (int i = 1; i <= 3; i++) {
            long tid = titleRepo.save(title("SHF-00" + i, aid)).getId();
            locationRepo.save(loc(tid, "s",
                    "/stars/minor/Shelly Fuji/Shelly Fuji (SHF-00" + i + ")"));
        }
        // FS: the canonical folder with 3 title subfolders
        fs.addDir("/stars/minor/Shelly Fuji");
        for (int i = 1; i <= 3; i++) {
            fs.addDir("/stars/minor/Shelly Fuji/Shelly Fuji (SHF-00" + i + ")");
        }

        var result = (FindActressFolderCandidatesTool.Result) tool.call(args(aid, "s", 0.0));
        // Should find one candidate: the canonical folder
        assertEquals(1, result.candidates().size());
        var c = result.candidates().get(0);
        assertEquals("/stars/minor/Shelly Fuji", c.folderPath());
        assertEquals(1.0, c.nameSimilarity(), 0.001); // exact match
        assertEquals(1.0, c.creditOverlap(), 0.001);  // all 3 credited
        // score = 0.6*1.0 + 0.4*1.0 = 1.0
        assertEquals(1.0, c.score(), 0.001);
        assertFalse(c.mixedContent());
    }

    // ── weak-name / strong-credit (Shelly case) ──────────────────────────────

    @Test
    void weakNameStrongCreditScoresAboveThreshold() throws Exception {
        long aid = actressRepo.save(actress("Shelly Fuji")).getId();
        // 3 titles credited on volume 's'
        for (int i = 1; i <= 3; i++) {
            long tid = titleRepo.save(title("XYZ-00" + i, aid)).getId();
            locationRepo.save(loc(tid, "s", "/stars/minor/Sherry Fujii/Sherry Fujii (XYZ-00" + i + ")"));
        }
        // FS: misspelled folder name "Sherry Fujii" with all 3 titles
        fs.addDir("/stars/minor/Sherry Fujii");
        for (int i = 1; i <= 3; i++) {
            fs.addDir("/stars/minor/Sherry Fujii/Sherry Fujii (XYZ-00" + i + ")");
        }

        var result = (FindActressFolderCandidatesTool.Result) tool.call(args(aid, "s", 0.3));
        assertEquals(1, result.candidates().size());
        var c = result.candidates().get(0);
        // name similarity < 1.0 (different spelling)
        assertTrue(c.nameSimilarity() < 1.0);
        // credit overlap = 1.0 (all 3 credited)
        assertEquals(1.0, c.creditOverlap(), 0.001);
        // score = 0.6*1.0 + 0.4*(nameSim) > 0.3 threshold
        assertTrue(c.score() >= 0.3, "Shelly Fuji case must exceed threshold: score=" + c.score());
    }

    // ── strong-name / no-credit → score = nameSimilarity ─────────────────────

    @Test
    void strongNameNoCreditScoreDrivenByName() throws Exception {
        long aid = actressRepo.save(actress("Exact Name")).getId();
        // No titles in DB for this actress on this volume
        // FS: exact-match folder, 2 title subfolders but none credited
        fs.addDir("/stars/minor/Exact Name");
        fs.addDir("/stars/minor/Exact Name/Exact Name (ABC-001)");
        fs.addDir("/stars/minor/Exact Name/Exact Name (ABC-002)");

        var result = (FindActressFolderCandidatesTool.Result) tool.call(args(aid, "s", 0.0));
        assertEquals(1, result.candidates().size());
        var c = result.candidates().get(0);
        assertEquals(1.0, c.nameSimilarity(), 0.001);
        assertEquals(0.0, c.creditOverlap(), 0.001);
        // dbCreditedCount=0 < 2, so score = nameSimilarity = 1.0
        assertEquals(1.0, c.score(), 0.001);
    }

    // ── single-credit floor: score drops to nameSimilarity ────────────────────

    @Test
    void singleCreditFolderScoresAsNameSimilarityOnly() throws Exception {
        long aid = actressRepo.save(actress("Long Actress Name")).getId();
        // 1 credited title — should use name signal only
        long tid = titleRepo.save(title("LAN-001", aid)).getId();
        locationRepo.save(loc(tid, "s", "/stars/library/Different Name/Different Name (LAN-001)"));

        fs.addDir("/stars/library/Different Name");
        fs.addDir("/stars/library/Different Name/Different Name (LAN-001)");
        // 3 titles total in folder, only 1 credited
        fs.addDir("/stars/library/Different Name/Other (OTHER-001)");
        fs.addDir("/stars/library/Different Name/Other (OTHER-002)");

        var result = (FindActressFolderCandidatesTool.Result) tool.call(args(aid, "s", 0.0));
        var opt = result.candidates().stream()
                .filter(c -> c.folderPath().equals("/stars/library/Different Name"))
                .findFirst();
        assertTrue(opt.isPresent());
        var c = opt.get();
        // dbCreditedCount=1 < 2 → score = nameSimilarity
        assertEquals(1, c.dbCreditedTitleCount());
        assertEquals(c.nameSimilarity(), c.score(), 0.001);
    }

    // ── mixedContent flag: some credited, some not ───────────────────────────

    @Test
    void mixedContentFlaggedWhenPartialCredit() throws Exception {
        long aid = actressRepo.save(actress("Mix Actress")).getId();
        // 2 credited (enough to use weighted score), 2 not credited
        long tid1 = titleRepo.save(title("MIX-001", aid)).getId();
        long tid2 = titleRepo.save(title("MIX-002", aid)).getId();
        locationRepo.save(loc(tid1, "s", "/stars/library/Mix Actress/Mix Actress (MIX-001)"));
        locationRepo.save(loc(tid2, "s", "/stars/library/Mix Actress/Mix Actress (MIX-002)"));

        fs.addDir("/stars/library/Mix Actress");
        fs.addDir("/stars/library/Mix Actress/Mix Actress (MIX-001)");
        fs.addDir("/stars/library/Mix Actress/Mix Actress (MIX-002)");
        fs.addDir("/stars/library/Mix Actress/Someone Else (OTHER-001)");
        fs.addDir("/stars/library/Mix Actress/Someone Else (OTHER-002)");

        var result = (FindActressFolderCandidatesTool.Result) tool.call(args(aid, "s", 0.0));
        var opt = result.candidates().stream()
                .filter(c -> c.folderPath().equals("/stars/library/Mix Actress"))
                .findFirst();
        assertTrue(opt.isPresent());
        assertTrue(opt.get().mixedContent(), "0 < creditOverlap < 1 should be flagged as mixedContent");
    }

    // ── min_score filtering ───────────────────────────────────────────────────

    @Test
    void minScoreFiltersOutLowScoringCandidates() throws Exception {
        long aid = actressRepo.save(actress("Test Actress")).getId();
        // Folder with very different name, no credits
        fs.addDir("/stars/library/Zzz Qqq Xxx");

        var resultHigh = (FindActressFolderCandidatesTool.Result) tool.call(args(aid, "s", 0.9));
        var resultLow  = (FindActressFolderCandidatesTool.Result) tool.call(args(aid, "s", 0.0));

        // The folder "Zzz Qqq Xxx" has low name similarity to "Test Actress", so it should
        // be filtered out by min_score=0.9 but visible at min_score=0.0
        assertTrue(resultHigh.candidates().stream()
                .noneMatch(c -> c.folderPath().equals("/stars/library/Zzz Qqq Xxx")),
                "High min_score should filter out distant names");
        assertTrue(resultLow.candidates().stream()
                .anyMatch(c -> c.folderPath().equals("/stars/library/Zzz Qqq Xxx")),
                "Low min_score should include even distant names");
    }

    // ── scoring unit tests ────────────────────────────────────────────────────

    @Test
    void computeNameSimilarityExactMatch() {
        double sim = FindActressFolderCandidatesTool.computeNameSimilarity("shelly fuji",
                List.of("Shelly Fuji", "alias"));
        assertEquals(1.0, sim, 0.001);
    }

    @Test
    void computeNameSimilarityBestAlias() {
        // Folder matches alias better than canonical
        double sim = FindActressFolderCandidatesTool.computeNameSimilarity("sherry fujii",
                List.of("Shelly Fuji", "Sherry Fujii"));
        assertEquals(1.0, sim, 0.001); // exact match vs alias
    }

    @Test
    void levenshteinDistance() {
        assertEquals(0, FindActressFolderCandidatesTool.levenshtein("abc", "abc"));
        assertEquals(1, FindActressFolderCandidatesTool.levenshtein("abc", "aXc"));
        assertEquals(3, FindActressFolderCandidatesTool.levenshtein("", "abc"));
        assertEquals(2, FindActressFolderCandidatesTool.levenshtein("shelly", "sherry"));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static Actress actress(String name) {
        return Actress.builder()
                .canonicalName(name)
                .tier(Actress.Tier.LIBRARY)
                .firstSeenAt(LocalDate.of(2024, 1, 1))
                .build();
    }

    private static com.organizer3.model.Title title(String code, long actressId) {
        return com.organizer3.model.Title.builder()
                .code(code).baseCode(code).label(code.split("-")[0]).seqNum(1)
                .actressId(actressId).build();
    }

    private static com.organizer3.model.TitleLocation loc(long titleId, String volumeId, String path) {
        return com.organizer3.model.TitleLocation.builder()
                .titleId(titleId).volumeId(volumeId).partitionId("p")
                .path(Path.of(path)).lastSeenAt(LocalDate.now()).build();
    }

    private static ObjectNode args(long actressId, String volumeId, double minScore) {
        ObjectNode n = M.createObjectNode();
        n.put("actress_id", actressId);
        n.put("volume_id", volumeId);
        n.put("min_score", minScore);
        return n;
    }

    private static OrganizerConfig makeConfig(String volumeId) {
        // Conventional structure: stars/ with minor + library tiers
        StructuredPartitionDef stars = new StructuredPartitionDef("stars", List.of(
                new PartitionDef("minor",   "minor"),
                new PartitionDef("library", "library")
        ));
        VolumeStructureDef conventional = new VolumeStructureDef(
                "conventional",
                List.of(new PartitionDef("queue", "queue")),
                stars);
        ServerConfig srv = new ServerConfig("host", "u", "p", null);
        VolumeConfig vol = new VolumeConfig(volumeId, "//host/" + volumeId, "conventional", "host", null);
        return new OrganizerConfig(
                "Test", "./data", 500, 500, 500, 8, 5, 47,
                List.of(srv), List.of(vol), List.of(conventional), List.of(), null);
    }

    // ── fake filesystem ───────────────────────────────────────────────────────

    static final class MapFs implements VolumeFileSystem {
        private final Map<String, List<Path>> childMap = new HashMap<>();
        private final java.util.Set<String>   dirs     = new java.util.HashSet<>();

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
        @Override public boolean exists(Path path) { return dirs.contains(path.toString()); }
        @Override public boolean isDirectory(Path path) { return dirs.contains(path.toString()); }
        @Override public long size(Path path) { return 0; }
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
