package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.organizer3.config.volume.OrganizerConfig;
import com.organizer3.config.volume.ServerConfig;
import com.organizer3.config.volume.VolumeConfig;
import com.organizer3.curation.CurationLog;
import com.organizer3.db.SchemaInitializer;
import com.organizer3.model.Title;
import com.organizer3.model.TitleLocation;
import com.organizer3.model.Video;
import com.organizer3.repository.jdbi.JdbiTitleLocationRepository;
import com.organizer3.repository.jdbi.JdbiTitleRepository;
import com.organizer3.repository.jdbi.JdbiVideoRepository;
import com.organizer3.shell.SessionContext;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ConsolidateTitleLocationsToolTest {

    private static final ObjectMapper M = new ObjectMapper();

    @TempDir Path tempDir;

    private Connection connection;
    private Jdbi jdbi;
    private JdbiTitleRepository titleRepo;
    private JdbiTitleLocationRepository locationRepo;
    private JdbiVideoRepository videoRepo;
    private SessionContext session;
    private InMemoryFS fs;
    private ConsolidateTitleLocationsTool tool;
    private CurationLog curationLog;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        jdbi.useHandle(h -> {
            h.execute("INSERT INTO volumes (id, structure_type) VALUES ('a', 'conventional')");
            h.execute("INSERT INTO volumes (id, structure_type) VALUES ('b', 'conventional')");
            h.execute("INSERT INTO volumes (id, structure_type) VALUES ('c', 'conventional')");
        });

        locationRepo = new JdbiTitleLocationRepository(jdbi);
        titleRepo    = new JdbiTitleRepository(jdbi, locationRepo);
        videoRepo    = new JdbiVideoRepository(jdbi);
        curationLog  = new CurationLog(tempDir);

        fs = new InMemoryFS();
        session = new SessionContext();
        session.setMountedVolume(new VolumeConfig("a", "//pandora/jav_A", "conventional", "pandora", null));
        session.setActiveConnection(new FakeConnection(fs));

        OrganizerConfig config = makeConfig();
        Clock fixed = Clock.fixed(Instant.parse("2026-04-19T10:00:00Z"), ZoneOffset.UTC);
        tool = new ConsolidateTitleLocationsTool(session, jdbi, config, videoRepo, curationLog, fixed);
    }

    @AfterEach
    void tearDown() throws Exception { connection.close(); }

    // ── 1. Single location, keepLocationId matches → no-op ────────────────────

    @Test
    void singleLocation_noOp() {
        long tid = titleRepo.save(title("ABC-001")).getId();
        long keepId = locationRepo.save(location(tid, "a", "/queue/ABC-001")).getId();

        var r = (ConsolidateTitleLocationsTool.Result) tool.call(args(tid, keepId, false));
        assertEquals("ok", r.status());
        assertTrue(r.trashed().isEmpty());
        assertEquals(0, r.failedCount());
        assertEquals(1, locationCount(tid));
    }

    // ── 2. Two locations same volume → trash one, keep the other ──────────────

    @Test
    void twoLocationsSameVolume_consolidatesNonKeep() {
        long tid = titleRepo.save(title("MIDE-100")).getId();
        long keepId = locationRepo.save(location(tid, "a", "/stars/a/MIDE-100")).getId();
        long dropId = locationRepo.save(location(tid, "a", "/queue/MIDE-100")).getId();
        Video v = videoRepo.save(video(tid, "mide100.mkv", "/queue/MIDE-100/video/mide100.mkv"));
        fs.file(v.getPath().toString());
        fs.dir("/queue/MIDE-100"); fs.dir("/queue/MIDE-100/video");

        var r = (ConsolidateTitleLocationsTool.Result) tool.call(args(tid, keepId, false));
        assertEquals("ok", r.status());
        assertEquals(1, r.trashed().size());
        assertEquals(dropId, r.trashed().get(0).id());

        // file trashed
        assertFalse(fs.exists(v.getPath()));
        assertTrue(fs.exists(Path.of("/_trash/queue/MIDE-100/video/mide100.mkv")));
        // DB: only keep row remains
        assertEquals(1, locationCount(tid));
        assertTrue(locationExists(keepId));
        assertFalse(locationExists(dropId));
    }

    // ── 3. Three locations across volumes — only same-volume in-scope ─────────

    @Test
    void threeLocationsAcrossVolumes_consolidatesOnlyMountedVolume() {
        long tid = titleRepo.save(title("MULTI-001")).getId();
        long keepId  = locationRepo.save(location(tid, "a", "/stars/a/MULTI-001")).getId();
        long dropA   = locationRepo.save(location(tid, "a", "/queue/MULTI-001")).getId();
        long offB    = locationRepo.save(location(tid, "b", "/queue/MULTI-001")).getId();
        long offC    = locationRepo.save(location(tid, "c", "/queue/MULTI-001")).getId();
        Video v = videoRepo.save(video(tid, "multi001.mkv", "/queue/MULTI-001/video/multi001.mkv"));
        fs.file(v.getPath().toString());
        fs.dir("/queue/MULTI-001"); fs.dir("/queue/MULTI-001/video");

        var r = (ConsolidateTitleLocationsTool.Result) tool.call(args(tid, keepId, false));
        // partial because off-volume locations remain
        assertEquals("partial", r.status());
        assertTrue(r.crossVolume(), "should flag crossVolume");
        assertEquals(1, r.trashed().size());
        assertEquals(dropA, r.trashed().get(0).id());
        assertEquals(2, r.skipped().size());
        Set<Long> skippedIds = new HashSet<>();
        for (var s : r.skipped()) skippedIds.add(s.id());
        assertTrue(skippedIds.contains(offB));
        assertTrue(skippedIds.contains(offC));

        // DB: keep + 2 off-volume rows remain (dropA gone)
        assertEquals(3, locationCount(tid));
        assertFalse(locationExists(dropA));
    }

    // ── 4. keepLocationId not in title's location set → refused ───────────────

    @Test
    void keepIdNotInLocationSet_errors() {
        long tid = titleRepo.save(title("ERR-001")).getId();
        long aId = locationRepo.save(location(tid, "a", "/queue/ERR-001")).getId();
        long bId = locationRepo.save(location(tid, "a", "/stars/a/ERR-001")).getId();

        // Use an id that doesn't belong to this title
        long stranger = 9999L;
        var r = (ConsolidateTitleLocationsTool.Result) tool.call(args(tid, stranger, false));
        assertEquals("refused", r.status());
        assertNotNull(r.error());
        assertTrue(r.error().contains("not in title's location set"));
        assertTrue(r.error().contains(Long.toString(aId)));
        assertTrue(r.error().contains(Long.toString(bId)));
    }

    @Test
    void noLocationsForTitle_errors() {
        var r = (ConsolidateTitleLocationsTool.Result) tool.call(args(42424L, 1L, false));
        assertEquals("refused", r.status());
        assertTrue(r.error().contains("no title_locations"));
    }

    // ── 5. Dry-run does not mutate ────────────────────────────────────────────

    @Test
    void dryRun_doesNotMutate() {
        long tid = titleRepo.save(title("DRY-001")).getId();
        long keepId = locationRepo.save(location(tid, "a", "/stars/a/DRY-001")).getId();
        locationRepo.save(location(tid, "a", "/queue/DRY-001"));
        Video v = videoRepo.save(video(tid, "dry001.mkv", "/queue/DRY-001/video/dry001.mkv"));
        fs.file(v.getPath().toString());
        fs.dir("/queue/DRY-001"); fs.dir("/queue/DRY-001/video");

        var r = (ConsolidateTitleLocationsTool.Result) tool.call(args(tid, keepId, true));
        assertEquals("dry-run", r.status());
        assertTrue(r.dryRun());
        assertEquals(1, r.plannedTrash().size());
        assertTrue(r.trashed().isEmpty());

        // FS + DB unchanged
        assertTrue(fs.exists(v.getPath()));
        assertEquals(2, locationCount(tid));
    }

    // ── 6. Partial failure: one location trash fails, others succeed ──────────

    @Test
    void partialFailure_continuesAndReports() {
        long tid = titleRepo.save(title("PART-001")).getId();
        long keepId = locationRepo.save(location(tid, "a", "/stars/a/PART-001")).getId();
        // 3 non-keep locations
        long dA = locationRepo.save(location(tid, "a", "/queue/PART-001")).getId();
        long dB = locationRepo.save(location(tid, "a", "/attention/PART-001")).getId();
        long dC = locationRepo.save(location(tid, "a", "/archive/PART-001")).getId();

        // dA: clean, video trashable
        Video va = videoRepo.save(video(tid, "a.mkv", "/queue/PART-001/video/a.mkv"));
        fs.file(va.getPath().toString());
        fs.dir("/queue/PART-001"); fs.dir("/queue/PART-001/video");

        // dB: has a non-noise file leftover (will fail partial)
        fs.file("/attention/PART-001/random-doc.txt");
        fs.dir("/attention/PART-001");

        // dC: clean noise-only
        fs.file("/archive/PART-001/Thumbs.db");
        fs.dir("/archive/PART-001");

        var r = (ConsolidateTitleLocationsTool.Result) tool.call(args(tid, keepId, false));
        assertEquals("partial", r.status());
        assertEquals(1, r.failedCount());
        // 2 succeeded (dA + dC), 1 failed (dB)
        Set<Long> ok = new HashSet<>();
        for (var x : r.trashed()) ok.add(x.id());
        assertTrue(ok.contains(dA));
        assertTrue(ok.contains(dC));
        assertEquals(1, r.failed().size());
        assertEquals(dB, r.failed().get(0).id());
    }

    // ── 7. Kept location is untouched (FS + DB) ──────────────────────────────

    @Test
    void keepLocationUntouched() {
        long tid = titleRepo.save(title("KEEP-001")).getId();
        long keepId = locationRepo.save(location(tid, "a", "/stars/a/KEEP-001")).getId();
        locationRepo.save(location(tid, "a", "/queue/KEEP-001"));

        // Set up keep folder + video
        Video keepV = videoRepo.save(video(tid, "keep.mkv", "/stars/a/KEEP-001/video/keep.mkv"));
        fs.file(keepV.getPath().toString());
        fs.dir("/stars/a/KEEP-001"); fs.dir("/stars/a/KEEP-001/video");

        // Drop folder
        Video dropV = videoRepo.save(video(tid, "drop.mkv", "/queue/KEEP-001/video/drop.mkv"));
        fs.file(dropV.getPath().toString());
        fs.dir("/queue/KEEP-001"); fs.dir("/queue/KEEP-001/video");

        var r = (ConsolidateTitleLocationsTool.Result) tool.call(args(tid, keepId, false));
        assertEquals("ok", r.status());

        // Keep folder + video untouched
        assertTrue(fs.exists(keepV.getPath()), "keep video survives");
        assertTrue(fs.exists(Path.of("/stars/a/KEEP-001")));
        assertTrue(videoRepo.findById(keepV.getId()).isPresent());
        assertTrue(locationExists(keepId));
        assertEquals(1, locationCount(tid));

        // Drop location gone
        assertFalse(fs.exists(dropV.getPath()));
    }

    // ── Path validation: refuses disallowed prefix on any non-keep location ──

    @Test
    void refusesWhenNonKeepLocationHasDisallowedPrefix() {
        long tid = titleRepo.save(title("BAD-001")).getId();
        long keepId = locationRepo.save(location(tid, "a", "/stars/a/BAD-001")).getId();
        // disallowed top-level
        locationRepo.save(location(tid, "a", "/unknown_tier/BAD-001"));

        var r = (ConsolidateTitleLocationsTool.Result) tool.call(args(tid, keepId, false));
        assertEquals("refused", r.status());
        assertTrue(r.error().contains("not allowed"));
    }

    // ── All non-keep are off-volume → status=skipped ─────────────────────────

    @Test
    void allNonKeepOffVolume_skippedStatus() {
        long tid = titleRepo.save(title("OFF-001")).getId();
        long keepId = locationRepo.save(location(tid, "a", "/stars/a/OFF-001")).getId();
        long offB = locationRepo.save(location(tid, "b", "/queue/OFF-001")).getId();

        var r = (ConsolidateTitleLocationsTool.Result) tool.call(args(tid, keepId, false));
        assertEquals("skipped", r.status());
        assertEquals(1, r.skipped().size());
        assertEquals(offB, r.skipped().get(0).id());
        assertTrue(r.crossVolume());
        // Nothing trashed
        assertEquals(2, locationCount(tid));
    }

    // ── fixtures ──────────────────────────────────────────────────────────────

    private static Title title(String code) {
        return Title.builder().code(code)
                .baseCode(code.toUpperCase())
                .label(code.split("-")[0]).seqNum(1).build();
    }

    private static TitleLocation location(long titleId, String volumeId, String path) {
        return TitleLocation.builder()
                .titleId(titleId)
                .volumeId(volumeId)
                .partitionId("default")
                .path(Path.of(path))
                .lastSeenAt(LocalDate.now())
                .build();
    }

    private static Video video(long titleId, String filename, String path) {
        return Video.builder()
                .titleId(titleId).volumeId("a")
                .filename(filename).path(Path.of(path))
                .sizeBytes(500_000_000L)
                .lastSeenAt(LocalDate.now()).build();
    }

    private static OrganizerConfig makeConfig() {
        ServerConfig srv = new ServerConfig("pandora", "u", "p", null, "_trash", null);
        VolumeConfig volA = new VolumeConfig("a", "//pandora/jav_A", "conventional", "pandora", null);
        VolumeConfig volB = new VolumeConfig("b", "//pandora/jav_B", "conventional", "pandora", null);
        VolumeConfig volC = new VolumeConfig("c", "//pandora/jav_C", "conventional", "pandora", null);
        return new OrganizerConfig(
                "Test", "./data", 500, 500, 500, 8, 5, 47,
                List.of(srv), List.of(volA, volB, volC), List.of(), List.of(), null);
    }

    private static ObjectNode args(long titleId, long keepLocationId, boolean dryRun) {
        ObjectNode n = M.createObjectNode();
        n.put("titleId",        titleId);
        n.put("keepLocationId", keepLocationId);
        n.put("dryRun",         dryRun);
        return n;
    }

    private boolean locationExists(long id) {
        return jdbi.withHandle(h -> h.createQuery(
                        "SELECT COUNT(*) FROM title_locations WHERE id = :id")
                .bind("id", id).mapTo(Integer.class).one()) > 0;
    }

    private int locationCount(long titleId) {
        return jdbi.withHandle(h -> h.createQuery(
                        "SELECT COUNT(*) FROM title_locations WHERE title_id = :tid")
                .bind("tid", titleId)
                .mapTo(Integer.class)
                .one());
    }

    // ── In-memory FS (mirrors TrashTitleLocationToolTest.InMemoryFS) ──────────

    private static final class FakeConnection implements com.organizer3.smb.VolumeConnection {
        private final com.organizer3.filesystem.VolumeFileSystem fs;
        FakeConnection(com.organizer3.filesystem.VolumeFileSystem fs) { this.fs = fs; }
        @Override public com.organizer3.filesystem.VolumeFileSystem fileSystem() { return fs; }
        @Override public boolean isConnected() { return true; }
        @Override public void close() {}
    }

    static final class InMemoryFS implements com.organizer3.filesystem.VolumeFileSystem {
        private final Map<Path, Boolean> nodes    = new HashMap<>();
        private final Map<Path, byte[]>  contents = new HashMap<>();

        void file(String p) {
            Path path = Path.of(p);
            nodes.put(path, false);
            Path parent = path.getParent();
            while (parent != null) { nodes.put(parent, true); parent = parent.getParent(); }
        }

        void dir(String p) { nodes.put(Path.of(p), true); }

        @Override public List<Path> listDirectory(Path path) {
            List<Path> out = new ArrayList<>();
            for (Path p : nodes.keySet()) {
                Path pp = p.getParent();
                if (pp != null && pp.equals(path)) out.add(p);
            }
            return out;
        }
        @Override public List<Path> walk(Path root) { throw new UnsupportedOperationException(); }
        @Override public boolean exists(Path path)       { return nodes.containsKey(path); }
        @Override public boolean isDirectory(Path path)  { return Boolean.TRUE.equals(nodes.get(path)); }
        @Override public LocalDate getLastModifiedDate(Path path) { return null; }
        @Override public InputStream openFile(Path path) throws IOException { throw new IOException("n/a"); }
        @Override public long size(Path path)            throws IOException { throw new IOException("n/a"); }
        @Override public void move(Path source, Path destination) {
            Boolean kind = nodes.remove(source);
            if (kind != null) nodes.put(destination, kind);
            byte[] body = contents.remove(source);
            if (body != null) contents.put(destination, body);
        }
        @Override public void rename(Path path, String newName) { move(path, path.resolveSibling(newName)); }
        @Override public void createDirectories(Path path) {
            Path cur = path;
            while (cur != null && !cur.equals(Path.of("/"))) {
                nodes.put(cur, true);
                cur = cur.getParent();
            }
        }
        @Override public void writeFile(Path path, byte[] body) {
            nodes.put(path, false);
            contents.put(path, body);
        }
        @Override public com.organizer3.filesystem.FileTimestamps getTimestamps(Path p) {
            return new com.organizer3.filesystem.FileTimestamps(null, null, null);
        }
        @Override public void setTimestamps(Path p, java.time.Instant c, java.time.Instant m) {}
        @Override public void delete(Path path) throws IOException {
            nodes.remove(path);
            contents.remove(path);
        }
    }
}
