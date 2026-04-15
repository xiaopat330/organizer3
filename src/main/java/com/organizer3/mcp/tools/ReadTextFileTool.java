package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.filesystem.VolumeFileSystem;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;
import com.organizer3.shell.SessionContext;
import com.organizer3.smb.VolumeConnection;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/**
 * Read a text file from the currently-mounted volume, capped at a configurable byte budget.
 *
 * <p>Intended for inspecting NFO files, README-style text, small JSON/YAML sidecars, etc.
 * Enforces an extension allowlist to protect against being asked to read a 40 GB MP4.
 */
public class ReadTextFileTool implements Tool {

    private static final int DEFAULT_MAX_BYTES = 32 * 1024;
    private static final int HARD_MAX_BYTES = 256 * 1024;

    /** Extensions considered safe to pipe through the MCP response as UTF-8 text. */
    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            "txt", "nfo", "json", "yaml", "yml", "md", "log", "csv", "xml", "ini"
    );

    private final SessionContext session;

    public ReadTextFileTool(SessionContext session) { this.session = session; }

    @Override public String name()        { return "read_text_file"; }
    @Override public String description() { return "Read up to N bytes of a small text file from the currently-mounted volume. Extension must be a known text type (nfo, txt, json, yaml, md, log, csv, xml, ini)."; }

    @Override
    public JsonNode inputSchema() {
        return Schemas.object()
                .prop("volumeId", "string",  "Volume id the file lives on. Must match the currently-mounted volume.")
                .prop("path",     "string",  "Volume-relative file path.")
                .prop("maxBytes", "integer", "Maximum bytes to read (default 32 KB, max 256 KB).", DEFAULT_MAX_BYTES)
                .require("volumeId", "path")
                .build();
    }

    @Override
    public Object call(JsonNode args) throws IOException {
        String volumeId = Schemas.requireString(args, "volumeId");
        String rawPath  = Schemas.requireString(args, "path");
        int maxBytes = Math.max(1, Math.min(Schemas.optInt(args, "maxBytes", DEFAULT_MAX_BYTES), HARD_MAX_BYTES));

        VolumeFileSystem fs = requireActiveVolume(volumeId);
        Path path = ListDirectoryTool.sanitizeRelative(rawPath);

        String ext = extensionOf(path.toString());
        if (!TEXT_EXTENSIONS.contains(ext)) {
            throw new IllegalArgumentException(
                    "Extension '" + ext + "' is not in the text allowlist " + TEXT_EXTENSIONS);
        }
        if (!fs.exists(path)) {
            throw new IllegalArgumentException("File does not exist: " + path);
        }
        if (fs.isDirectory(path)) {
            throw new IllegalArgumentException("Path is a directory, not a file: " + path);
        }

        byte[] buffer = new byte[maxBytes];
        int totalRead = 0;
        boolean truncated = false;
        try (InputStream in = fs.openFile(path)) {
            while (totalRead < maxBytes) {
                int n = in.read(buffer, totalRead, maxBytes - totalRead);
                if (n < 0) break;
                totalRead += n;
            }
            // Probe one more byte to see whether there's more
            if (totalRead == maxBytes && in.read() >= 0) truncated = true;
        }

        String text = new String(buffer, 0, totalRead, StandardCharsets.UTF_8);
        return new Result(volumeId, path.toString(), totalRead, truncated, text);
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

    private static String extensionOf(String path) {
        int dot = path.lastIndexOf('.');
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        if (dot < 0 || dot < slash) return "";
        return path.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    public record Result(
            String volumeId,
            String path,
            int bytesRead,
            boolean truncated,
            String text
    ) {}
}
