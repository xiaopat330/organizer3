package com.organizer3.utilities.health;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The output of one scan — a map from check id to its result, plus the run that produced it.
 * Held briefly in memory by {@link LibraryHealthService} so the right-pane detail endpoints
 * can read a consistent snapshot without re-running checks.
 */
public record LibraryHealthReport(
        String runId,
        Instant scannedAt,
        Map<String, CheckEntry> checks
) {

    /** A single check's entry in the report. Carries identity + the run result. */
    public record CheckEntry(
            String id,
            String label,
            String description,
            LibraryHealthCheck.FixRouting fixRouting,
            LibraryHealthCheck.CheckResult result
    ) {}

    public static LibraryHealthReport empty(String runId) {
        return new LibraryHealthReport(runId, Instant.now(), new LinkedHashMap<>());
    }
}
