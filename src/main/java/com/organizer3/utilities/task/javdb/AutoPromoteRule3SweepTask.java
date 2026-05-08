package com.organizer3.utilities.task.javdb;

import com.organizer3.javdb.enrichment.AutoPromoter;
import com.organizer3.javdb.enrichment.EnrichmentReviewQueueRepository;
import com.organizer3.utilities.task.Task;
import com.organizer3.utilities.task.TaskIO;
import com.organizer3.utilities.task.TaskInputs;
import com.organizer3.utilities.task.TaskSpec;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.Jdbi;

import java.util.List;

/**
 * One-shot sweep that re-runs {@link AutoPromoter#promoteFromActressProfile} for every actress
 * with a fetched {@code javdb_actress_staging} row.
 *
 * <p>Surfaces accumulated {@code stage_name_conflict} review-queue rows that were never
 * enqueued because Rule 3 was added after many staging rows were already populated.
 *
 * <p><b>Idempotency:</b> {@link AutoPromoter#promoteFromActressProfile} is idempotent for
 * Rules 1 &amp; 2 (never overwrites existing non-null CJK stage_name). Rule 3 uses
 * {@link EnrichmentReviewQueueRepository#enqueueWithDetail}, which uses {@code INSERT OR IGNORE}
 * on a partial unique index over (title_id, reason) where resolved_at IS NULL, so duplicate
 * conflict rows are silently suppressed. Safe to re-run.
 *
 * <p>Option #4 from {@code spec/PROPOSAL_ACTRESS_PROFILE_HARDENING.md}.
 */
@Slf4j
public final class AutoPromoteRule3SweepTask implements Task {

    public static final String ID = "javdb.autopromote_rule3_sweep";

    private static final TaskSpec SPEC = new TaskSpec(
            ID,
            "AutoPromoter Rule 3 backfill sweep",
            "Re-runs AutoPromoter.promoteFromActressProfile for every actress with a fetched "
                    + "javdb_actress_staging row. Surfaces stage_name_conflict review entries "
                    + "that pre-dated Rule 3. Idempotent; safe to re-run.",
            List.of()
    );

    private final Jdbi jdbi;
    private final AutoPromoter autoPromoter;
    private final EnrichmentReviewQueueRepository reviewQueueRepo;

    public AutoPromoteRule3SweepTask(Jdbi jdbi,
                                     AutoPromoter autoPromoter,
                                     EnrichmentReviewQueueRepository reviewQueueRepo) {
        this.jdbi = jdbi;
        this.autoPromoter = autoPromoter;
        this.reviewQueueRepo = reviewQueueRepo;
    }

    @Override
    public TaskSpec spec() { return SPEC; }

    @Override
    public void run(TaskInputs inputs, TaskIO io) {
        io.phaseStart("scan", "Loading fetched actress staging rows");

        List<Long> actressIds = jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT actress_id
                        FROM javdb_actress_staging
                        WHERE status = 'fetched'
                          AND name_variants_json IS NOT NULL
                        ORDER BY actress_id
                        """)
                        .mapTo(Long.class)
                        .list());

        int total = actressIds.size();
        io.phaseEnd("scan", "ok", total + " actress(es) with fetched staging rows");

        if (total == 0) {
            log.info("AutoPromote Rule 3 sweep: scanned=0, stage_name_updated=0, conflicts_enqueued=0, skipped=0");
            return;
        }

        io.phaseStart("sweep", "Running promoteFromActressProfile per actress");

        int conflictsBefore = reviewQueueRepo.countOpen("stage_name_conflict");

        int processed = 0;
        int errors = 0;
        for (int i = 0; i < actressIds.size(); i++) {
            if (io.isCancellationRequested()) {
                io.phaseLog("sweep", "Cancelled after " + i + " of " + total + " actress(es)");
                break;
            }
            long actressId = actressIds.get(i);
            io.phaseProgress("sweep", i, total, "actress_id=" + actressId);
            try {
                autoPromoter.promoteFromActressProfile(actressId);
                processed++;
            } catch (Exception e) {
                log.warn("AutoPromoteRule3SweepTask: failed for actress_id={}: {}", actressId, e.getMessage());
                io.phaseLog("sweep", "FAIL actress_id=" + actressId + " — " + e.getMessage());
                errors++;
            }
        }

        int conflictsAfter = reviewQueueRepo.countOpen("stage_name_conflict");
        int newConflicts = conflictsAfter - conflictsBefore;

        String summary = "processed=" + processed + " · new_conflicts_enqueued=" + newConflicts
                + (errors > 0 ? " · errors=" + errors : "");
        io.phaseEnd("sweep", errors > 0 ? "failed" : "ok", summary);

        log.info("AutoPromote Rule 3 sweep: scanned={}, processed={}, conflicts_enqueued={}, errors={}",
                total, processed, newConflicts, errors);
    }
}
