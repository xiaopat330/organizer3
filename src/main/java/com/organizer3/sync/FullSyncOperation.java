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
import com.organizer3.repository.TitleRepository;
import com.organizer3.repository.VideoRepository;
import com.organizer3.repository.VolumeRepository;
import com.organizer3.shell.SessionContext;
import com.organizer3.shell.io.CommandIO;
import com.organizer3.sync.scanner.DiscoveredTitle;
import com.organizer3.sync.scanner.ScannerRegistry;
import com.organizer3.sync.scanner.VolumeScanner;

import java.io.IOException;
import java.util.List;

/**
 * Syncs the entire volume: clears all existing location and video records for the volume,
 * then delegates to the appropriate {@link VolumeScanner} for filesystem discovery
 * and persists the results.
 */
public class FullSyncOperation extends AbstractSyncOperation {

    private final ScannerRegistry scannerRegistry;

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
        super(titleRepo, videoRepo, actressRepo, volumeRepo, titleLocationRepo, titleActressRepo,
                indexLoader, titleEffectiveTagsService, actressCompaniesService, coverPath,
                revalidationPendingRepo, identityMatcher);
        this.scannerRegistry = scannerRegistry;
    }

    @Override
    public void execute(VolumeConfig volume, VolumeStructureDef structure,
                        VolumeFileSystem fs, SessionContext ctx, CommandIO io) throws IOException {
        io.println("Syncing " + volume.id() + " (full) ...");
        ensureVolumeRecord(volume);

        // Clear existing records for this volume — videos first (FK), then locations.
        // Titles are NOT deleted — they may have locations on other volumes.
        videoRepo.deleteByVolume(volume.id());
        titleLocationRepo.deleteByVolume(volume.id());

        // Load soft-match state after clears: titles whose only locations were on this volume
        // now appear as orphans in the matcher's maps, enabling recode detection.
        identityMatcher.loadForSync();

        // Scan filesystem via the structure-specific scanner
        VolumeScanner scanner = scannerRegistry.forStructureType(volume.structureType());
        List<DiscoveredTitle> discovered = scanner.scan(structure, fs, io);

        // Persist all discovered titles and link their cast to the junction table
        SyncStats stats = new SyncStats();
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

        // Drop titles whose locations all disappeared AND their local cover files.
        pruneOrphanedTitlesAndCovers(io);

        finalizeSync(volume.id(), ctx, stats);
        printStats(stats, io);
    }
}
