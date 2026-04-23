package com.organizer3.utilities.task.duplicates;

import com.organizer3.model.MergeCandidate;
import com.organizer3.repository.MergeCandidateRepository;
import com.organizer3.utilities.task.Task;
import com.organizer3.utilities.task.TaskIO;
import com.organizer3.utilities.task.TaskInputs;
import com.organizer3.utilities.task.TaskSpec;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.Jdbi;

import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Execute pending MERGE decisions from the Merge Candidates tool.
 *
 * <p>For each candidate with {@code decision = 'MERGE'}, runs a single DB transaction:
 * reparents all child rows from the loser title to the winner (using INSERT OR IGNORE
 * for uniqueness-constrained tables), updates watch_history codes, cleans up duplicate_decisions
 * and other merge_candidates that reference the loser, then deletes the loser title row.
 * Stamps {@code executed_at} on the merge_candidates row after the transaction commits.
 */
@Slf4j
public final class ExecuteMergeTask implements Task {

    public static final String ID = "duplicates.execute_merge";

    private static final TaskSpec SPEC = new TaskSpec(
            ID,
            "Execute merge candidates",
            "Reparent all data from each loser title to its winner and delete the loser title row.",
            List.of()
    );

    private final MergeCandidateRepository repo;
    private final Jdbi jdbi;
    private final Clock clock;

    public ExecuteMergeTask(MergeCandidateRepository repo, Jdbi jdbi) {
        this(repo, jdbi, Clock.systemUTC());
    }

    ExecuteMergeTask(MergeCandidateRepository repo, Jdbi jdbi, Clock clock) {
        this.repo = repo;
        this.jdbi = jdbi;
        this.clock = clock;
    }

    @Override
    public TaskSpec spec() { return SPEC; }

    @Override
    public void run(TaskInputs inputs, TaskIO io) throws Exception {
        io.phaseStart("plan", "Load MERGE decisions");
        List<MergeCandidate> pending = repo.listPendingMerge();
        if (pending.isEmpty()) {
            io.phaseEnd("plan", "ok", "No pending MERGE decisions");
            return;
        }
        io.phaseEnd("plan", "ok", pending.size() + " merge(s) to execute");

        io.phaseStart("execute", "Execute merges");
        int merged = 0;
        List<String> failed = new ArrayList<>();

        for (int i = 0; i < pending.size(); i++) {
            if (io.isCancellationRequested()) {
                io.phaseLog("execute", "Cancelled — stopped after " + i + " item(s)");
                break;
            }

            MergeCandidate candidate = pending.get(i);
            String winnerCode = candidate.getWinnerCode();
            String loserCode = winnerCode.equals(candidate.getTitleCodeA())
                    ? candidate.getTitleCodeB()
                    : candidate.getTitleCodeA();

            io.phaseProgress("execute", i, pending.size(), winnerCode + " ← " + loserCode);

            try {
                executeMerge(candidate.getId(), winnerCode, loserCode);
                log.info("ExecuteMergeTask: merged {} → {}", loserCode, winnerCode);
                io.phaseLog("execute", "OK " + loserCode + " → " + winnerCode);
                merged++;
            } catch (Exception e) {
                log.warn("ExecuteMergeTask: failed {} → {}: {}", loserCode, winnerCode, e.getMessage());
                io.phaseLog("execute", "FAIL " + loserCode + " → " + winnerCode + ": " + e.getMessage());
                failed.add(winnerCode + " ← " + loserCode + ": " + e.getMessage());
            }
        }

        String summary = merged + " merged" + (failed.isEmpty() ? "" : " · " + failed.size() + " failed");
        io.phaseEnd("execute", failed.isEmpty() ? "ok" : "failed", summary);
    }

    private void executeMerge(long candidateId, String winnerCode, String loserCode) {
        jdbi.useTransaction(h -> {
            Long winnerId = h.createQuery("SELECT id FROM titles WHERE UPPER(code) = UPPER(:code)")
                    .bind("code", winnerCode).mapTo(Long.class).findFirst()
                    .orElseThrow(() -> new IllegalStateException("winner title not found: " + winnerCode));
            Long loserId = h.createQuery("SELECT id FROM titles WHERE UPPER(code) = UPPER(:code)")
                    .bind("code", loserCode).mapTo(Long.class).findFirst()
                    .orElseThrow(() -> new IllegalStateException("loser title not found: " + loserCode));

            // title_locations
            h.createUpdate("""
                    INSERT OR IGNORE INTO title_locations (title_id, volume_id, partition_id, path, last_seen_at, added_date)
                    SELECT :w, volume_id, partition_id, path, last_seen_at, added_date
                    FROM title_locations WHERE title_id = :l
                    """).bind("w", winnerId).bind("l", loserId).execute();
            h.execute("DELETE FROM title_locations WHERE title_id = ?", loserId);

            // videos
            h.execute("UPDATE videos SET title_id = ? WHERE title_id = ?", winnerId, loserId);

            // title_tags
            h.createUpdate("""
                    INSERT OR IGNORE INTO title_tags (title_id, tag)
                    SELECT :w, tag FROM title_tags WHERE title_id = :l
                    """).bind("w", winnerId).bind("l", loserId).execute();
            h.execute("DELETE FROM title_tags WHERE title_id = ?", loserId);

            // title_effective_tags
            h.createUpdate("""
                    INSERT OR IGNORE INTO title_effective_tags (title_id, tag, source)
                    SELECT :w, tag, source FROM title_effective_tags WHERE title_id = :l
                    """).bind("w", winnerId).bind("l", loserId).execute();
            h.execute("DELETE FROM title_effective_tags WHERE title_id = ?", loserId);

            // title_actresses
            h.createUpdate("""
                    INSERT OR IGNORE INTO title_actresses (title_id, actress_id)
                    SELECT :w, actress_id FROM title_actresses WHERE title_id = :l
                    """).bind("w", winnerId).bind("l", loserId).execute();
            h.execute("DELETE FROM title_actresses WHERE title_id = ?", loserId);

            // watch_history (unique on title_code, watched_at — insert then delete to avoid conflicts)
            h.createUpdate("""
                    INSERT OR IGNORE INTO watch_history (title_code, watched_at)
                    SELECT :w, watched_at FROM watch_history WHERE title_code = :l
                    """).bind("w", winnerCode).bind("l", loserCode).execute();
            h.execute("DELETE FROM watch_history WHERE title_code = ?", loserCode);

            // duplicate_decisions referencing the loser
            h.execute("DELETE FROM duplicate_decisions WHERE title_code = ?", loserCode);

            // other merge_candidates involving the loser (stale after the merge)
            h.createUpdate("""
                    DELETE FROM merge_candidates
                    WHERE id != :id AND (title_code_a = :l OR title_code_b = :l)
                    """).bind("id", candidateId).bind("l", loserCode).execute();

            // delete loser title
            h.execute("DELETE FROM titles WHERE id = ?", loserId);
        });

        String executedAt = DateTimeFormatter.ISO_INSTANT.format(clock.instant());
        repo.markExecuted(candidateId, executedAt);
    }
}
