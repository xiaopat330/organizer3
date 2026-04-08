package com.organizer3.sync.scanner;

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
import java.util.Set;

import static com.organizer3.sync.scanner.ScannerSupport.*;

/**
 * Scanner for exhibition volumes (e.g., QNAP): a single {@code stars/} root containing
 * actress folders directly, with no tier sub-folders.
 *
 * <p>Inside each actress folder, titles may live at the top level or inside arbitrary
 * subfolders (e.g., {@code favorites/}, {@code processed/}, {@code meh/}). The scanner
 * recursively walks each actress folder, collecting any directory whose name contains
 * a parseable JAV code as a title. Directories without a parseable code are treated as
 * subfolders and recursed into, except for known image-only folders which are skipped.
 *
 * <p>All titles are assigned partition ID {@code "stars"} and tier {@code LIBRARY},
 * regardless of their subfolder location. The full path to the title is preserved in the
 * {@link DiscoveredTitle#path()} for precise file operations later.
 */
public class ExhibitionScanner implements VolumeScanner {

    private static final String PARTITION_ID = "stars";

    /** Folders inside actress directories that contain only images, not titles. */
    private static final Set<String> SKIP_FOLDERS = Set.of(
            "covers", "_covers", "cover"
    );

    /** Top-level entries under stars/ that are not actress folders. */
    private static final Set<String> SKIP_TOP_LEVEL = Set.of(
            "temp"
    );

    @Override
    public boolean isCoverScannable(String partitionId) {
        return "stars".equals(partitionId);
    }

    @Override
    public List<DiscoveredTitle> scan(VolumeStructureDef structure, VolumeFileSystem fs,
                                      CommandIO io) throws IOException {
        StructuredPartitionDef stars = structure.structuredPartition();
        if (stars == null) return List.of();

        Path starsRoot = Path.of("/").resolve(stars.path());
        io.println("  Scanning stars/ ...");

        List<Path> actressFolders = listSubdirectories(starsRoot, fs);
        if (actressFolders.isEmpty()) {
            if (!fs.exists(starsRoot)) {
                io.println("  [skip] " + starsRoot + " — not found");
            }
            return List.of();
        }

        List<DiscoveredTitle> results = new ArrayList<>();
        try (Progress progress = io.startProgress("stars/", actressFolders.size())) {
            for (Path actressFolder : actressFolders) {
                String actressName = actressFolder.getFileName().toString();
                if (SKIP_TOP_LEVEL.contains(actressName)) {
                    progress.advance();
                    continue;
                }
                progress.setLabel(actressName);
                collectTitles(actressFolder, actressName, fs, results);
                progress.advance();
            }
        }

        return results;
    }

    /**
     * Recursively walks the contents of an actress folder, collecting title folders.
     * A directory is a title if its name contains a parseable JAV code; otherwise it
     * is treated as a subfolder and recursed into (unless it is a known skip folder).
     */
    private void collectTitles(Path dir, String actressName, VolumeFileSystem fs,
                                List<DiscoveredTitle> results) throws IOException {
        List<Path> children = fs.listDirectory(dir).stream()
                .filter(fs::isDirectory).toList();
        for (Path child : children) {
            String name = child.getFileName().toString();

            if (SKIP_FOLDERS.contains(name.toLowerCase())) {
                continue;
            }

            if (hasParenthesizedTitleCode(name)) {
                results.add(new DiscoveredTitle(child, PARTITION_ID, List.of(actressName), Actress.Tier.LIBRARY));
            } else {
                // Arbitrary subfolder — recurse to find titles inside
                collectTitles(child, actressName, fs, results);
            }
        }
    }
}
