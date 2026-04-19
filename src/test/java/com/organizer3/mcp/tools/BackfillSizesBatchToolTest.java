package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.organizer3.db.SchemaInitializer;
import com.organizer3.filesystem.FileTimestamps;
import com.organizer3.filesystem.VolumeFileSystem;
import com.organizer3.model.Title;
import com.organizer3.model.Video;
import com.organizer3.repository.jdbi.JdbiTitleLocationRepository;
import com.organizer3.repository.jdbi.JdbiTitleRepository;
import com.organizer3.repository.jdbi.JdbiVideoRepository;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BackfillSizesBatchToolTest {

    private static final ObjectMapper M = new ObjectMapper();

    private Connection connection;
    private JdbiTitleRepository titleRepo;
    private JdbiVideoRepository videoRepo;
    private SessionContext session;
    private StubFS fs;
    private BackfillSizesBatchTool tool;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        Jdbi jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        jdbi.useHandle(h -> h.execute("INSERT INTO volumes (id, structure_type) VALUES ('a', 'conventional')"));
        titleRepo = new JdbiTitleRepository(jdbi, new JdbiTitleLocationRepository(jdbi));
        videoRepo = new JdbiVideoRepository(jdbi);

        fs = new StubFS();
        session = new SessionContext();
        session.setMountedVolume(new com.organizer3.config.volume.VolumeConfig(
                "a", "//host/share", "conventional", "host", "g"));
        session.setActiveConnection(new StubConnection(fs));

        tool = new BackfillSizesBatchTool(session, videoRepo);
    }

    @AfterEach
    void tearDown() throws Exception { connection.close(); }

    @Test
    void populatesSizeForUnsizedRows() {
        long tid = titleRepo.save(title("ABP-001")).getId();
        Video v1 = videoRepo.save(row(tid, "a.mkv", "/a.mkv"));
        Video v2 = videoRepo.save(row(tid, "b.mkv", "/b.mkv"));
        fs.sizes.put(Path.of("/a.mkv"), 111L);
        fs.sizes.put(Path.of("/b.mkv"), 222L);

        var result = (BackfillSizesBatchTool.Result) tool.call(args(0, 10));
        assertEquals(2, result.scanned());
        assertEquals(2, result.ok());
        assertEquals(0, result.failed());
        assertEquals(0, result.remainingWithoutSize());
        assertEquals(111L, videoRepo.findById(v1.getId()).orElseThrow().getSizeBytes());
        assertEquals(222L, videoRepo.findById(v2.getId()).orElseThrow().getSizeBytes());
    }

    @Test
    void cursorAdvancesPastFailedRows() {
        long tid = titleRepo.save(title("ABP-001")).getId();
        Video v1 = videoRepo.save(row(tid, "a.mkv", "/a.mkv"));
        Video v2 = videoRepo.save(row(tid, "b.mkv", "/b.mkv"));
        fs.sizes.put(Path.of("/a.mkv"), 100L);
        // /b.mkv intentionally missing → IOException → counted as failed, stays null

        var r = (BackfillSizesBatchTool.Result) tool.call(args(0, 10));
        assertEquals(1, r.ok());
        assertEquals(1, r.failed());
        assertTrue(r.nextCursor() >= v2.getId(), "cursor must advance past the failure");
        // Next call from cursor finds no more rows.
        assertNull(videoRepo.findById(v2.getId()).orElseThrow().getSizeBytes());
        assertEquals(100L, videoRepo.findById(v1.getId()).orElseThrow().getSizeBytes());
    }

    @Test
    void respectsLimitAndReportsRemaining() {
        long tid = titleRepo.save(title("ABP-001")).getId();
        for (int i = 0; i < 5; i++) {
            Video v = videoRepo.save(row(tid, "v" + i + ".mkv", "/v" + i + ".mkv"));
            fs.sizes.put(Path.of("/v" + i + ".mkv"), 1000L + i);
        }
        var first = (BackfillSizesBatchTool.Result) tool.call(args(0, 2));
        assertEquals(2, first.scanned());
        assertEquals(3, first.remainingWithoutSize());
        var next = (BackfillSizesBatchTool.Result) tool.call(args(first.nextCursor(), 10));
        assertEquals(3, next.scanned());
        assertEquals(0, next.remainingWithoutSize());
    }

    @Test
    void emptyBatchWhenAllFilled() {
        long tid = titleRepo.save(title("ABP-001")).getId();
        Video v = videoRepo.save(row(tid, "a.mkv", "/a.mkv").toBuilder().sizeBytes(500L).build());
        var r = (BackfillSizesBatchTool.Result) tool.call(args(0, 10));
        assertEquals(0, r.scanned());
        assertEquals(500L, videoRepo.findById(v.getId()).orElseThrow().getSizeBytes());
    }

    @Test
    void failsWhenNothingMounted() {
        session.setMountedVolume(null);
        assertThrows(IllegalArgumentException.class, () -> tool.call(args(0, 10)));
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private static Title title(String code) {
        return Title.builder().code(code)
                .baseCode(code.toUpperCase().replace("-", "-00"))
                .label(code.split("-")[0]).seqNum(1).build();
    }

    private static Video row(long titleId, String filename, String path) {
        return Video.builder()
                .titleId(titleId).volumeId("a")
                .filename(filename).path(Path.of(path))
                .lastSeenAt(LocalDate.now()).build();
    }

    private static ObjectNode args(long fromId, int limit) {
        ObjectNode n = M.createObjectNode();
        n.put("fromId", fromId);
        n.put("limit", limit);
        return n;
    }

    private static final class StubFS implements VolumeFileSystem {
        final Map<Path, Long> sizes = new HashMap<>();
        @Override public long size(Path path) throws IOException {
            Long s = sizes.get(path);
            if (s == null) throw new IOException("no size for " + path);
            return s;
        }
        @Override public List<Path> listDirectory(Path p) { return List.of(); }
        @Override public List<Path> walk(Path p)          { return List.of(); }
        @Override public boolean exists(Path p)           { return sizes.containsKey(p); }
        @Override public boolean isDirectory(Path p)      { return false; }
        @Override public LocalDate getLastModifiedDate(Path p) { return null; }
        @Override public InputStream openFile(Path p) throws IOException { throw new IOException("n/a"); }
        @Override public void move(Path s, Path d)        {}
        @Override public void rename(Path p, String n)    {}
        @Override public void createDirectories(Path p)   {}
        @Override public void writeFile(Path p, byte[] b) {}
        @Override public FileTimestamps getTimestamps(Path p) { return new FileTimestamps(null, null, null); }
        @Override public void setTimestamps(Path p, Instant c, Instant m) {}
    }

    private static final class StubConnection implements VolumeConnection {
        private final VolumeFileSystem fs;
        StubConnection(VolumeFileSystem fs) { this.fs = fs; }
        @Override public VolumeFileSystem fileSystem() { return fs; }
        @Override public boolean isConnected() { return true; }
        @Override public void close() {}
    }
}
