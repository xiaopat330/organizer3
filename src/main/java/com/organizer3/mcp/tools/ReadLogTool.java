package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Read recent entries from the app's rolling log file ({@code logs/organizer3.log}),
 * optionally filtered by minimum level and/or a regex, so an agent can correlate a
 * user action to log evidence without shell access.
 *
 * <p>Level filter applies to logback's pattern output (WARN, INFO, DEBUG, etc.);
 * lines without a parseable level (stack-trace continuations) are always included
 * when they follow a matched line so tracebacks aren't truncated.
 *
 * <p>Read-only: there is no rotate / clear capability.
 */
public class ReadLogTool implements Tool {

    /** Caller-supplied {@code lines} is clamped to this. Keeps the agent's context bounded. */
    private static final int MAX_LINES = 2000;
    private static final int DEFAULT_LINES = 200;

    /** Logback default pattern: "yyyy-MM-dd HH:mm:ss [thread] LEVEL logger - msg" */
    private static final Pattern LINE_RE =
            Pattern.compile("^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\s+\\[[^\\]]*\\]\\s+(TRACE|DEBUG|INFO|WARN|ERROR)\\b");

    private static final List<String> LEVELS = List.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR");

    private final Path logFile;

    public ReadLogTool(Path logFile) {
        this.logFile = logFile;
    }

    @Override public String name()        { return "read_log"; }
    @Override public String description() {
        return "Read recent lines from logs/organizer3.log. Use to correlate a user action "
             + "or mutation to log evidence (merges, FS moves, tag edits, thumbnail gens, "
             + "sync summaries, etc.). Filters: minimum level, regex match. Read-only.";
    }

    @Override
    public JsonNode inputSchema() {
        return Schemas.object()
                .prop("lines",    "integer", "Max matching lines to return (1.." + MAX_LINES + ").", DEFAULT_LINES)
                .prop("levelMin", "string",  "Minimum level: TRACE|DEBUG|INFO|WARN|ERROR. Lines at or above this level pass.", "INFO")
                .prop("grep",     "string",  "Optional regex (Java); only lines matching are kept. Continuation lines (stack traces) are kept with their parent.")
                .build();
    }

    @Override
    public Object call(JsonNode args) {
        int want = Schemas.optInt(args, "lines", DEFAULT_LINES);
        want = Math.max(1, Math.min(MAX_LINES, want));
        String levelMin = Schemas.optString(args, "levelMin", "INFO").toUpperCase();
        int minRank = Math.max(0, LEVELS.indexOf(levelMin));
        String grepRaw = Schemas.optString(args, "grep", null);
        Pattern grep;
        try {
            grep = (grepRaw == null || grepRaw.isBlank()) ? null : Pattern.compile(grepRaw);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("Invalid grep regex: " + e.getMessage());
        }

        if (!Files.isRegularFile(logFile)) {
            return new Result(false, logFile.toString(), 0, 0, List.of());
        }

        List<String> recent;
        try {
            recent = readLastLines(logFile);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read log file: " + e.getMessage(), e);
        }

        // Filter newest-to-oldest so 'want' caps at the N most recent matches,
        // but emit oldest-to-newest for a natural read order.
        Deque<String> kept = new ArrayDeque<>();
        boolean lastMatched = false;
        for (int i = recent.size() - 1; i >= 0 && kept.size() < want; i--) {
            String line = recent.get(i);
            int lineRank = rankOf(line);
            boolean isContinuation = lineRank < 0;
            boolean levelOk  = isContinuation || lineRank >= minRank;
            boolean grepOk   = (grep == null) || grep.matcher(line).find();
            boolean matched;
            if (isContinuation) {
                // Continuation lines are emitted alongside their parent match.
                matched = lastMatched;
            } else {
                matched = levelOk && grepOk;
                lastMatched = matched;
            }
            if (matched) kept.push(line);
        }

        long sizeBytes = 0;
        try { sizeBytes = Files.size(logFile); } catch (IOException ignore) {}
        return new Result(true, logFile.toString(), sizeBytes, kept.size(), new ArrayList<>(kept));
    }

    private static int rankOf(String line) {
        var m = LINE_RE.matcher(line);
        if (!m.find()) return -1;
        return LEVELS.indexOf(m.group(1));
    }

    /**
     * Read up to {@link #MAX_LINES}×4 most recent lines from the tail of the file. We over-read
     * because level/grep filters may discard most of what we pulled; 8k lines of raw tail is a
     * few MB at most for our logging pattern.
     */
    private static List<String> readLastLines(Path file) throws IOException {
        int targetLines = MAX_LINES * 4;
        long size = Files.size(file);
        // Start near the end; read back in 64KB chunks until we have enough newlines or hit BOF.
        int chunk = 64 * 1024;
        long pos = Math.max(0, size - chunk);
        byte[] buf = new byte[chunk];
        StringBuilder collected = new StringBuilder();
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            while (true) {
                int readLen = (int) Math.min(chunk, size - pos);
                raf.seek(pos);
                int n = 0;
                while (n < readLen) {
                    int r = raf.read(buf, n, readLen - n);
                    if (r < 0) break;
                    n += r;
                }
                collected.insert(0, new String(buf, 0, n, StandardCharsets.UTF_8));
                if (countNewlines(collected) >= targetLines || pos == 0) break;
                pos = Math.max(0, pos - chunk);
                if (pos == 0) {
                    // next loop reads [0, chunk) and then stops because pos == 0
                    continue;
                }
            }
        }
        String[] lines = collected.toString().split("\\R", -1);
        // Drop a possibly-partial leading line (we sliced mid-line on the first chunk).
        int from = (collected.length() > 0 && lines.length > 1) ? 1 : 0;
        int start = Math.max(from, lines.length - targetLines);
        // Drop trailing empty line from trailing newline.
        int end = lines.length;
        if (end > 0 && lines[end - 1].isEmpty()) end--;
        List<String> result = new ArrayList<>(end - start);
        for (int i = start; i < end; i++) result.add(lines[i]);
        return result;
    }

    private static int countNewlines(CharSequence s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == '\n') n++;
        return n;
    }

    public record Result(boolean found, String path, long fileSizeBytes,
                         int returnedLines, List<String> lines) {}
}
