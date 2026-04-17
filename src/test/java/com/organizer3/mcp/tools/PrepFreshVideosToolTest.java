package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.organizer3.config.volume.MediaConfig;
import com.organizer3.config.volume.NormalizeConfig;
import com.organizer3.config.volume.OrganizerConfig;
import com.organizer3.config.volume.PartitionDef;
import com.organizer3.config.volume.ServerConfig;
import com.organizer3.config.volume.VolumeConfig;
import com.organizer3.config.volume.VolumeStructureDef;
import com.organizer3.filesystem.VolumeFileSystem;
import com.organizer3.organize.FreshPrepService;
import com.organizer3.shell.SessionContext;
import com.organizer3.smb.VolumeConnection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PrepFreshVideosToolTest {

    private static final ObjectMapper M = new ObjectMapper();

    private SessionContext session;
    private FakeFS fs;
    private PrepFreshVideosTool tool;

    @BeforeEach
    void setUp() {
        fs = new FakeFS();
        session = new SessionContext();
        session.setMountedVolume(new VolumeConfig("u", "//host/u", "queue", "host", null));
        session.setActiveConnection(new FakeConnection(fs));

        OrganizerConfig config = makeConfig();
        NormalizeConfig n = new NormalizeConfig(List.of("hhd800.com@"), List.of());
        FreshPrepService service = new FreshPrepService(n, MediaConfig.DEFAULTS);
        tool = new PrepFreshVideosTool(session, config, service);
    }

    @Test
    void dryRunReturnsPlanWithoutMoving() {
        fs.mkdir("/fresh");
        fs.file("/fresh/hhd800.com@JUR-717-h265.mkv");

        var r = (FreshPrepService.Result) tool.call(args("u", "queue", true));
        assertTrue(r.dryRun());
        assertEquals(1, r.planned().size());
        assertTrue(fs.exists(Path.of("/fresh/hhd800.com@JUR-717-h265.mkv")),
                "dry-run must leave files in place");
    }

    @Test
    void executeMovesFilesIntoSkeletons() {
        fs.mkdir("/fresh");
        fs.file("/fresh/hhd800.com@JUR-717-h265.mkv");

        var r = (FreshPrepService.Result) tool.call(args("u", "queue", false));
        assertFalse(r.dryRun());
        assertEquals(1, r.moved().size());
        assertTrue(fs.exists(Path.of("/fresh/(JUR-717)/h265/JUR-717-h265.mkv")));
        assertFalse(fs.exists(Path.of("/fresh/hhd800.com@JUR-717-h265.mkv")));
    }

    @Test
    void rejectsWhenVolumeNotMounted() {
        session.setMountedVolume(null);
        assertThrows(IllegalArgumentException.class,
                () -> tool.call(args("u", "queue", true)));
    }

    @Test
    void rejectsUnknownPartition() {
        fs.mkdir("/fresh");
        assertThrows(IllegalArgumentException.class,
                () -> tool.call(args("u", "nonexistent", true)));
    }

    @Test
    void rejectsMismatchedVolume() {
        fs.mkdir("/fresh");
        // mounted is 'u' but args ask for 'other'
        assertThrows(IllegalArgumentException.class,
                () -> tool.call(args("other", "queue", true)));
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private static ObjectNode args(String volumeId, String partitionId, boolean dryRun) {
        ObjectNode n = M.createObjectNode();
        n.put("volumeId", volumeId);
        n.put("partitionId", partitionId);
        n.put("dryRun", dryRun);
        return n;
    }

    private static OrganizerConfig makeConfig() {
        VolumeStructureDef queueDef = new VolumeStructureDef(
                "queue",
                List.of(new PartitionDef("queue", "fresh")),
                null);
        ServerConfig srv = new ServerConfig("host", "u", "p", null);
        VolumeConfig vol = new VolumeConfig("u", "//host/u", "queue", "host", null);
        return new OrganizerConfig(
                "Test", "./data", 500, 500, 500, 8, 5, 47,
                List.of(srv), List.of(vol), List.of(queueDef), List.of(), null);
    }

    private static final class FakeConnection implements VolumeConnection {
        private final VolumeFileSystem fs;
        FakeConnection(VolumeFileSystem fs) { this.fs = fs; }
        @Override public VolumeFileSystem fileSystem() { return fs; }
        @Override public boolean isConnected()         { return true; }
        @Override public void close()                  {}
    }

    static final class FakeFS implements VolumeFileSystem {
        private final Map<Path, Boolean> nodes = new HashMap<>();

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
        @Override public List<Path> walk(Path root)            { throw new UnsupportedOperationException(); }
        @Override public boolean exists(Path path)             { return nodes.containsKey(path); }
        @Override public boolean isDirectory(Path path)        { return Boolean.TRUE.equals(nodes.get(path)); }
        @Override public LocalDate getLastModifiedDate(Path p) { return null; }
        @Override public InputStream openFile(Path p) throws IOException { throw new IOException("n/a"); }
        @Override public void move(Path s, Path d) {
            Boolean kind = nodes.remove(s);
            if (kind != null) nodes.put(d, kind);
        }
        @Override public void rename(Path p, String n) { move(p, p.resolveSibling(n)); }
        @Override public void createDirectories(Path path) {
            Path cur = path;
            while (cur != null && !cur.equals(Path.of("/"))) {
                nodes.put(cur, true);
                cur = cur.getParent();
            }
        }
        @Override public void writeFile(Path p, byte[] b) { nodes.put(p, false); }
        @Override public com.organizer3.filesystem.FileTimestamps getTimestamps(Path p) {
            return new com.organizer3.filesystem.FileTimestamps(null, null, null);
        }
        @Override public void setTimestamps(Path p, java.time.Instant c, java.time.Instant m) {}
    }
}
