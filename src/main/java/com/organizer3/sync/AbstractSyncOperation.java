package com.organizer3.sync;

import com.organizer3.config.volume.PartitionDef;
import com.organizer3.config.volume.VolumeConfig;
import com.organizer3.config.volume.VolumeStructureDef;
import com.organizer3.db.ActressCompaniesService;
import com.organizer3.db.TitleEffectiveTagsService;
import com.organizer3.filesystem.VolumeFileSystem;
import com.organizer3.model.Actress;
import com.organizer3.model.Title;
import com.organizer3.model.TitleLocation;
import com.organizer3.model.Video;
import com.organizer3.model.Volume;
import com.organizer3.repository.ActressRepository;
import com.organizer3.repository.TitleActressRepository;
import com.organizer3.repository.TitleLocationRepository;
import com.organizer3.repository.TitleRepository;
import com.organizer3.repository.VideoRepository;
import com.organizer3.repository.VolumeRepository;
import com.organizer3.shell.SessionContext;
import com.organizer3.shell.io.CommandIO;
import com.organizer3.shell.io.Progress;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import com.organizer3.sync.scanner.DiscoveredTitle;

import static com.organizer3.shell.Ansi.*;
import static com.organizer3.sync.scanner.ScannerSupport.extractActressName;

/**
 * Shared helpers for sync operation implementations.
 *
 * <p>Subclasses decide what scope to scan; this class provides the mechanics of scanning
 * a partition folder, parsing title codes, and persisting titles, videos, and cast links.
 */
@Slf4j
@RequiredArgsConstructor(access = AccessLevel.PROTECTED)
abstract class AbstractSyncOperation implements SyncOperation {

    protected final TitleRepository titleRepo;
    protected final VideoRepository videoRepo;
    protected final ActressRepository actressRepo;
    protected final VolumeRepository volumeRepo;
    protected final TitleLocationRepository titleLocationRepo;
    protected final TitleActressRepository titleActressRepo;
    protected final IndexLoader indexLoader;
    protected final TitleEffectiveTagsService titleEffectiveTagsService;
    protected final ActressCompaniesService actressCompaniesService;
    private final TitleCodeParser codeParser = new TitleCodeParser();

    /** Subdirectories inside a title folder that may contain video files. */
    private static final List<String> VIDEO_SUBDIRECTORIES = List.of("video", "h265");

    // -------------------------------------------------------------------------
    // Shared scanning helpers
    // -------------------------------------------------------------------------

    /**
     * Ensures the volume record exists in the DB (insert or no-op if already present).
     */
    protected void ensureVolumeRecord(VolumeConfig volume) {
        if (volumeRepo.findById(volume.id()).isEmpty()) {
            volumeRepo.save(new Volume(volume.id(), volume.structureType()));
        }
    }

