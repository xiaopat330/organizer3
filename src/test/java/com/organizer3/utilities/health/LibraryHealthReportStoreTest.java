package com.organizer3.utilities.health;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class LibraryHealthReportStoreTest {

    @TempDir Path tempDir;

    private LibraryHealthReportStore store() {
        return new LibraryHealthReportStore(tempDir.resolve("health.json"));
    }

    @Test
    void loadReturnsEmptyWhenFileDoesNotExist() {
        assertTrue(store().load().isEmpty());
    }

    @Test
    void roundTripsReport() {
        LibraryHealthReportStore store = store();
        LibraryHealthReport report = buildReport("run-1", 5, 0);
        store.save(report);

        Optional<LibraryHealthReport> loaded = store.load();

        assertTrue(loaded.isPresent());
        LibraryHealthReport r = loaded.get();
        assertEquals("run-1", r.runId());
        assertEquals(5, r.checks().get("check_a").result().total());
        assertEquals(0, r.checks().get("check_b").result().total());
    }

    @Test
    void persistedTimestampIsPreserved() {
        LibraryHealthReportStore store = store();
        Instant before = Instant.now().minusSeconds(60);
        LibraryHealthReport report = new LibraryHealthReport("run-2", before,
                Map.of("x", new LibraryHealthReport.CheckEntry(
                        "x", "X", "desc", LibraryHealthCheck.FixRouting.SURFACE_ONLY,
                        LibraryHealthCheck.CheckResult.empty())));
        store.save(report);

        LibraryHealthReport loaded = store.load().orElseThrow();
        // Truncated to millisecond precision by JSON round-trip — compare at seconds
        assertEquals(before.getEpochSecond(), loaded.scannedAt().getEpochSecond());
    }

    @Test
    void findingsRoundTripCorrectly() {
        LibraryHealthReportStore store = store();
        var finding = new LibraryHealthCheck.Finding("f1", "Label", "Detail text");
        var result = new LibraryHealthCheck.CheckResult(1, List.of(finding));
        var entry = new LibraryHealthReport.CheckEntry(
                "chk", "Check", "desc", LibraryHealthCheck.FixRouting.INLINE, result);
        LibraryHealthReport report = new LibraryHealthReport("run-3", Instant.now(), Map.of("chk", entry));
        store.save(report);

        LibraryHealthReport loaded = store.load().orElseThrow();
        var loadedEntry = loaded.checks().get("chk");
        assertEquals(1, loadedEntry.result().total());
        assertEquals("f1", loadedEntry.result().rows().get(0).id());
        assertEquals("Label", loadedEntry.result().rows().get(0).label());
        assertEquals("Detail text", loadedEntry.result().rows().get(0).detail());
        assertEquals(LibraryHealthCheck.FixRouting.INLINE, loadedEntry.fixRouting());
    }

    @Test
    void subsequentSaveOverwritesPreviousReport() {
        LibraryHealthReportStore store = store();
        store.save(buildReport("run-1", 10, 10));
        store.save(buildReport("run-2", 0, 0));

        LibraryHealthReport loaded = store.load().orElseThrow();
        assertEquals("run-2", loaded.runId());
        assertEquals(0, loaded.checks().get("check_a").result().total());
    }

    @Test
    void saveCreatesParentDirectoriesIfMissing() {
        var nestedStore = new LibraryHealthReportStore(
                tempDir.resolve("deep").resolve("nested").resolve("health.json"));
        nestedStore.save(buildReport("run-1", 1, 0));
        assertTrue(nestedStore.load().isPresent());
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static LibraryHealthReport buildReport(String runId, int totalA, int totalB) {
        var a = new LibraryHealthReport.CheckEntry("check_a", "Check A", "desc a",
                LibraryHealthCheck.FixRouting.SURFACE_ONLY,
                new LibraryHealthCheck.CheckResult(totalA, List.of()));
        var b = new LibraryHealthReport.CheckEntry("check_b", "Check B", "desc b",
                LibraryHealthCheck.FixRouting.SURFACE_ONLY,
                new LibraryHealthCheck.CheckResult(totalB, List.of()));
        return new LibraryHealthReport(runId, Instant.now(), Map.of("check_a", a, "check_b", b));
    }
}
