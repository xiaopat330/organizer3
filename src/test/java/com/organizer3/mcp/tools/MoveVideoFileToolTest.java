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

class MoveVideoFileToolTest {

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
    private MoveVideoFileTool tool;

    @BeforeEach
    void setUp() throws Exception {
        connection   = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi         = Jdbi.create(connection);
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
        tool = new MoveVideoFileTool(session, titleRepo, locationRepo, jdbi, curationLog);
    }

    @AfterEach
    void tearDown() throws Exception { connection.close(); }

    // ── Happy path: move without DB integration ───────────────────────────────

    @Test
    void dryRunReturnsPlanWithoutTouchingFs() {
        fs.existingPaths.add(Path.of("/attention/Saki Okuda (MIMK-190_4K)/h265/MIMK-190_4K-h265.mkv"));
        fs.existingPaths.add(Path.of("/stars/goddess/Saki Okuda/Saki Okuda (MIMK-190)/h265"));

        var result = (MoveVideoFileTool.Result) tool.call(args(
                "s",
                "/attention/Saki Okuda (MIMK-190_4K)/h265/MIMK-190_4K-h265.mkv",
                "/stars/goddess/Saki Okuda/Saki Okuda (MIMK-190)/h265/MIMK-190_4K-h265.mkv",
                null, true));

        assertEquals("dry-run", result.status());
        assertTrue(fs.moveCalls.isEmpty(), "dry-run must not touch FS");
        assertNull(result.dbInserts());
    }

    @Test
    void movesFileWithoutDbRegistration() {
        fs.existingPaths.add(Path.of("/attention/folder/h265/MIMK-001.mkv"));
        fs.existingPaths.add(Path.of("/stars/goddess/Saki/Saki (MIMK-001)/h265"));

        var result = (MoveVideoFileTool.Result) tool.call(args(
                "s",
                "/attention/folder/h265/MIMK-001.mkv",
                "/stars/goddess/Saki/Saki (MIMK-001)/h265/MIMK-001.mkv",
                null, false));

        assertEquals("ok", result.status());
        assertEquals(1, fs.moveCalls.size());
        assertEquals(Path.of("/attention/folder/h265/MIMK-001.mkv"), fs.moveCalls.get(0).from());
        assertEquals(Path.of("/stars/goddess/Saki/Saki (MIMK-001)/h265/MIMK-001.mkv"), fs.moveCalls.get(0).to());
        assertNull(result.dbInserts());
    }

    // ── Happy path: move with addAsLocationOf ─────────────────────────────────

    @Test
    void moveSetsupVideoRowWhenTitleAlreadyHasLocation() {
        long titleId = seedTitle("MIMK-190");
        // Title already has a location at the title folder
        seedLocation(titleId, "s", "/stars/goddess/Saki Okuda/Saki Okuda (MIMK-190)");

        fs.existingPaths.add(Path.of("/attention/Saki Okuda (MIMK-190_4K)/h265/MIMK-190_4K-h265.mkv"));
        fs.existingPaths.add(Path.of("/stars/goddess/Saki Okuda/Saki Okuda (MIMK-190)/h265"));
        fs.sizes.put(Path.of("/stars/goddess/Saki Okuda/Saki Okuda (MIMK-190)/h265/MIMK-190_4K-h265.mkv"), 5_000_000L);

        var result = (MoveVideoFileTool.Result) tool.call(args(
                "s",
                "/attention/Saki Okuda (MIMK-190_4K)/h265/MIMK-190_4K-h265.mkv",
                "/stars/goddess/Saki Okuda/Saki Okuda (MIMK-190)/h265/MIMK-190_4K-h265.mkv",
                "MIMK-190", false));

        assertEquals("ok", result.status());
        assertNotNull(result.dbInserts());
        // video row inserted
        assertTrue(result.dbInserts().videoId() > 0);
        // location already covered — no new location row
        assertFalse(result.dbInserts().insertedLocation());
        assertNull(result.dbInserts().locationId());

        // DB: videos row exists with correct path
        long videoCount = jdbi.withHandle(h -> h.createQuery(
                "SELECT COUNT(*) FROM videos WHERE path = '/stars/goddess/Saki Okuda/Saki Okuda (MIMK-190)/h265/MIMK-190_4K-h265.mkv'")
                .mapTo(Long.class).one());
        assertEquals(1, videoCount);

        // DB: still only one title_locations row
        long locCount = jdbi.withHandle(h -> h.createQuery(
                "SELECT COUNT(*) FROM title_locations WHERE title_id = " + titleId)
                .mapTo(Long.class).one());
        assertEquals(1, locCount);
    }

