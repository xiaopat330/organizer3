package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.organizer3.config.volume.OrganizerConfig;
import com.organizer3.config.volume.ServerConfig;
import com.organizer3.config.volume.VolumeConfig;
import com.organizer3.db.SchemaInitializer;
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
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TrashDuplicateVideoToolTest {

    private static final ObjectMapper M = new ObjectMapper();

    private Connection connection;
    private Jdbi jdbi;
    private JdbiTitleRepository titleRepo;
    private JdbiVideoRepository videoRepo;
    private SessionContext session;
    private InMemoryFS fs;
    private TrashDuplicateVideoTool tool;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        jdbi.useHandle(h -> h.execute("INSERT INTO volumes (id, structure_type) VALUES ('a', 'conventional')"));
        titleRepo = new JdbiTitleRepository(jdbi, new JdbiTitleLocationRepository(jdbi));
        videoRepo = new JdbiVideoRepository(jdbi);

        fs = new InMemoryFS();
        session = new SessionContext();
        session.setMountedVolume(new VolumeConfig("a", "//pandora/jav_A", "conventional", "pandora", null));
        session.setActiveConnection(new FakeConnection(fs));

        OrganizerConfig config = makeConfig("pandora", "_trash");
        Clock fixed = Clock.fixed(Instant.parse("2026-04-19T10:00:00Z"), ZoneOffset.UTC);
        tool = new TrashDuplicateVideoTool(session, jdbi, config, videoRepo, fixed);
    }

    @AfterEach
    void tearDown() throws Exception { connection.close(); }

    @Test
    void dryRunReturnsPlanWithoutMoving() {
        long tid = titleRepo.save(title("MIDE-123")).getId();
        Video keep  = videoRepo.save(video(tid, "mide123-h265.mkv", "/q/jav/MIDE-123/h265/mide123-h265.mkv", 2_000_000_000L));
        Video other = videoRepo.save(video(tid, "mide123.mkv",      "/q/jav/MIDE-123/video/mide123.mkv",      500_000_000L));
        fs.file(keep.getPath().toString());
        fs.file(other.getPath().toString());

        var r = (TrashDuplicateVideoTool.Result) tool.call(args("MIDE-123", keep.getId(), true));
        assertTrue(r.dryRun());
        assertEquals(keep.getId(), r.plan().keep().id());
        assertEquals(1, r.plan().toTrash().size());
        assertEquals(other.getId(), r.plan().toTrash().get(0).id());
        assertTrue(r.trashed().isEmpty(), "dry run: nothing trashed");
        assertTrue(fs.exists(other.getPath()), "dry run: file untouched");
    }

    @Test
    void liveRunTrashesAndRemovesDbRecords() {
        long tid = titleRepo.save(title("PRED-001")).getId();
        Video keep  = videoRepo.save(video(tid, "pred001-h265.mkv", "/q/PRED-001/h265/pred001-h265.mkv", 3_000_000_000L));
        Video other = videoRepo.save(video(tid, "pred001.mkv",      "/q/PRED-001/video/pred001.mkv",      800_000_000L));
        fs.file(keep.getPath().toString());
        fs.file(other.getPath().toString());

        var r = (TrashDuplicateVideoTool.Result) tool.call(args("PRED-001", keep.getId(), false));
        assertFalse(r.dryRun());
        assertEquals(1, r.trashed().size());
        assertTrue(r.failed().isEmpty());

        // file moved to trash
        assertFalse(fs.exists(other.getPath()), "original file removed from source");
        assertTrue(fs.exists(Path.of("/_trash/q/PRED-001/video/pred001.mkv")), "file in trash");

        // DB record removed
        assertTrue(videoRepo.findById(other.getId()).isEmpty(), "DB record deleted");
        assertTrue(videoRepo.findById(keep.getId()).isPresent(), "kept video still in DB");
    }

    @Test
    void rejectsWhenKeepIdNotInTitleVideos() {
        long tid = titleRepo.save(title("SKY-001")).getId();
        videoRepo.save(video(tid, "sky.mkv", "/q/SKY-001/video/sky.mkv", 1_000_000_000L));

        long bogusId = 99999L;
        var ex = assertThrows(IllegalArgumentException.class,
                () -> tool.call(args("SKY-001", bogusId, true)));
        assertTrue(ex.getMessage().contains("keepVideoId"));
    }

    @Test
    void rejectsWhenNoVolumeMounted() {
        session.setMountedVolume(null);
        assertThrows(IllegalArgumentException.class,
                () -> tool.call(args("ANY-001", 1L, true)));
    }

    @Test
    void rejectsWhenTitleNotFound() {
        assertThrows(IllegalArgumentException.class,
                () -> tool.call(args("NOTEXIST-001", 1L, true)));
    }

    @Test
    void rejectsWhenServerHasNoTrashConfigured() {
        OrganizerConfig noTrash = makeConfig("pandora", null);
        var t = new TrashDuplicateVideoTool(session, jdbi, noTrash, videoRepo);
        long tid = titleRepo.save(title("MIDE-456")).getId();
        videoRepo.save(video(tid, "v.mkv", "/q/v.mkv", 1_000_000_000L));
        assertThrows(IllegalArgumentException.class,
                () -> t.call(args("MIDE-456", 1L, true)));
    }

    // ── fixtures ──────────────────────────────────────────────────────────────

    private static Title title(String code) {
        return Title.builder().code(code)
                .baseCode(code.toUpperCase().replace("-", "-00"))
                .label(code.split("-")[0]).seqNum(1).build();
    }

    private static Video video(long titleId, String filename, String path, long sizeBytes) {
        return Video.builder()
                .titleId(titleId).volumeId("a")
                .filename(filename).path(Path.of(path))
                .sizeBytes(sizeBytes)
                .lastSeenAt(LocalDate.now()).build();
    }

    private static OrganizerConfig makeConfig(String serverId, String trashFolder) {
        ServerConfig srv = new ServerConfig(serverId, "u", "p", null, trashFolder, null);
        VolumeConfig vol = new VolumeConfig("a", "//pandora/jav_A", "conventional", serverId, null);
        return new OrganizerConfig(
                "Test", "./data", 500, 500, 500, 8, 5, 47,
                List.of(srv), List.of(vol), List.of(), List.of(), null);
    }

    private static ObjectNode args(String titleCode, long keepVideoId, boolean dryRun) {
        ObjectNode n = M.createObjectNode();
        n.put("titleCode",   titleCode);
        n.put("keepVideoId", keepVideoId);
        n.put("dryRun",      dryRun);
        return n;
    }

    // ── in-memory helpers ─────────────────────────────────────────────────────

    private static final class FakeConnection implements VolumeConnection {
        private final VolumeFileSystem fs;
        FakeConnection(VolumeFileSystem fs) { this.fs = fs; }
        @Override public VolumeFileSystem fileSystem() { return fs; }
        @Override public boolean isConnected()         { return true; }
        @Override public void close()                  {}
    }

    static final class InMemoryFS implements VolumeFileSystem {
        private final Map<Path, Boolean> nodes    = new HashMap<>();
        private final Map<Path, byte[]>  contents = new HashMap<>();

        void file(String p) {
            Path path = Path.of(p);
            nodes.put(path, false);
            Path parent = path.getParent();
            while (parent != null) { nodes.put(parent, true); parent = parent.getParent(); }
        }

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
    }
}
