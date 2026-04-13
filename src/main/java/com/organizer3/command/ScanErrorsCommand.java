package com.organizer3.command;

import com.organizer3.config.AppConfig;
import com.organizer3.config.volume.PartitionDef;
import com.organizer3.config.volume.StructuredPartitionDef;
import com.organizer3.config.volume.VolumeConfig;
import com.organizer3.config.volume.VolumeStructureDef;
import com.organizer3.filesystem.VolumeFileSystem;
import com.organizer3.model.Actress;
import com.organizer3.repository.ActressRepository;
import com.organizer3.shell.SessionContext;
import com.organizer3.shell.io.CommandIO;
import com.organizer3.sync.scanner.ScannerSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Scans actress names on the mounted volume against the DB actress table and reports
 * name-order swaps, likely typos, and unrecognized names.
 *
 * <p>Unlike {@code check names} (which compares DB entries against each other), this command
 * reads what is actually on disk and checks each actress name against the canonical actress
 * table. The scanning mode is selected automatically from the mounted volume's structure:
 *
 * <ul>
 *   <li><b>Conventional</b> — {@code stars.partitions} non-empty: each {@code stars/<tier>/}
 *       sub-folder name is an actress name.</li>
 *   <li><b>Exhibition</b> — {@code stars.partitions} empty: actress folders live directly
 *       inside {@code stars/} with no tier sub-folders.</li>
 *   <li><b>Collections / Queue</b> — non-empty {@code unstructuredPartitions}: actress names
 *       are extracted from the title folder name prefix (same logic as sync scanners).</li>
 *   <li><b>Sort-pool</b> — empty partitions everywhere: the bespoke root + {@code /__later}
 *       layout used by {@code SortPoolScanner}.</li>
 * </ul>
 *
 * <p>Requires a volume to be mounted and connected.
 */
@Slf4j
@RequiredArgsConstructor
public class ScanErrorsCommand implements Command {

    /** Metadata sub-folders in unstructured partitions that contain no title folders. */
    private static final Set<String> UNSTRUCTURED_SKIP = Set.of("__covers");

    /** Top-level entries under {@code stars/} in exhibition volumes that are not actress folders. */
    private static final Set<String> EXHIBITION_SKIP = Set.of("temp");

    /** Prefix that marks special (non-title) folders at the root of sort_pool volumes. */
    private static final String SORT_POOL_SKIP_PREFIX = "__";

    /** Path to the deferred partition in sort_pool volumes. */
    private static final Path SORT_POOL_LATER = Path.of("/__later");

    private final ActressRepository actressRepo;
    private final ErrorScanService svc;

    @Override
    public String name() { return "scan errors"; }

    @Override
    public String description() {
        return "Scan actress names on the mounted volume and report swaps, typos, and unknowns.";
    }

    @Override
    public void execute(String[] args, SessionContext ctx, CommandIO io) {
        if (!ctx.isConnected()) {
            io.println("No volume mounted. Use: mount <id>");
            return;
        }

        VolumeConfig volume = ctx.getMountedVolume();
        VolumeStructureDef structure = AppConfig.get().volumes()
                .findStructureById(volume.structureType())
                .orElseThrow(() -> new IllegalStateException(
                        "No structure definition for type: " + volume.structureType()));

        VolumeFileSystem fs = ctx.getActiveConnection().fileSystem();
        Map<String, Path> nameToPath = collect(volume, structure, fs, io);

        if (nameToPath.isEmpty()) {
            io.println("No actress names found.");
            return;
        }

        List<Actress> dbActresses = actressRepo.findAll();
        io.println("Checking " + nameToPath.size() + " unique actress names against "
                + dbActresses.size() + " DB actresses...");

        ErrorScanService.ScanResults results =
                svc.scan(new ArrayList<>(nameToPath.keySet()), dbActresses);

        printReport(results, nameToPath, volume.smbPath(), io);
    }