    @Test
    void moveSetsupVideoAndLocationRowWhenTitleHasNoLocationOnVolume() {
        long titleId = seedTitle("NEWC-001");
        // Title has no location at all

        fs.existingPaths.add(Path.of("/queue/NEWC-001.mp4"));
        fs.existingPaths.add(Path.of("/stars/star/Actress/Actress (NEWC-001)"));
        fs.sizes.put(Path.of("/stars/star/Actress/Actress (NEWC-001)/NEWC-001.mp4"), 1_000_000L);

        var result = (MoveVideoFileTool.Result) tool.call(args(
                "s",
                "/queue/NEWC-001.mp4",
                "/stars/star/Actress/Actress (NEWC-001)/NEWC-001.mp4",
                "NEWC-001", false));

        assertEquals("ok", result.status());
        assertNotNull(result.dbInserts());
        assertTrue(result.dbInserts().insertedLocation());
        assertNotNull(result.dbInserts().locationId());

        // DB: videos row
        long videoCount = jdbi.withHandle(h -> h.createQuery(
                "SELECT COUNT(*) FROM videos WHERE path = '/stars/star/Actress/Actress (NEWC-001)/NEWC-001.mp4'")
                .mapTo(Long.class).one());
        assertEquals(1, videoCount);

        // DB: title_locations row pointing at dest parent
        String locPath = jdbi.withHandle(h -> h.createQuery(
                "SELECT path FROM title_locations WHERE title_id = " + titleId)
                .mapTo(String.class).one());
        assertEquals("/stars/star/Actress/Actress (NEWC-001)", locPath);

        // DB: partition_id derived correctly for /stars/<tier>/...
        String partition = jdbi.withHandle(h -> h.createQuery(
                "SELECT partition_id FROM title_locations WHERE title_id = " + titleId)
                .mapTo(String.class).one());
        assertEquals("star", partition);
    }

    @Test
    void sizeNullOnIoFailureDoesNotBlockCommit() {
        long titleId = seedTitle("ABC-001");
        seedLocation(titleId, "s", "/stars/library/A/A (ABC-001)");

        fs.existingPaths.add(Path.of("/queue/ABC-001.avi"));
        fs.existingPaths.add(Path.of("/stars/library/A/A (ABC-001)"));
        fs.failSizeFor.add(Path.of("/stars/library/A/A (ABC-001)/ABC-001.avi"));

        var result = (MoveVideoFileTool.Result) tool.call(args(
                "s",
                "/queue/ABC-001.avi",
                "/stars/library/A/A (ABC-001)/ABC-001.avi",
                "ABC-001", false));

        assertEquals("ok", result.status());
        // video row still inserted despite null size
        Long sizeInDb = jdbi.withHandle(h -> h.createQuery(
                "SELECT size_bytes FROM videos WHERE path = '/stars/library/A/A (ABC-001)/ABC-001.avi'")
                .mapTo(Long.class).findFirst().orElse(null));
        assertNull(sizeInDb);
    }

    // ── Existing-videos-row update behavior (data-integrity fix) ──────────────

