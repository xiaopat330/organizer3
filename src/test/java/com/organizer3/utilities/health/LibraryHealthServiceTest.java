package com.organizer3.utilities.health;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class LibraryHealthServiceTest {

    @TempDir Path tempDir;

    private LibraryHealthService service(LibraryHealthCheck... checks) {
        return new LibraryHealthService(List.of(checks),
                new LibraryHealthReportStore(tempDir.resolve("health.json")));
    }

    @Test
    void runsEveryCheckAndStoresLatestReport() {
        LibraryHealthCheck c1 = stubCheck("a", "A", 3);
        LibraryHealthCheck c2 = stubCheck("b", "B", 0);
        LibraryHealthService svc = service(c1, c2);

        List<String> visited = new ArrayList<>();
        AtomicInteger afterCount = new AtomicInteger();
        LibraryHealthReport report = svc.scan("run-1",
                c -> visited.add(c.id()),
                (c, r, err) -> { afterCount.incrementAndGet(); assertNull(err); });

        assertEquals(List.of("a", "b"), visited);
        assertEquals(2, afterCount.get());
        assertEquals(3, report.checks().get("a").result().total());
        assertEquals(0, report.checks().get("b").result().total());
        assertTrue(svc.latest().isPresent());
        assertEquals("run-1", svc.latest().get().runId());
    }

    @Test
    void failingCheckDoesNotHaltScan() {
        LibraryHealthCheck boom = new LibraryHealthCheck() {
            @Override public String id() { return "boom"; }
            @Override public String label() { return "Boom"; }
            @Override public String description() { return ""; }
            @Override public FixRouting fixRouting() { return FixRouting.SURFACE_ONLY; }
            @Override public CheckResult run() { throw new RuntimeException("kaboom"); }
        };
        LibraryHealthCheck ok = stubCheck("ok", "OK", 1);
        LibraryHealthService svc = service(boom, ok);

        List<Exception> errors = new ArrayList<>();
        svc.scan("run-2",
                c -> {},
                (c, r, err) -> { if (err != null) errors.add(err); });

        assertEquals(1, errors.size());
        assertEquals("kaboom", errors.get(0).getMessage());
        assertEquals(0, svc.latest().get().checks().get("boom").result().total());
        assertEquals(1, svc.latest().get().checks().get("ok").result().total());
    }

    @Test
    void findLooksUpByCheckId() {
        LibraryHealthCheck c1 = stubCheck("x", "X", 0);
        LibraryHealthService svc = service(c1);
        assertTrue(svc.find("x").isPresent());
        assertTrue(svc.find("missing").isEmpty());
    }

    private static LibraryHealthCheck stubCheck(String id, String label, int total) {
        List<LibraryHealthCheck.Finding> rows = new ArrayList<>();
        for (int i = 0; i < total; i++) rows.add(new LibraryHealthCheck.Finding("r" + i, "row " + i, ""));
        return new LibraryHealthCheck() {
            @Override public String id() { return id; }
            @Override public String label() { return label; }
            @Override public String description() { return label + " desc"; }
            @Override public FixRouting fixRouting() { return FixRouting.SURFACE_ONLY; }
            @Override public CheckResult run() { return new CheckResult(total, List.copyOf(rows)); }
        };
    }
}
