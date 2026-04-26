package com.organizer3.web;

import com.organizer3.javdb.enrichment.EnrichmentJob;
import com.organizer3.javdb.enrichment.EnrichmentQueue;
import com.organizer3.javdb.enrichment.EnrichmentRunner;
import com.organizer3.model.Title;
import com.organizer3.repository.TitleRepository;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * Mutation operations for the javdb Discovery screen's action panel.
 */
@RequiredArgsConstructor
public class JavdbEnrichmentActionService {

    private final TitleRepository titleRepo;
    private final EnrichmentQueue queue;
    private final EnrichmentRunner runner;

    /**
     * Enqueues a fetch_title job for every title belonging to the actress.
     * Already-pending/in-flight/done titles are skipped (idempotent per enqueueTitle).
     *
     * @return total number of titles for the actress (not just newly enqueued)
     */
    public int enqueueActress(long actressId) {
        List<Title> titles = titleRepo.findByActress(actressId);
        for (Title title : titles) {
            queue.enqueueTitle(title.getId(), actressId);
        }
        return titles.size();
    }

    /** Cancels all pending jobs for the actress (does not touch in_flight). */
    public void cancelForActress(long actressId) {
        queue.cancelForActress(actressId);
    }

    /** Cancels all pending jobs across all actresses. */
    public void cancelAll() {
        queue.cancelAll();
    }

    /** Pauses or resumes the enrichment runner. */
    public void setPaused(boolean paused) {
        runner.setPaused(paused);
    }

    /** Returns whether the enrichment runner is currently paused. */
    public boolean isPaused() {
        return runner.isPaused();
    }

    /**
     * Resets all failed jobs for the actress back to pending so they will be retried.
     */
    public void retryFailedForActress(long actressId) {
        queue.resetFailedForActress(actressId);
    }

    /** Returns all failed jobs for the actress for the Errors tab. */
    public List<EnrichmentJob> getErrorsForActress(long actressId) {
        return queue.listFailedForActress(actressId);
    }

    /** Force re-enqueues a single title even if it was already successfully enriched. */
    public void reEnqueueTitle(long titleId, long actressId) {
        queue.enqueueTitleForce(titleId, actressId);
    }

    /** Force re-enqueues the actress profile even if it was already successfully fetched. */
    public void reEnqueueActressProfile(long actressId) {
        queue.enqueueActressProfileForce(actressId);
    }
}
