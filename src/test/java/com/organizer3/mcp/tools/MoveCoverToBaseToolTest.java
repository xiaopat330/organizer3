package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.organizer3.config.volume.VolumeConfig;
import com.organizer3.db.SchemaInitializer;
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

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class MoveCoverToBaseToolTest {

    private static final ObjectMapper M = new ObjectMapper();

    private Connection connection;
    private JdbiTitleRepository titleRepo;
    private JdbiTitleLocationRepository locationRepo;
    private SessionContext session;
    private TrashDuplicateCoverToolTest.InMemoryFS fs;
    private MoveCoverToBaseTool tool;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        Jdbi jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        jdbi.useHandle(h -> h.execute("INSERT INTO volumes (id, structure_type) VALUES ('a', 'conventional')"));
        locationRepo = new JdbiTitleLocationRepository(jdbi);
        titleRepo = new JdbiTitleRepository(jdbi, locationRepo);

        fs = new TrashDuplicateCoverToolTest.InMemoryFS();
        session = new SessionContext();
        session.setMountedVolume(new VolumeConfig("a", "//pandora/jav_A", "conventional", "pandora", null));
        session.setActiveConnection(new FakeConnection(fs));

        tool = new MoveCoverToBaseTool(session, jdbi);
    }

    @AfterEach
    void tearDown() throws Exception { connection.close(); }

    @Test
    void dryRunPlansMovesWithoutExecuting() {
        seedTitle("IPZ-463", "/stars/Aino Kishi/Aino Kishi (IPZ-463)");
        fs.mkdir("/stars/Aino Kishi/Aino Kishi (IPZ-463)");
        fs.mkdir("/stars/Aino Kishi/Aino Kishi (IPZ-463)/video");
        fs.file("/stars/Aino Kishi/Aino Kishi (IPZ-463)/video/ipz463pl.jpg");

        var r = (MoveCoverToBaseTool.Result) tool.call(args("IPZ-463", true));
        assertTrue(r.dryRun());
        assertEquals(1, r.planned().size());
        assertEquals("/stars/Aino Kishi/Aino Kishi (IPZ-463)/video/ipz463pl.jpg", r.planned().get(0).from());
        assertEquals("/stars/Aino Kishi/Aino Kishi (IPZ-463)/ipz463pl.jpg", r.planned().get(0).to());
        assertTrue(r.moved().isEmpty());
        assertTrue(fs.exists(Path.of("/stars/Aino Kishi/Aino Kishi (IPZ-463)/video/ipz463pl.jpg")),
                "dry-run must leave file in place");
    }

    @Test
    void executeMovesCoverToBase() {
        seedTitle("IPZ-463", "/stars/Aino Kishi/Aino Kishi (IPZ-463)");
        fs.mkdir("/stars/Aino Kishi/Aino Kishi (IPZ-463)");
        fs.mkdir("/stars/Aino Kishi/Aino Kishi (IPZ-463)/video");
        fs.file("/stars/Aino Kishi/Aino Kishi (IPZ-463)/video/ipz463pl.jpg");

        var r = (MoveCoverToBaseTool.Result) tool.call(args("IPZ-463", false));
        assertFalse(r.dryRun());
        assertEquals(1, r.moved().size());
        assertTrue(r.failed().isEmpty());
        assertFalse(fs.exists(Path.of("/stars/Aino Kishi/Aino Kishi (IPZ-463)/video/ipz463pl.jpg")));
        assertTrue(fs.exists(Path.of("/stars/Aino Kishi/Aino Kishi (IPZ-463)/ipz463pl.jpg")));
    }

    @Test
    void recordsCollisionWhenBaseAlreadyHasSameName() {
        seedTitle("IPZ-463", "/stars/Aino Kishi/Aino Kishi (IPZ-463)");
        fs.mkdir("/stars/Aino Kishi/Aino Kishi (IPZ-463)");
        fs.file("/stars/Aino Kishi/Aino Kishi (IPZ-463)/ipz463pl.jpg");          // already exists at base
        fs.mkdir("/stars/Aino Kishi/Aino Kishi (IPZ-463)/video");
        fs.file("/stars/Aino Kishi/Aino Kishi (IPZ-463)/video/ipz463pl.jpg");    // misfiled copy

        var r = (MoveCoverToBaseTool.Result) tool.call(args("IPZ-463", true));
        assertEquals(0, r.planned().size());
        assertEquals(1, r.collisions().size());
        // both copies still exist
        assertTrue(fs.exists(Path.of("/stars/Aino Kishi/Aino Kishi (IPZ-463)/ipz463pl.jpg")));
        assertTrue(fs.exists(Path.of("/stars/Aino Kishi/Aino Kishi (IPZ-463)/video/ipz463pl.jpg")));
    }

    @Test
    void rejectsWhenNoMisfiledCovers() {
        seedTitle("IPZ-463", "/stars/Aino Kishi/Aino Kishi (IPZ-463)");
        fs.mkdir("/stars/Aino Kishi/Aino Kishi (IPZ-463)");
        fs.file("/stars/Aino Kishi/Aino Kishi (IPZ-463)/ipz463pl.jpg");          // only at base, no subs

        var ex = assertThrows(IllegalArgumentException.class,
                () -> tool.call(args("IPZ-463", true)));
        assertTrue(ex.getMessage().contains("no misfiled covers"));
    }

    @Test
    void rejectsWhenNoVolumeMounted() {
        session.setMountedVolume(null);
        var ex = assertThrows(IllegalArgumentException.class,
                () -> tool.call(args("X-1", true)));
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

    private static ObjectNode args(String titleCode, boolean dryRun) {
        ObjectNode n = M.createObjectNode();
        n.put("titleCode", titleCode);
        n.put("dryRun", dryRun);
        return n;
    }

    private static final class FakeConnection implements VolumeConnection {
        private final com.organizer3.filesystem.VolumeFileSystem fs;
        FakeConnection(com.organizer3.filesystem.VolumeFileSystem fs) { this.fs = fs; }
        @Override public com.organizer3.filesystem.VolumeFileSystem fileSystem() { return fs; }
        @Override public boolean isConnected()         { return true; }
        @Override public void close()                  {}
    }
}
