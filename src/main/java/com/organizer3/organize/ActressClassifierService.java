package com.organizer3.organize;

import com.organizer3.config.volume.LibraryConfig;
import com.organizer3.config.volume.VolumeConfig;
import com.organizer3.filesystem.VolumeFileSystem;
import com.organizer3.model.Actress;
import com.organizer3.model.TitleLocation;
import com.organizer3.repository.ActressRepository;
import com.organizer3.repository.TitleLocationRepository;
import com.organizer3.repository.TitleRepository;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.Jdbi;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Phase 4 of the organize pipeline: re-tier an actress's folder based on her current
 * title count. See {@code spec/PROPOSAL_ORGANIZE_PIPELINE.md} §3.4.
 *
 * <p>Invariants (§6.1):
 * <ul>
 *   <li>Actresses only move up. Never down. If target tier is lower than current, skip.</li>
 *   <li>Actresses in the {@code favorites} or {@code archive} partitions (user-curated)
 *       are never touched by classify.</li>
 *   <li>Actresses below the {@code star} threshold don't have a tier folder at all;
 *       their titles stay in the volume queue.</li>
 * </ul>
 *
 * <p>Move operation: moves {@code /stars/{oldTier}/{name}} → {@code /stars/{newTier}/{name}}
 * atomically (single SMB rename of the actress folder), then rewrites every
 * {@code title_locations} row's {@code path} + {@code partition_id} to reflect the new
 * tier. DB + FS in a single Jdbi transaction — if FS throws, rollback.
 */
@Slf4j
public class ActressClassifierService {

    /** Tiers in ascending order. Index == strength. */
    public static final List<String> TIER_ORDER = List.of(
            "pool", "library", "minor", "popular", "superstar", "goddess");

    /** Partitions classify never touches (user-curated). */
    private static final Set<String> EXCLUDED_PARTITIONS = Set.of("favorites", "archive");

    private final ActressRepository actressRepo;
    private final TitleRepository titleRepo;
    private final TitleLocationRepository titleLocationRepo;
    private final LibraryConfig libraryConfig;

    public ActressClassifierService(
            ActressRepository actressRepo,
            TitleRepository titleRepo,
            TitleLocationRepository titleLocationRepo,
            LibraryConfig libraryConfig) {
        this.actressRepo = actressRepo;
        this.titleRepo = titleRepo;
        this.titleLocationRepo = titleLocationRepo;
        this.libraryConfig = libraryConfig != null ? libraryConfig : LibraryConfig.DEFAULTS;
    }

