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
import java.util.Set;

import static com.organizer3.sync.scanner.ScannerSupport.*;

/**
 * Scanner for collections volumes: all unstructured partitions where each title folder
 * encodes one or more actress names followed by the JAV code in parentheses.
 *
 * <p>Folder name format:
 * <pre>
 *   Actress1, Actress2[, ...] [- Suffix] (CODE-123)
 *   Various (CODE-123)              ← too many actresses; treated as no-cast
 * </pre>
 *
 * <p>Each comma-separated name before the parenthesized code is resolved as a distinct
 * actress. Folders named {@code "Various (...)"} produce an empty cast list (actress_id
 * stays null; no junction rows are written). Future special handling for these titles
 * is expected but not yet implemented.
 *
 * <p>Metadata-only sub-folders ({@code __covers}) are skipped — they contain cover
 * images, not title content.
 */
public class CollectionsScanner implements VolumeScanner {

    /** Sub-folder names that contain metadata, not titles — skipped during scan. */
    private static final Set<String> SKIP_FOLDERS = Set.of("__covers");

    @Override
    public boolean isCoverScannable(String partitionId) {
        return true;
    }

    @Override
    public List<DiscoveredTitle> scan(VolumeStructureDef structure, VolumeFileSystem fs,
                                      CommandIO io) throws IOException {
        List<DiscoveredTitle> results = new ArrayList<>();
        Path root = Path.of("/");

        for (PartitionDef partition : structure.unstructuredPartitions()) {
            Path partRoot = root.resolve(partition.path());
            io.println("  Scanning " + partition.id() + "/ ...");

            List<Path> all = listSubdirectories(partRoot, fs);
            if (all.isEmpty()) {
                if (!fs.exists(partRoot)) {
                    io.println("  [skip] " + partRoot + " — not found");
                }
                continue;
            }

            List<Path> titleFolders = all.stream()
                    .filter(p -> !SKIP_FOLDERS.contains(p.getFileName().toString()))
                    .toList();

            if (titleFolders.isEmpty()) continue;

            try (Progress progress = io.startProgress(partition.id() + "/", titleFolders.size())) {
                for (Path child : titleFolders) {
                    String folderName = child.getFileName().toString();
                    progress.setLabel(folderName);
                    List<String> actressNames = parseActressNames(folderName);
                    results.add(new DiscoveredTitle(child, partition.id(), actressNames, Actress.Tier.LIBRARY));
                    progress.advance();
                }
            }
        }

        return results;
    }

    /**
     * Parses actress names from a collections folder name.
     *
     * <ul>
     *   <li>{@code "Aika, Yui Hatano (HMN-102)"} → {@code ["Aika", "Yui Hatano"]}
     *   <li>{@code "Ai Mukai, Rena Aoi - Demosaiced (MVSD-503)"} → {@code ["Ai Mukai", "Rena Aoi"]}
     *   <li>{@code "Various (DCX-137)"} → {@code []} (empty — special sentinel)
     *   <li>{@code "Various - Demosaiced (MIBD-917)"} → {@code []}
     * </ul>
     */
    static List<String> parseActressNames(String folderName) {
        List<String> names = extractAllActressNames(folderName);
        if (names.size() == 1 && names.get(0).equalsIgnoreCase("various")) {
            return List.of();
        }
        return names;
    }
}
