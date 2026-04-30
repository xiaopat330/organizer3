package com.organizer3.sync;

import com.organizer3.config.volume.PartitionDef;
import com.organizer3.config.volume.VolumeConfig;
import com.organizer3.config.volume.VolumeStructureDef;
import com.organizer3.covers.CoverPath;
import com.organizer3.db.ActressCompaniesService;
import com.organizer3.db.TitleEffectiveTagsService;
import com.organizer3.filesystem.VolumeFileSystem;
import com.organizer3.javdb.enrichment.RevalidationPendingRepository;
import com.organizer3.model.Actress;
import com.organizer3.model.Title;
import com.organizer3.model.TitleLocation;
import com.organizer3.model.Video;
import com.organizer3.model.Volume;
import com.organizer3.repository.ActressRepository;
import com.organizer3.repository.CatastrophicDeleteException;
import com.organizer3.repository.TitleActressRepository;
import com.organizer3.repository.TitleLocationRepository;
import com.organizer3.repository.TitleRepository;
import com.organizer3.repository.VideoRepository;
import com.organizer3.repository.VolumeRepository;
import com.organizer3.shell.SessionContext;
import com.organizer3.shell.io.CommandIO;
import com.organizer3.shell.io.Progress;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
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
    protected final CoverPath coverPath;
    protected final SyncIdentityMatcher identityMatcher;
    private final RevalidationPendingRepository revalidationPendingRepo;
    private final TitleCodeParser codeParser = new TitleCodeParser();

    protected AbstractSyncOperation(TitleRepository titleRepo, VideoRepository videoRepo,
                                    ActressRepository actressRepo, VolumeRepository volumeRepo,
                                    TitleLocationRepository titleLocationRepo,
                                    TitleActressRepository titleActressRepo,
                                    IndexLoader indexLoader,
                                    TitleEffectiveTagsService titleEffectiveTagsService,
                                    ActressCompaniesService actressCompaniesService,
                                    CoverPath coverPath,
                                    RevalidationPendingRepository revalidationPendingRepo,
                                    SyncIdentityMatcher identityMatcher) {
        this.titleRepo = titleRepo;
        this.videoRepo = videoRepo;
        this.actressRepo = actressRepo;
        this.volumeRepo = volumeRepo;
        this.titleLocationRepo = titleLocationRepo;
        this.titleActressRepo = titleActressRepo;
        this.indexLoader = indexLoader;
        this.titleEffectiveTagsService = titleEffectiveTagsService;
        this.actressCompaniesService = actressCompaniesService;
        this.coverPath = coverPath;
        this.revalidationPendingRepo = revalidationPendingRepo;
        this.identityMatcher = identityMatcher;
    }

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

        boolean isNewTitle = titleRepo.findByCode(parsed.code()).isEmpty();
        Title title = titleRepo.findOrCreateByCode(template);
        if (isNewTitle) {
            identityMatcher.noteTitleCandidate(parsed, title);
        }

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
                videoRepo.save(buildVideoRecord(child, titleId, volumeId, fs));
            }
        }
        for (String subdir : VIDEO_SUBDIRECTORIES) {
            Path videoSubdir = titleFolder.resolve(subdir);
            if (fs.exists(videoSubdir) && fs.isDirectory(videoSubdir)) {
                for (Path child : fs.listDirectory(videoSubdir)) {
                    if (!fs.isDirectory(child) && MediaExtensions.isVideo(child)) {
                        videoRepo.save(buildVideoRecord(child, titleId, volumeId, fs));
                    }
                }
            }
        }
    }

    private Video buildVideoRecord(Path path, long titleId, String volumeId, VolumeFileSystem fs) {
        Long sizeBytes = null;
        try {
            sizeBytes = fs.size(path);
        } catch (IOException e) {
            log.warn("Failed to read size for {}: {}", path, e.getMessage());
        }
        return Video.builder()
                .titleId(titleId)
                .volumeId(volumeId)
                .filename(path.getFileName().toString())
                .path(path)
                .lastSeenAt(LocalDate.now())
                .sizeBytes(sizeBytes)
                .build();
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
        identityMatcher.noteActressCandidate(name);
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
     * Drop titles whose locations all disappeared during this sync, and delete their local
     * cover cache files in the same pass. Called between scan and {@link #finalizeSync}.
     *
     * <p>Returns covers for every orphaned title to its pre-fetch state — closes the gap that
     * was leaving thousands of unreferenced covers on disk as folders got renamed on the NAS.
     * Any cover whose title is dropped here would be flagged by Library Health on the next
     * scan, so removing them eagerly keeps the app's on-disk footprint honest.
     *
     * <p>Partition sync calls this after per-partition clears; full sync calls it after the
     * complete discovery+save pass. In both cases the predicate is the same — zero
     * {@code title_locations} rows.
     */
    protected void pruneOrphanedTitlesAndCovers(CommandIO io) {
        List<TitleRepository.OrphanedTitleRef> orphans = titleRepo.findOrphanedTitles();

        // Cascade guard (pre-cover): check before deleting any cover files. The repo-level
        // guard in deleteOrphaned() is defense-in-depth, but cover deletion runs first and
        // a bug that orphans every title would otherwise nuke the local cover cache before
        // the DB guard could stop it — thousands of SMB round trips to re-fetch on recovery.
        if (!orphans.isEmpty()) {
            int total = titleRepo.countAll();
            int threshold = com.organizer3.repository.jdbi.JdbiTitleRepository.orphanDeleteThreshold(total);
            if (orphans.size() > threshold) {
                log.error("Orphan prune refused (pre-cover): {} of {} titles would be deleted (threshold {}). "
                        + "title_locations may be corrupted — investigate before re-running sync.",
                        orphans.size(), total, threshold);
                io.println("  ⚠ Orphan prune refused — " + orphans.size() + " of " + total
                        + " titles would be deleted (threshold " + threshold + ").");
                io.println("  ⚠ This usually indicates a title_locations corruption."
                        + " Investigate before re-running sync.");
                return;
            }

            // Catastrophic-flagging guard: refuse to flag more than max(50, 10% of titles)
            // enriched orphans in a single sync — likely indicates a volume-mount issue or bug.
            int enrichedOrphans = titleRepo.countOrphansWithEnrichment();
            int flagThreshold   = com.organizer3.repository.jdbi.JdbiTitleRepository.orphanFlagThreshold(total);
            if (enrichedOrphans > flagThreshold) {
                log.error("Orphan prune refused (flagging-guard): {} enriched titles would be flagged (threshold {}). "
                        + "This likely indicates a volume-mount issue or sync bug — investigate before re-running.",
                        enrichedOrphans, flagThreshold);
                io.println("  ⚠ Orphan prune refused — " + enrichedOrphans + " enriched titles would be flagged"
                        + " (threshold " + flagThreshold + ").");
                io.println("  ⚠ This likely indicates a volume-mount issue or sync bug."
                        + " Investigate before re-running sync.");
                return;
            }
        }

        int coversDeleted = 0;
        for (TitleRepository.OrphanedTitleRef ref : orphans) {
            // Build a minimal synthetic title so CoverPath.find() can probe extensions.
            Title synth = Title.builder().label(ref.label()).baseCode(ref.baseCode()).build();
            var found = coverPath.find(synth);
            if (found.isEmpty()) continue;
            try {
                if (Files.deleteIfExists(found.get())) coversDeleted++;
            } catch (IOException e) {
                log.warn("Failed to delete orphan cover {} for {}-{}", found.get(), ref.label(), ref.baseCode(), e);
            }
        }
        if (orphans.isEmpty() && coversDeleted == 0) return;
        try {
            TitleRepository.OrphanPruneResult result = titleRepo.deleteOrphaned();
            titleActressRepo.deleteOrphaned();
            if (result.total() > 0 || coversDeleted > 0) {
                log.info("Pruned {} orphan title(s) ({} deleted, {} flagged for review); deleted {} cover file(s)",
                        result.total(), result.deleted(), result.flagged(), coversDeleted);
                io.println("  Pruned " + result.total() + " orphan title(s)"
                        + (result.flagged() > 0
                                ? " (" + result.deleted() + " deleted, " + result.flagged() + " flagged for enrichment review)"
                                : "")
                        + "; deleted " + coversDeleted + " cover file(s).");
            }
        } catch (CatastrophicDeleteException e) {
            // Repo-level guard tripped despite the pre-check — total/orphan counts must have
            // shifted mid-prune (unlikely under normal sync serialization, but possible).
            log.error("Orphan prune refused (repo-guard): {}", e.getMessage(), e);
            io.println("  ⚠ Orphan prune refused — " + e.wouldDelete() + " of "
                    + e.total() + " titles would be deleted (threshold " + e.threshold() + ").");
            io.println("  ⚠ This usually indicates a title_locations corruption."
                    + " Investigate before re-running sync.");
        }
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
        if (revalidationPendingRepo != null && !stats.touchedTitleIds().isEmpty()) {
            for (long titleId : stats.touchedTitleIds()) {
                revalidationPendingRepo.enqueue(titleId, "sync");
            }
        }
        ctx.setIndex(indexLoader.load(volumeId));
        log.info("Sync finalized — volume={} actresses={} queue={} attention={} total={} touchedTitles={} touchedActresses={}",
                volumeId, stats.actressCount(), stats.queue, stats.attention, stats.total,
                stats.touchedTitleIds().size(), stats.touchedActressIds().size());
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