    public Result classify(
            VolumeFileSystem fs,
            VolumeConfig volumeConfig,
            Jdbi jdbi,
            long actressId,
            boolean dryRun) {

        Actress actress = actressRepo.findById(actressId).orElseThrow(
                () -> new IllegalArgumentException("No actress with id " + actressId));
        String name = actress.getCanonicalName();
        String volumeId = volumeConfig.id();

        int titleCount = titleRepo.countByActress(actressId);
        String targetTier = libraryConfig.tierFor(titleCount);

        if ("pool".equals(targetTier)) {
            return new Result(dryRun, Outcome.SKIPPED, actressId, name, null, targetTier, null, null,
                    "titleCount=" + titleCount + " — below star threshold; no tier folder");
        }

        // Find current partition for this actress on this volume (from DB).
        Set<String> currentPartitions = new HashSet<>();
        List<TitleLocation> allLocations = titleLocationRepo.findByVolume(volumeId);
        List<TitleLocation> hers = allLocations.stream()
                .filter(l -> isPathUnderActress(l.getPath(), name))
                .toList();
        for (TitleLocation l : hers) currentPartitions.add(l.getPartitionId());

        if (hers.isEmpty()) {
            return new Result(dryRun, Outcome.SKIPPED, actressId, name, null, targetTier, null, null,
                    "no title locations for actress on volume '" + volumeId + "'");
        }
        if (currentPartitions.size() > 1) {
            return new Result(dryRun, Outcome.SKIPPED, actressId, name,
                    String.join("+", currentPartitions), targetTier, null, null,
                    "titles split across multiple partitions — manual review needed");
        }

        String currentTier = currentPartitions.iterator().next();

        if (EXCLUDED_PARTITIONS.contains(currentTier)) {
            return new Result(dryRun, Outcome.SKIPPED, actressId, name, currentTier, targetTier, null, null,
                    "actress is in '" + currentTier + "' (user-curated) — classify skips");
        }

        if (currentTier.equals(targetTier)) {
            return new Result(dryRun, Outcome.SKIPPED, actressId, name, currentTier, targetTier, null, null,
                    "already at target tier");
        }

        int currentOrdinal = TIER_ORDER.indexOf(currentTier);
        int targetOrdinal  = TIER_ORDER.indexOf(targetTier);
        if (currentOrdinal < 0) {
            return new Result(dryRun, Outcome.SKIPPED, actressId, name, currentTier, targetTier, null, null,
                    "current tier '" + currentTier + "' not in known ordering — manual review");
        }
        if (targetOrdinal <= currentOrdinal) {
            return new Result(dryRun, Outcome.SKIPPED, actressId, name, currentTier, targetTier, null, null,
                    "never demote: target=" + targetTier + " <= current=" + currentTier);
        }

        Path actressFolder = Path.of("/", "stars", currentTier, name);
        Path targetFolder  = Path.of("/", "stars", targetTier,  name);

        if (!fs.exists(actressFolder) || !fs.isDirectory(actressFolder)) {
            return new Result(dryRun, Outcome.FAILED, actressId, name, currentTier, targetTier,
                    actressFolder.toString(), null,
                    "actress folder does not exist on disk at expected path");
        }
        if (fs.exists(targetFolder)) {
            return new Result(dryRun, Outcome.FAILED, actressId, name, currentTier, targetTier,
                    actressFolder.toString(), targetFolder.toString(),
                    "target tier folder already exists (collision) — manual review needed");
        }

        if (dryRun) {
            return new Result(true, Outcome.WOULD_PROMOTE, actressId, name, currentTier, targetTier,
                    actressFolder.toString(), targetFolder.toString(),
                    "would promote " + hers.size() + " title location(s)");
        }

        try {
            jdbi.useTransaction(h -> {
                fs.createDirectories(targetFolder.getParent());
                fs.move(actressFolder, targetFolder);
                for (TitleLocation l : hers) {
                    Path newPath = rebase(l.getPath(), currentTier, targetTier, name);
                    titleLocationRepo.updatePathAndPartition(l.getId(), newPath, targetTier);
                }
            });
            log.info("FS mutation [ActressClassifier.classify]: promoted actress tier — actressId={} name=\"{}\" from={} to={} folderFrom={} folderTo={} titleLocations={}",
                    actressId, name, currentTier, targetTier, actressFolder, targetFolder, hers.size());
        } catch (Exception e) {
            log.warn("FS mutation [ActressClassifier.classify] failed — actressId={} name=\"{}\" from={} to={} error={}",
                    actressId, name, actressFolder, targetFolder, describe(e));
            return new Result(false, Outcome.FAILED, actressId, name, currentTier, targetTier,
                    actressFolder.toString(), targetFolder.toString(),
                    "apply failed: " + describe(e));
        }

        return new Result(false, Outcome.PROMOTED, actressId, name, currentTier, targetTier,
                actressFolder.toString(), targetFolder.toString(),
                "promoted " + hers.size() + " title location(s)");
    }

