package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.filesystem.VolumeFileSystem;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;
import com.organizer3.shell.SessionContext;
import com.organizer3.smb.VolumeConnection;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Non-recursive directory listing on the currently-mounted volume.
 *
 * <p>The MCP server does not own mount state — the user mounts volumes through the
 * interactive shell, and filesystem tools operate on whatever is currently mounted.
 * If no volume is mounted, or the requested {@code volumeId} doesn't match, the tool
 * returns an explanatory error rather than silently failing.
 *
 * <p>All paths are volume-relative. Absolute paths (leading with a separator) and
 * parent-traversal segments ({@code ..}) are rejected.
 */
public class ListDirectoryTool implements Tool {

    private final SessionContext session;

    public ListDirectoryTool(SessionContext session) { this.session = session; }

    @Override public String name()        { return "list_directory"; }
    @Override public String description() { return "List the immediate children of a directory on the currently-mounted volume. Paths are volume-relative."; }

    @Override
    public JsonNode inputSchema() {
        return Schemas.object()
                .prop("volumeId", "string", "Volume id the directory lives on. Must match the currently-mounted volume.")
                .prop("path",     "string", "Volume-relative path. Empty string or '/' means the share root.")
                .require("volumeId")
                .build();
    }

    @Override
    public Object call(JsonNode args) throws IOException {
        String volumeId = Schemas.requireString(args, "volumeId");
        String rawPath = Schemas.optString(args, "path", "");

        VolumeFileSystem fs = requireActiveVolume(volumeId);
        Path path = sanitizeRelative(rawPath);

        if (!fs.exists(path)) {
            throw new IllegalArgumentException("Path does not exist: " + path);
        }
        if (!fs.isDirectory(path)) {
            throw new IllegalArgumentException("Path is not a directory: " + path);
        }

        List<Path> children = fs.listDirectory(path);
        List<Entry> entries = new ArrayList<>(children.size());
        for (Path child : children) {
            boolean isDir = fs.isDirectory(child);
            String mtime = null;
            try {
                var date = fs.getLastModifiedDate(child);
                mtime = date == null ? null : date.toString();
            } catch (Exception e) {
                // swallow — mtime is informational, not worth failing on
            }
            entries.add(new Entry(
                    child.getFileName() == null ? child.toString() : child.getFileName().toString(),
                    child.toString(),
                    isDir,
                    mtime
            ));
        }
        return new Result(volumeId, path.toString(), entries.size(), entries);
    }

    private VolumeFileSystem requireActiveVolume(String volumeId) {
        String active = session.getMountedVolumeId();
        if (active == null) {
            throw new IllegalArgumentException(
                    "No volume is currently mounted. Mount one with the shell before using filesystem tools.");
        }
        if (!active.equals(volumeId)) {
            throw new IllegalArgumentException(
                    "Volume '" + volumeId + "' is not active. Currently mounted: " + active);
        }
        VolumeConnection conn = session.getActiveConnection();
        if (conn == null || !conn.isConnected()) {
            throw new IllegalArgumentException("Active connection is closed; re-mount the volume.");
        }
        return conn.fileSystem();
    }

    static Path sanitizeRelative(String raw) {
        if (raw == null) raw = "";
        String cleaned = raw.startsWith("/") ? raw.substring(1) : raw;
        Path p = Path.of(cleaned);
        for (Path segment : p) {
            if (segment.toString().equals("..")) {
                throw new IllegalArgumentException("Parent-traversal segments are not allowed");
            }
        }
        return p;
    }

    public record Result(String volumeId, String path, int total, List<Entry> entries) {}
    public record Entry(String name, String path, boolean isDirectory, String lastModified) {}
}
