package com.organizer3.sync;

import com.organizer3.config.volume.PartitionDef;
import com.organizer3.config.volume.StructuredPartitionDef;
import com.organizer3.config.volume.VolumeConfig;
import com.organizer3.config.volume.VolumeStructureDef;
import com.organizer3.filesystem.VolumeFileSystem;
import com.organizer3.repository.ActressRepository;
import com.organizer3.repository.TitleRepository;
import com.organizer3.repository.VideoRepository;
import com.organizer3.repository.VolumeRepository;
import com.organizer3.shell.SessionContext;

import java.io.IOException;
import java.io.PrintWriter;
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
                        VolumeFileSystem fs, SessionContext ctx, PrintWriter out) throws IOException {
        out.println("Syncing " + volume.id() + " (full) ...");
        ensureVolumeRecord(volume);

        // Clear existing records — videos first (FK), then titles
        videoRepo.deleteByVolume(volume.id());
        titleRepo.deleteByVolume(volume.id());

        int count = 0;
        Path mountPoint = volume.mountPoint();

        // Unstructured partitions
        for (PartitionDef partition : structure.unstructuredPartitions()) {
            Path partRoot = mountPoint.resolve(partition.path());
            out.println("  Scanning " + partition.id() + "/ ...");
            count += scanUnstructuredPartition(partRoot, partition.id(),
                    volume.id(), null, fs, out);
        }

        // Structured partition (stars/)
        StructuredPartitionDef stars = structure.structuredPartition();
        if (stars != null) {
            Path starsRoot = mountPoint.resolve(stars.path());
            for (PartitionDef sub : stars.partitions()) {
                Path tierRoot = starsRoot.resolve(sub.path());
                out.println("  Scanning stars/" + sub.id() + "/ ...");
                count += scanStarPartition(tierRoot, "stars/" + sub.id(),
                        volume.id(), toActressTier(sub.id()), fs, out);
            }
        }

        finalizeSync(volume.id(), ctx);
        printStats(count, out);
    }
}