    /**
     * Reconcile-from-disk mode: finds where the actress folder physically lives on disk,
     * compares it to {@code actresses.tier} in the DB, and updates the DB tier upward when
     * the folder has been promoted manually (e.g. post-merge curation).
     *
     * <p>This is strictly DB-only — the folder is already in the right place.  It never
     * moves files and never downgrades the DB tier.
     *
     * <p>Acceptance criteria:
     * <ul>
     *   <li>DB LIBRARY + disk POPULAR + reconcileFromDisk=true → DB updated to POPULAR</li>
     *   <li>DB LIBRARY + disk POPULAR + reconcileFromDisk=false → delegate to classify()</li>
     *   <li>DB POPULAR + disk LIBRARY (pathological) → SKIPPED, no downgrade</li>
     *   <li>DB POPULAR + disk POPULAR → no-op SKIPPED</li>
     * </ul>
     */
    public Result reconcileFromDisk(
            VolumeFileSystem fs,
            VolumeConfig volumeConfig,
            Jdbi jdbi,
            long actressId,
            boolean dryRun) {

        Actress actress = actressRepo.findById(actressId).orElseThrow(
                () -> new IllegalArgumentException("No actress with id " + actressId));
        String name = actress.getCanonicalName();

        // Walk tier directories on disk to find where the folder actually lives.
        Optional<String> diskTierOpt = findDiskTier(fs, name);
        if (diskTierOpt.isEmpty()) {
            return new Result(dryRun, Outcome.SKIPPED, actressId, name, null, null, null, null,
                    "actress folder not found under /stars/<tier>/ on disk — cannot reconcile");
        }
        String diskTier = diskTierOpt.get();

        // Translate disk tier string → Actress.Tier enum (non-tier partitions default to LIBRARY).
        Actress.Tier diskActressTier = toActressTierEnum(diskTier);
        Actress.Tier dbTier = actress.getTier();

        if (diskActressTier == dbTier) {
            return new Result(dryRun, Outcome.SKIPPED, actressId, name,
                    dbTier.name(), diskActressTier.name(), null, null,
                    "DB tier already matches disk tier (" + diskTier + ")");
        }

        if (diskActressTier.ordinal() < dbTier.ordinal()) {
            return new Result(dryRun, Outcome.SKIPPED, actressId, name,
                    dbTier.name(), diskActressTier.name(), null, null,
                    "never downgrade: disk=" + diskTier + " < db=" + dbTier.name().toLowerCase());
        }

        // disk tier > DB tier: reconcile upward.
        if (dryRun) {
            return new Result(true, Outcome.WOULD_RECONCILE, actressId, name,
                    dbTier.name(), diskActressTier.name(), null, null,
                    "would update actresses.tier from " + dbTier.name() + " → " + diskActressTier.name());
        }

        jdbi.useHandle(h -> actressRepo.updateTier(actressId, diskActressTier));
        log.info("DB update [ActressClassifier.reconcileFromDisk]: actresses.tier reconciled — actressId={} name=\"{}\" from={} to={}",
                actressId, name, dbTier, diskActressTier);

        return new Result(false, Outcome.RECONCILED, actressId, name,
                dbTier.name(), diskActressTier.name(), null, null,
                "updated actresses.tier from " + dbTier.name() + " → " + diskActressTier.name());
    }