    @Test
    void updatesExistingVideosRowAtSourcePathWhenAddAsLocationOfOmitted() {
        long titleId = seedTitle("EBOD-185");
        seedLocation(titleId, "s", "/queue/EBOD-185_Uncensored");
        long existingVideoId = seedVideo(titleId, "s", "/queue/EBOD-185_Uncensored/video/EBOD-185_Uncensored.mp4");

        fs.existingPaths.add(Path.of("/queue/EBOD-185_Uncensored/video/EBOD-185_Uncensored.mp4"));
        fs.existingPaths.add(Path.of("/stars/goddess/Sora Aoi/Sora Aoi (EBOD-185)/video"));

        var result = (MoveVideoFileTool.Result) tool.call(args(
                "s",
                "/queue/EBOD-185_Uncensored/video/EBOD-185_Uncensored.mp4",
                "/stars/goddess/Sora Aoi/Sora Aoi (EBOD-185)/video/EBOD-185_Uncensored.mp4",
                null, false));

        assertEquals("ok", result.status());
        assertEquals("updated_existing", result.dbAction());
        assertNotNull(result.dbInserts());
        assertEquals(existingVideoId, result.dbInserts().videoId());
        assertNull(result.dbInserts().locationId());

        String pathInDb = jdbi.withHandle(h -> h.createQuery(
                "SELECT path FROM videos WHERE id = " + existingVideoId)
                .mapTo(String.class).one());
        assertEquals("/stars/goddess/Sora Aoi/Sora Aoi (EBOD-185)/video/EBOD-185_Uncensored.mp4", pathInDb);

        long countAtOld = jdbi.withHandle(h -> h.createQuery(
                "SELECT COUNT(*) FROM videos WHERE path = '/queue/EBOD-185_Uncensored/video/EBOD-185_Uncensored.mp4'")
                .mapTo(Long.class).one());
        assertEquals(0, countAtOld);
    }

    @Test
    void noExistingRowAndNoAddAsLocationOfYieldsDbActionNone() {
        fs.existingPaths.add(Path.of("/attention/folder/h265/MIMK-001.mkv"));
        fs.existingPaths.add(Path.of("/stars/goddess/Saki/Saki (MIMK-001)/h265"));

        var result = (MoveVideoFileTool.Result) tool.call(args(
                "s",
                "/attention/folder/h265/MIMK-001.mkv",
                "/stars/goddess/Saki/Saki (MIMK-001)/h265/MIMK-001.mkv",
                null, false));

        assertEquals("ok", result.status());
        assertEquals("none", result.dbAction());
        assertNull(result.dbInserts());

        long videoCount = jdbi.withHandle(h -> h.createQuery("SELECT COUNT(*) FROM videos").mapTo(Long.class).one());
        assertEquals(0, videoCount);
    }

    @Test
    void existingRowPlusMatchingAddAsLocationOfUpdatesNotInserts() {
        long titleId = seedTitle("MIMK-190");
        seedLocation(titleId, "s", "/stars/goddess/Saki Okuda/Saki Okuda (MIMK-190)");
        long existingVideoId = seedVideo(titleId, "s",
                "/attention/Saki Okuda (MIMK-190_4K)/h265/MIMK-190_4K-h265.mkv");

        fs.existingPaths.add(Path.of("/attention/Saki Okuda (MIMK-190_4K)/h265/MIMK-190_4K-h265.mkv"));
        fs.existingPaths.add(Path.of("/stars/goddess/Saki Okuda/Saki Okuda (MIMK-190)/h265"));

        var result = (MoveVideoFileTool.Result) tool.call(args(
                "s",
                "/attention/Saki Okuda (MIMK-190_4K)/h265/MIMK-190_4K-h265.mkv",
                "/stars/goddess/Saki Okuda/Saki Okuda (MIMK-190)/h265/MIMK-190_4K-h265.mkv",
                "MIMK-190", false));

        assertEquals("ok", result.status());
        assertEquals("updated_existing", result.dbAction());
        assertEquals(existingVideoId, result.dbInserts().videoId());

        long videoCount = jdbi.withHandle(h -> h.createQuery("SELECT COUNT(*) FROM videos").mapTo(Long.class).one());
        assertEquals(1, videoCount, "should not have inserted a second videos row");
    }

    @Test
    void existingRowPlusMismatchedAddAsLocationOfFailsBeforeMove() {
        long titleA = seedTitle("AAA-001");
        long titleB = seedTitle("BBB-002");
        seedLocation(titleA, "s", "/queue/A");
        seedVideo(titleA, "s", "/queue/A/AAA-001.mkv");

        fs.existingPaths.add(Path.of("/queue/A/AAA-001.mkv"));
        fs.existingPaths.add(Path.of("/stars/star/B/B (BBB-002)"));

        var result = (MoveVideoFileTool.Result) tool.call(args(
                "s",
                "/queue/A/AAA-001.mkv",
                "/stars/star/B/B (BBB-002)/AAA-001.mkv",
                "BBB-002", false));

        assertEquals("failed", result.status());
        assertEquals("none", result.dbAction());
        assertTrue(result.error().contains("title_id"), "error: " + result.error());
        assertTrue(fs.moveCalls.isEmpty(), "must refuse before moving");

        String pathStillThere = jdbi.withHandle(h -> h.createQuery(
                "SELECT path FROM videos WHERE title_id = " + titleA).mapTo(String.class).one());
        assertEquals("/queue/A/AAA-001.mkv", pathStillThere);
    }