    /**
     * Dispatches to the appropriate collection strategy based on the volume's structure.
     *
     * <ol>
     *   <li>Conventional — {@code structuredPartition} with non-empty {@code partitions}</li>
     *   <li>Exhibition — {@code structuredPartition} with empty {@code partitions}</li>
     *   <li>Collections / Queue — non-empty {@code unstructuredPartitions}</li>
     *   <li>Sort-pool — empty everywhere; bespoke root + {@code /__later}</li>
     * </ol>
     */
    private Map<String, Path> collect(VolumeConfig volume, VolumeStructureDef structure,
                                       VolumeFileSystem fs, CommandIO io) {
        StructuredPartitionDef stars = structure.structuredPartition();

        if (stars != null && !stars.partitions().isEmpty()) {
            io.println("Scanning stars/* tiers on volume '" + volume.id() + "' ...");
            return collectFromTieredStars(stars, fs, io);
        }

        if (stars != null) {
            io.println("Scanning stars/ on volume '" + volume.id() + "' ...");
            return collectFromFlatStars(stars, fs, io);
        }

        if (!structure.unstructuredPartitions().isEmpty()) {
            io.println("Scanning partitions on volume '" + volume.id() + "' ...");
            return collectFromUnstructured(structure.unstructuredPartitions(), fs, io);
        }

        io.println("Scanning sort_pool on volume '" + volume.id() + "' ...");
        return collectFromSortPool(fs, io);
    }

    // ── Collection strategies ────────────────────────────────────────────────

    /**
     * Conventional: actress folders are one level below each tier sub-folder
     * ({@code stars/<tier>/<ActressName>/}).
     */
    private Map<String, Path> collectFromTieredStars(StructuredPartitionDef stars,
                                                      VolumeFileSystem fs, CommandIO io) {
        Map<String, Path> nameToPath = new LinkedHashMap<>();
        Path starsRoot = Path.of("/").resolve(stars.path());

        for (PartitionDef tier : stars.partitions()) {
            Path tierRoot = starsRoot.resolve(tier.path());
            if (!fs.exists(tierRoot)) {
                io.println("  [skip] stars/" + tier.id() + "/ — not found");
                continue;
            }
            try {
                List<Path> actressFolders = fs.listDirectory(tierRoot).stream()
                        .filter(fs::isDirectory)
                        .toList();
                io.println("  stars/" + tier.id() + "/: " + actressFolders.size() + " folders");
                for (Path p : actressFolders) {
                    nameToPath.putIfAbsent(p.getFileName().toString(), p);
                }
            } catch (IOException e) {
                io.println("  [error] stars/" + tier.id() + "/: " + e.getMessage());
                log.warn("Error scanning stars/{}/", tier.id(), e);
            }
        }

        return nameToPath;
    }

    /**
     * Exhibition: actress folders live directly under {@code stars/} with no tier sub-folders
     * ({@code stars/<ActressName>/}).
     */
    private Map<String, Path> collectFromFlatStars(StructuredPartitionDef stars,
                                                    VolumeFileSystem fs, CommandIO io) {
        Map<String, Path> nameToPath = new LinkedHashMap<>();
        Path starsRoot = Path.of("/").resolve(stars.path());

        if (!fs.exists(starsRoot)) {
            io.println("  [skip] stars/ — not found");
            return nameToPath;
        }
        try {
            List<Path> actressFolders = fs.listDirectory(starsRoot).stream()
                    .filter(fs::isDirectory)
                    .filter(p -> !EXHIBITION_SKIP.contains(p.getFileName().toString()))
                    .toList();
            io.println("  stars/: " + actressFolders.size() + " actress folders");
            for (Path p : actressFolders) {
                nameToPath.putIfAbsent(p.getFileName().toString(), p);
            }
        } catch (IOException e) {
            io.println("  [error] stars/: " + e.getMessage());
            log.warn("Error scanning stars/", e);
        }

        return nameToPath;
    }

    /**
     * Collections / Queue: actress names are embedded in title folder name prefixes
     * (e.g., {@code "Actress1, Actress2 (CODE-123)"}). The representative path for each
     * unique name is the first title folder where it appeared.
     */
    private Map<String, Path> collectFromUnstructured(List<PartitionDef> partitions,
                                                       VolumeFileSystem fs, CommandIO io) {
        Map<String, Path> nameToPath = new LinkedHashMap<>();
        Path root = Path.of("/");

        for (PartitionDef partition : partitions) {
            Path partRoot = root.resolve(partition.path());
            if (!fs.exists(partRoot)) {
                io.println("  [skip] " + partition.id() + "/ — not found");
                continue;
            }
            try {
                List<Path> titleFolders = fs.listDirectory(partRoot).stream()
                        .filter(fs::isDirectory)
                        .filter(p -> !UNSTRUCTURED_SKIP.contains(p.getFileName().toString()))
                        .toList();
                int added = addNamesFromTitleFolders(titleFolders, nameToPath);
                io.println("  " + partition.id() + "/: " + titleFolders.size()
                        + " title folders, " + added + " new unique names");
            } catch (IOException e) {
                io.println("  [error] " + partition.id() + "/: " + e.getMessage());
                log.warn("Error scanning {}/", partition.id(), e);
            }
        }

        return nameToPath;
    }

