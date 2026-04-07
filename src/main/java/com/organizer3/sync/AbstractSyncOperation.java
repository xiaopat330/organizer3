package com.organizer3.sync;

import com.organizer3.config.volume.PartitionDef;
import com.organizer3.filesystem.VolumeFileSystem;
import com.organizer3.model.Actress;
import com.organizer3.model.Title;
import com.organizer3.model.Video;
import com.organizer3.model.Volume;
import com.organizer3.repository.ActressRepository;
import com.organizer3.repository.TitleRepository;
import com.organizer3.repository.VideoRepository;
import com.organizer3.repository.VolumeRepository;
import com.organizer3.shell.io.CommandIO;
import com.organizer3.shell.io.Progress;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared helpers for sync operation implementations.
 *
 * <p>Subclasses decide what scope to scan; this class provides the mechanics of scanning
 * a partition folder, parsing title codes, and persisting titles and videos.
 */
@Slf4j
@RequiredArgsConstructor(access = AccessLevel.PROTECTED)
abstract class AbstractSyncOperation implements SyncOperation {

    protected final TitleRepository titleRepo;
    protected final VideoRepository videoRepo;
    protected final ActressRepository actressRepo;
    protected final VolumeRepository volumeRepo;
    protected final IndexLoader indexLoader;
    private final TitleCodeParser codeParser = new TitleCodeParser();

    // -------------------------------------------------------------------------
    // Shared scanning helpers
    // -------------------------------------------------------------------------

    /**
     * Ensures the volume record exists in the DB (insert or no-op if already present).
     */
    protected void ensureVolumeRecord(com.organizer3.config.volume.VolumeConfig volume) {
        if (volumeRepo.findById(volume.id()).isEmpty()) {
            volumeRepo.save(new Volume(volume.id(), volume.structureType()));
        }
    }

    /**
     * Scans an unstructured partition: immediate child directories are title folders.
     * When {@code actressId} is null, attempts to infer the actress from each folder name
     * (e.g., "Marin Yakuno (IPZZ-679)" → actress "Marin Yakuno").
     * Returns the number of titles saved.
     */
    protected int scanUnstructuredPartition(Path partitionRoot, String partitionId,
                                            String volumeId, Long actressId,
                                            VolumeFileSystem fs, CommandIO io,
                                            SyncStats stats) throws IOException {
        if (!fs.exists(partitionRoot)) {
            io.println("  [skip] " + partitionRoot + " — not found");
            return 0;
        }
        List<Path> titleFolders = fs.listDirectory(partitionRoot).stream()
                .filter(fs::isDirectory)
                .toList();
        if (titleFolders.isEmpty()) return 0;

        try (Progress progress = io.startProgress(partitionId + "/", titleFolders.size())) {
            for (Path child : titleFolders) {
                progress.setLabel(child.getFileName().toString());
                Long resolvedActressId = actressId;
                if (resolvedActressId == null) {
                    resolvedActressId = inferActressFromFolderName(child.getFileName().toString());
                }
                saveTitleAndVideos(child, volumeId, partitionId, resolvedActressId, fs);
                progress.advance();
            }
        }
        int count = titleFolders.size();
        stats.addTitles(partitionId, count);
        return count;
    }

    /**
     * Scans an actress-folder tree: child dirs are actress folders, and inside each actress
     * folder are title folders. Works for both flat {@code stars/} layouts (no tier sub-folders)
     * and tiered layouts ({@code stars/popular/}, {@code stars/goddess/}, etc.).
     * Returns the number of titles saved.
     */
    protected int scanStarsFolder(Path root, String partitionId, String progressLabel,
                                  String volumeId, Actress.Tier actressTier,
                                  VolumeFileSystem fs, CommandIO io,
                                  SyncStats stats) throws IOException {
        if (!fs.exists(root)) {
            io.println("  [skip] " + root + " — not found");
            return 0;
        }
        List<Path> actressFolders = fs.listDirectory(root).stream()
                .filter(fs::isDirectory)
                .toList();
        if (actressFolders.isEmpty()) return 0;

        int count = 0;
        try (Progress progress = io.startProgress(progressLabel, actressFolders.size())) {
            for (Path actressFolder : actressFolders) {
                String actressName = actressFolder.getFileName().toString();
                progress.setLabel(actressName);
                Actress actress = resolveOrCreateActress(actressName, actressTier);
                stats.addActress(actress.getId());
                for (Path titleFolder : fs.listDirectory(actressFolder)) {
                    if (fs.isDirectory(titleFolder)) {
                        saveTitleAndVideos(titleFolder, volumeId, partitionId, actress.getId(), fs);
                        count++;
                    }
                }
                progress.advance();
            }
        }
        stats.addTitles(partitionId, count);
        return count;
    }

    private void saveTitleAndVideos(Path titleFolder, String volumeId, String partitionId,
                                    Long actressId, VolumeFileSystem fs) throws IOException {
        String folderName = titleFolder.getFileName().toString();
        TitleCodeParser.ParsedCode parsed = codeParser.parse(folderName);

        Title title = titleRepo.save(Title.builder()
                .code(parsed.code())
                .baseCode(parsed.baseCode())
                .label(parsed.label())
                .seqNum(parsed.seqNum())
                .volumeId(volumeId)
                .partitionId(partitionId)
                .actressId(actressId)
                .path(titleFolder)
                .lastSeenAt(LocalDate.now())
                .addedDate(computeAddedDate(titleFolder, fs))
                .build());

        saveVideosForTitle(titleFolder, title.getId(), fs);
    }