    @Test
    void multipleExistingRowsAtSourcePathFailsBeforeMove() {
        long titleId = seedTitle("DUP-001");
        seedLocation(titleId, "s", "/queue/dup");
        seedVideo(titleId, "s", "/queue/dup/DUP-001.mkv");
        seedVideo(titleId, "s", "/queue/dup/DUP-001.mkv");

        fs.existingPaths.add(Path.of("/queue/dup/DUP-001.mkv"));
        fs.existingPaths.add(Path.of("/stars/star/D/D (DUP-001)"));

        var result = (MoveVideoFileTool.Result) tool.call(args(
                "s",
                "/queue/dup/DUP-001.mkv",
                "/stars/star/D/D (DUP-001)/DUP-001.mkv",
                null, false));

        assertEquals("failed", result.status());
        assertEquals("none", result.dbAction());
        assertTrue(result.error().contains("2 videos rows"), "error: " + result.error());
        assertTrue(fs.moveCalls.isEmpty(), "must refuse before moving");
    }

    @Test
    void dryRunReportsPlannedDbAction() {
        long titleId = seedTitle("EBOD-185");
        seedLocation(titleId, "s", "/queue");
        seedVideo(titleId, "s", "/queue/EBOD-185.mp4");

        fs.existingPaths.add(Path.of("/queue/EBOD-185.mp4"));
        fs.existingPaths.add(Path.of("/stars/goddess/X/X (EBOD-185)"));

        var result = (MoveVideoFileTool.Result) tool.call(args(
                "s",
                "/queue/EBOD-185.mp4",
                "/stars/goddess/X/X (EBOD-185)/EBOD-185.mp4",
                null, true));

        assertEquals("dry-run", result.status());
        assertEquals("updated_existing", result.dbAction());

        String pathInDb = jdbi.withHandle(h -> h.createQuery(
                "SELECT path FROM videos WHERE title_id = " + titleId).mapTo(String.class).one());
        assertEquals("/queue/EBOD-185.mp4", pathInDb);
    }

    @Test
    void existingDbInsertedNewResponseFieldOnFreshInsert() {
        long titleId = seedTitle("NEWC-001");
        fs.existingPaths.add(Path.of("/queue/NEWC-001.mp4"));
        fs.existingPaths.add(Path.of("/stars/star/Actress/Actress (NEWC-001)"));
        fs.sizes.put(Path.of("/stars/star/Actress/Actress (NEWC-001)/NEWC-001.mp4"), 1_000_000L);

        var result = (MoveVideoFileTool.Result) tool.call(args(
                "s",
                "/queue/NEWC-001.mp4",
                "/stars/star/Actress/Actress (NEWC-001)/NEWC-001.mp4",
                "NEWC-001", false));

        assertEquals("ok", result.status());
        assertEquals("inserted_new", result.dbAction());
        assertNotNull(result.dbInserts());
        assertTrue(result.dbInserts().videoId() > 0);
        // suppress unused warning
        assertNotNull(titleRepo.findByCode("NEWC-001").orElseThrow());
        assertTrue(titleId > 0);
    }

    // ── Refusal cases ─────────────────────────────────────────────────────────

    @Test
    void refusesWhenNoVolumeMounted() {
        session.setMountedVolume(null);
        session.setActiveConnection(null);
        var result = (MoveVideoFileTool.Result) tool.call(args("s", "/a/b.mkv", "/c/d.mkv", null, false));
        assertEquals("failed", result.status());
        assertTrue(result.error().contains("no volume mounted"));
    }

    @Test
    void refusesWhenVolumeIdMismatch() {
        var result = (MoveVideoFileTool.Result) tool.call(args("r", "/a/b.mkv", "/c/d.mkv", null, false));
        assertEquals("failed", result.status());
        assertTrue(result.error().contains("does not match mounted volume"));
    }

