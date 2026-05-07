package com.organizer3.sync;

import com.organizer3.covers.CoverPath;
import com.organizer3.db.ActressCompaniesService;
import com.organizer3.db.TitleEffectiveTagsService;
import com.organizer3.javdb.enrichment.RevalidationPendingRepository;
import com.organizer3.repository.ActressRepository;
import com.organizer3.repository.TitleActressRepository;
import com.organizer3.repository.TitleLocationRepository;
import com.organizer3.repository.TitleRepository;
import com.organizer3.repository.VideoRepository;
import com.organizer3.repository.VolumeRepository;
import com.organizer3.shell.SessionContext;
import com.organizer3.shell.io.CommandIO;

import lombok.extern.slf4j.Slf4j;

/**
 * Exposes the global orphan-prune and finalize-sync steps to callers outside the
 * {@code sync} package (specifically
 * {@link com.organizer3.utilities.task.volume.CoherentMultiVolumeSyncTask}).
 *
 * <p>This class is a thin facade over {@link AbstractSyncOperation}. It constructs a
 * no-op dummy operation purely to access the protected helpers without duplicating them.
 * No filesystem scanning ever occurs through this class — it is prune-and-finalize only.
 */
@Slf4j
public final class SyncPruneService {

    private final PruneDelegate delegate;

    public SyncPruneService(TitleRepository titleRepo,
                            VideoRepository videoRepo,
                            ActressRepository actressRepo,
                            VolumeRepository volumeRepo,
                            TitleLocationRepository titleLocationRepo,
                            TitleActressRepository titleActressRepo,
                            IndexLoader indexLoader,
                            TitleEffectiveTagsService titleEffectiveTagsService,
                            ActressCompaniesService actressCompaniesService,
                            CoverPath coverPath,
                            RevalidationPendingRepository revalidationPendingRepo,
                            SyncIdentityMatcher identityMatcher) {
        this.delegate = new PruneDelegate(titleRepo, videoRepo, actressRepo, volumeRepo,
                titleLocationRepo, titleActressRepo, indexLoader,
                titleEffectiveTagsService, actressCompaniesService,
                coverPath, revalidationPendingRepo, identityMatcher);
    }

    /**
     * Runs the global orphan-prune and stale-row sweep over the entire library.
     * Called by coherent-sync after every requested volume has been scanned.
     *
     * @param io            output sink for user-visible progress messages
     * @param staleGraceDays grace window in days; titles with only stale rows inside this
     *                      window are not yet pruned
     * @param stats         aggregate {@link SyncStats} that receives the {@code swept} counter
     */
    public void pruneOrphanedTitlesAndCovers(CommandIO io, int staleGraceDays, SyncStats stats) {
        delegate.pruneOrphanedTitlesAndCovers(io, staleGraceDays, stats);
    }

    /**
     * Finalizes the sync for a single volume: stamps {@code last_synced_at}, recalculates
     * actress tiers, recomputes denorm tables, and reloads the volume index.
     *
     * <p>Called per-volume in coherent-sync after each volume's scan completes.
     */
    public void finalizeSync(String volumeId, SessionContext ctx, SyncStats stats) {
        delegate.finalizeSync(volumeId, ctx, stats);
    }

    /**
     * Prints the aggregate sync stats summary to {@code io}.
     */
    public void printStats(SyncStats stats, CommandIO io) {
        delegate.printStats(stats, io);
    }

    // -------------------------------------------------------------------------
    // Internal: extends AbstractSyncOperation with a no-scan execute() to satisfy
    // the abstract contract. The real work is done by calling the inherited helpers
    // directly from SyncPruneService's public API.
    // -------------------------------------------------------------------------

    private static final class PruneDelegate extends AbstractSyncOperation {

        PruneDelegate(TitleRepository titleRepo, VideoRepository videoRepo,
                      ActressRepository actressRepo, VolumeRepository volumeRepo,
                      TitleLocationRepository titleLocationRepo,
                      TitleActressRepository titleActressRepo,
                      IndexLoader indexLoader,
                      TitleEffectiveTagsService titleEffectiveTagsService,
                      ActressCompaniesService actressCompaniesService,
                      CoverPath coverPath,
                      RevalidationPendingRepository revalidationPendingRepo,
                      SyncIdentityMatcher identityMatcher) {
            super(titleRepo, videoRepo, actressRepo, volumeRepo, titleLocationRepo,
                    titleActressRepo, indexLoader, titleEffectiveTagsService, actressCompaniesService,
                    coverPath, revalidationPendingRepo, identityMatcher);
        }

        @Override
        public void execute(com.organizer3.config.volume.VolumeConfig volume,
                            com.organizer3.config.volume.VolumeStructureDef structure,
                            com.organizer3.filesystem.VolumeFileSystem fs,
                            com.organizer3.shell.SessionContext ctx,
                            CommandIO io) {
            throw new UnsupportedOperationException(
                    "SyncPruneService.PruneDelegate is not a real scan operation — "
                    + "call pruneOrphanedTitlesAndCovers / finalizeSync directly via SyncPruneService.");
        }

        // No need to override the protected helpers — SyncPruneService is in the same package
        // as AbstractSyncOperation and can call them on 'delegate' directly via package access.
    }
}
