package com.organizer3.sync;

import com.organizer3.config.volume.PartitionDef;
import com.organizer3.config.volume.StructuredPartitionDef;
import com.organizer3.config.volume.VolumeConfig;
import com.organizer3.config.volume.VolumeStructureDef;
import com.organizer3.filesystem.VolumeFileSystem;
import com.organizer3.model.Actress;
import com.organizer3.repository.ActressRepository;
import com.organizer3.repository.TitleRepository;
import com.organizer3.repository.VideoRepository;
import com.organizer3.repository.VolumeRepository;
import com.organizer3.shell.SessionContext;
import com.organizer3.shell.io.CommandIO;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Syncs the entire volume: all unstructured partitions and the full {@code stars/} tree.
 *
 * <p>Clears all existing title and video records for the volume before scanning, then
 * re-populates from the current filesystem state.
 */
public class FullSyncOperation extends AbstractSyncOperation {

    public FullSyncOperation(TitleRepository titleRepo, VideoRepository videoRepo,
                             ActressRepository actressRepo, VolumeRepository volumeRepo,
                             IndexLoader indexLoader) {
        super(titleRepo, videoRepo, actressRepo, volumeRepo, indexLoader);
    }

    @Override
    public void execute(VolumeConfig volume, VolumeStructureDef structure,
                        VolumeFileSystem fs, SessionContext ctx, CommandIO io) throws IOException {
        io.println("Syncing " + volume.id() + " (full) ...");
        ensureVolumeRecord(volume);

        // Clear existing records — videos first (FK), then titles
        videoRepo.deleteByVolume(volume.id());
        titleRepo.deleteByVolume(volume.id());

        SyncStats stats = new SyncStats();
        Path root = Path.of("/");

        // Unstructured partitions
        for (PartitionDef partition : structure.unstructuredPartitions()) {
            Path partRoot = root.resolve(partition.path());
            io.println("  Scanning " + partition.id() + "/ ...");
            scanUnstructuredPartition(partRoot, partition.id(), volume.id(), null, fs, io, stats);
        }

        // Structured partition (stars/)
        StructuredPartitionDef stars = structure.structuredPartition();
        if (stars != null) {
            Path starsRoot = root.resolve(stars.path());
            if (stars.partitions() == null || stars.partitions().isEmpty()) {
                // Flat layout: actress folders sit directly under stars/
                io.println("  Scanning stars/ ...");
                scanStarsFolder(starsRoot, "stars", "stars/",
                        volume.id(), Actress.Tier.LIBRARY, fs, io, stats);
            } else {
                for (PartitionDef sub : stars.partitions()) {
                    Path tierRoot = starsRoot.resolve(sub.path());
                    io.println("  Scanning stars/" + sub.id() + "/ ...");
                    scanStarsFolder(tierRoot, "stars/" + sub.id(), "stars/" + sub.id(),
                            volume.id(), toActressTier(sub.id()), fs, io, stats);
                }
            }
        }

        finalizeSync(volume.id(), ctx);
        printStats(stats, io);
    }
}
