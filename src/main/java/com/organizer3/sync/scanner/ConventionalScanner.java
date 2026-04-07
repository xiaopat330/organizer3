package com.organizer3.sync.scanner;

import com.organizer3.config.volume.PartitionDef;
import com.organizer3.config.volume.StructuredPartitionDef;
import com.organizer3.config.volume.VolumeStructureDef;
import com.organizer3.filesystem.VolumeFileSystem;
import com.organizer3.model.Actress;
import com.organizer3.shell.io.CommandIO;
import com.organizer3.shell.io.Progress;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static com.organizer3.sync.scanner.ScannerSupport.*;

/**
 * Scanner for conventional volumes: unstructured partitions (queue, archive, etc.)
 * plus a tiered {@code stars/} tree ({@code stars/library}, {@code stars/popular}, etc.).
 *
 * <p>Inside each tier sub-folder, child directories are actress folders, and inside each
 * actress folder are title folders (one level deep, no recursion).
 */
public class ConventionalScanner implements VolumeScanner {

    @Override
    public List<DiscoveredTitle> scan(VolumeStructureDef structure, VolumeFileSystem fs,
                                      CommandIO io) throws IOException {
        List<DiscoveredTitle> results = new ArrayList<>();
        Path root = Path.of("/");

        // Unstructured partitions
        for (PartitionDef partition : structure.unstructuredPartitions()) {
            Path partRoot = root.resolve(partition.path());
            io.println("  Scanning " + partition.id() + "/ ...");
            results.addAll(scanUnstructured(partRoot, partition.id(), fs, io));
        }

        // Structured partition (stars/)
        StructuredPartitionDef stars = structure.structuredPartition();
        if (stars != null) {
            Path starsRoot = root.resolve(stars.path());
            for (PartitionDef sub : stars.partitions()) {
                Path tierRoot = starsRoot.resolve(sub.path());
                String partitionId = "stars/" + sub.id();
                io.println("  Scanning stars/" + sub.id() + "/ ...");
                results.addAll(scanTieredStars(tierRoot, partitionId, sub.id(), fs, io));
            }
        }

        return results;
    }

    @Override
    public boolean isCoverScannable(String partitionId) {
        return partitionId.startsWith("stars/") || "queue".equals(partitionId);
    }

    private List<DiscoveredTitle> scanUnstructured(Path partRoot, String partitionId,
                                                    VolumeFileSystem fs, CommandIO io) throws IOException {
        List<Path> titleFolders = listSubdirectories(partRoot, fs);
        if (titleFolders.isEmpty()) {
            if (!fs.exists(partRoot)) {
                io.println("  [skip] " + partRoot + " — not found");
            }
            return List.of();
        }

        List<DiscoveredTitle> results = new ArrayList<>();
        try (Progress progress = io.startProgress(partitionId + "/", titleFolders.size())) {
            for (Path child : titleFolders) {
                String folderName = child.getFileName().toString();
                progress.setLabel(folderName);
                String actressName = extractActressName(folderName);
                results.add(new DiscoveredTitle(child, partitionId, actressName, Actress.Tier.LIBRARY));
                progress.advance();
            }
        }
        return results;
    }

    private List<DiscoveredTitle> scanTieredStars(Path tierRoot, String partitionId,
                                                   String tierId, VolumeFileSystem fs,
                                                   CommandIO io) throws IOException {
        List<Path> actressFolders = listSubdirectories(tierRoot, fs);
        if (actressFolders.isEmpty()) {
            if (!fs.exists(tierRoot)) {
                io.println("  [skip] " + tierRoot + " — not found");
            }
            return List.of();
        }

        Actress.Tier tier = toActressTier(tierId);
        List<DiscoveredTitle> results = new ArrayList<>();
        try (Progress progress = io.startProgress("stars/" + tierId, actressFolders.size())) {
            for (Path actressFolder : actressFolders) {
                String actressName = actressFolder.getFileName().toString();
                progress.setLabel(actressName);
                for (Path titleFolder : fs.listDirectory(actressFolder).stream()
                        .filter(fs::isDirectory).toList()) {
                    results.add(new DiscoveredTitle(titleFolder, partitionId, actressName, tier));
                }
                progress.advance();
            }
        }
        return results;
    }
}
