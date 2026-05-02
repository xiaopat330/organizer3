package com.organizer3.javdb.draft;

import com.organizer3.sync.TitleSyncObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.time.Instant;

/**
 * Production {@link TitleSyncObserver} that marks active drafts as
 * {@code upstream_changed = 1} when sync rediscovers their underlying title.
 *
 * <p>The editor surfaces a banner when {@code upstream_changed = 1}:
 * "The underlying title was re-synced after this draft started."
 *
 * <p>The update is best-effort: if the underlying draft row is missing (which
 * is the common case for titles that have no active draft) the UPDATE affects
 * zero rows and returns silently. Any unexpected failure is logged and swallowed
 * so that sync is never blocked by the draft layer.
 *
 * <p>See spec/PROPOSAL_DRAFT_MODE.md §9.1.
 */
@Slf4j
@RequiredArgsConstructor
public class DraftSyncObserver implements TitleSyncObserver {

    private final DraftTitleRepository draftTitleRepo;
    private final Clock clock;

    /** Convenience constructor using the system UTC clock. */
    public DraftSyncObserver(DraftTitleRepository draftTitleRepo) {
        this(draftTitleRepo, Clock.systemUTC());
    }

    @Override
    public void onTitleSyncWrite(long titleId) {
        String nowIso = Instant.now(clock).toString();
        draftTitleRepo.setUpstreamChanged(titleId, nowIso);
    }
}
