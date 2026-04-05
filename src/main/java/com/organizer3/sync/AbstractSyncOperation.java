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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Shared helpers for sync operation implementations.
 *
 * <p>Subclasses decide what scope to scan; this class provides the mechanics of scanning
 * a partition folder, parsing title codes, and persisting titles and videos.
 */
abstract class AbstractSyncOperation implements SyncOperation {

    private static final Logger log = LoggerFactory.getLogger(AbstractSyncOperation.class);

    protected final TitleRepository titleRepo;
    protected final VideoRepository videoRepo;
    protected final ActressRepository actressRepo;
    protected final VolumeRepository volumeRepo;
    protected final IndexLoader indexLoader;
    private final TitleCodeParser codeParser = new TitleCodeParser();

    protected AbstractSyncOperation(TitleRepository titleRepo, VideoRepository videoRepo,
                                    ActressRepository actressRepo, VolumeRepository volumeRepo,
                                    IndexLoader indexLoader) {
        this.titleRepo   = titleRepo;
        this.videoRepo   = videoRepo;
        this.actressRepo = actressRepo;
        this.volumeRepo  = volumeRepo;
        this.indexLoader = indexLoader;
    }

    // -------------------------------------------------------------------------
    // Shared scanning helpers
    // -------------------------------------------------------------------------

    /**
     * Ensures the volume record exists in the DB (insert or no-op if already present).
     */
    protected void ensureVolumeRecord(com.organizer3.config.volume.VolumeConfig volume) {
        if (volumeRepo.findById(volume.id()).isEmpty()) {
            volumeRepo.save(new Volume(volume.id(), volume.mountPoint(), volume.structureType()));
        }
    }

    /**
     * Scans an unstructured partition: immediate child directories are title folders.
     * Returns the number of titles saved.
     */
    protected int scanUnstructuredPartition(Path partitionRoot, String partitionId,
                                            String volumeId, Long actressId,
                                            VolumeFileSystem fs, PrintWriter out) throws IOException {
        if (!fs.exists(partitionRoot)) {
            out.println("  [skip] " + partitionRoot + " — not found");
            return 0;
        }
        List<Path> children = fs.listDirectory(partitionRoot);
        int count = 0;
        for (Path child : children) {
            if (fs.isDirectory(child)) {
                saveTitleAndVideos(child, volumeId, partitionId, actressId, fs);
                count++;
            }
        }
        return count;
    }

    /**
     * Scans a tier sub-folder under {@code stars/}: child dirs are actress folders,
     * and inside each actress folder are title folders.
     * Returns the number of titles saved.
     */
    protected int scanStarPartition(Path tierRoot, String partitionId,
                                    String volumeId, Actress.Tier actressTier,
                                    VolumeFileSystem fs, PrintWriter out) throws IOException {
        if (!fs.exists(tierRoot)) {
            return 0;
        }
        int count = 0;
        for (Path actressFolder : fs.listDirectory(tierRoot)) {
            if (!fs.isDirectory(actressFolder)) continue;
            String actressName = actressFolder.getFileName().toString();
            Actress actress = resolveOrCreateActress(actressName, actressTier);
            for (Path titleFolder : fs.listDirectory(actressFolder)) {
                if (fs.isDirectory(titleFolder)) {
                    saveTitleAndVideos(titleFolder, volumeId, partitionId, actress.getId(), fs);
                    count++;
                }
            }
        }
        return count;
    }

    private void saveTitleAndVideos(Path titleFolder, String volumeId, String partitionId,
                                    Long actressId, VolumeFileSystem fs) throws IOException {
        String folderName = titleFolder.getFileName().toString();
        TitleCodeParser.ParsedCode parsed = codeParser.parse(folderName);

        Title title = titleRepo.save(new Title(
                null, parsed.code(), parsed.baseCode(),
                volumeId, partitionId, actressId,
                titleFolder, LocalDate.now()));

        saveVideosForTitle(titleFolder, title.id(), fs);
    }

    private void saveVideosForTitle(Path titleFolder, long titleId,
                                    VolumeFileSystem fs) throws IOException {
        // Direct video files in the title folder
        for (Path child : fs.listDirectory(titleFolder)) {
            if (!fs.isDirectory(child) && MediaExtensions.isVideo(child)) {
                videoRepo.save(new Video(null, titleId,
                        child.getFileName().toString(), child, LocalDate.now()));
            }
        }
        // Optional video/ subdirectory
        Path videoSubdir = titleFolder.resolve("video");
        if (fs.exists(videoSubdir) && fs.isDirectory(videoSubdir)) {
            for (Path child : fs.listDirectory(videoSubdir)) {
                if (!fs.isDirectory(child) && MediaExtensions.isVideo(child)) {
                    videoRepo.save(new Video(null, titleId,
                            child.getFileName().toString(), child, LocalDate.now()));
                }
            }
        }
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

    protected void printStats(int titleCount, PrintWriter out) {
        out.println("Sync complete. " + titleCount + " title(s) indexed.");
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
