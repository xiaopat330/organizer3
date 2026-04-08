package com.organizer3.sync;

import com.organizer3.config.volume.PartitionDef;
import com.organizer3.config.volume.VolumeConfig;
import com.organizer3.config.volume.VolumeStructureDef;
import com.organizer3.filesystem.VolumeFileSystem;
import com.organizer3.repository.ActressRepository;
import com.organizer3.repository.TitleActressRepository;
import com.organizer3.repository.TitleLocationRepository;
import com.organizer3.repository.TitleRepository;
import com.organizer3.repository.VideoRepository;
import com.organizer3.repository.VolumeRepository;
import com.organizer3.shell.SessionContext;
import com.organizer3.shell.io.CommandIO;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Syncs a named subset of unstructured partitions only (e.g., just {@code queue/}).
 *
 * <p>Clears and re-scans only the specified partitions, leaving the rest of the volume's
 * DB records untouched. Only applicable to unstructured partitions — the structured
 * {@code stars/} tree is never partially synced.
 */
public class PartitionSyncOperation extends AbstractSyncOperation {

    private final List<String> partitionIds;

    public PartitionSyncOperation(List<String> partitionIds,
                                  TitleRepository titleRepo, VideoRepository videoRepo,
                                  ActressRepository actressRepo, VolumeRepository volumeRepo,
                                  TitleLocationRepository titleLocationRepo,
                                  TitleActressRepository titleActressRepo,
                                  IndexLoader indexLoader) {
        super(titleRepo, videoRepo, actressRepo, volumeRepo, titleLocationRepo, titleActressRepo, indexLoader);
        this.partitionIds = partitionIds;
    }

    @Override
    public void execute(VolumeConfig volume, VolumeStructureDef structure,
                        VolumeFileSystem fs, SessionContext ctx, CommandIO io) throws IOException {
        io.println("Syncing " + volume.id() + " (partitions: " + partitionIds + ") ...");
        ensureVolumeRecord(volume);

        SyncStats stats = new SyncStats();
        Path root = Path.of("/");

        for (String partitionId : partitionIds) {
            PartitionDef partition = requirePartitionDef(structure, partitionId);
            Path partRoot = root.resolve(partition.path());
            io.println("  Scanning " + partitionId + "/ ...");

            // Clear only this partition's records before re-scanning
            videoRepo.deleteByVolumeAndPartition(volume.id(), partitionId);
            titleLocationRepo.deleteByVolumeAndPartition(volume.id(), partitionId);

            scanUnstructuredPartition(partRoot, partitionId, volume.id(), fs, io, stats);
        }

        // Remove titles with no remaining locations, then clean up orphaned cast rows
        titleRepo.deleteOrphaned();
        titleActressRepo.deleteOrphaned();

        finalizeSync(volume.id(), ctx);
        printStats(stats, io);
    }
}