    /**
     * Estimates when a title was added by taking the earliest last-modified date from all
     * files directly in the title folder and all files one level deep in subdirectories.
     * Returns {@code null} if the folder contains no files.
     */
    private LocalDate computeAddedDate(Path titleFolder, VolumeFileSystem fs) throws IOException {
        LocalDate earliest = null;
        List<Path> children = fs.listDirectory(titleFolder);
        for (Path child : children) {
            if (!fs.isDirectory(child)) {
                earliest = earlierOf(earliest, fs.getLastModifiedDate(child));
            }
        }
        for (Path child : children) {
            if (fs.isDirectory(child)) {
                for (Path grandchild : fs.listDirectory(child)) {
                    if (!fs.isDirectory(grandchild)) {
                        earliest = earlierOf(earliest, fs.getLastModifiedDate(grandchild));
                    }
                }
            }
        }
        return earliest;
    }

    private static LocalDate earlierOf(LocalDate a, LocalDate b) {
        if (b == null) return a;
        return (a == null || b.isBefore(a)) ? b : a;
    }

    private void saveVideosForTitle(Path titleFolder, long titleId,
                                    VolumeFileSystem fs) throws IOException {
        // Direct video files in the title folder
        for (Path child : fs.listDirectory(titleFolder)) {
            if (!fs.isDirectory(child) && MediaExtensions.isVideo(child)) {
                videoRepo.save(Video.builder().titleId(titleId)
                        .filename(child.getFileName().toString()).path(child).lastSeenAt(LocalDate.now()).build());
            }
        }
        // Optional video/ subdirectory
        Path videoSubdir = titleFolder.resolve("video");
        if (fs.exists(videoSubdir) && fs.isDirectory(videoSubdir)) {
            for (Path child : fs.listDirectory(videoSubdir)) {
                if (!fs.isDirectory(child) && MediaExtensions.isVideo(child)) {
                    videoRepo.save(Video.builder().titleId(titleId)
                            .filename(child.getFileName().toString()).path(child).lastSeenAt(LocalDate.now()).build());
                }
            }
        }
    }

    // Matches "Actress Name (CODE-123)" or "Actress Name - Suffix (CODE-123)"
    private static final Pattern ACTRESS_PREFIX = Pattern.compile(
            "^(.+?)\\s*(?:-\\s*[^(]+)?\\s*\\(");

    /**
     * Extracts the actress name from a folder name like "Marin Yakuno (IPZZ-679)".
     * For multi-actress folders (comma-separated), returns only the first actress name.
     * Returns null if no name can be extracted.
     */
    static String extractActressName(String folderName) {
        Matcher m = ACTRESS_PREFIX.matcher(folderName);
        if (!m.find()) return null;
        String rawName = m.group(1).trim();
        if (rawName.isEmpty()) return null;
        int comma = rawName.indexOf(',');
        if (comma > 0) rawName = rawName.substring(0, comma).trim();
        return rawName;
    }

    /**
     * Extracts the actress name from a folder name and resolves (or creates) it via the
     * alias system. New actresses discovered in queue folders default to {@code LIBRARY} tier.
     * Returns null if no actress name can be extracted.
     */
    private Long inferActressFromFolderName(String folderName) {
        String name = extractActressName(folderName);
        if (name == null) return null;
        return resolveOrCreateActress(name, Actress.Tier.LIBRARY).getId();
    }

    /**
     * Resolves an actress by name (canonical or alias). Creates a new record if not found.
     */
    protected Actress resolveOrCreateActress(String name, Actress.Tier tier) {
        return actressRepo.resolveByName(name).orElseGet(() -> {
            log.debug("New actress discovered during sync: {}", name);
            return actressRepo.save(Actress.builder()
                    .canonicalName(name)
                    .tier(tier)
                    .firstSeenAt(LocalDate.now())
                    .build());
        });
    }

    /**
     * Maps a star sub-partition id to an {@link Actress.Tier}.
     * Non-tier sub-partitions (favorites, archive) default to {@code LIBRARY}.
     */
    protected static Actress.Tier toActressTier(String partitionId) {
        return switch (partitionId) {
            case "minor"     -> Actress.Tier.MINOR;
            case "popular"   -> Actress.Tier.POPULAR;
            case "superstar" -> Actress.Tier.SUPERSTAR;
            case "goddess"   -> Actress.Tier.GODDESS;
            default          -> Actress.Tier.LIBRARY;
        };
    }

    /**
     * Stamps the volume's {@code last_synced_at} to now and reloads the in-memory index.
     */
    protected void finalizeSync(String volumeId,
                                com.organizer3.shell.SessionContext ctx) {
        volumeRepo.updateLastSyncedAt(volumeId, LocalDateTime.now());
        ctx.setIndex(indexLoader.load(volumeId));
    }

    protected void printStats(SyncStats stats, CommandIO io) {
        io.println("Sync complete.");
        io.println("  Actresses:  " + stats.actressCount());
        io.printlnAnsi("  Queue:      \033[32m" + stats.queue     + "\033[0m");
        io.printlnAnsi("  Attention:  \033[31m" + stats.attention + "\033[0m");
        io.printlnAnsi("  Total:      \033[36m" + stats.total     + "\033[0m");
    }

    // Expose partition def lookup for unstructured partitions (used by PartitionSyncOperation)
    protected static PartitionDef requirePartitionDef(
            com.organizer3.config.volume.VolumeStructureDef structure, String partitionId) {
        return structure.findUnstructuredById(partitionId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No unstructured partition '" + partitionId
                                + "' in structure '" + structure.id() + "'"));
    }
}
