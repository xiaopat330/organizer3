package com.organizer3.sync.scanner;

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
 * Scanner for sort_pool volumes: a flat root directory containing title folders directly,
 * where each title folder name encodes the actress name and JAV code (e.g., "Actress Name (CODE-123)").
 *
 * <p>Structure:
 * <pre>
 *   /                    ← volume root (= smbPath)
 *     Actress (CODE)/    ← title folder (partition: pool)
 *     __later/           ← deferred items (partition: later)
 *       Actress (CODE)/
 *       (CODE)/          ← code-only, no actress name
 * </pre>
 *
 * <p>Folders starting with {@code __} at the root are skipped (they are special, not titles).
 * {@code __later} is then scanned separately as its own partition.
 */
public class SortPoolScanner implements VolumeScanner {

    private static final String PARTITION_POOL  = "pool";
    private static final String PARTITION_LATER = "later";
    private static final Path   LATER_PATH      = Path.of("/__later");

    @Override
    public boolean isCoverScannable(String partitionId) {
        return PARTITION_POOL.equals(partitionId) || PARTITION_LATER.equals(partitionId);
    }

    @Override
    public List<DiscoveredTitle> scan(VolumeStructureDef structure, VolumeFileSystem fs,
                                      CommandIO io) throws IOException {
        List<DiscoveredTitle> results = new ArrayList<>();
        results.addAll(scanRoot(fs, io));
        results.addAll(scanLater(fs, io));
        return results;
    }

    private List<DiscoveredTitle> scanRoot(VolumeFileSystem fs, CommandIO io) throws IOException {
        Path root = Path.of("/");
        io.println("  Scanning pool/ ...");

        List<Path> all = listSubdirectories(root, fs);
        List<Path> titleFolders = all.stream()
                .filter(p -> !p.getFileName().toString().startsWith("__"))
                .toList();

        if (titleFolders.isEmpty()) {
            if (!fs.exists(root)) io.println("  [skip] / — not found");
            return List.of();
        }

        List<DiscoveredTitle> results = new ArrayList<>();
        try (Progress progress = io.startProgress(PARTITION_POOL + "/", titleFolders.size())) {
            for (Path child : titleFolders) {
                String folderName = child.getFileName().toString();
                progress.setLabel(folderName);
                String actressName = extractActressName(folderName);
                results.add(new DiscoveredTitle(child, PARTITION_POOL, actressName, Actress.Tier.LIBRARY));
                progress.advance();
            }
        }
        return results;
    }

    private List<DiscoveredTitle> scanLater(VolumeFileSystem fs, CommandIO io) throws IOException {
        io.println("  Scanning __later/ ...");

        if (!fs.exists(LATER_PATH)) {
            io.println("  [skip] __later — not found");
            return List.of();
        }

        List<Path> titleFolders = listSubdirectories(LATER_PATH, fs);
        if (titleFolders.isEmpty()) return List.of();

        List<DiscoveredTitle> results = new ArrayList<>();
        try (Progress progress = io.startProgress(PARTITION_LATER + "/", titleFolders.size())) {
            for (Path child : titleFolders) {
                String folderName = child.getFileName().toString();
                progress.setLabel(folderName);
                String actressName = extractActressName(folderName);
                results.add(new DiscoveredTitle(child, PARTITION_LATER, actressName, Actress.Tier.LIBRARY));
                progress.advance();
            }
        }
        return results;
    }
}
