package com.organizer3.organize;

import com.organizer3.config.volume.VolumeConfig;
import com.organizer3.filesystem.VolumeFileSystem;
import com.organizer3.model.Title;
import com.organizer3.model.TitleLocation;
import com.organizer3.repository.TitleLocationRepository;
import com.organizer3.repository.TitleRepository;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.Jdbi;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Composite organize pipeline — walks every title in the volume's {@code queue}
 * partition and runs selected phases per title, then phase 4 over the set of
 * affected actresses. See {@code spec/PROPOSAL_ORGANIZE_PIPELINE.md} §7.2.
 *
 * <p>Per-title pipeline order: normalize → restructure → sort. Phase failures are
 * isolated — a failure in one phase doesn't stop other phases, nor does it abort
 * subsequent titles.
 *
 * <p>Phase 4 (classify) runs over every actress that had at least one title filed
 * during phase 3, plus any actresses the caller explicitly requests via
 * {@code alwaysClassify}. (When classify is the only phase enabled, we classify
 * every actress with filing titles on the volume.)
 *
 * <p>Pagination: accepts {@code limit} + {@code offset} on the queue scan. An agent
 * can chunk large volumes across calls; a full-volume run is just {@code limit=0}.
 */
@Slf4j
public class OrganizeVolumeService {

    public enum Phase { NORMALIZE, RESTRUCTURE, SORT, CLASSIFY }

    public static final Set<Phase> ALL = Set.of(Phase.NORMALIZE, Phase.RESTRUCTURE, Phase.SORT, Phase.CLASSIFY);

    private final TitleRepository titleRepo;
    private final TitleLocationRepository titleLocationRepo;
    private final TitleNormalizerService normalizer;
    private final TitleRestructurerService restructurer;
    private final TitleSorterService sorter;
    private final ActressClassifierService classifier;

    public OrganizeVolumeService(
            TitleRepository titleRepo,
            TitleLocationRepository titleLocationRepo,
            TitleNormalizerService normalizer,
            TitleRestructurerService restructurer,
            TitleSorterService sorter,
            ActressClassifierService classifier) {
        this.titleRepo = titleRepo;
        this.titleLocationRepo = titleLocationRepo;
        this.normalizer = normalizer;
        this.restructurer = restructurer;
        this.sorter = sorter;
        this.classifier = classifier;
    }

