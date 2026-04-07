package com.organizer3.sync.scanner;

import com.organizer3.config.volume.PartitionDef;
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
 * Scanner for queue/intake volumes: flat unstructured partitions where child
 * directories are title folders directly (no actress sub-folders).
 *
 * <p>Attempts to infer the actress name from each folder name (e.g.,
 * "Marin Yakuno (IPZZ-679)" → "Marin Yakuno").
 */
public class QueueScanner implements VolumeScanner {

    @Override
    public List<DiscoveredTitle> scan(VolumeStructureDef structure, VolumeFileSystem fs,
                                      CommandIO io) throws IOException {
        List<DiscoveredTitle> results = new ArrayList<>();
        Path root = Path.of("/");

        for (PartitionDef partition : structure.unstructuredPartitions()) {
            Path partRoot = root.resolve(partition.path());
            io.println("  Scanning " + partition.id() + "/ ...");

            List<Path> titleFolders = listSubdirectories(partRoot, fs);
            if (titleFolders.isEmpty()) {
                if (!fs.exists(partRoot)) {
                    io.println("  [skip] " + partRoot + " — not found");
                }
                continue;
            }

            try (Progress progress = io.startProgress(partition.id() + "/", titleFolders.size())) {
                for (Path child : titleFolders) {
                    String folderName = child.getFileName().toString();
                    progress.setLabel(folderName);
                    String actressName = extractActressName(folderName);
                    results.add(new DiscoveredTitle(child, partition.id(), actressName, Actress.Tier.LIBRARY));
                    progress.advance();
                }
            }
        }

        return results;
    }
}
