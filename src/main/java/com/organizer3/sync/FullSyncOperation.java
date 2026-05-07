package com.organizer3.sync;

import com.organizer3.config.volume.VolumeConfig;
import com.organizer3.config.volume.VolumeStructureDef;
import com.organizer3.db.ActressCompaniesService;
import com.organizer3.db.TitleEffectiveTagsService;
import com.organizer3.filesystem.VolumeFileSystem;
import com.organizer3.model.Title;
import com.organizer3.repository.ActressRepository;
import com.organizer3.repository.TitleActressRepository;
import com.organizer3.repository.TitleLocationRepository;
import com.organizer3.repository.TitlePathHistoryRepository;
import com.organizer3.repository.TitleRepository;
import com.organizer3.repository.VideoRepository;
import com.organizer3.repository.VolumeRepository;
import com.organizer3.shell.SessionContext;
import com.organizer3.shell.io.CommandIO;
import com.organizer3.sync.scanner.DiscoveredTitle;
import com.organizer3.sync.scanner.ScannerRegistry;
import com.organizer3.sync.scanner.VolumeScanner;

import com.organizer3.config.AppConfig;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

/**
 * Syncs the entire volume: clears all existing location and video records for the volume,
 * then delegates to the appropriate {@link VolumeScanner} for filesystem discovery
 * and persists the results.
 */
@Slf4j
public class FullSyncOperation extends AbstractSyncOperation {

    private final ScannerRegistry scannerRegistry;
    /**
     * When {@code true}, skips the call to {@link #pruneOrphanedTitlesAndCovers} and the
     * stale-row sweep at the end of {@link #execute}. Used by
     * {@link com.organizer3.utilities.task.volume.CoherentMultiVolumeSyncTask}, which defers
     * the global prune until every requested volume has been scanned, so a title that moved
     * A → B is observed at B before it is judged absent at A.
     *
     * <p>All other steps (mark-stale, scan, save, finalizeSync) still run normally.
     */
    private final boolean suppressPrune;

    public FullSyncOperation(ScannerRegistry scannerRegistry,
                             TitleRepository titleRepo, VideoRepository videoRepo,
                             ActressRepository actressRepo, VolumeRepository volumeRepo,
                             TitleLocationRepository titleLocationRepo,
                             TitleActressRepository titleActressRepo,
                             IndexLoader indexLoader,
                             TitleEffectiveTagsService titleEffectiveTagsService,
                             ActressCompaniesService actressCompaniesService,
                             com.organizer3.covers.CoverPath coverPath,
                             com.organizer3.javdb.enrichment.RevalidationPendingRepository revalidationPendingRepo,
                             SyncIdentityMatcher identityMatcher) {
        this(scannerRegistry, titleRepo, videoRepo, actressRepo, volumeRepo, titleLocationRepo,
                titleActressRepo, indexLoader, titleEffectiveTagsService, actressCompaniesService,
                coverPath, revalidationPendingRepo, identityMatcher, TitleSyncObserver.NO_OP, null, false);
    }

    public FullSyncOperation(ScannerRegistry scannerRegistry,
                             TitleRepository titleRepo, VideoRepository videoRepo,
                             ActressRepository actressRepo, VolumeRepository volumeRepo,
                             TitleLocationRepository titleLocationRepo,
                             TitleActressRepository titleActressRepo,
                             IndexLoader indexLoader,
                             TitleEffectiveTagsService titleEffectiveTagsService,
                             ActressCompaniesService actressCompaniesService,
                             com.organizer3.covers.CoverPath coverPath,
                             com.organizer3.javdb.enrichment.RevalidationPendingRepository revalidationPendingRepo,
                             SyncIdentityMatcher identityMatcher,
                             TitleSyncObserver syncObserver) {
        this(scannerRegistry, titleRepo, videoRepo, actressRepo, volumeRepo, titleLocationRepo,
                titleActressRepo, indexLoader, titleEffectiveTagsService, actressCompaniesService,
                coverPath, revalidationPendingRepo, identityMatcher, syncObserver, null, false);
    }

    public FullSyncOperation(ScannerRegistry scannerRegistry,
                             TitleRepository titleRepo, VideoRepository videoRepo,
                             ActressRepository actressRepo, VolumeRepository volumeRepo,
                             TitleLocationRepository titleLocationRepo,
                             TitleActressRepository titleActressRepo,
                             IndexLoader indexLoader,
                             TitleEffectiveTagsService titleEffectiveTagsService,
                             ActressCompaniesService actressCompaniesService,
                             com.organizer3.covers.CoverPath coverPath,
                             com.organizer3.javdb.enrichment.RevalidationPendingRepository revalidationPendingRepo,
                             SyncIdentityMatcher identityMatcher,
                             TitleSyncObserver syncObserver,
                             TitlePathHistoryRepository pathHistoryRepo) {
        this(scannerRegistry, titleRepo, videoRepo, actressRepo, volumeRepo, titleLocationRepo,
                titleActressRepo, indexLoader, titleEffectiveTagsService, actressCompaniesService,
                coverPath, revalidationPendingRepo, identityMatcher, syncObserver, pathHistoryRepo, false);
    }