    /**
     * Scans an unstructured partition: immediate child directories are title folders.
     * Attempts to infer a single actress from each folder name (e.g., "Marin Yakuno (IPZZ-679)").
     * Saves each title, links the inferred actress to the junction table, and returns the count.
     */
    protected int scanUnstructuredPartition(Path partitionRoot, String partitionId,
                                            String volumeId,
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
                String inferred = extractActressName(child.getFileName().toString());
                Long filingActressId = null;
                List<Long> castIds = List.of();
                if (inferred != null) {
                    Actress actress = resolveOrCreateActress(inferred, Actress.Tier.LIBRARY);
                    filingActressId = actress.getId();
                    castIds = List.of(filingActressId);
                }
                Title title = saveTitleAndVideos(child, volumeId, partitionId, filingActressId, fs);
                titleActressRepo.linkAll(title.getId(), castIds);
                stats.addTitle(title.getId());
                progress.advance();
            }
        }
        int count = titleFolders.size();
        stats.addTitles(partitionId, count);
        return count;
    }

    /**
     * Resolves all actress names from a {@link DiscoveredTitle},
     * creates actress records for any new names, records them in stats, and returns their ids.
     *
     * <p>For single-name titles the returned list has one element (the filing actress id).
     * For multi-name collections titles it has all cast members. Empty when actress is unknown.
     */
    protected List<Long> resolveCast(DiscoveredTitle dt, SyncStats stats) {
        List<Long> ids = new ArrayList<>();
        for (String name : dt.actressNames()) {
            Actress actress = resolveOrCreateActress(name, dt.actressTier());
            stats.addActress(actress.getId());
            ids.add(actress.getId());
        }
        return ids;
    }

    /**
     * Persists a title folder: find-or-create the title record, save its location,
     * and collect video files. Returns the persisted title (with id populated).
     */
    protected Title saveTitleAndVideos(Path titleFolder, String volumeId, String partitionId,
                                       Long actressId, VolumeFileSystem fs) throws IOException {
        String folderName = titleFolder.getFileName().toString();
        TitleCodeParser.ParsedCode parsed = codeParser.parse(folderName);

        Title template = Title.builder()
                .code(parsed.code())
                .baseCode(parsed.baseCode())
                .label(parsed.label())
                .seqNum(parsed.seqNum())
                .actressId(actressId)
                .build();
        Title title = titleRepo.findOrCreateByCode(template);

        LocalDate addedDate = computeAddedDate(titleFolder, fs);
        titleLocationRepo.save(TitleLocation.builder()
                .titleId(title.getId())
                .volumeId(volumeId)
                .partitionId(partitionId)
                .path(titleFolder)
                .lastSeenAt(LocalDate.now())
                .addedDate(addedDate)
                .build());

        saveVideosForTitle(titleFolder, title.getId(), volumeId, fs);
        return title;
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

    private void saveVideosForTitle(Path titleFolder, long titleId, String volumeId,
                                    VolumeFileSystem fs) throws IOException {
        for (Path child : fs.listDirectory(titleFolder)) {
            if (!fs.isDirectory(child) && MediaExtensions.isVideo(child)) {
                videoRepo.save(Video.builder().titleId(titleId).volumeId(volumeId)
                        .filename(child.getFileName().toString()).path(child).lastSeenAt(LocalDate.now()).build());
            }
        }
        for (String subdir : VIDEO_SUBDIRECTORIES) {
            Path videoSubdir = titleFolder.resolve(subdir);
            if (fs.exists(videoSubdir) && fs.isDirectory(videoSubdir)) {
                for (Path child : fs.listDirectory(videoSubdir)) {
                    if (!fs.isDirectory(child) && MediaExtensions.isVideo(child)) {
                        videoRepo.save(Video.builder().titleId(titleId).volumeId(volumeId)
                                .filename(child.getFileName().toString()).path(child).lastSeenAt(LocalDate.now()).build());
                    }
                }
            }
        }
    }

    /**
     * Resolves an actress by name (canonical or alias). Creates a new record if not found.
     * If the actress already exists but the requested tier is higher (further right in the
     * enum), promotes her tier — this ensures that scanning a goddess partition after the
     * actress was created via alias import (LIBRARY) correctly updates the tier.
     */
    protected Actress resolveOrCreateActress(String name, Actress.Tier tier) {
        var existing = actressRepo.resolveByName(name);
        if (existing.isPresent()) {
            Actress actress = existing.get();
            if (tier.ordinal() > actress.getTier().ordinal()) {
                log.debug("Promoting actress '{}' tier: {} → {}", name, actress.getTier(), tier);
                actressRepo.updateTier(actress.getId(), tier);
            }
            return actress;
        }
        log.debug("New actress discovered during sync: {}", name);
        return actressRepo.save(Actress.builder()
                .canonicalName(name)
                .tier(tier)
                .firstSeenAt(LocalDate.now())
                .build());
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
     * Stamps the volume's {@code last_synced_at} to now, recalculates actress tiers,
     * updates the denorm tables for all touched titles and actresses, and reloads the index.
     */
    protected void finalizeSync(String volumeId, SessionContext ctx, SyncStats stats) {
        volumeRepo.updateLastSyncedAt(volumeId, LocalDateTime.now());
        actressRepo.recalcTiers();
        titleEffectiveTagsService.recomputeForTitles(stats.touchedTitleIds());
        actressCompaniesService.recomputeForActresses(stats.touchedActressIds());
        ctx.setIndex(indexLoader.load(volumeId));
    }

    protected void printStats(SyncStats stats, CommandIO io) {
        io.println("Sync complete.");
        io.println("  Actresses:  " + stats.actressCount());
        io.printlnAnsi("  Queue:      " + GREEN + stats.queue     + RESET);
        io.printlnAnsi("  Attention:  " + RED   + stats.attention + RESET);
        io.printlnAnsi("  Total:      " + CYAN  + stats.total     + RESET);
    }

    // Expose partition def lookup for unstructured partitions (used by PartitionSyncOperation)
    protected static PartitionDef requirePartitionDef(
            VolumeStructureDef structure, String partitionId) {
        return structure.findUnstructuredById(partitionId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No unstructured partition '" + partitionId
                                + "' in structure '" + structure.id() + "'"));
    }
}
