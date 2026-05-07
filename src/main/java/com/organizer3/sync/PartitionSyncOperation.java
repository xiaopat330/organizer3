package com.organizer3.sync;

import com.organizer3.config.volume.PartitionDef;
import com.organizer3.config.volume.VolumeConfig;
import com.organizer3.config.volume.VolumeStructureDef;
import com.organizer3.db.ActressCompaniesService;
import com.organizer3.db.TitleEffectiveTagsService;
import com.organizer3.filesystem.VolumeFileSystem;
import com.organizer3.repository.ActressRepository;
import com.organizer3.repository.TitleActressRepository;
import com.organizer3.repository.TitleLocationRepository;
import com.organizer3.repository.TitlePathHistoryRepository;
import com.organizer3.repository.TitleRepository;
import com.organizer3.repository.VideoRepository;
import com.organizer3.repository.VolumeRepository;
import com.organizer3.shell.SessionContext;
import com.organizer3.shell.io.CommandIO;

import com.organizer3.config.AppConfig;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * Syncs a named subset of unstructured partitions only (e.g., just {@code queue/}).
 *
 * <p>Clears and re-scans only the specified partitions, leaving the rest of the volume's
 * DB records untouched. Only applicable to unstructured partitions — the structured
 * {@code stars/} tree is never partially synced.
 */
@Slf4j
public class PartitionSyncOperation extends AbstractSyncOperation {

    private final List<String> partitionIds;

    public PartitionSyncOperation(List<String> partitionIds,
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
        this(partitionIds, titleRepo, videoRepo, actressRepo, volumeRepo, titleLocationRepo,
                titleActressRepo, indexLoader, titleEffectiveTagsService, actressCompaniesService,
                coverPath, revalidationPendingRepo, identityMatcher, TitleSyncObserver.NO_OP, null);
    }

    public PartitionSyncOperation(List<String> partitionIds,
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
        this(partitionIds, titleRepo, videoRepo, actressRepo, volumeRepo, titleLocationRepo,
                titleActressRepo, indexLoader, titleEffectiveTagsService, actressCompaniesService,
                coverPath, revalidationPendingRepo, identityMatcher, syncObserver, null);
    }

    public PartitionSyncOperation(List<String> partitionIds,
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
        super(titleRepo, videoRepo, actressRepo, volumeRepo, titleLocationRepo, titleActressRepo,
                indexLoader, titleEffectiveTagsService, actressCompaniesService, coverPath,
                revalidationPendingRepo, identityMatcher, syncObserver, pathHistoryRepo);
        this.partitionIds = partitionIds;
    }

    @Override
    public void execute(VolumeConfig volume, VolumeStructureDef structure,
                        VolumeFileSystem fs, SessionContext ctx, CommandIO io) throws IOException {
        io.println("Syncing " + volume.id() + " (partitions: " + partitionIds + ") ...");
        ensureVolumeRecord(volume);

        int staleGraceDays = AppConfig.get().volumes().syncOrDefaults().staleGraceDaysOrDefault();
        String nowIso = Instant.now().toString();

        SyncStats stats = new SyncStats();
        Path root = Path.of("/");

        // Phase 1: mark all target partitions stale so renamed titles appear as no-live-location
        // when the soft-match maps are loaded in phase 2.
        for (String partitionId : partitionIds) {
            videoRepo.deleteByVolumeAndPartition(volume.id(), partitionId);
            stats.staleMarked += titleLocationRepo.markStaleByVolumeAndPartition(volume.id(), partitionId, nowIso);
        }

        // Phase 2: load soft-match state after all marks.
        identityMatcher.loadForSync();

        // Phase 3: scan all target partitions.
        for (String partitionId : partitionIds) {
            PartitionDef partition = requirePartitionDef(structure, partitionId);
            Path partRoot = root.resolve(partition.path());
            io.println("  Scanning " + partitionId + "/ ...");
            scanUnstructuredPartition(partRoot, partitionId, volume.id(), fs, io, stats);
        }

        // Flush soft-match candidates before orphan pruning.
        identityMatcher.flushToQueue(titleRepo.countAll());

        // Count stale-cleared: rows marked at start of this run that are now live (re-observed).
        int stillStaleFromThisRun = 0;
        for (String partitionId : partitionIds) {
            // For partition sync, count stale rows per partition that match this run's timestamp.
            stillStaleFromThisRun += titleLocationRepo.countStaleMarkedAt(volume.id(), nowIso);
        }
        // Avoid double-counting: countStaleMarkedAt counts all on the volume with this timestamp,
        // but partition sync may mark multiple partitions. Since nowIso is unique per-run and
        // applies across all partitions in this run, querying once is correct.
        stats.staleCleared = stats.staleMarked - titleLocationRepo.countStaleMarkedAt(volume.id(), nowIso);

        log.info("Partition sync scan complete — volume={} partitions={} staleMarked={} staleCleared={}",
                volume.id(), partitionIds, stats.staleMarked, stats.staleCleared);

        // Drop titles whose locations all disappeared AND their local cover files.
        // Also sweeps stale rows past the grace period.
        pruneOrphanedTitlesAndCovers(io, staleGraceDays, stats);

        finalizeSync(volume.id(), ctx, stats);
        printStats(stats, io);
    }
}
