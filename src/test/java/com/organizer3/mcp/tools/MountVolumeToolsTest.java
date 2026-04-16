package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.organizer3.config.AppConfig;
import com.organizer3.config.volume.OrganizerConfig;
import com.organizer3.config.volume.ServerConfig;
import com.organizer3.config.volume.VolumeConfig;
import com.organizer3.db.SchemaInitializer;
import com.organizer3.filesystem.VolumeFileSystem;
import com.organizer3.repository.jdbi.JdbiActressRepository;
import com.organizer3.repository.jdbi.JdbiTitleLocationRepository;
import com.organizer3.repository.jdbi.JdbiTitleRepository;
import com.organizer3.shell.SessionContext;
import com.organizer3.smb.MountProgressListener;
import com.organizer3.smb.SmbConnectionException;
import com.organizer3.smb.SmbConnector;
import com.organizer3.smb.VolumeConnection;
import com.organizer3.sync.IndexLoader;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MountVolumeToolsTest {

    private static final ObjectMapper M = new ObjectMapper();

    private Connection connection;
    private SessionContext session;
    private FakeConnector connector;
    private IndexLoader indexLoader;
    private MountVolumeTool mountTool;
    private UnmountVolumeTool unmountTool;
    private MountStatusTool statusTool;

    @BeforeEach
    void setUp() throws Exception {
        AppConfig.initializeForTest(new OrganizerConfig(
                null, null, null, null, null, null, null, null,
                List.of(new ServerConfig("host", "u", "p", null)),
                List.of(
                        new VolumeConfig("a", "//host/a", "conventional", "host", null),
                        new VolumeConfig("b", "//host/b", "conventional", "host", null)),
                List.of(), List.of(), null));

        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        Jdbi jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        jdbi.useHandle(h -> {
            h.execute("INSERT INTO volumes (id, structure_type) VALUES ('a', 'conventional')");
            h.execute("INSERT INTO volumes (id, structure_type) VALUES ('b', 'conventional')");
        });
        indexLoader = new IndexLoader(new JdbiTitleRepository(jdbi, new JdbiTitleLocationRepository(jdbi)),
                new JdbiActressRepository(jdbi));

        session = new SessionContext();
        connector = new FakeConnector();
        mountTool = new MountVolumeTool(session, connector, indexLoader);
        unmountTool = new UnmountVolumeTool(session);
        statusTool = new MountStatusTool(session);
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
        AppConfig.reset();
    }

    // ── mount_status ────────────────────────────────────────────────────────

    @Test
    void statusReflectsNoMount() {
        MountStatusTool.Status s = (MountStatusTool.Status) statusTool.call(null);
        assertNull(s.volumeId());
        assertFalse(s.connected());
    }

    @Test
    void statusReflectsActiveMount() {
        mountTool.call(args("a"));
        MountStatusTool.Status s = (MountStatusTool.Status) statusTool.call(null);
        assertEquals("a", s.volumeId());
        assertTrue(s.connected());
    }

    // ── mount_volume ────────────────────────────────────────────────────────

    @Test
    void mountSetsSessionState() {
        MountVolumeTool.Result r = (MountVolumeTool.Result) mountTool.call(args("a"));
        assertTrue(r.mounted());
        assertEquals("a", r.volumeId());
        assertEquals("mounted", r.state());
        assertEquals("a", session.getMountedVolumeId());
        assertTrue(session.isConnected());
    }

    @Test
    void mountIsIdempotentWhenSameVolumeAlreadyActive() {
        mountTool.call(args("a"));
        int connectsBefore = connector.connectCount;
        MountVolumeTool.Result r = (MountVolumeTool.Result) mountTool.call(args("a"));
        assertEquals("already_mounted", r.state());
        assertEquals(connectsBefore, connector.connectCount, "no extra connect() call");
    }

    @Test
    void mountDropsPriorConnectionBeforeSwitching() {
        mountTool.call(args("a"));
        FakeConnection first = connector.lastConnection;
        mountTool.call(args("b"));
        assertTrue(first.closed, "prior connection must be closed when switching");
        assertEquals("b", session.getMountedVolumeId());
    }

    @Test
    void mountRejectsUnknownVolume() {
        assertThrows(IllegalArgumentException.class, () -> mountTool.call(args("zzz")));
    }

    @Test
    void mountWrapsConnectionFailureAsIllegalState() {
        connector.failNext = true;
        assertThrows(IllegalStateException.class, () -> mountTool.call(args("a")));
    }

    // ── unmount_volume ─────────────────────────────────────────────────────

    @Test
    void unmountClearsState() {
        mountTool.call(args("a"));
        UnmountVolumeTool.Result r = (UnmountVolumeTool.Result) unmountTool.call(null);
        assertTrue(r.unmounted());
        assertEquals("a", r.priorVolumeId());
        assertNull(session.getMountedVolumeId());
        assertFalse(session.isConnected());
    }

    @Test
    void unmountIsNoOpWhenNoMount() {
        UnmountVolumeTool.Result r = (UnmountVolumeTool.Result) unmountTool.call(null);
        assertFalse(r.unmounted());
        assertEquals("no_mount_active", r.state());
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static ObjectNode args(String volumeId) {
        ObjectNode n = M.createObjectNode();
        n.put("volumeId", volumeId);
        return n;
    }

    private static final class FakeConnector implements SmbConnector {
        int connectCount = 0;
        boolean failNext = false;
        FakeConnection lastConnection;

        @Override
        public VolumeConnection connect(VolumeConfig v, ServerConfig s, MountProgressListener p)
                throws SmbConnectionException {
            connectCount++;
            if (failNext) {
                failNext = false;
                throw new SmbConnectionException("simulated auth failure");
            }
            lastConnection = new FakeConnection();
            return lastConnection;
        }
    }

    private static final class FakeConnection implements VolumeConnection {
        boolean closed = false;
        @Override public VolumeFileSystem fileSystem() { return NO_FS; }
        @Override public boolean isConnected()         { return !closed; }
        @Override public void close()                  { closed = true; }
    }

    private static final VolumeFileSystem NO_FS = new VolumeFileSystem() {
        @Override public List<Path> listDirectory(Path p)   { return List.of(); }
        @Override public List<Path> walk(Path r)            { return List.of(); }
        @Override public boolean exists(Path p)             { return false; }
        @Override public boolean isDirectory(Path p)        { return false; }
        @Override public LocalDate getLastModifiedDate(Path p) { return null; }
        @Override public InputStream openFile(Path p)       { throw new UnsupportedOperationException(); }
        @Override public void move(Path s, Path d)          {}
        @Override public void rename(Path p, String n)      {}
        @Override public void createDirectories(Path p)     {}
    };
}
