package com.organizer3.sync;

import com.organizer3.config.volume.VolumeConfig;
import com.organizer3.config.volume.VolumeStructureDef;
import com.organizer3.filesystem.VolumeFileSystem;
import com.organizer3.repository.ActressRepository;
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
                             IndexLoader indexLoader) {
        super(titleRepo, videoRepo, actressRepo, volumeRepo, titleLocationRepo, indexLoader);
        this.scannerRegistry = scannerRegistry;
    }

    @Override
    public void execute(VolumeConfig volume, VolumeStructureDef structure,
                        VolumeFileSystem fs, SessionContext ctx, CommandIO io) throws IOException {
        io.println("Syncing " + volume.id() + " (full) ...");
        ensureVolumeRecord(volume);

        // Clear existing records for this volume — videos first (FK), then locations
        // Titles are NOT deleted — they may have locations on other volumes
        videoRepo.deleteByVolume(volume.id());
        titleLocationRepo.deleteByVolume(volume.id());

        // Scan filesystem via the structure-specific scanner
        VolumeScanner scanner = scannerRegistry.forStructureType(volume.structureType());
        List<DiscoveredTitle> discovered = scanner.scan(structure, fs, io);

        // Persist all discovered titles
        SyncStats stats = new SyncStats();
        try (var progress = io.startProgress("Saving", discovered.size())) {
            for (DiscoveredTitle dt : discovered) {
                progress.setLabel(dt.path().getFileName().toString());
                Long actressId = resolveActressId(dt, stats);
                saveTitleAndVideos(dt.path(), volume.id(), dt.partitionId(), actressId, fs);
                stats.addTitles(dt.partitionId(), 1);
                progress.advance();
            }
        }

        // Clean up titles that no longer have any locations
        titleRepo.deleteOrphaned();

        finalizeSync(volume.id(), ctx);
        printStats(stats, io);
    }
}
