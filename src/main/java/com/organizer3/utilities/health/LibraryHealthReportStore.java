package com.organizer3.utilities.health;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Persists the latest library health report as JSON under {@code dataDir}.
 *
 * <p>Uses private stored DTOs rather than annotating the domain model, so the
 * domain stays Jackson-free. Forward-compatible: unknown JSON fields are ignored,
 * and unrecognized {@code fixRouting} strings fall back to {@code SURFACE_ONLY}.
 *
 * <p>Writes are best-effort — a failed write logs an error but does not throw.
 */
@Slf4j
public final class LibraryHealthReportStore {

    private static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(SerializationFeature.INDENT_OUTPUT);

    private final Path storeFile;

    public LibraryHealthReportStore(Path storeFile) {
        this.storeFile = storeFile;
    }

    /** Returns the persisted report, or empty if no file exists or it cannot be read. */
    public Optional<LibraryHealthReport> load() {
        if (!Files.isRegularFile(storeFile)) return Optional.empty();
        try {
            StoredReport stored = JSON.readValue(storeFile.toFile(), StoredReport.class);
            return Optional.of(fromStored(stored));
        } catch (IOException e) {
            log.warn("Could not read library health report from {}: {}", storeFile, e.getMessage());
            return Optional.empty();
        }
    }

    /** Writes the report to disk. Logs on failure; never throws. */
    public void save(LibraryHealthReport report) {
        try {
            Files.createDirectories(storeFile.getParent());
            JSON.writeValue(storeFile.toFile(), toStored(report));
        } catch (IOException e) {
            log.error("Could not persist library health report to {}: {}", storeFile, e.getMessage());
        }
    }

    // ── Stored DTOs ──────────────────────────────────────────────────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    record StoredReport(String runId, Instant scannedAt, Map<String, StoredEntry> checks) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record StoredEntry(String id, String label, String description,
                       String fixRouting, StoredResult result) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record StoredResult(int total, List<StoredFinding> rows) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record StoredFinding(String id, String label, String detail) {}

    // ── Domain → stored ──────────────────────────────────────────────────────

    private static StoredReport toStored(LibraryHealthReport r) {
        Map<String, StoredEntry> entries = new LinkedHashMap<>();
        for (Map.Entry<String, LibraryHealthReport.CheckEntry> e : r.checks().entrySet()) {
            LibraryHealthReport.CheckEntry ce = e.getValue();
            entries.put(e.getKey(), new StoredEntry(
                    ce.id(), ce.label(), ce.description(),
                    ce.fixRouting() != null ? ce.fixRouting().name() : null,
                    toStoredResult(ce.result())));
        }
        return new StoredReport(r.runId(), r.scannedAt(), entries);
    }

    private static StoredResult toStoredResult(LibraryHealthCheck.CheckResult r) {
        if (r == null) return new StoredResult(0, List.of());
        List<StoredFinding> findings = r.rows() == null ? List.of()
                : r.rows().stream().map(f -> new StoredFinding(f.id(), f.label(), f.detail())).toList();
        return new StoredResult(r.total(), findings);
    }

    // ── Stored → domain ──────────────────────────────────────────────────────

    private static LibraryHealthReport fromStored(StoredReport s) {
        Map<String, LibraryHealthReport.CheckEntry> entries = new LinkedHashMap<>();
        if (s.checks() != null) {
            for (Map.Entry<String, StoredEntry> e : s.checks().entrySet()) {
                StoredEntry se = e.getValue();
                if (se == null) continue;
                entries.put(e.getKey(), new LibraryHealthReport.CheckEntry(
                        se.id(), se.label(), se.description(),
                        parseRouting(se.fixRouting()),
                        se.result() != null ? fromStoredResult(se.result())
                                            : LibraryHealthCheck.CheckResult.empty()));
            }
        }
        return new LibraryHealthReport(
                s.runId(),
                s.scannedAt() != null ? s.scannedAt() : Instant.EPOCH,
                entries);
    }

    private static LibraryHealthCheck.CheckResult fromStoredResult(StoredResult s) {
        List<LibraryHealthCheck.Finding> rows = s.rows() == null ? List.of()
                : s.rows().stream()
                          .filter(f -> f != null)
                          .map(f -> new LibraryHealthCheck.Finding(f.id(), f.label(), f.detail()))
                          .toList();
        return new LibraryHealthCheck.CheckResult(s.total(), rows);
    }

    private static LibraryHealthCheck.FixRouting parseRouting(String value) {
        if (value == null) return LibraryHealthCheck.FixRouting.SURFACE_ONLY;
        try {
            return LibraryHealthCheck.FixRouting.valueOf(value);
        } catch (IllegalArgumentException e) {
            return LibraryHealthCheck.FixRouting.SURFACE_ONLY;
        }
    }
}
