package com.organizer3.command;

import com.organizer3.shell.SessionContext;
import com.organizer3.shell.io.CommandIO;
import com.organizer3.sync.ReconcileDetailSerializer;
import com.organizer3.sync.ReconcileReport;
import com.organizer3.sync.ReconcileService;
import lombok.extern.slf4j.Slf4j;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Shell command: {@code reconcile [--verbose] [--sweep]}
 *
 * <p>Runs the reconcile-only pass — a read-mostly examination of {@code title_locations} DB
 * state that surfaces four signals without touching the filesystem:
 * <ul>
 *   <li><b>Duplicate live locations</b> — same title on >1 live volume row.</li>
 *   <li><b>Pending-grace</b> — stale rows still inside the grace window.</li>
 *   <li><b>Past-grace stragglers</b> — stale rows past the grace window.</li>
 *   <li><b>Actress-folder mismatches</b> — live location path doesn't contain the actress name.</li>
 * </ul>
 *
 * <p>Flags:
 * <ul>
 *   <li>{@code --verbose} — also print detail lists (each capped at 20 entries).</li>
 *   <li>{@code --sweep} — after running, prompt for confirmation then drop past-grace rows
 *       immediately (subject to the catastrophic-delete guard).</li>
 * </ul>
 *
 * <p>Each run persists a report row with {@code triggered_by='manual'}.
 */
@Slf4j
public class ReconcileCommand implements Command {

    private static final int DETAIL_CAP = 20;
    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'").withZone(ZoneId.of("UTC"));

    private final Supplier<ReconcileService> reconcileServiceSupplier;

    /**
     * @param reconcileServiceSupplier supplier returning the fully-wired {@link ReconcileService}.
     *        A {@link Supplier} is used so the command can be registered before the service is
     *        constructed (same pattern as {@link SyncCoherentCommand}).
     */
    public ReconcileCommand(Supplier<ReconcileService> reconcileServiceSupplier) {
        this.reconcileServiceSupplier = reconcileServiceSupplier;
    }

    @Override
    public String name() { return "reconcile"; }

    @Override
    public String description() {
        return "Run the reconcile-only pass (no filesystem I/O). "
             + "Reports duplicate live locations, pending-grace titles, past-grace stragglers, "
             + "and actress-folder mismatches. Flags: --verbose (detail lists), "
             + "--sweep (drop past-grace rows after confirmation).";
    }