    public Result organize(
            VolumeFileSystem fs,
            VolumeConfig volumeConfig,
            AttentionRouter attentionRouter,
            Jdbi jdbi,
            Set<Phase> phases,
            int limit,
            int offset,
            boolean dryRun) {

        Set<Phase> active = phases == null || phases.isEmpty() ? ALL : phases;
        String volumeId = volumeConfig.id();

        // Pull every queue-partition title on this volume. Caller paginates with limit/offset.
        List<TitleLocation> queueLocations = titleLocationRepo.findByVolume(volumeId).stream()
                .filter(l -> "queue".equals(l.getPartitionId()))
                .sorted((a, b) -> Long.compare(a.getId(), b.getId()))
                .toList();

        int from = Math.max(0, offset);
        int to   = limit > 0 ? Math.min(queueLocations.size(), from + limit) : queueLocations.size();
        List<TitleLocation> slice = from >= queueLocations.size() ? List.of() : queueLocations.subList(from, to);

        List<TitleOutcome> titleOutcomes = new ArrayList<>();
        Set<Long> affectedActressIds = new LinkedHashSet<>();

        for (TitleLocation loc : slice) {
            Title title = titleRepo.findById(loc.getTitleId()).orElse(null);
            if (title == null) {
                titleOutcomes.add(TitleOutcome.forError(loc.getPath().toString(), "no title row for location id " + loc.getId()));
                continue;
            }
            String titleCode = title.getCode();

            TitleNormalizerService.Result normalizeResult  = null;
            TitleRestructurerService.Result restructureResult = null;
            TitleSorterService.Result sortResult = null;
            String firstError = null;

            if (active.contains(Phase.NORMALIZE)) {
                try {
                    normalizeResult = normalizer.apply(fs, loc.getPath(), titleCode, dryRun);
                } catch (Exception e) {
                    firstError = firstError != null ? firstError : "normalize: " + describe(e);
                }
            }
            if (active.contains(Phase.RESTRUCTURE)) {
                try {
                    restructureResult = restructurer.apply(fs, loc.getPath(), dryRun);
                } catch (Exception e) {
                    firstError = firstError != null ? firstError : "restructure: " + describe(e);
                }
            }
            if (active.contains(Phase.SORT)) {
                try {
                    sortResult = sorter.sort(fs, volumeConfig, attentionRouter, jdbi, titleCode, dryRun);
                    if (sortResult.outcome() == TitleSorterService.Outcome.SORTED
                            || sortResult.outcome() == TitleSorterService.Outcome.WOULD_SORT) {
                        if (title.getActressId() != null) affectedActressIds.add(title.getActressId());
                    }
                } catch (Exception e) {
                    firstError = firstError != null ? firstError : "sort: " + describe(e);
                }
            }

            titleOutcomes.add(new TitleOutcome(
                    titleCode,
                    loc.getPath().toString(),
                    normalizeResult,
                    restructureResult,
                    sortResult,
                    firstError));
        }

        // Phase 4 — classify each affected actress. If classify is the only phase
        // enabled (e.g. caller wants a library-wide re-tier pass), discover actresses
        // from the full volume, not just sort-affected ones.
        List<ActressOutcome> actressOutcomes = new ArrayList<>();
        Set<Long> toClassify = affectedActressIds;
        if (active.contains(Phase.CLASSIFY) && !active.contains(Phase.SORT)) {
            // Classify-only mode: gather every filing actress with title locations on this volume.
            toClassify = new LinkedHashSet<>();
            for (TitleLocation l : titleLocationRepo.findByVolume(volumeId)) {
                Title t = titleRepo.findById(l.getTitleId()).orElse(null);
                if (t != null && t.getActressId() != null) toClassify.add(t.getActressId());
            }
        }
        if (active.contains(Phase.CLASSIFY)) {
            for (long aid : toClassify) {
                try {
                    ActressClassifierService.Result r = classifier.classify(fs, volumeConfig, jdbi, aid, dryRun);
                    actressOutcomes.add(new ActressOutcome(aid, r, null));
                } catch (Exception e) {
                    actressOutcomes.add(new ActressOutcome(aid, null, describe(e)));
                }
            }
        }

        Summary summary = Summary.from(titleOutcomes, actressOutcomes);
        return new Result(volumeId, dryRun, active, from, slice.size(),
                queueLocations.size(), titleOutcomes, actressOutcomes, summary);
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

    // ── result shapes ──────────────────────────────────────────────────────

    public record TitleOutcome(
            String titleCode,
            String path,
            TitleNormalizerService.Result normalize,
            TitleRestructurerService.Result restructure,
            TitleSorterService.Result sort,
            String error
    ) {
        static TitleOutcome forError(String path, String error) {
            return new TitleOutcome(null, path, null, null, null, error);
        }
    }

    public record ActressOutcome(long actressId, ActressClassifierService.Result result, String error) {}

    public record Summary(
            int titlesProcessed,
            int normalizeSuccesses,
            int restructureSuccesses,
            int sortedToStars,
            int sortedToAttention,
            int sortsSkipped,
            int titlesWithErrors,
            int actressesClassified,
            int actressesPromoted
    ) {
        static Summary from(List<TitleOutcome> titles, List<ActressOutcome> actresses) {
            int normOk = 0, restrOk = 0, sortStars = 0, sortAttn = 0, sortSkip = 0, errs = 0;
            for (TitleOutcome t : titles) {
                if (t.error() != null) errs++;
                if (t.normalize() != null && !t.normalize().planned().isEmpty()
                        && !t.normalize().applied().isEmpty()) normOk++;
                if (t.restructure() != null && !t.restructure().planned().isEmpty()
                        && !t.restructure().moved().isEmpty()) restrOk++;
                if (t.sort() != null) {
                    switch (t.sort().outcome()) {
                        case SORTED, WOULD_SORT -> sortStars++;
                        case ROUTED_TO_ATTENTION, WOULD_ROUTE_TO_ATTENTION -> sortAttn++;
                        case SKIPPED -> sortSkip++;
                        default -> {}
                    }
                }
            }
            int classified = 0, promoted = 0;
            for (ActressOutcome a : actresses) {
                if (a.result() != null) {
                    classified++;
                    if (a.result().outcome() == ActressClassifierService.Outcome.PROMOTED
                            || a.result().outcome() == ActressClassifierService.Outcome.WOULD_PROMOTE) promoted++;
                }
            }
            return new Summary(titles.size(), normOk, restrOk, sortStars, sortAttn, sortSkip, errs, classified, promoted);
        }
    }

    public record Result(
            String volumeId,
            boolean dryRun,
            Set<Phase> phases,
            int offset,
            int titlesInSlice,
            int queueTotal,
            List<TitleOutcome> titles,
            List<ActressOutcome> actresses,
            Summary summary
    ) {}
}
