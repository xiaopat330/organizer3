package com.organizer3.javdb.draft;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Garbage-collects stale and orphaned draft artifacts.
 *
 * <p>Sweep order is deliberately sequenced:
 * <ol>
 *   <li>Reap stale {@code draft_titles} rows (older than {@code maxAgeDays}).
 *       The {@code ON DELETE CASCADE} on {@code draft_title_actresses} and
 *       {@code draft_title_javdb_enrichment} ensures all child rows are removed
 *       atomically.</li>
 *   <li>Reap orphan {@code draft_actresses} rows — any actress whose ref-count
 *       dropped to zero (including those just freed by step 1).</li>
 *   <li>Reap orphan scratch cover files — files under
 *       {@code _sandbox/draft_covers/} whose numeric id no longer appears in
 *       {@code draft_titles} (using the post-step-1 live-id snapshot).</li>
 * </ol>
 *
 * <p>{@link #sweep()} returns the total count of reaped items and logs a
 * structured breakdown at INFO level.
 *
 * <p>See spec/PROPOSAL_DRAFT_MODE.md §9.2 and §7.4.
 */
@Slf4j
@RequiredArgsConstructor
public class DraftGcService {

    private final DraftTitleRepository draftTitleRepo;
    private final DraftActressRepository draftActressRepo;
    private final DraftCoverScratchStore coverScratchStore;
    private final int maxAgeDays;

    /**
     * Runs a full GC sweep.
     *
     * @return total number of items reaped (stale drafts + orphan actresses + orphan covers).
     */
    public int sweep() {
        // Step 1: reap stale draft_titles (cascades to child tables via FK).
        int staleCount = draftTitleRepo.reapStale(maxAgeDays);

        // Step 2: reap orphan draft_actresses (ref-count = 0 after step 1).
        int orphanActressCount = draftActressRepo.reapOrphans();

        // Step 3: compute live draft ids AFTER step 1 so the file-set delta is accurate.
        Set<Long> liveIds = draftTitleRepo.listAll(0, Integer.MAX_VALUE)
                .stream()
                .map(DraftTitle::getId)
                .collect(Collectors.toSet());

        int orphanCoverCount = 0;
        try {
            orphanCoverCount = coverScratchStore.reapOrphans(liveIds);
        } catch (IOException e) {
            log.warn("Draft GC: cover scratch reap failed — orphan files may remain: {}", e.getMessage(), e);
        }

        int total = staleCount + orphanActressCount + orphanCoverCount;
        log.info("Draft GC sweep: stale_drafts={}, orphan_actresses={}, orphan_covers={}",
                staleCount, orphanActressCount, orphanCoverCount);
        return total;
    }
}