    @Test
    void refusesBadExtension() {
        var result = (MoveVideoFileTool.Result) tool.call(args("s", "/a/b.txt", "/c/d.txt", null, false));
        assertEquals("failed", result.status());
        assertTrue(result.error().contains("not a recognized video extension"));
    }

    @Test
    void refusesUnknownTitleForAddAsLocationOf() {
        // Title doesn't exist in DB
        fs.existingPaths.add(Path.of("/a/b.mkv"));
        fs.existingPaths.add(Path.of("/c"));
        var result = (MoveVideoFileTool.Result) tool.call(args("s", "/a/b.mkv", "/c/d.mkv", "GHOST-999", false));
        assertEquals("failed", result.status());
        assertTrue(result.error().contains("GHOST-999"));
        // No FS operations should have happened
        assertTrue(fs.moveCalls.isEmpty(), "should refuse before moving");
    }

    @Test
    void refusesSourceMissing() {
        // fs.existingPaths doesn't include source
        fs.existingPaths.add(Path.of("/c"));
        var result = (MoveVideoFileTool.Result) tool.call(args("s", "/a/b.mkv", "/c/d.mkv", null, false));
        assertEquals("failed", result.status());
        assertTrue(result.error().contains("does not exist"));
    }

    @Test
    void refusesSourceIsDirectory() {
        fs.existingPaths.add(Path.of("/a/b.mkv"));
        fs.directories.add(Path.of("/a/b.mkv"));
        fs.existingPaths.add(Path.of("/c"));
        var result = (MoveVideoFileTool.Result) tool.call(args("s", "/a/b.mkv", "/c/d.mkv", null, false));
        assertEquals("failed", result.status());
        assertTrue(result.error().contains("directory"));
    }

    @Test
    void refusesDestinationExists() {
        fs.existingPaths.add(Path.of("/a/b.mkv"));
        fs.existingPaths.add(Path.of("/c/d.mkv")); // collision
        fs.existingPaths.add(Path.of("/c"));
        var result = (MoveVideoFileTool.Result) tool.call(args("s", "/a/b.mkv", "/c/d.mkv", null, false));
        assertEquals("failed", result.status());
        assertTrue(result.error().contains("already exists"));
    }

    @Test
    void refusesDestParentMissing() {
        fs.existingPaths.add(Path.of("/a/b.mkv"));
        // dest parent /c does NOT exist
        var result = (MoveVideoFileTool.Result) tool.call(args("s", "/a/b.mkv", "/c/d.mkv", null, false));
        assertEquals("failed", result.status());
        assertTrue(result.error().contains("parent does not exist"));
    }

    @Test
    void returnsFailedOnMoveIoException() {
        fs.existingPaths.add(Path.of("/a/b.mkv"));
        fs.existingPaths.add(Path.of("/c"));
        fs.failOnMove = true;
        var result = (MoveVideoFileTool.Result) tool.call(args("s", "/a/b.mkv", "/c/d.mkv", null, false));
        assertEquals("failed", result.status());
        assertNotNull(result.error());
    }

    // ── Unit tests for pure helpers ───────────────────────────────────────────

    @Test
    void extensionOfExtractsCorrectly() {
        assertEquals("mkv", MoveVideoFileTool.extensionOf("/foo/bar/video.mkv"));
        assertEquals("mp4", MoveVideoFileTool.extensionOf("/foo/VIDEO.MP4"));
        assertEquals("",    MoveVideoFileTool.extensionOf("/foo/noext"));
        assertEquals("avi", MoveVideoFileTool.extensionOf("simple.avi"));
    }

    @Test
    void isAncestorOrEqualChecksCorrectly() {
        Path titleFolder = Path.of("/stars/goddess/Saki/Saki (MIMK-190)");
        Path h265Dir     = Path.of("/stars/goddess/Saki/Saki (MIMK-190)/h265");
        Path otherTitle  = Path.of("/stars/goddess/Saki/Other (FOO-001)");
        Path unrelated   = Path.of("/attention");

        assertTrue(MoveVideoFileTool.isAncestorOrEqual(titleFolder, h265Dir));
        assertTrue(MoveVideoFileTool.isAncestorOrEqual(titleFolder, titleFolder));
        assertFalse(MoveVideoFileTool.isAncestorOrEqual(titleFolder, otherTitle));
        assertFalse(MoveVideoFileTool.isAncestorOrEqual(titleFolder, unrelated));
    }