    /**
     * Full constructor — the deepest overload that all others chain to.
     *
     * @param suppressPrune when {@code true}, skips {@code pruneOrphanedTitlesAndCovers} so
     *                      the caller (e.g., coherent-sync task) can run a single global prune
     *                      after every volume has been scanned.
     */
    public FullSyncOperation(ScannerRegistry scannerRegistry,
                             TitleRepository titleRepo, VideoRepository videoRepo,
                             ActressRepository actressRepo, VolumeRepository volumeRepo,
                             TitleLocationRepository titleLocationRepo,
                             TitleActressRepository titleActressRepo,
                             IndexLoader indexLoader,
                             TitleEffectiveTagsService titleEffectiveTagsService,
                             ActressCompaniesService actressCompaniesService,
                             com.organizer3.covers.CoverPath coverPath,
                             com.organizer3.javdb.enrichment.RevalidationPendingRepository revalidationPendingRepo,
                             SyncIdentityMatcher identityMatcher,
                             TitleSyncObserver syncObserver,
                             TitlePathHistoryRepository pathHistoryRepo,
                             boolean suppressPrune) {
        super(titleRepo, videoRepo, actressRepo, volumeRepo, titleLocationRepo, titleActressRepo,
                indexLoader, titleEffectiveTagsService, actressCompaniesService, coverPath,
                revalidationPendingRepo, identityMatcher, syncObserver, pathHistoryRepo);
        this.scannerRegistry = scannerRegistry;
        this.suppressPrune = suppressPrune;
    }

    @Override
    public void execute(VolumeConfig volume, VolumeStructureDef structure,
                        VolumeFileSystem fs, SessionContext ctx, CommandIO io) throws IOException {
        io.println("Syncing " + volume.id() + " (full) ...");
        ensureVolumeRecord(volume);

        int staleGraceDays = AppConfig.get().volumes().syncOrDefaults().staleGraceDaysOrDefault();
        String nowIso = Instant.now().toString();

        // Mark existing records for this volume stale (videos hard-deleted as before, locations soft-marked).
        // Titles are NOT deleted — they may have locations on other volumes.
        videoRepo.deleteByVolume(volume.id());
        SyncStats stats = new SyncStats();
        stats.staleMarked = titleLocationRepo.markStaleByVolume(volume.id(), nowIso);

        // Load soft-match state after marks: titles with no live locations anywhere now appear
        // as orphans in the matcher's maps, enabling recode detection.
        identityMatcher.loadForSync();

        // Scan filesystem via the structure-specific scanner
        VolumeScanner scanner = scannerRegistry.forStructureType(volume.structureType());
        List<DiscoveredTitle> discovered = scanner.scan(structure, fs, io);

        // Persist all discovered titles and link their cast to the junction table
        try (var progress = io.startProgress("Saving", discovered.size())) {
            for (DiscoveredTitle dt : discovered) {
                progress.setLabel(dt.path().getFileName().toString());
                List<Long> castIds = resolveCast(dt, stats);
                Long filingActressId = castIds.size() == 1 ? castIds.get(0) : null;
                Title title = saveTitleAndVideos(dt.path(), volume.id(), dt.partitionId(), filingActressId, fs);
                titleActressRepo.linkAll(title.getId(), castIds);
                stats.addTitle(title.getId());
                stats.addTitles(dt.partitionId(), 1);
                progress.advance();
            }
        }

        // Flush soft-match candidates before orphan pruning so the queue reflects the new sync state.
        identityMatcher.flushToQueue(titleRepo.countAll());

        // Count how many rows marked stale at the start of this scan were cleared by re-observation.
        // staleMarked = rows we marked at start (WHERE stale_since IS NULL at that time).
        // staleCleared = those that were subsequently re-observed (upsert sets stale_since = NULL).
        // Proxy: count rows on this volume still stale with stale_since = nowIso (not re-observed).
        int stillStaleFromThisRun = titleLocationRepo.countStaleMarkedAt(volume.id(), nowIso);
        stats.staleCleared = stats.staleMarked - stillStaleFromThisRun;

        log.info("Sync scan complete — volume={} staleMarked={} staleCleared={}", volume.id(), stats.staleMarked, stats.staleCleared);

        if (suppressPrune) {
            // Coherent-sync path: caller (CoherentMultiVolumeSyncTask) will run a single global
            // prune after every volume has been scanned. Skipping here ensures a title that moved
            // A → B is observed at B before being judged absent at A.
            log.info("Orphan prune suppressed for volume={} (coherent-sync mode)", volume.id());
        } else {
            // Drop titles whose locations all disappeared AND their local cover files.
            // Also sweeps stale rows past the grace period.
            pruneOrphanedTitlesAndCovers(io, staleGraceDays, stats);
        }

        finalizeSync(volume.id(), ctx, stats);
        printStats(stats, io);
    }
}