    /**
     * Treats {@code actresses.tier} as authoritative and moves the actress's on-disk tier
     * folder to match — in EITHER direction (up or down). Rebases only the title_locations
     * that live under the moved source folder; queue, comp, pool, and other-partition
     * locations are left untouched.
     *
     * <p>Guards:
     * <ul>
     *   <li>Folder not found under any /stars tier → SKIPPED.</li>
     *   <li>Folder already at correct tier → SKIPPED.</li>
     *   <li>Folder found under more than one non-target tier → SKIPPED (manual review).</li>
     *   <li>Target tier folder already exists (collision) → FAILED (never blindly merge).</li>
     * </ul>
     *
     * <p>Default {@code dryRun:true} — returns the plan without touching files or DB.
     */
    public Result reconcileTierFolders(
            VolumeFileSystem fs,
            VolumeConfig volumeConfig,
            Jdbi jdbi,
            long actressId,
            boolean dryRun) {

        Actress actress = actressRepo.findById(actressId).orElseThrow(
                () -> new IllegalArgumentException("No actress with id " + actressId));
        String name = actress.getCanonicalName();
        String volumeId = volumeConfig.id();

        Actress.Tier dbTierEnum = actress.getTier();
        String targetTier = dbTierEnum.name().toLowerCase();

        // Collect all tier directories on disk where the folder exists.
        List<String> matchedTiers = findAllDiskTiers(fs, name);

        if (matchedTiers.isEmpty()) {
            return new Result(dryRun, Outcome.SKIPPED, actressId, name, null, targetTier, null, null,
                    "folder not found under /stars/<tier>/ on disk");
        }
        if (matchedTiers.equals(List.of(targetTier))) {
            return new Result(dryRun, Outcome.SKIPPED, actressId, name, targetTier, targetTier, null, null,
                    "already at correct tier (" + targetTier + ")");
        }
        if (matchedTiers.contains(targetTier)) {
            // Target exists alongside at least one other — collision.
            return new Result(dryRun, Outcome.FAILED, actressId, name,
                    String.join("+", matchedTiers), targetTier, null,
                    Path.of("/", "stars", targetTier, name).toString(),
                    "target tier folder already exists (collision) — manual review needed");
        }
        if (matchedTiers.size() > 1) {
            return new Result(dryRun, Outcome.SKIPPED, actressId, name,
                    String.join("+", matchedTiers), targetTier, null, null,
                    "folder under multiple tiers — manual review needed");
        }

        // Exactly one non-target tier matched.
        String sourceTier = matchedTiers.get(0);
        Path sourceFolder = Path.of("/", "stars", sourceTier, name);
        Path targetFolder = Path.of("/", "stars", targetTier, name);

        // Collect only the title_locations under the source folder on this volume.
        List<TitleLocation> volLocs = titleLocationRepo.findByVolume(volumeId);
        List<TitleLocation> toMove = volLocs.stream()
                .filter(l -> l.getPath() != null && l.getPath().startsWith(sourceFolder))
                .toList();

        if (dryRun) {
            return new Result(true, Outcome.WOULD_MOVE, actressId, name, sourceTier, targetTier,
                    sourceFolder.toString(), targetFolder.toString(),
                    "would move " + toMove.size() + " title location(s) from " + sourceTier + " → " + targetTier);
        }

        try {
            jdbi.useTransaction(h -> {
                fs.createDirectories(targetFolder.getParent());
                fs.move(sourceFolder, targetFolder);
                for (TitleLocation l : toMove) {
                    Path newPath = rebase(l.getPath(), sourceTier, targetTier, name);
                    titleLocationRepo.updatePathAndPartition(l.getId(), newPath, targetTier);
                }
            });
            log.info("FS mutation [ActressClassifier.reconcileTierFolders]: moved actress tier folder — actressId={} name=\"{}\" from={} to={} folderFrom={} folderTo={} titleLocations={}",
                    actressId, name, sourceTier, targetTier, sourceFolder, targetFolder, toMove.size());
        } catch (Exception e) {
            log.warn("FS mutation [ActressClassifier.reconcileTierFolders] failed — actressId={} name=\"{}\" from={} to={} error={}",
                    actressId, name, sourceFolder, targetFolder, describe(e));
            return new Result(false, Outcome.FAILED, actressId, name, sourceTier, targetTier,
                    sourceFolder.toString(), targetFolder.toString(),
                    "apply failed: " + describe(e));
        }

        return new Result(false, Outcome.MOVED, actressId, name, sourceTier, targetTier,
                sourceFolder.toString(), targetFolder.toString(),
                "moved " + toMove.size() + " title location(s) from " + sourceTier + " → " + targetTier);
    }

    /**
     * Batch mode: reconciles every actress on the mounted volume whose on-disk tier folder
     * does not match her {@code actresses.tier}. Runs {@link #reconcileTierFolders} for each
     * stranded actress and collects per-actress Results.  Per-actress guards (skips, collisions)
     * are reported in the list and are never fatal to the batch.
     *
     * @param dryRun if true, compute the plan only — no file or DB mutations
     * @return list of Results, one per actress where a mis-tier was detected (or a skip/fail occurred)
     */
    public List<Result> reconcileTierFoldersOnVolume(
            VolumeFileSystem fs,
            VolumeConfig volumeConfig,
            Jdbi jdbi,
            boolean dryRun) {

        List<Actress> all = actressRepo.findAll();
        List<Result> results = new ArrayList<>();

        for (Actress actress : all) {
            String name = actress.getCanonicalName();
            String targetTier = actress.getTier().name().toLowerCase();

            List<String> matchedTiers = findAllDiskTiers(fs, name);
            // Fast-path: no folder or already correct — include in results only if mismatch.
            if (matchedTiers.isEmpty()) continue;
            if (matchedTiers.equals(List.of(targetTier))) continue;

            Result r = reconcileTierFolders(fs, volumeConfig, jdbi, actress.getId(), dryRun);
            results.add(r);
        }

        return results;
    }

