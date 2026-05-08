package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.organizer3.config.volume.VolumeConfig;
import com.organizer3.curation.CurationLog;
import com.organizer3.filesystem.FileTimestamps;
import com.organizer3.filesystem.VolumeFileSystem;
import com.organizer3.shell.SessionContext;
import com.organizer3.smb.VolumeConnection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WriteTextFileToolTest {

    @TempDir
    Path logDir;

    private static final ObjectMapper M = new ObjectMapper();

    private SessionContext session;
    private FakeFs fs;
    private CurationLog curationLog;
    private WriteTextFileTool tool;

    @BeforeEach
    void setUp() {
        fs           = new FakeFs();
        curationLog  = new CurationLog(logDir);
        session      = new SessionContext();
        session.setMountedVolume(new VolumeConfig("s", "//host/s", "conventional", "host", null));
        session.setActiveConnection(new FakeConnection(fs));
        tool = new WriteTextFileTool(session, curationLog);
    }

    // ── happy path ────────────────────────────────────────────────────────────

    @Test
    void writesFileToAllowedPath() {
        var result = (WriteTextFileTool.Result) tool.call(args("s", "/attention/Foo/NOTES.md", "hello", false));
        assertEquals("ok", result.status());
        assertEquals("/attention/Foo/NOTES.md", result.path());
        assertEquals("hello".getBytes().length, result.bytes());
        assertNull(result.error());

        assertEquals(1, fs.writeCalls.size());
        assertEquals(Path.of("/attention/Foo/NOTES.md"), fs.writeCalls.get(0).path());
        assertEquals("hello", new String(fs.writeCalls.get(0).contents()));
    }

    @Test
    void createsParentDirectories() {
        tool.call(args("s", "/queue/Foo Bar/NOTES.txt", "content", false));
        assertTrue(fs.createDirCalls.contains(Path.of("/queue/Foo Bar")));
    }

    @Test
    void writesToQueuePrefix() {
        var result = (WriteTextFileTool.Result) tool.call(args("s", "/queue/Notes.txt", "x", false));
        assertEquals("ok", result.status());
    }

    @Test
    void writesToSandboxPrefix() {
        var result = (WriteTextFileTool.Result) tool.call(args("s", "/_sandbox/test.log", "debug", false));
        assertEquals("ok", result.status());
    }

    @Test
    void emitsCurationLogOnOk() throws Exception {
        tool.call(args("s", "/attention/Foo/NOTES.md", "hello", false));

        // curation log file should exist for today
        Path logFile = logDir.resolve("curation-log/s")
                .resolve(java.time.LocalDate.now() + ".jsonl");
        assertTrue(Files.exists(logFile), "curation log file should exist");
        List<String> lines = Files.readAllLines(logFile);
        assertEquals(1, lines.size());
        var parsed = new com.fasterxml.jackson.databind.ObjectMapper().readTree(lines.get(0));
        assertEquals("write_text_file", parsed.get("tool").asText());
        assertEquals("ok", parsed.get("status").asText());
    }

    // ── refusals ─────────────────────────────────────────────────────────────

    @Test
    void refusesDisallowedTopLevelPrefix() {
        var result = (WriteTextFileTool.Result) tool.call(args("s", "/stars/Foo/NOTES.md", "x", false));
        assertEquals("refused", result.status());
        assertNotNull(result.error());
        assertTrue(result.error().contains("stars"));
    }

    @Test
    void refusesDisallowedExtension() {
        var result = (WriteTextFileTool.Result) tool.call(args("s", "/attention/Foo/script.sh", "#!/bin/bash", false));
        assertEquals("refused", result.status());
        assertTrue(result.error().contains(".sh") || result.error().contains("extension"));
    }

    @Test
    void refusesPathTraversal() {
        var result = (WriteTextFileTool.Result) tool.call(args("s", "/attention/../stars/evil.txt", "x", false));
        assertEquals("refused", result.status());
        assertTrue(result.error().contains(".."));
    }

    @Test
    void refusesOverwriteWhenFalse() {
        fs.existingPaths.add(Path.of("/attention/Foo/NOTES.md"));
        var result = (WriteTextFileTool.Result) tool.call(args("s", "/attention/Foo/NOTES.md", "x", false));
        assertEquals("refused", result.status());
        assertTrue(result.error().contains("overwrite") || result.error().contains("exists"));
    }

    @Test
    void allowsOverwriteWhenTrue() {
        fs.existingPaths.add(Path.of("/attention/Foo/NOTES.md"));
        var result = (WriteTextFileTool.Result) tool.call(args("s", "/attention/Foo/NOTES.md", "new content", true));
        assertEquals("ok", result.status());
    }

    @Test
    void refusesVolumeMismatch() {
        var result = (WriteTextFileTool.Result) tool.call(args("r", "/attention/Foo/NOTES.md", "x", false));
        assertEquals("refused", result.status());
        assertTrue(result.error().contains("mismatch") || result.error().contains("r"));
    }

    @Test
    void refusesWhenNoVolumeMounted() {
        session.setMountedVolume(null);
        session.setActiveConnection(null);
        var result = (WriteTextFileTool.Result) tool.call(args("s", "/attention/Foo/NOTES.md", "x", false));
        assertEquals("refused", result.status());
    }

    @Test
    void refusesTooShallowPath() {
        // path with only 1 segment = just a top-level name, no parent folder
        var result = (WriteTextFileTool.Result) tool.call(args("s", "/attention", "x", false));
        assertEquals("refused", result.status());
    }

    @Test
    void emitsCurationLogOnRefusal() throws Exception {
        tool.call(args("s", "/stars/Foo/NOTES.md", "x", false));
        Path logFile = logDir.resolve("curation-log/s")
                .resolve(java.time.LocalDate.now() + ".jsonl");
        assertTrue(Files.exists(logFile));
        var parsed = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(Files.readAllLines(logFile).get(0));
        assertEquals("write_text_file", parsed.get("tool").asText());
        assertEquals("failed", parsed.get("status").asText());
    }

    @Test
    void returnsFailedStatusOnIoException() {
        fs.failOnWrite = true;
        var result = (WriteTextFileTool.Result) tool.call(args("s", "/attention/Foo/NOTES.md", "x", false));
        assertEquals("failed", result.status());
        assertNotNull(result.error());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static ObjectNode args(String volumeId, String path, String content, boolean overwrite) {
        ObjectNode n = M.createObjectNode();
        n.put("volumeId", volumeId);
        n.put("path",     path);
        n.put("content",  content);
        n.put("overwrite", overwrite);
        return n;
    }

    private static final class FakeConnection implements VolumeConnection {
        private final VolumeFileSystem fs;
        FakeConnection(VolumeFileSystem fs) { this.fs = fs; }
        @Override public VolumeFileSystem fileSystem() { return fs; }
        @Override public boolean isConnected() { return true; }
        @Override public void close() {}
    }

    static final class FakeFs implements VolumeFileSystem {
        record WriteCall(Path path, byte[] contents) {}

        final List<WriteCall> writeCalls = new ArrayList<>();
        final List<Path>      createDirCalls = new ArrayList<>();
        final java.util.Set<Path> existingPaths = new java.util.HashSet<>();
        boolean failOnWrite = false;

        @Override public boolean exists(Path path) { return existingPaths.contains(path); }
        @Override public boolean isDirectory(Path path) { return false; }

        @Override
        public void writeFile(Path path, byte[] contents) throws IOException {
            if (failOnWrite) throw new IOException("simulated write failure");
            writeCalls.add(new WriteCall(path, contents));
        }

        @Override
        public void createDirectories(Path path) throws IOException {
            createDirCalls.add(path);
        }

        @Override public List<Path> listDirectory(Path path) { return List.of(); }
        @Override public List<Path> walk(Path root) { return List.of(); }
        @Override public LocalDate getLastModifiedDate(Path path) { return null; }
        @Override public InputStream openFile(Path path) { throw new UnsupportedOperationException(); }
        @Override public long size(Path path) { return 0; }
        @Override public void move(Path source, Path destination) { throw new UnsupportedOperationException(); }
        @Override public void rename(Path path, String newName) { throw new UnsupportedOperationException(); }
        @Override public FileTimestamps getTimestamps(Path path) { return null; }
        @Override public void setTimestamps(Path path, Instant created, Instant modified) {}
    }
}
