package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.organizer3.config.volume.VolumeConfig;
import com.organizer3.db.SchemaInitializer;
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

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ScanTitleFolderAnomaliesToolTest {

    private static final ObjectMapper M = new ObjectMapper();

    private Connection connection;
    private JdbiTitleRepository titleRepo;
    private JdbiTitleLocationRepository locationRepo;
    private SessionContext session;
    private FakeVolumeFS fs;
    private ScanTitleFolderAnomaliesTool tool;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        Jdbi jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        jdbi.useHandle(h -> h.execute("INSERT INTO volumes (id, structure_type) VALUES ('a', 'conventional')"));
        locationRepo = new JdbiTitleLocationRepository(jdbi);
        titleRepo = new JdbiTitleRepository(jdbi, locationRepo);

        fs = new FakeVolumeFS();
        session = new SessionContext();
        session.setMountedVolume(new VolumeConfig("a", "//host/a", "conventional", "host", null));
        session.setActiveConnection(new FakeConnection(fs));
        tool = new ScanTitleFolderAnomaliesTool(session, titleRepo, locationRepo);
    }

    @AfterEach
    void tearDown() throws Exception { connection.close(); }

    @Test
    void reportsNoAnomaliesForWellFormedFolder() throws Exception {
        seedTitle("OK-001", "/archive/OK-001");
        fs.mkdir("/archive/OK-001");
        fs.file("/archive/OK-001/cover.jpg");
        fs.mkdir("/archive/OK-001/video");
        fs.file("/archive/OK-001/video/OK-001.mkv");

        var r = (ScanTitleFolderAnomaliesTool.Result) tool.call(args("OK-001"));
        assertEquals(1, r.locations().size());
        assertTrue(r.locations().get(0).anomalies().isEmpty());
    }

    @Test
    void flagsMultipleBaseCovers() throws Exception {
        seedTitle("DUP-001", "/archive/DUP-001");
        fs.mkdir("/archive/DUP-001");
        fs.file("/archive/DUP-001/cover.jpg");
        fs.file("/archive/DUP-001/cover-big.jpg");

        var r = (ScanTitleFolderAnomaliesTool.Result) tool.call(args("DUP-001"));
        assertTrue(r.locations().get(0).anomalies().contains("multiple_base_covers"));
        assertEquals(2, r.locations().get(0).baseCovers().size());
    }

    @Test
    void flagsMisfiledCovers() throws Exception {
        seedTitle("MIS-001", "/archive/MIS-001");
        fs.mkdir("/archive/MIS-001");
        fs.file("/archive/MIS-001/cover.jpg");
        fs.mkdir("/archive/MIS-001/video");
        fs.file("/archive/MIS-001/video/MIS-001.mkv");
        fs.file("/archive/MIS-001/video/cover-extra.jpg"); // misfiled

        var r = (ScanTitleFolderAnomaliesTool.Result) tool.call(args("MIS-001"));
        assertTrue(r.locations().get(0).anomalies().contains("covers_in_subfolder"));
        assertEquals(1, r.locations().get(0).misfiledCovers().size());
    }

    @Test
    void flagsVideosAtBase() throws Exception {
        seedTitle("VID-001", "/archive/VID-001");
        fs.mkdir("/archive/VID-001");
        fs.file("/archive/VID-001/cover.jpg");
        fs.file("/archive/VID-001/VID-001.mkv");  // should be in a subfolder

        var r = (ScanTitleFolderAnomaliesTool.Result) tool.call(args("VID-001"));
        assertTrue(r.locations().get(0).anomalies().contains("videos_at_base"));
    }

    @Test
    void flagsMissingCover() throws Exception {
        seedTitle("NC-001", "/archive/NC-001");
        fs.mkdir("/archive/NC-001");
        fs.mkdir("/archive/NC-001/video");
        fs.file("/archive/NC-001/video/NC-001.mkv");

        var r = (ScanTitleFolderAnomaliesTool.Result) tool.call(args("NC-001"));
        assertTrue(r.locations().get(0).anomalies().contains("no_base_cover"));
    }

    @Test
    void rejectsMissingTitle() {
        assertThrows(IllegalArgumentException.class, () -> tool.call(args("DOES-NOT-EXIST")));
    }

    @Test
    void rejectsWhenNoVolumeMounted() {
        session.setMountedVolume(null);
        seedTitle("X-001", "/archive/X-001");
        assertThrows(IllegalArgumentException.class, () -> tool.call(args("X-001")));
    }

    private void seedTitle(String code, String path) {
        Title t = titleRepo.save(Title.builder()
                .code(code).baseCode(code.toUpperCase().replace("-", "-00"))
                .label(code.split("-")[0]).seqNum(1).build());
        locationRepo.save(TitleLocation.builder()
                .titleId(t.getId()).volumeId("a").partitionId("p")
                .path(Path.of(path)).lastSeenAt(LocalDate.now()).build());
    }

    private static ObjectNode args(String code) {
        ObjectNode n = M.createObjectNode();
        n.put("code", code);
        return n;
    }

    // ── In-memory VolumeFileSystem ──────────────────────────────────────────

    private static final class FakeVolumeFS implements VolumeFileSystem {
        private final Map<Path, Boolean> nodes = new HashMap<>(); // true = dir, false = file

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
        @Override public List<Path> walk(Path root)          { throw new UnsupportedOperationException(); }
        @Override public boolean exists(Path path)           { return nodes.containsKey(path); }
        @Override public boolean isDirectory(Path path)      { return Boolean.TRUE.equals(nodes.get(path)); }
        @Override public LocalDate getLastModifiedDate(Path path) { return null; }
        @Override public InputStream openFile(Path path) throws IOException { throw new IOException("n/a"); }
        @Override public void move(Path s, Path d)           {}
        @Override public void rename(Path p, String n)       {}
        @Override public void createDirectories(Path p)      {}
        @Override public void writeFile(Path p, byte[] b)    {}
        @Override public com.organizer3.filesystem.FileTimestamps getTimestamps(Path p) { return new com.organizer3.filesystem.FileTimestamps(null, null, null); }
        @Override public void setTimestamps(Path p, java.time.Instant c, java.time.Instant m) {}
    }

    private static final class FakeConnection implements VolumeConnection {
        private final VolumeFileSystem fs;
        FakeConnection(VolumeFileSystem fs) { this.fs = fs; }
        @Override public VolumeFileSystem fileSystem() { return fs; }
        @Override public boolean isConnected()         { return true; }
        @Override public void close()                  {}
    }
}