    /**
     * Sort-pool: title folders at the volume root (excluding {@code __}-prefixed folders)
     * and inside {@code /__later}. Mirrors the hardcoded paths in {@code SortPoolScanner}.
     */
    private Map<String, Path> collectFromSortPool(VolumeFileSystem fs, CommandIO io) {
        Map<String, Path> nameToPath = new LinkedHashMap<>();
        Path root = Path.of("/");

        try {
            List<Path> poolFolders = fs.listDirectory(root).stream()
                    .filter(fs::isDirectory)
                    .filter(p -> !p.getFileName().toString().startsWith(SORT_POOL_SKIP_PREFIX))
                    .toList();
            int added = addNamesFromTitleFolders(poolFolders, nameToPath);
            io.println("  pool/: " + poolFolders.size() + " title folders, " + added + " new unique names");
        } catch (IOException e) {
            io.println("  [error] pool/: " + e.getMessage());
            log.warn("Error scanning sort_pool root", e);
        }

        if (!fs.exists(SORT_POOL_LATER)) {
            io.println("  [skip] __later/ — not found");
        } else {
            try {
                List<Path> laterFolders = fs.listDirectory(SORT_POOL_LATER).stream()
                        .filter(fs::isDirectory)
                        .toList();
                int added = addNamesFromTitleFolders(laterFolders, nameToPath);
                io.println("  __later/: " + laterFolders.size() + " title folders, " + added + " new unique names");
            } catch (IOException e) {
                io.println("  [error] __later/: " + e.getMessage());
                log.warn("Error scanning __later/", e);
            }
        }

        return nameToPath;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Extracts actress names from each title folder's name prefix and inserts new entries
     * into {@code nameToPath}. Returns the count of newly added unique names.
     * "Various" is filtered out as it is a sentinel for unknown/multi-actress titles.
     */
    private static int addNamesFromTitleFolders(List<Path> titleFolders,
                                                 Map<String, Path> nameToPath) {
        int added = 0;
        for (Path titleFolder : titleFolders) {
            List<String> names = ScannerSupport.extractAllActressNames(
                    titleFolder.getFileName().toString());
            for (String name : names) {
                if (!name.equalsIgnoreCase("various")
                        && nameToPath.putIfAbsent(name, titleFolder) == null) {
                    added++;
                }
            }
        }
        return added;
    }

    private void printReport(ErrorScanService.ScanResults results,
                             Map<String, Path> nameToPath,
                             String smbBase, CommandIO io) {
        if (!results.swaps().isEmpty()) {
            io.println("\n── Name-order swaps ──────────────────────────────────────────");
            for (var r : results.swaps()) {
                io.println(String.format("[SWAP]  \"%s\"  →  DB: \"%s\"",
                        r.folderName(), r.dbMatch().getCanonicalName()));
                io.println("        " + smbPath(smbBase, nameToPath.get(r.folderName())));
            }
        }

        if (!results.typos().isEmpty()) {
            io.println("\n── Possible typos ────────────────────────────────────────────");
            for (var r : results.typos()) {
                String detail = r.dist() == 0 ? "romanization variant" : "dist=" + r.dist();
                io.println(String.format("[TYPO]  \"%s\"  →  DB: \"%s\"  (%s)",
                        r.folderName(), r.dbMatch().getCanonicalName(), detail));
                io.println("        " + smbPath(smbBase, nameToPath.get(r.folderName())));
            }
        }

        if (!results.unknowns().isEmpty()) {
            io.println("\n── Unrecognized names ────────────────────────────────────────");
            for (var r : results.unknowns()) {
                io.println("[UNKNOWN]  \"" + r.folderName() + "\"  —  no close DB match");
                io.println("           " + smbPath(smbBase, nameToPath.get(r.folderName())));
            }
        }

        io.println("");
        if (!results.hasIssues()) {
            io.println("No issues found. All " + results.totalScanned()
                    + " actress names match DB entries.");
        } else {
            io.println(String.format("Scanned %d names · %d swap(s) · %d typo(s) · %d unknown(s)",
                    results.totalScanned(),
                    results.swaps().size(),
                    results.typos().size(),
                    results.unknowns().size()));
        }
    }

    private static String smbPath(String smbBase, Path fsPath) {
        return smbBase + fsPath.toString() + "/";
    }
}
