package com.organizer3.web.routes;

import io.javalin.Javalin;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;

/**
 * GET /api/logs/tail?since=&lt;long&gt; — streams incremental content from the active
 * app log file ({@code logs/organizer3.log}) for the in-app Logs viewer.
 *
 * <p>Response: {@code { size: <long>, offset: <long>, content: <string>, truncated: <bool>, rotated: <bool> }}
 * where {@code offset} is the new read cursor the client should pass on next poll.
 *
 * <p>Behaviour:
 * <ul>
 *   <li>No {@code since} or {@code since >= size} → returns the last {@link #INITIAL_TAIL_BYTES}
 *       of the file (trimmed to a line boundary).</li>
 *   <li>{@code since > size} → treated as log rotation: returns from offset 0, {@code rotated: true}.</li>
 *   <li>Chunk capped at {@link #MAX_CHUNK_BYTES}; {@code truncated} signals the client to poll again.</li>
 * </ul>
 */
public class LogRoutes {

    private static final int INITIAL_TAIL_BYTES = 32 * 1024;
    private static final int MAX_CHUNK_BYTES    = 256 * 1024;

    private final Path logFile;

    public LogRoutes(Path logFile) {
        this.logFile = logFile;
    }

    public void register(Javalin app) {
        app.get("/api/logs/tail", ctx -> {
            var result = new LinkedHashMap<String, Object>();
            if (!Files.isRegularFile(logFile)) {
                result.put("size", 0L);
                result.put("offset", 0L);
                result.put("content", "");
                result.put("truncated", false);
                result.put("rotated", false);
                result.put("missing", true);
                ctx.json(result);
                return;
            }

            long size = Files.size(logFile);
            Long sinceParam = parseLong(ctx.queryParam("since"));
            long start;
            boolean rotated = false;
            if (sinceParam == null || sinceParam >= size) {
                // Initial load or caller caught up — return recent tail.
                start = Math.max(0, size - INITIAL_TAIL_BYTES);
            } else if (sinceParam < 0) {
                start = Math.max(0, size - INITIAL_TAIL_BYTES);
                rotated = true;
            } else {
                start = sinceParam;
            }

            long end = Math.min(size, start + MAX_CHUNK_BYTES);
            boolean truncated = end < size;
            byte[] bytes = readRange(logFile, start, (int) (end - start));
            String content = new String(bytes, StandardCharsets.UTF_8);

            // If we started mid-file without a prior cursor, drop leading partial line.
            if ((sinceParam == null || sinceParam >= size) && start > 0) {
                int nl = content.indexOf('\n');
                if (nl >= 0 && nl + 1 < content.length()) {
                    content = content.substring(nl + 1);
                }
            }

            result.put("size", size);
            result.put("offset", end);
            result.put("content", content);
            result.put("truncated", truncated);
            result.put("rotated", rotated);
            result.put("missing", false);
            ctx.json(result);
        });
    }

    private static Long parseLong(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Long.parseLong(s.trim()); }
        catch (NumberFormatException e) { return null; }
    }

    private static byte[] readRange(Path file, long offset, int length) throws IOException {
        if (length <= 0) return new byte[0];
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            raf.seek(offset);
            byte[] buf = new byte[length];
            int read = 0;
            while (read < length) {
                int n = raf.read(buf, read, length - read);
                if (n < 0) break;
                read += n;
            }
            if (read == length) return buf;
            byte[] trimmed = new byte[read];
            System.arraycopy(buf, 0, trimmed, 0, read);
            return trimmed;
        }
    }
}
