package com.organizer3.web;

import com.organizer3.javdb.enrichment.ActressAvatarStore;
import com.organizer3.javdb.enrichment.EnrichmentJob;
import com.organizer3.javdb.enrichment.EnrichmentQueue;
import com.organizer3.javdb.enrichment.EnrichmentRunner;
import com.organizer3.javdb.enrichment.JavdbActressStagingRow;
import com.organizer3.javdb.enrichment.JavdbStagingRepository;
import com.organizer3.model.Title;
import com.organizer3.repository.TitleRepository;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.Optional;

/**
 * Mutation operations for the javdb Discovery screen's action panel.
 */
@RequiredArgsConstructor
public class JavdbEnrichmentActionService {

    private final TitleRepository titleRepo;
    private final EnrichmentQueue queue;
    private final EnrichmentRunner runner;
    private final JavdbStagingRepository stagingRepo;
    private final ActressAvatarStore avatarStore;

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

    /**
     * Immediately lifts any active rate-limit or burst pause and resets backoff counters.
     * Use after switching VPN — processing resumes on the runner's next loop tick.
     */
    public void forceResume() {
        runner.forceResume();
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

    /** Pauses a pending item (no-op if already in_flight or not found). */
    public void pauseItem(long id) { queue.pauseItem(id); }

    /** Resumes a paused item back to pending. */
    public void resumeItem(long id) { queue.resumeItem(id); }

    /** Moves a pending/paused item to the front of the queue. */
    public void moveToTop(long id) { queue.moveToTop(id); }

    /** Moves a pending/paused item to the back of the queue. */
    public void moveToBottom(long id) { queue.moveToBottom(id); }

    /** Moves a pending/paused item one position earlier in the queue. */
    public void promoteItem(long id) { queue.promoteItem(id); }

    /** Moves a pending/paused item one position later in the queue. */
    public void demoteItem(long id) { queue.demoteItem(id); }

    /** Re-queues a failed item as pending, appended to the end of the queue. */
    public void requeueItem(long id) { queue.requeueItem(id); }

    /** Force re-enqueues a single title even if it was already successfully enriched. */
    public void reEnqueueTitle(long titleId, long actressId) {
        queue.enqueueTitleForce(titleId, actressId);
    }

    /** Force re-enqueues the actress profile even if it was already successfully fetched. */
    public void reEnqueueActressProfile(long actressId) {
        queue.enqueueActressProfileForce(actressId);
    }

    /**
     * Result of a slug-derivation attempt for an actress with no profile yet.
     *
     * <p>{@code status}: {@code "ok"} (slug derived + profile enqueued),
     * {@code "ambiguous"} (multiple candidates tied), {@code "no_data"} (no enriched titles
     * with cast data), {@code "already_resolved"} (slug already on staging row).
     */
    public record DeriveSlugResult(
            String status,
            String chosenSlug,
            String chosenName,
            int chosenTitleCount,
            int totalEnrichedTitles,
            List<JavdbStagingRepository.CastSlugCount> candidates) {}

    /**
     * Attempts to derive the actress's javdb slug from the cast lists of her already-enriched
     * titles, then enqueues a profile fetch. Picks the slug appearing in the most of her
     * enriched titles. Refuses to act if the top two candidates are tied (ambiguous).
     */
    public DeriveSlugResult deriveSlugAndEnqueueProfile(long actressId) {
        Optional<JavdbActressStagingRow> existing = stagingRepo.findActressStaging(actressId);
        if (existing.isPresent() && existing.get().javdbSlug() != null) {
            // Already has a slug — just enqueue the profile fetch.
            queue.enqueueActressProfileForce(actressId);
            JavdbActressStagingRow row = existing.get();
            return new DeriveSlugResult("already_resolved", row.javdbSlug(), null, 0, 0, List.of());
        }

        int totalEnriched = stagingRepo.countEnrichedTitlesForActress(actressId);
        List<JavdbStagingRepository.CastSlugCount> candidates =
                stagingRepo.findEnrichedCastSlugCounts(actressId);

        if (candidates.isEmpty()) {
            return new DeriveSlugResult("no_data", null, null, 0, totalEnriched, List.of());
        }

        var top = candidates.get(0);
        if (candidates.size() > 1 && candidates.get(1).titleCount() == top.titleCount()) {
            return new DeriveSlugResult("ambiguous", null, null, 0, totalEnriched, candidates);
        }

        stagingRepo.upsertActressSlugOnly(actressId, top.slug(), top.sampleTitleCode());
        queue.enqueueActressProfile(actressId);
        return new DeriveSlugResult("ok", top.slug(), top.name(), top.titleCount(), totalEnriched, candidates);
    }

    /**
     * Result of an avatar download attempt.
     *
     * <p>{@code status}:
     * {@code "ok"} — downloaded and persisted (or already on disk),
     * {@code "no_profile"} — no staging row,
     * {@code "no_url"} — staging row has no avatar_url,
     * {@code "failed"} — CDN fetch failed.
     */
    public record AvatarDownloadResult(String status, String localAvatarUrl) {}

    /**
     * Downloads the actress's avatar from the CDN using the {@code avatar_url} already
     * stored on her staging row. Does not call javdb — only the CDN. Idempotent.
     */
    public AvatarDownloadResult downloadAvatarForActress(long actressId) {
        Optional<JavdbActressStagingRow> maybeRow = stagingRepo.findActressStaging(actressId);
        if (maybeRow.isEmpty()) return new AvatarDownloadResult("no_profile", null);
        JavdbActressStagingRow row = maybeRow.get();
        if (row.avatarUrl() == null || row.avatarUrl().isBlank()) {
            return new AvatarDownloadResult("no_url", null);
        }
        String relPath = avatarStore.download(row.javdbSlug(), row.avatarUrl());
        if (relPath == null) return new AvatarDownloadResult("failed", null);
        stagingRepo.updateLocalAvatarPath(actressId, relPath);
        return new AvatarDownloadResult("ok", "/" + relPath);
    }
}