    @Test
    void derivePartitionIdForStarsPath() {
        assertEquals("goddess", MoveVideoFileTool.derivePartitionId(
                Path.of("/stars/goddess/Saki/Saki (MIMK-190)/h265")));
        assertEquals("library", MoveVideoFileTool.derivePartitionId(
                Path.of("/stars/library/A/A (B-001)")));
    }

    @Test
    void derivePartitionIdForNonStarsPath() {
        assertEquals("attention", MoveVideoFileTool.derivePartitionId(Path.of("/attention/review")));
        assertEquals("queue",     MoveVideoFileTool.derivePartitionId(Path.of("/queue")));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private long seedTitle(String code) {
        return jdbi.withHandle(h ->
                h.createQuery("INSERT INTO titles (code) VALUES (?) RETURNING id")
                        .bind(0, code)
                        .mapTo(Long.class).one());
    }

    private long seedVideo(long titleId, String volumeId, String path) {
        String filename = path.substring(path.lastIndexOf('/') + 1);
        return jdbi.withHandle(h -> h.createQuery("""
                INSERT INTO videos (title_id, volume_id, filename, path, last_seen_at)
                VALUES (?, ?, ?, ?, '2024-01-01') RETURNING id
                """)
                .bind(0, titleId).bind(1, volumeId).bind(2, filename).bind(3, path)
                .mapTo(Long.class).one());
    }

    private void seedLocation(long titleId, String volumeId, String path) {
        jdbi.useHandle(h -> h.execute("""
                INSERT INTO title_locations (title_id, volume_id, partition_id, path, last_seen_at)
                VALUES (?, ?, 'goddess', ?, '2024-01-01')
                """, titleId, volumeId, path));
    }

    private static ObjectNode args(String volumeId, String sourcePath, String destPath,
                                   String addAsLocationOf, boolean dryRun) {
        ObjectNode n = M.createObjectNode();
        n.put("volumeId",   volumeId);
        n.put("sourcePath", sourcePath);
        n.put("destPath",   destPath);
        if (addAsLocationOf != null) n.put("addAsLocationOf", addAsLocationOf);
        n.put("dryRun", dryRun);
        return n;
    }

    // ── Test doubles ──────────────────────────────────────────────────────────

    private static final class FakeConnection implements VolumeConnection {
        private final VolumeFileSystem fs;
        FakeConnection(VolumeFileSystem fs) { this.fs = fs; }
        @Override public VolumeFileSystem fileSystem() { return fs; }
        @Override public boolean isConnected() { return true; }
        @Override public void close() {}
    }

    static final class FakeFs implements VolumeFileSystem {
        record MoveCall(Path from, Path to) {}

        final List<MoveCall>   moveCalls    = new ArrayList<>();
        final Set<Path>        existingPaths = new HashSet<>();
        final Set<Path>        directories   = new HashSet<>();
        final Map<Path, Long>  sizes         = new HashMap<>();
        final Set<Path>        failSizeFor   = new HashSet<>();
        boolean failOnMove = false;

        @Override public boolean exists(Path path) { return existingPaths.contains(path); }
        @Override public boolean isDirectory(Path path) { return directories.contains(path); }

        @Override public void move(Path source, Path destination) throws IOException {
            if (failOnMove) throw new IOException("simulated move failure");
            moveCalls.add(new MoveCall(source, destination));
        }

        @Override public long size(Path path) throws IOException {
            if (failSizeFor.contains(path)) throw new IOException("simulated size failure");
            return sizes.getOrDefault(path, 0L);
        }

        @Override public void createDirectories(Path path) {}
        @Override public List<Path> listDirectory(Path path) { return List.of(); }
        @Override public List<Path> walk(Path root) { return List.of(); }
        @Override public LocalDate getLastModifiedDate(Path path) { return null; }
        @Override public InputStream openFile(Path path) { throw new UnsupportedOperationException(); }
        @Override public void rename(Path path, String newName) { throw new UnsupportedOperationException(); }
        @Override public void writeFile(Path path, byte[] contents) { throw new UnsupportedOperationException(); }
        @Override public FileTimestamps getTimestamps(Path path) { return null; }
        @Override public void setTimestamps(Path path, Instant created, Instant modified) {}
    }
}
