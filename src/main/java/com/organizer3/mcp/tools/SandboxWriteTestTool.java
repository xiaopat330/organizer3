package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.config.volume.OrganizerConfig;
import com.organizer3.config.volume.ServerConfig;
import com.organizer3.config.volume.VolumeConfig;
import com.organizer3.filesystem.VolumeFileSystem;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;
import com.organizer3.shell.SessionContext;
import com.organizer3.smb.VolumeConnection;
import com.organizer3.trash.Sandbox;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Diagnostic: exercise the full write-op surface (createDirectories, writeFile, move,
 * rename, read-back) against the currently-mounted volume's {@code _sandbox} area.
 *
 * <p>Purpose: verify that the configured SMB credentials actually allow write on a given
 * share. Different servers and shares can have different ACLs; this tool confirms each
 * volume is writable in a way that's isolated from user data (everything happens inside
 * {@code _sandbox}, which is app-owned and volatile by design).
 *
 * <p>Gated on {@code mcp.allowFileOps}. Requires the volume's server to have a
 * {@code sandbox:} folder configured.
 */
public class SandboxWriteTestTool implements Tool {

    private final SessionContext session;
    private final OrganizerConfig config;

    public SandboxWriteTestTool(SessionContext session, OrganizerConfig config) {
        this.session = session;
        this.config = config;
    }

    @Override public String name()        { return "sandbox_write_test"; }
    @Override public String description() {
        return "Run a createDirectories + writeFile + move + rename + read-back cycle inside "
             + "the mounted volume's _sandbox folder. Proves write-permission end-to-end.";
    }

    @Override
    public JsonNode inputSchema() {
        return Schemas.object().build();
    }

    @Override
    public Object call(JsonNode args) {
        String volumeId = session.getMountedVolumeId();
        if (volumeId == null) {
            throw new IllegalArgumentException("No volume is currently mounted.");
        }
        VolumeConnection conn = session.getActiveConnection();
        if (conn == null || !conn.isConnected()) {
            throw new IllegalArgumentException("Active connection is closed; re-mount the volume.");
        }
        VolumeConfig vol = config.findById(volumeId).orElseThrow(
                () -> new IllegalArgumentException("Volume not in config: " + volumeId));
        ServerConfig srv = config.findServerById(vol.server()).orElseThrow(
                () -> new IllegalArgumentException("Server not in config: " + vol.server()));
        if (srv.sandbox() == null || srv.sandbox().isBlank()) {
            throw new IllegalArgumentException(
                    "Server '" + srv.id() + "' has no 'sandbox:' folder configured.");
        }

        VolumeFileSystem fs = conn.fileSystem();
        Sandbox sandbox = new Sandbox(fs, srv.sandbox());
        String runId = "write-test-" + Instant.now().toEpochMilli();
        Path runDir = sandbox.resolve(runId);
        Path marker = runDir.resolve("marker.txt");
        Path movedInto = runDir.resolve("subdir");
        Path moved = movedInto.resolve("marker.txt");
        Path renamed = movedInto.resolve("renamed.txt");
        String payload = "sandbox write-test " + runId;
        byte[] body = payload.getBytes(StandardCharsets.UTF_8);

        List<Step> steps = new ArrayList<>();
        boolean ok = runSafely(steps, "ensure sandbox root",   () -> sandbox.ensureExists());
        if (ok) ok = runSafely(steps, "createDirectories run", () -> fs.createDirectories(runDir));
        if (ok) ok = runSafely(steps, "writeFile marker",      () -> fs.writeFile(marker, body));
        if (ok) ok = runSafely(steps, "createDirectories sub", () -> fs.createDirectories(movedInto));
        if (ok) ok = runSafely(steps, "move marker",           () -> fs.move(marker, moved));
        if (ok) ok = runSafely(steps, "rename moved",          () -> fs.rename(moved, "renamed.txt"));
        if (ok) ok = runSafely(steps, "readback renamed",      () -> assertReadback(fs, renamed, body));

        return new Result(volumeId, srv.sandbox(), runDir.toString(), ok, steps);
    }

    private static boolean runSafely(List<Step> steps, String label, IOAction action) {
        try {
            action.run();
            steps.add(new Step(label, true, null));
            return true;
        } catch (Exception e) {
            steps.add(new Step(label, false, describe(e)));
            return false;
        }
    }

    /** Build a string that includes the full cause chain — SMB errors nest their detail below the IOException wrapper. */
    private static String describe(Throwable e) {
        StringBuilder sb = new StringBuilder();
        Throwable cur = e;
        int depth = 0;
        while (cur != null && depth < 6) {
            if (depth > 0) sb.append(" | caused by ");
            sb.append(cur.getClass().getSimpleName()).append(": ").append(cur.getMessage());
            cur = cur.getCause();
            depth++;
        }
        return sb.toString();
    }

    private static void assertReadback(VolumeFileSystem fs, Path path, byte[] expected) throws IOException {
        try (InputStream in = fs.openFile(path)) {
            byte[] actual = in.readAllBytes();
            if (actual.length != expected.length) {
                throw new IOException("readback length mismatch: expected " + expected.length + ", got " + actual.length);
            }
            for (int i = 0; i < actual.length; i++) {
                if (actual[i] != expected[i]) {
                    throw new IOException("readback byte mismatch at offset " + i);
                }
            }
        }
    }

    @FunctionalInterface
    private interface IOAction {
        void run() throws IOException;
    }

    public record Step(String op, boolean ok, String error) {}
    public record Result(String volumeId, String sandboxFolder, String runDir, boolean success, List<Step> steps) {}
}
