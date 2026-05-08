package com.organizer3.curation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.ZoneOffset;

/**
 * Append-only JSONL writer for the destructive-operation audit trail.
 *
 * <p>Output layout: {@code <dataDir>/curation-log/<volumeId>/<YYYY-MM-DD>.jsonl}.
 * Day-rolling is driven by the record's {@code ts} field in UTC, not wall-clock time,
 * so records with back-dated timestamps land in the correct file.
 *
 * <p>Concurrent callers are safe: {@link #append} is synchronized so no two threads
 * can interleave bytes into the same JSONL line.
 */
@Slf4j
public class CurationLog {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final Path logRoot;

    /**
     * @param dataDir the application data directory (as resolved by {@code Application.java}'s
     *                {@code resolveDataDir}); the curation-log subtree is created on first use.
     */
    public CurationLog(Path dataDir) {
        this.logRoot = dataDir.resolve("curation-log");
    }

    /**
     * Appends one JSONL record to the volume's day-file, creating it if absent.
     *
     * <p>The call is synchronized to prevent torn lines under concurrent MCP invocations.
     *
     * @param volumeId the mounted volume identifier (e.g. {@code "s"})
     * @param record   the fully-populated curation record
     * @throws RuntimeException wrapping {@link IOException} if the write fails
     */
    public synchronized void append(String volumeId, CurationLogRecord record) {
        Path dayFile = resolveFile(volumeId, record);
        try {
            Files.createDirectories(dayFile.getParent());
            String line = MAPPER.writeValueAsString(record) + "\n";
            Files.writeString(dayFile, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            log.info("Curation log [{}] {} status={} → {}",
                    volumeId, record.tool(), record.status(), dayFile.getFileName());
        } catch (IOException e) {
            throw new RuntimeException("Failed to append curation log record for volume=" + volumeId, e);
        }
    }

    private Path resolveFile(String volumeId, CurationLogRecord record) {
        String date = record.ts().atZone(ZoneOffset.UTC).toLocalDate().toString();
        return logRoot.resolve(volumeId).resolve(date + ".jsonl");
    }
}
