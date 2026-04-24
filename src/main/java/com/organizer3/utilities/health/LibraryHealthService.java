package com.organizer3.utilities.health;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs library health checks and holds the latest report. The service is the composition root
 * for checks — the list is injected at construction, and the service iterates it during a scan.
 * New checks are added by appending to the injected list in {@code Application} wiring.
 *
 * <p>The "latest report" is kept in a volatile reference so the right-pane detail endpoints
 * can serve consistent data without re-scanning. When a {@link LibraryHealthReportStore} is
 * provided, the report is loaded from disk at construction and persisted after each scan, so
 * findings survive app restarts with a visible {@code scannedAt} timestamp.
 */
public final class LibraryHealthService {

    private final List<LibraryHealthCheck> checks;
    private final AtomicReference<LibraryHealthReport> latest = new AtomicReference<>();
    private final LibraryHealthReportStore store;

    public LibraryHealthService(List<LibraryHealthCheck> checks, LibraryHealthReportStore store) {
        this.checks = List.copyOf(checks);
        this.store  = store;
        store.load().ifPresent(latest::set);
    }

    /** Immutable check list — used by the UI to render empty rows before a scan has run. */
    public List<LibraryHealthCheck> checks() { return checks; }

    /** Most recent completed report, or empty if no scan has run yet this process. */
    public Optional<LibraryHealthReport> latest() { return Optional.ofNullable(latest.get()); }

    /** Look up one check by id. Used by the detail route. */
    public Optional<LibraryHealthCheck> find(String checkId) {
        return checks.stream().filter(c -> c.id().equals(checkId)).findFirst();
    }

    /**
     * Run every check and publish a new report under {@code runId}. The {@code onCheck}
     * callback fires before each check runs, letting the task wrapper emit phase events.
     * The {@code afterCheck} callback fires with the check's result so the task can report
     * per-check counts. Returns the built report.
     */
    public LibraryHealthReport scan(String runId,
                                    ScanCallback onCheck,
                                    AfterCheckCallback afterCheck) {
        Map<String, LibraryHealthReport.CheckEntry> entries = new LinkedHashMap<>();
        for (LibraryHealthCheck check : checks) {
            onCheck.onCheck(check);
            LibraryHealthCheck.CheckResult result;
            try {
                result = check.run();
            } catch (RuntimeException e) {
                // A failing check must not take out the whole scan — treat as empty + log via
                // the callback so the task can mark that phase failed while others proceed.
                result = LibraryHealthCheck.CheckResult.empty();
                afterCheck.afterCheck(check, result, e);
                entries.put(check.id(), entryOf(check, result));
                continue;
            }
            afterCheck.afterCheck(check, result, null);
            entries.put(check.id(), entryOf(check, result));
        }
        LibraryHealthReport report = new LibraryHealthReport(runId, Instant.now(), entries);
        latest.set(report);
        store.save(report);
        return report;
    }

    private static LibraryHealthReport.CheckEntry entryOf(LibraryHealthCheck c,
                                                           LibraryHealthCheck.CheckResult r) {
        return new LibraryHealthReport.CheckEntry(c.id(), c.label(), c.description(), c.fixRouting(), r);
    }

    @FunctionalInterface public interface ScanCallback {
        void onCheck(LibraryHealthCheck check);
    }
    @FunctionalInterface public interface AfterCheckCallback {
        void afterCheck(LibraryHealthCheck check, LibraryHealthCheck.CheckResult result, Exception error);
    }
}