    @Override
    public void execute(String[] args, SessionContext ctx, CommandIO io) {
        List<String> flags = Arrays.asList(args);
        boolean verbose = flags.contains("--verbose");
        boolean sweep   = flags.contains("--sweep");

        io.println("Running reconcile pass…");

        ReconcileService reconcileService = reconcileServiceSupplier.get();
        ReconcileReport report = reconcileService.run(verbose);
        String detailJson = ReconcileDetailSerializer.toJson(report);
        reconcileService.persist(report, "manual", detailJson);

        printSummary(report, io);

        if (verbose) {
            printVerbose(report, io);
        }

        if (sweep) {
            runSweep(report, reconcileService, io);
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void printSummary(ReconcileReport r, CommandIO io) {
        io.println();
        io.println("Reconcile report (" + FMT.format(r.generatedAt()) + ")");
        io.println(String.format("  Duplicate live locations:   %5d  (likely unsynced source volumes after cross-volume moves)",
                r.duplicateLiveLocations()));
        io.println(String.format("  Pending-grace titles:       %5d  (oldest: %d days)",
                r.pendingGrace(), r.oldestPendingGraceDays()));
        io.println(String.format("  Past-grace stragglers:      %5d  (would be swept on next sync)",
                r.pastGraceStragglers()));
        io.println(String.format("  Actress-folder mismatches:  %5d  (location path ≠ filing actress)",
                r.actressFolderMismatches()));
        io.println();
        if (r.isClean()) {
            io.println("  Library is fully consistent — no issues found.");
        } else {
            io.println("  Run with --verbose to list each. Run with --sweep to drop past-grace stragglers.");
        }
    }

    private void printVerbose(ReconcileReport r, CommandIO io) {
        if (!r.duplicateLiveDetails().isEmpty()) {
            io.println();
            io.println("── Duplicate live locations ─────────────────────────────");
            int shown = Math.min(r.duplicateLiveDetails().size(), DETAIL_CAP);
            for (int i = 0; i < shown; i++) {
                var dup = r.duplicateLiveDetails().get(i);
                io.println("  " + dup.code() + " — " + dup.locations().size() + " live locations:");
                dup.locations().forEach(loc ->
                        io.println("    [" + loc.volumeId() + "] " + loc.path()));
            }
            if (r.duplicateLiveDetails().size() > DETAIL_CAP) {
                io.println("  …" + (r.duplicateLiveDetails().size() - DETAIL_CAP) + " more");
            }
        }

        if (!r.pendingGraceDetails().isEmpty()) {
            io.println();
            io.println("── Pending-grace titles ─────────────────────────────────");
            int shown = Math.min(r.pendingGraceDetails().size(), DETAIL_CAP);
            for (int i = 0; i < shown; i++) {
                var row = r.pendingGraceDetails().get(i);
                io.println(String.format("  %s  stale %d days  [%s] %s",
                        row.code(), row.daysStale(), row.volumeId(), row.path()));
            }
            if (r.pendingGraceDetails().size() > DETAIL_CAP) {
                io.println("  …" + (r.pendingGraceDetails().size() - DETAIL_CAP) + " more");
            }
        }

        if (!r.pastGraceDetails().isEmpty()) {
            io.println();
            io.println("── Past-grace stragglers ────────────────────────────────");
            int shown = Math.min(r.pastGraceDetails().size(), DETAIL_CAP);
            for (int i = 0; i < shown; i++) {
                var row = r.pastGraceDetails().get(i);
                io.println(String.format("  %s  stale %d days  [%s] %s",
                        row.code(), row.daysStale(), row.volumeId(), row.path()));
            }
            if (r.pastGraceDetails().size() > DETAIL_CAP) {
                io.println("  …" + (r.pastGraceDetails().size() - DETAIL_CAP) + " more");
            }
        }

        if (!r.mismatchDetails().isEmpty()) {
            io.println();
            io.println("── Actress-folder mismatches ────────────────────────────");
            int shown = Math.min(r.mismatchDetails().size(), DETAIL_CAP);
            for (int i = 0; i < shown; i++) {
                var m = r.mismatchDetails().get(i);
                io.println(String.format("  %s  actress=%s  [%s] %s",
                        m.code(), m.actressName(), m.volumeId(), m.path()));
            }
            if (r.mismatchDetails().size() > DETAIL_CAP) {
                io.println("  …" + (r.mismatchDetails().size() - DETAIL_CAP) + " more");
            }
        }
    }

    private void runSweep(ReconcileReport r, ReconcileService reconcileService, CommandIO io) {
        if (r.pastGraceStragglers() == 0) {
            io.println("No past-grace stragglers to sweep.");
            return;
        }

        io.println();
        io.println("This will delete " + r.pastGraceStragglers()
                + " past-grace location row(s). Continue? [y/N]");

        Optional<String> choice = io.pick(List.of("y", "N"));
        boolean confirmed = choice.map("y"::equalsIgnoreCase).orElse(false);

        if (!confirmed) {
            io.println("Sweep cancelled.");
            return;
        }

        io.println("Sweeping…");
        int deleted = reconcileService.sweepPastGraceStragglers();
        if (deleted < 0) {
            io.println("Sweep REFUSED — catastrophic-delete guard tripped. "
                    + "Investigate before retrying.");
            log.warn("Reconcile sweep refused by catastrophic-delete guard (pastGrace={})",
                    r.pastGraceStragglers());
        } else {
            io.println("Swept " + deleted + " past-grace stale row(s).");
        }
    }
}
