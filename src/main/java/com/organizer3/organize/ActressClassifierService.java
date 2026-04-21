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
import java.util.HashSet;
import java.util.List;
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
