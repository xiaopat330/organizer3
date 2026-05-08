package com.organizer3.curation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class CurationLogTest {

    @TempDir
    Path tempDir;

    private CurationLog log;

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @BeforeEach
    void setUp() {
        log = new CurationLog(tempDir);
    }

    private CurationLogRecord minimalRecord(String tool, String status, Instant ts) {
        return new CurationLogRecord(
                ts,
                tool,
                "mcp",
                "session-abc",
                Map.of("actressId", 42),
                null,
                null,
                null,
                status,
                List.of()
        );
    }

    // ── Test 1: append writes valid JSONL (one record = one line ending with newline) ──────────

    @Test
    void appendWritesSingleValidJsonlLine() throws Exception {
        Instant ts = Instant.parse("2026-05-09T20:14:33Z");
        log.append("s", minimalRecord("rename_actress_folder", "ok", ts));

        Path dayFile = tempDir.resolve("curation-log/s/2026-05-09.jsonl");
        assertTrue(Files.exists(dayFile), "Day file should exist");

        String content = Files.readString(dayFile);
        String[] lines = content.split("\n", -1);
        // last element after split is empty string (trailing newline)
        assertEquals(2, lines.length, "Should be exactly one line + trailing newline");
        assertEquals("", lines[1], "File should end with newline");

        var parsed = MAPPER.readTree(lines[0]);
        assertEquals("rename_actress_folder", parsed.get("tool").asText());
        assertEquals("ok", parsed.get("status").asText());
        assertEquals("2026-05-09T20:14:33Z", parsed.get("ts").asText());
    }

    // ── Test 2: multiple appends within same day land in same file ────────────────────────────

    @Test
    void multipleAppendsSameDayLandInSameFile() throws Exception {
        Instant ts = Instant.parse("2026-05-09T10:00:00Z");
        log.append("s", minimalRecord("rename_actress_folder", "ok", ts));
        log.append("s", minimalRecord("rename_actress_folder", "dry-run", ts));
        log.append("s", minimalRecord("move_title_folder", "ok", ts));

        Path dayFile = tempDir.resolve("curation-log/s/2026-05-09.jsonl");
        List<String> lines = Files.readAllLines(dayFile);
        assertEquals(3, lines.size(), "All three records should be in the same file");
    }

    // ── Test 3: appends on different ts dates land in different files ─────────────────────────

    @Test
    void appendsDifferentDatesLandInDifferentFiles() throws Exception {
        Instant ts1 = Instant.parse("2026-05-08T23:59:59Z");
        Instant ts2 = Instant.parse("2026-05-09T00:00:01Z");

        log.append("s", minimalRecord("rename_actress_folder", "ok", ts1));
        log.append("s", minimalRecord("rename_actress_folder", "ok", ts2));

        Path file1 = tempDir.resolve("curation-log/s/2026-05-08.jsonl");
        Path file2 = tempDir.resolve("curation-log/s/2026-05-09.jsonl");

        assertTrue(Files.exists(file1), "May 8 file should exist");
        assertTrue(Files.exists(file2), "May 9 file should exist");
        assertEquals(1, Files.readAllLines(file1).size());
        assertEquals(1, Files.readAllLines(file2).size());
    }

    // ── Test 4: concurrent appends produce correct count, no torn lines ───────────────────────

    @Test
    void concurrentAppendsProduceNoTornLines() throws Exception {
        int threads = 10;
        int recordsPerThread = 50;
        Instant ts = Instant.parse("2026-05-09T12:00:00Z");

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Exception> failures = new ArrayList<>();

        for (int t = 0; t < threads; t++) {
            final int threadId = t;
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    for (int i = 0; i < recordsPerThread; i++) {
                        CurationLogRecord r = new CurationLogRecord(
                                ts, "rename_actress_folder", "mcp",
                                "session-" + threadId + "-" + i,
                                Map.of("thread", threadId, "seq", i),
                                null, null, null, "ok", List.of());
                        log.append("s", r);
                    }
                } catch (Exception e) {
                    synchronized (failures) { failures.add(e); }
                }
            });
        }

        ready.await();
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "Pool should finish within 30s");

        assertTrue(failures.isEmpty(), "No threads should have thrown: " + failures);

        Path dayFile = tempDir.resolve("curation-log/s/2026-05-09.jsonl");
        List<String> lines = Files.readAllLines(dayFile);
        assertEquals(threads * recordsPerThread, lines.size(),
                "Should have exactly " + (threads * recordsPerThread) + " lines");

        // Every line must parse as valid JSON
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            assertFalse(line.isBlank(), "Line " + i + " should not be blank");
            assertDoesNotThrow(() -> MAPPER.readTree(line), "Line " + i + " should be valid JSON");
        }
    }

    // ── Test 5: null optional fields serialize correctly ─────────────────────────────────────

    @Test
    void nullOptionalFieldsSerializeCorrectly() throws Exception {
        Instant ts = Instant.parse("2026-05-09T15:00:00Z");
        CurationLogRecord record = new CurationLogRecord(
                ts,
                "consolidate_actress_folders",
                "mcp",
                "session-xyz",
                Map.of("actressId", 99),
                null,   // plan
                null,   // before
                null,   // after (dry-run)
                "dry-run",
                List.of()  // errors
        );
        log.append("vol1", record);

        Path dayFile = tempDir.resolve("curation-log/vol1/2026-05-09.jsonl");
        String line = Files.readAllLines(dayFile).get(0);
        var parsed = MAPPER.readTree(line);

        assertTrue(parsed.has("plan"),   "plan field should be present");
        assertTrue(parsed.get("plan").isNull(), "plan should be null");
        assertTrue(parsed.has("before"), "before field should be present");
        assertTrue(parsed.get("before").isNull(), "before should be null");
        assertTrue(parsed.has("after"),  "after field should be present");
        assertTrue(parsed.get("after").isNull(), "after should be null");
        assertTrue(parsed.has("errors"), "errors field should be present");
        assertTrue(parsed.get("errors").isArray(), "errors should be array");
        assertEquals(0, parsed.get("errors").size(), "errors should be empty array");
        assertEquals("dry-run", parsed.get("status").asText());
    }

    // ── Test 6: different volumeIds write to different files ─────────────────────────────────

    @Test
    void differentVolumeIdsWriteToDifferentFiles() throws Exception {
        Instant ts = Instant.parse("2026-05-09T18:00:00Z");

        log.append("vol-a", minimalRecord("rename_actress_folder", "ok", ts));
        log.append("vol-b", minimalRecord("rename_actress_folder", "ok", ts));
        log.append("vol-a", minimalRecord("move_title_folder", "ok", ts));

        Path fileA = tempDir.resolve("curation-log/vol-a/2026-05-09.jsonl");
        Path fileB = tempDir.resolve("curation-log/vol-b/2026-05-09.jsonl");

        assertTrue(Files.exists(fileA), "vol-a file should exist");
        assertTrue(Files.exists(fileB), "vol-b file should exist");
        assertEquals(2, Files.readAllLines(fileA).size(), "vol-a should have 2 records");
        assertEquals(1, Files.readAllLines(fileB).size(), "vol-b should have 1 record");
    }
}
