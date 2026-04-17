package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.organizer3.config.volume.OrganizerConfig;
import com.organizer3.config.volume.ServerConfig;
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
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TrashDuplicateCoverToolTest {

    private static final ObjectMapper M = new ObjectMapper();

    private Connection connection;
    private JdbiTitleRepository titleRepo;
    private JdbiTitleLocationRepository locationRepo;
    private SessionContext session;
    private InMemoryFS fs;
    private TrashDuplicateCoverTool tool;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        Jdbi jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        jdbi.useHandle(h -> h.execute("INSERT INTO volumes (id, structure_type) VALUES ('a', 'conventional')"));
        locationRepo = new JdbiTitleLocationRepository(jdbi);
        titleRepo = new JdbiTitleRepository(jdbi, locationRepo);

        fs = new InMemoryFS();
        session = new SessionContext();
        session.setMountedVolume(new VolumeConfig("a", "//pandora/jav_A", "conventional", "pandora", null));
        session.setActiveConnection(new FakeConnection(fs));

        OrganizerConfig config = makeConfig("pandora", "_trash");
        Clock fixed = Clock.fixed(Instant.parse("2026-04-16T10:00:00Z"), ZoneOffset.UTC);
        tool = new TrashDuplicateCoverTool(session, jdbi, config, fixed);
    }

    @AfterEach
    void tearDown() throws Exception { connection.close(); }

    @Test
    void dryRunReturnsPlanWithoutMoving() throws Exception {
        seedTitle("MIDE-123", "/stars/popular/Someone/Someone (MIDE-123)");
        fs.mkdir("/stars/popular/Someone/Someone (MIDE-123)");
        fs.file("/stars/popular/Someone/Someone (MIDE-123)/mide123pl.jpg");
        fs.file("/stars/popular/Someone/Someone (MIDE-123)/mide123pl (2).jpg");

        var r = (TrashDuplicateCoverTool.Result) tool.call(args("MIDE-123", "mide123pl.jpg", true));
        assertTrue(r.dryRun());
        assertEquals(1, r.plan().toTrash().size());
        assertEquals("mide123pl (2).jpg", r.plan().toTrash().get(0));
        assertTrue(fs.exists(Path.of("/stars/popular/Someone/Someone (MIDE-123)/mide123pl (2).jpg")),
                "dry-run must leave files in place");
    }

    @Test
    void executeMovesDuplicatesIntoTrash() throws Exception {
        seedTitle("MIDE-123", "/stars/popular/Someone/Someone (MIDE-123)");
        fs.mkdir("/stars/popular/Someone/Someone (MIDE-123)");
        fs.file("/stars/popular/Someone/Someone (MIDE-123)/mide123pl.jpg");
        fs.file("/stars/popular/Someone/Someone (MIDE-123)/mide123pl (2).jpg");

        var r = (TrashDuplicateCoverTool.Result) tool.call(args("MIDE-123", "mide123pl.jpg", false));
        assertFalse(r.dryRun());
        assertEquals(1, r.trashed().size());
        assertTrue(r.failed().isEmpty());
        assertTrue(r.trashed().get(0).startsWith("/_trash/stars/popular/Someone/Someone (MIDE-123)/"));

        // kept cover still there, dup gone
        assertTrue(fs.exists(Path.of("/stars/popular/Someone/Someone (MIDE-123)/mide123pl.jpg")));
        assertFalse(fs.exists(Path.of("/stars/popular/Someone/Someone (MIDE-123)/mide123pl (2).jpg")));

        // sidecar written
        assertTrue(fs.exists(Path.of("/_trash/stars/popular/Someone/Someone (MIDE-123)/mide123pl (2).jpg.json")));
    }

    @Test
    void rejectsWhenKeepIsNotAmongCovers() throws Exception {
        seedTitle("MIDE-123", "/stars/popular/Someone/Someone (MIDE-123)");
        fs.mkdir("/stars/popular/Someone/Someone (MIDE-123)");
        fs.file("/stars/popular/Someone/Someone (MIDE-123)/a.jpg");
        fs.file("/stars/popular/Someone/Someone (MIDE-123)/b.jpg");

        var ex = assertThrows(IllegalArgumentException.class,
                () -> tool.call(args("MIDE-123", "nonexistent.jpg", true)));
        assertTrue(ex.getMessage().contains("not among the covers"));
    }

    @Test
    void rejectsWhenOnlyOneCoverExists() throws Exception {
        seedTitle("MIDE-123", "/stars/popular/Someone/Someone (MIDE-123)");
        fs.mkdir("/stars/popular/Someone/Someone (MIDE-123)");
        fs.file("/stars/popular/Someone/Someone (MIDE-123)/only.jpg");

        var ex = assertThrows(IllegalArgumentException.class,
                () -> tool.call(args("MIDE-123", "only.jpg", true)));
        assertTrue(ex.getMessage().contains("nothing to dedupe"));
    }

    @Test
    void rejectsWhenServerHasNoTrashConfigured() throws Exception {
        Jdbi jdbi = Jdbi.create(connection);
        OrganizerConfig noTrash = makeConfig("pandora", null);
        TrashDuplicateCoverTool t = new TrashDuplicateCoverTool(session, jdbi, noTrash,
                Clock.fixed(Instant.parse("2026-04-16T10:00:00Z"), ZoneOffset.UTC));

        seedTitle("MIDE-123", "/stars/popular/Someone/Someone (MIDE-123)");
        fs.mkdir("/stars/popular/Someone/Someone (MIDE-123)");
        fs.file("/stars/popular/Someone/Someone (MIDE-123)/a.jpg");
        fs.file("/stars/popular/Someone/Someone (MIDE-123)/b.jpg");

        var ex = assertThrows(IllegalArgumentException.class,
                () -> t.call(args("MIDE-123", "a.jpg", true)));
        assertTrue(ex.getMessage().contains("no 'trash:' folder"));
    }

    @Test
    void rejectsWhenNoVolumeMounted() {
        session.setMountedVolume(null);
        var ex = assertThrows(IllegalArgumentException.class,
                () -> tool.call(args("MIDE-123", "a.jpg", true)));
        assertTrue(ex.getMessage().contains("No volume"));
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private void seedTitle(String code, String path) {
        Title t = titleRepo.save(Title.builder()
                .code(code)
                .baseCode(code.toUpperCase().replace("-", "-00"))
                .label(code.split("-")[0])
                .seqNum(1)
                .build());
        locationRepo.save(TitleLocation.builder()
                .titleId(t.getId())
                .volumeId("a")
                .partitionId("library")
                .path(Path.of(path))
                .addedDate(LocalDate.of(2024, 1, 2))
                .lastSeenAt(LocalDate.of(2024, 1, 2))
                .build());
    }

    private static OrganizerConfig makeConfig(String serverId, String trashFolder) {
        ServerConfig srv = new ServerConfig(serverId, "u", "p", null, trashFolder, null);
        VolumeConfig vol = new VolumeConfig("a", "//pandora/jav_A", "conventional", serverId, null);
        return new OrganizerConfig(
                "Test", "./data", 500, 500, 500, 8, 5, 47,
                List.of(srv), List.of(vol), List.of(), List.of(), null);
    }

    private static ObjectNode args(String titleCode, String keep, boolean dryRun) {
        ObjectNode n = M.createObjectNode();
        n.put("titleCode", titleCode);
        n.put("keep", keep);
        n.put("dryRun", dryRun);
        return n;
    }

    // ── in-memory helpers ───────────────────────────────────────────────────

    private static final class FakeConnection implements VolumeConnection {
        private final VolumeFileSystem fs;
        FakeConnection(VolumeFileSystem fs) { this.fs = fs; }
        @Override public VolumeFileSystem fileSystem() { return fs; }
        @Override public boolean isConnected()         { return true; }
        @Override public void close()                  {}
    }

    static final class InMemoryFS implements VolumeFileSystem {
        private final Map<Path, Boolean> nodes = new HashMap<>();
        private final Map<Path, byte[]> contents = new HashMap<>();

        void mkdir(String p) {
            Path path = Path.of(p);
            Path cur = path;
            while (cur != null) { nodes.put(cur, true); cur = cur.getParent(); }
        }
        void file(String p) {
            Path path = Path.of(p);
            nodes.put(path, false);
            Path parent = path.getParent();
            while (parent != null) { nodes.put(parent, true); parent = parent.getParent(); }
        }

        @Override public List<Path> listDirectory(Path path) {
            List<Path> out = new ArrayList<>();
            String parent = path.toString();
            for (Path p : nodes.keySet()) {
                Path pp = p.getParent();
                if (pp != null && pp.toString().equals(parent)) out.add(p);
            }
            return out;
        }
        @Override public List<Path> walk(Path root) { throw new UnsupportedOperationException(); }
        @Override public boolean exists(Path path)  { return nodes.containsKey(path); }
        @Override public boolean isDirectory(Path path) { return Boolean.TRUE.equals(nodes.get(path)); }
        @Override public LocalDate getLastModifiedDate(Path path) { return null; }
        @Override public InputStream openFile(Path path) throws IOException { throw new IOException("n/a"); }
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
