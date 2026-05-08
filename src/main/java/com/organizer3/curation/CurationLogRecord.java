package com.organizer3.curation;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Immutable record of a single destructive curation operation written to the curation log.
 *
 * <p>One record per JSONL line in {@code <dataDir>/curation-log/<volumeId>/<YYYY-MM-DD>.jsonl}.
 * The {@code ts} field drives which day-file the record lands in.
 *
 * <p>Fields {@code plan}, {@code before}, and {@code after} are nullable — {@code after} is
 * always null on dry-run executions. {@code errors} is never null but may be empty.
 */
public record CurationLogRecord(
        Instant ts,
        String tool,
        String actor,
        String sessionId,
        Map<String, Object> inputs,
        Map<String, Object> plan,
        Map<String, Object> before,
        Map<String, Object> after,
        String status,
        List<Object> errors
) {
    public CurationLogRecord {
        if (errors == null) errors = List.of();
    }
}
