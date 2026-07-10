package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.organizer3.config.volume.VolumeConfig;
import com.organizer3.covers.CoverPath;
import com.organizer3.db.SchemaInitializer;
import com.organizer3.filesystem.FileTimestamps;
import com.organizer3.filesystem.VolumeFileSystem;
import com.organizer3.model.Title;
import com.organizer3.model.TitleLocation;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BackfillFolderCoversToolTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static final String VOLUME_ID = "a";

    @TempDir
    Path tempDir;

    private Connection connection;
    private JdbiTitleRepository titleRepo;
    private JdbiTitleLocationRepository locationRepo;
    private SessionContext session;
    private FakeVolumeFS fs;
    private CoverPath coverPath;
    private Jdbi jdbi;
    private BackfillFolderCoversTool tool;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        jdbi.useHandle(h -> h.execute("INSERT INTO volumes (id, structure_type) VALUES ('a', 'conventional')"));

        locationRepo = new JdbiTitleLocationRepository(jdbi);
        titleRepo = new JdbiTitleRepository(jdbi, locationRepo);
        coverPath = new CoverPath(tempDir);

        fs = new FakeVolumeFS();
        session = new SessionContext();
        session.setMountedVolume(new VolumeConfig(VOLUME_ID, "//host/a", "conventional", "host", null));
        session.setActiveConnection(new FakeConnection(fs));

        tool = new BackfillFolderCoversTool(session, jdbi, coverPath);
    }

    @AfterEach
    void tearDown() throws Exception { connection.close(); }

    @Test
    void zeroByteNasCoverIsPushedFromLocalCache() throws Exception {
        seedTitle("ZBT-001", "ZBT-00001", "ZBT", "/archive/ZBT-001");
        writeLocalCover("ZBT", "ZBT-00001", "real-image-bytes".getBytes());
        fs.putFile("/archive/ZBT-001/ZBT-00001.jpg", new byte[0]); // zero-byte NAS cover

        var dryRunResult = (BackfillFolderCoversTool.Result) tool.call(args(VOLUME_ID, true, 0, 0));
        assertEquals(1, dryRunResult.counts().zeroByte());
        assertEquals(0, dryRunResult.counts().pushed());
        assertEquals(1, dryRunResult.candidates().size());
        assertEquals("zeroByte", dryRunResult.candidates().get(0).state());
        assertEquals(0, fs.writeCalls.size(), "dry run must not write");

        var realResult = (BackfillFolderCoversTool.Result) tool.call(args(VOLUME_ID, false, 0, 0));
        assertEquals(1, realResult.counts().pushed());
        assertEquals(0, realResult.counts().failed());
        // Terminal reclassification: a real run does not also tally missing/zeroByte for
        // titles it successfully pushed — counts must sum to scanned in either mode.
        assertEquals(0, realResult.counts().zeroByte());
        assertEquals(0, realResult.counts().missing());
        assertEquals(1, fs.writeCalls.size());
        assertEquals(Path.of("/archive/ZBT-001/ZBT-00001.jpg"), fs.writeCalls.get(0).path());
        assertArrayEquals("real-image-bytes".getBytes(), fs.writeCalls.get(0).bytes());
    }

    @Test
    void validNasCoverIsClassifiedOk() throws Exception {
        seedTitle("OK-001", "OK-00001", "OK", "/archive/OK-001");
        writeLocalCover("OK", "OK-00001", "real-image-bytes".getBytes());
        fs.putFile("/archive/OK-001/OK-00001.jpg", "nas-bytes".getBytes()); // valid NAS cover

        var result = (BackfillFolderCoversTool.Result) tool.call(args(VOLUME_ID, true, 0, 0));
        assertEquals(1, result.counts().ok());
        assertEquals(0, result.counts().missing());
        assertEquals(0, result.counts().zeroByte());
        assertTrue(result.candidates().isEmpty());
        assertEquals(0, fs.writeCalls.size());
    }

    @Test
    void titleWithNoLocalCoverIsNotPushable() throws Exception {
        seedTitle("NC-001", "NC-00001", "NC", "/archive/NC-001");
        // No local cover written at all.
        fs.putFile("/archive/NC-001/NC-00001.jpg", new byte[0]); // zero-byte NAS cover, but no local backup

        var result = (BackfillFolderCoversTool.Result) tool.call(args(VOLUME_ID, false, 0, 0));
        assertEquals(1, result.counts().noLocalCover());
        assertEquals(0, result.counts().zeroByte());
        assertEquals(0, result.counts().pushed());
        assertTrue(result.candidates().isEmpty());
        assertEquals(0, fs.writeCalls.size());
    }

    @Test
    void missingNasCoverIsPushedFromLocalCache() throws Exception {
        seedTitle("MIS-001", "MIS-00001", "MIS", "/archive/MIS-001");
        writeLocalCover("MIS", "MIS-00001", "cover-bytes".getBytes());
        // No NAS file registered at all -> missing.

        var result = (BackfillFolderCoversTool.Result) tool.call(args(VOLUME_ID, false, 0, 0));
        assertEquals(1, result.counts().pushed());
        assertEquals(1, fs.writeCalls.size());
    }

    @Test
    void rejectsVolumeIdMismatch() {
        assertThrows(IllegalArgumentException.class,
                () -> tool.call(args("other-volume", true, 0, 0)));
    }

    @Test
    void curatedOnlyExcludesUncuratedTitles() throws Exception {
        long curatedId = seedTitle("CUR-001", "CUR-00001", "CUR", "/archive/CUR-001");
        writeLocalCover("CUR", "CUR-00001", "curated-bytes".getBytes());
        fs.putFile("/archive/CUR-001/CUR-00001.jpg", new byte[0]); // zero-byte NAS cover
        markCurated(curatedId);

        seedTitle("UNC-001", "UNC-00001", "UNC", "/archive/UNC-001");
        writeLocalCover("UNC", "UNC-00001", "uncurated-bytes".getBytes());
        // No NAS file registered at all -> would be "missing" if scanned.

        var result = (BackfillFolderCoversTool.Result) tool.call(args(VOLUME_ID, true, 0, 0, true));
        assertTrue(result.curatedOnly());
        assertEquals(1, result.scanned(), "only the curated title should be enumerated");
        assertEquals(1, result.candidates().size());
        assertEquals("CUR-001", result.candidates().get(0).code());
        assertEquals("zeroByte", result.candidates().get(0).state());
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private long seedTitle(String code, String baseCode, String label, String path) {
        Title t = titleRepo.save(Title.builder()
                .code(code).baseCode(baseCode).label(label).seqNum(1).build());
        locationRepo.save(TitleLocation.builder()
                .titleId(t.getId()).volumeId(VOLUME_ID).partitionId("p")
                .path(Path.of(path)).lastSeenAt(LocalDate.now()).build());
        return t.getId();
    }

    private void markCurated(long titleId) {
        jdbi.useHandle(h -> h.createUpdate(
                "UPDATE title_locations SET curated_at = :now WHERE title_id = :titleId AND volume_id = :volumeId")
                .bind("now", Instant.now().toString())
                .bind("titleId", titleId)
                .bind("volumeId", VOLUME_ID)
                .execute());
    }

    private void writeLocalCover(String label, String baseCode, byte[] bytes) throws IOException {
        Path dir = tempDir.resolve("covers").resolve(label);
        Files.createDirectories(dir);
        Files.write(dir.resolve(baseCode + ".jpg"), bytes);
    }

    private static ObjectNode args(String volumeId, boolean dryRun, int limit, int offset) {
        ObjectNode n = M.createObjectNode();
        n.put("volumeId", volumeId);
        n.put("dryRun", dryRun);
        n.put("limit", limit);
        n.put("offset", offset);
        return n;
    }

    private static ObjectNode args(String volumeId, boolean dryRun, int limit, int offset, boolean curatedOnly) {
        ObjectNode n = args(volumeId, dryRun, limit, offset);
        n.put("curatedOnly", curatedOnly);
        return n;
    }

    // ── In-memory VolumeFileSystem ──────────────────────────────────────────

    private static final class FakeVolumeFS implements VolumeFileSystem {
        private final Map<Path, byte[]> files = new HashMap<>();
        final List<WriteCall> writeCalls = new ArrayList<>();

        record WriteCall(Path path, byte[] bytes) {}

        void putFile(String p, byte[] contents) { files.put(Path.of(p), contents); }

        @Override public List<Path> listDirectory(Path path) { return List.of(); }
        @Override public List<Path> walk(Path root)          { throw new UnsupportedOperationException(); }
        @Override public boolean exists(Path path)           { return files.containsKey(path); }
        @Override public boolean isDirectory(Path path)      { return false; }
        @Override public LocalDate getLastModifiedDate(Path path) { return null; }
        @Override public InputStream openFile(Path path) throws IOException { throw new IOException("n/a"); }
        @Override public long size(Path path) throws IOException {
            byte[] b = files.get(path);
            if (b == null) throw new IOException("no such file: " + path);
            return b.length;
        }
        @Override public void move(Path s, Path d)           {}
        @Override public void rename(Path p, String n)       {}
        @Override public void createDirectories(Path p)      {}
        @Override public void writeFile(Path path, byte[] bytes) {
            writeCalls.add(new WriteCall(path, bytes));
            files.put(path, bytes);
        }
        @Override public FileTimestamps getTimestamps(Path p) { return new FileTimestamps(null, null, null); }
        @Override public void setTimestamps(Path p, Instant c, Instant m) {}
    }

    private static final class FakeConnection implements VolumeConnection {
        private final VolumeFileSystem fs;
        FakeConnection(VolumeFileSystem fs) { this.fs = fs; }
        @Override public VolumeFileSystem fileSystem() { return fs; }
        @Override public boolean isConnected()         { return true; }
        @Override public void close()                  {}
    }
}
