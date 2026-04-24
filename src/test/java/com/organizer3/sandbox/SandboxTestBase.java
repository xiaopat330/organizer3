package com.organizer3.sandbox;

import com.organizer3.config.volume.OrganizerConfig;
import com.organizer3.config.volume.OrganizerConfigLoader;
import com.organizer3.config.volume.ServerConfig;
import com.organizer3.config.volume.VolumeConfig;
import com.organizer3.db.SchemaInitializer;
import com.organizer3.filesystem.VolumeFileSystem;
import com.organizer3.smb.SmbjConnector;
import com.organizer3.smb.SmbConnectionException;
import com.organizer3.smb.VolumeConnection;
import com.organizer3.trash.Sandbox;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInfo;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Base class for sandbox E2E tests that run against the real NAS over SMB.
 *
 * <p>Tests are skipped (not failed) when the NAS is unreachable or no sandbox is configured,
 * so CI without VPN access sees green rather than red.
 *
 * <p>SMB-differentiating cases these tests exist to catch (LocalFS unit tests cannot):
 * <ul>
 *   <li>Case-insensitive rename: {@code mide-123.mp4 → MIDE-123.mp4} on SMB/NTFS
 *   <li>Timestamp round-trip: write Instant with sub-second precision, read back, assert within ±1s
 *   <li>Atomic move across subfolders on a real SMB share
 * </ul>
 */
@Tag("sandbox")
public abstract class SandboxTestBase {

    static final String TEST_VOLUME_PROP = "sandbox.test.volume";
    static final String DEFAULT_TEST_VOLUME = "a";

    protected static OrganizerConfig config;
    protected static VolumeConfig testVolume;
    protected static VolumeConnection conn;
    protected static VolumeFileSystem fs;
    protected static Jdbi jdbi;
    protected static Connection dbConn;

    /** Parent run dir for this test class: {@code /_sandbox/tests/{className}-{uuid}}. */
    protected static Path runDir;

    /** Per-method run dir: {@code runDir/{methodName}-{shortUuid}}. Set in {@code @BeforeEach}. */
    protected Path methodRunDir;

    @BeforeAll
    static void connectToNas(TestInfo info) throws Exception {
        config = new OrganizerConfigLoader().load();

        String volumeId = System.getProperty(TEST_VOLUME_PROP, DEFAULT_TEST_VOLUME);
        testVolume = config.findById(volumeId)
                .orElseThrow(() -> new IllegalStateException("No volume with id: " + volumeId));

        ServerConfig server = config.findServerById(testVolume.server())
                .orElseThrow(() -> new IllegalStateException("No server with id: " + testVolume.server()));

        assumeTrue(server.sandbox() != null && !server.sandbox().isBlank(),
                "No sandbox configured for server " + server.id());

        try {
            conn = new SmbjConnector().connect(testVolume, server, msg -> {});
        } catch (SmbConnectionException e) {
            assumeTrue(false, "NAS not reachable (run with VPN): " + e.getMessage());
        }
        fs = conn.fileSystem();

        Sandbox sandbox = new Sandbox(fs, server.sandbox());
        sandbox.ensureExists();

        String className = info.getTestClass()
                .map(Class::getSimpleName)
                .orElse("sandbox");
        runDir = sandbox.resolve("tests/" + className + "-" + shortUuid());
        fs.createDirectories(runDir);

        dbConn = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(dbConn);
        new SchemaInitializer(jdbi).initialize();
        seedVolume();
    }

    @AfterAll
    static void disconnectFromNas() throws Exception {
        if (conn != null) conn.close();
        if (dbConn != null) dbConn.close();
    }

    @BeforeEach
    void createMethodRunDir(TestInfo info) throws IOException {
        String methodName = info.getTestMethod()
                .map(m -> m.getName())
                .orElse("unknown");
        methodRunDir = runDir.resolve(methodName + "-" + shortUuid());
        fs.createDirectories(methodRunDir);
        clearTestData();
    }

    private void clearTestData() {
        jdbi.useHandle(h -> {
            h.execute("DELETE FROM title_actresses");
            h.execute("DELETE FROM title_locations");
            h.execute("DELETE FROM titles");
            h.execute("DELETE FROM actresses");
        });
    }

    private static void seedVolume() {
        jdbi.useHandle(h -> h.execute(
                "INSERT OR IGNORE INTO volumes (id, structure_type) VALUES (?, ?)",
                testVolume.id(), testVolume.structureType()));
    }

    static String shortUuid() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