    /**
     * Walks all real /stars tier directories (excluding pool) and returns every tier where
     * {@code /stars/<tier>/<name>} exists as a directory. Unlike {@link #findDiskTier}, this
     * collects ALL matches so the caller can detect multi-tier collisions.
     */
    List<String> findAllDiskTiers(VolumeFileSystem fs, String name) {
        List<String> found = new ArrayList<>();
        for (String tier : TIER_ORDER) {
            if ("pool".equals(tier)) continue;
            Path candidate = Path.of("/", "stars", tier, name);
            if (fs.exists(candidate) && fs.isDirectory(candidate)) {
                found.add(tier);
            }
        }
        return found;
    }

    /**
     * Scans {@code /stars/<tier>/<name>} for each known tier (in ascending order) and
     * returns the first tier where the folder exists.  Returns empty if not found.
     */
    Optional<String> findDiskTier(VolumeFileSystem fs, String name) {
        for (String tier : TIER_ORDER) {
            if ("pool".equals(tier)) continue;   // pool has no tier folder
            Path candidate = Path.of("/", "stars", tier, name);
            if (fs.exists(candidate) && fs.isDirectory(candidate)) {
                return Optional.of(tier);
            }
        }
        return Optional.empty();
    }

    /** Maps a tier string to {@link Actress.Tier}; mirrors {@link AbstractSyncOperation#toActressTier}. */
    static Actress.Tier toActressTierEnum(String tier) {
        return switch (tier) {
            case "minor"     -> Actress.Tier.MINOR;
            case "popular"   -> Actress.Tier.POPULAR;
            case "superstar" -> Actress.Tier.SUPERSTAR;
            case "goddess"   -> Actress.Tier.GODDESS;
            default          -> Actress.Tier.LIBRARY;
        };
    }

    /** True if {@code path} is {@code /stars/<anything>/<name>/...} or exactly {@code /stars/<anything>/<name>}. */
    static boolean isPathUnderActress(Path path, String actressName) {
        if (path == null || actressName == null) return false;
        // Expect: /stars/<tier>/<actressName>/<titleFolder> (4+ segments)
        // or     /stars/<tier>/<actressName>                (3 segments — unusual)
        int count = path.getNameCount();
        if (count < 3) return false;
        // path.getName(0) = "stars", path.getName(1) = tier, path.getName(2) = actressName
        if (!"stars".equals(path.getName(0).toString())) return false;
        return actressName.equals(path.getName(2).toString());
    }

    /** Rewrites {@code /stars/{oldTier}/{name}/{...}} to {@code /stars/{newTier}/{name}/{...}}. */
    static Path rebase(Path original, String oldTier, String newTier, String actressName) {
        // Replace the tier segment at index 1 (after "stars")
        Path prefix = Path.of("/", "stars", oldTier, actressName);
        if (!original.startsWith(prefix)) {
            // Shouldn't happen if isPathUnderActress returned true, but guard anyway
            return original;
        }
        Path suffix = prefix.relativize(original);
        Path newBase = Path.of("/", "stars", newTier, actressName);
        return suffix.toString().isEmpty() ? newBase : newBase.resolve(suffix);
    }

    private static String describe(Throwable e) {
        StringBuilder sb = new StringBuilder();
        Throwable cur = e;
        int depth = 0;
        while (cur != null && depth < 4) {
            if (depth > 0) sb.append(" | caused by ");
            sb.append(cur.getClass().getSimpleName()).append(": ").append(cur.getMessage());
            cur = cur.getCause();
            depth++;
        }
        return sb.toString();
    }

    public enum Outcome {
        PROMOTED,
        WOULD_PROMOTE,
        RECONCILED,
        WOULD_RECONCILE,
        MOVED,
        WOULD_MOVE,
        SKIPPED,
        FAILED
    }

    public record Result(
            boolean dryRun,
            Outcome outcome,
            long actressId,
            String actressName,
            String fromTier,
            String toTier,
            String fromPath,
            String toPath,
            String reason
    ) {}
}
