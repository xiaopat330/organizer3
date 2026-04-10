package com.organizer3.web;

import com.organizer3.covers.CoverPath;
import com.organizer3.model.Label;
import com.organizer3.model.StudioGroup;
import com.organizer3.model.Title;
import com.organizer3.repository.ActressRepository;
import com.organizer3.repository.LabelRepository;
import com.organizer3.repository.TitleActressRepository;
import com.organizer3.repository.TitleRepository;
import com.organizer3.repository.WatchHistoryRepository;
import com.organizer3.sync.TitleCodeQuery;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;

/**
 * Read-only query service backing the browse home page.
 */
@RequiredArgsConstructor
public class TitleBrowseService {

    static final int MAX_LIMIT = 500;

    private final TitleRepository titleRepo;
    private final ActressRepository actressRepo;
    private final CoverPath coverPath;
    private final LabelRepository labelRepo;
    private final TitleActressRepository titleActressRepo;
    private final WatchHistoryRepository watchHistoryRepo;
    /** volumeId → smbPath, e.g. "a" → "//pandora/jav_A" */
    private final Map<String, String> volumeSmbPaths;

    /**
     * Returns at most {@code limit} titles starting at {@code offset}, ordered newest-first.
     * Hard-capped at {@link #MAX_LIMIT} total regardless of requested limit.
     */
    public List<TitleSummary> findRecent(int offset, int limit) {
        limit = Math.min(limit, MAX_LIMIT);
        return toSummaries(titleRepo.findRecent(limit, offset));
    }

    /**
     * Searches titles by a product-number fragment using {@link TitleCodeQuery} normalization.
     * Returns an empty list if the query can't be parsed into at least a label prefix.
     */
    public List<TitleSummary> searchByCodePaged(String rawQuery, int offset, int limit) {
        limit = Math.min(limit, MAX_LIMIT);
        TitleCodeQuery.ParsedQuery parsed = TitleCodeQuery.parse(rawQuery);
        if (parsed.labelPrefix().isEmpty()) return List.of();
        return toSummaries(titleRepo.findByCodePrefixPaged(
                parsed.labelPrefix(), parsed.seqPrefix(), limit, offset));
    }

    /** Returns favorited titles, ordered newest-first. */
    public List<TitleSummary> findFavoritesPaged(int offset, int limit) {
        limit = Math.min(limit, MAX_LIMIT);
        return toSummaries(titleRepo.findFavoritesPaged(limit, offset));
    }

    /** Returns bookmarked titles, ordered newest-first. */
    public List<TitleSummary> findBookmarksPaged(int offset, int limit) {
        limit = Math.min(limit, MAX_LIMIT);
        return toSummaries(titleRepo.findBookmarksPaged(limit, offset));
    }

    /**
     * Returns the full label reference catalog, sorted by code. Used by the title landing
     * page to power tab-completion of the product-number search.
     */
    public List<Label> listLabels() {
        return labelRepo.findAllAsMap().values().stream()
                .sorted(Comparator.comparing(Label::code))
                .toList();
    }

    /**
     * Returns all studio group definitions in declaration order from {@code studios.yaml}.
     * Used by the Studio browser sub-nav.
     */
    public List<StudioGroup> listStudioGroups() {
        return new StudioGroupLoader().load();
    }

    /** Lightweight projection for top-actresses-by-label queries. */
    public record ActressCount(long id, String name, String tier, long count) {}

    /**
     * Returns the top actresses by title count for titles whose label is in {@code labels}.
     */
    public List<ActressCount> topActressesByLabels(List<String> labels, int limit) {
        return titleRepo.findTopActressesByLabels(labels, limit).stream()
                .map(row -> new ActressCount(
                        (Long) row[0],
                        (String) row[1],
                        (String) row[2],
                        (Long) row[3]))
                .toList();
    }

    public List<ActressCount> newestActressesByLabels(List<String> labels, int limit) {
        return titleRepo.findNewestActressesByLabels(labels, limit).stream()
                .map(row -> new ActressCount((Long) row[0], (String) row[1], (String) row[2], 0L))
                .toList();
    }

    /** Returns titles having ALL of the given tags, ordered newest-first. */
    public List<TitleSummary> findByTagsPaged(List<String> tags, int offset, int limit) {
        limit = Math.min(limit, MAX_LIMIT);
        return toSummaries(titleRepo.findByTagsPaged(tags, limit, offset));
    }

    /**
     * Returns a random sample of at most {@code limit} titles with an actress attribution.
     * Each call returns a fresh random set — offset-based pagination is intentionally not
     * supported here, so the frontend just keeps requesting more until it hits the cap.
     */
    public List<TitleSummary> findRandom(int limit) {
        limit = Math.min(limit, MAX_LIMIT);
        return toSummaries(titleRepo.findRandom(limit));
    }

    /**
     * Returns titles in the queue partition of the given volume, ordered newest-first.
     * Hard-capped at {@link #MAX_LIMIT} total regardless of requested limit.
     *
     * <p>Queue titles are synced without an actress_id. For each one, we attempt to infer
     * the actress by finding another title in the DB with the same base_code that has already
     * been attributed (e.g. a stars copy on another volume). Only exact base_code matches
     * are used — label-based guessing produced too many false positives.
     */
    public List<TitleSummary> findByVolumeQueue(String volumeId, int offset, int limit) {
        return findByVolumePartition(volumeId, "queue", offset, limit);
    }

    public List<TitleSummary> findByVolumePaged(String volumeId, int offset, int limit) {
        limit = Math.min(limit, MAX_LIMIT);
        List<Title> titles = titleRepo.findByVolumePaged(volumeId, limit, offset);
        return toSummaries(titles);
    }

    public List<TitleSummary> findByVolumePartition(String volumeId, String partition, int offset, int limit) {
        limit = Math.min(limit, MAX_LIMIT);
        List<Title> titles = titleRepo.findByVolumeAndPartition(volumeId, partition, limit, offset);

        // Infer actress for unattributed titles via exact base_code match only.
        Map<String, Long> actressIdByBaseCode = new HashMap<>();

        titles.stream()
                .filter(t -> t.getActressId() == null)
                .forEach(t -> {
                    if (t.getBaseCode() != null && !actressIdByBaseCode.containsKey(t.getBaseCode())) {
                        titleRepo.findByBaseCode(t.getBaseCode()).stream()
                                .filter(other -> other.getActressId() != null)
                                .findFirst()
                                .ifPresent(other -> actressIdByBaseCode.put(t.getBaseCode(), other.getActressId()));
                    }
                });

        if (!actressIdByBaseCode.isEmpty()) {
            titles = titles.stream()
                    .map(t -> {
                        if (t.getActressId() != null) return t;
                        Long inferred = t.getBaseCode() != null ? actressIdByBaseCode.get(t.getBaseCode()) : null;
                        return inferred != null ? t.toBuilder().actressId(inferred).build() : t;
                    })
                    .toList();
        }

        return toSummaries(titles);
    }

    private List<TitleSummary> toSummaries(List<Title> titles) {
        record ActressInfo(String name, String tier) {}

        Map<Long, ActressInfo> actressInfo = new HashMap<>(titles.stream()
                .map(Title::getActressId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toMap(
                        id -> id,
                        id -> actressRepo.findById(id)
                                .map(a -> new ActressInfo(a.getCanonicalName(),
                                        a.getTier() != null ? a.getTier().name() : null))
                                .orElse(new ActressInfo(null, null))
                )));

        Map<String, Label> labelMap = labelRepo.findAllAsMap();

        List<String> codes = titles.stream().map(Title::getCode).toList();
        Map<String, WatchHistoryRepository.WatchStats> watchStatsMap = watchHistoryRepo.findWatchStatsBatch(codes);

        return titles.stream()
                .map(t -> {
                    Label lbl = t.getLabel() != null ? labelMap.get(t.getLabel().toUpperCase()) : null;
                    String coverUrl = t.getLabel() != null
                            ? coverPath.find(t)
                                    .map(p -> "/covers/" + t.getLabel().toUpperCase() + "/" + p.getFileName())
                                    .orElse(null)
                            : null;
                    ActressInfo ai = t.getActressId() != null ? actressInfo.get(t.getActressId()) : null;
                    List<String> allLocations = t.getLocations().stream()
                            .map(loc -> loc.getPath().toString())
                            .toList();
                    List<String> nasPaths = t.getLocations().stream()
                            .filter(loc -> loc.getVolumeId() != null && volumeSmbPaths.containsKey(loc.getVolumeId()))
                            .map(loc -> volumeSmbPaths.get(loc.getVolumeId()) + "/" + loc.getPath())
                            .distinct()
                            .toList();

                    // Multi-actress entries from junction table
                    List<TitleSummary.ActressEntry> actresses = List.of();
                    if (t.getId() != null) {
                        List<Long> linkedIds = titleActressRepo.findActressIdsByTitle(t.getId());
                        if (linkedIds.size() > 1) {
                            actresses = linkedIds.stream()
                                    .map(id -> {
                                        ActressInfo linked = actressInfo.computeIfAbsent(id,
                                                k -> actressRepo.findById(k)
                                                        .map(a -> new ActressInfo(a.getCanonicalName(),
                                                                a.getTier() != null ? a.getTier().name() : null))
                                                        .orElse(new ActressInfo(null, null)));
                                        return TitleSummary.ActressEntry.builder()
                                                .id(id)
                                                .name(linked.name())
                                                .tier(linked.tier())
                                                .build();
                                    })
                                    .filter(e -> e.getName() != null)
                                    .toList();
                        }
                    }

                    // Merge direct title tags with indirect label tags, deduplicated and sorted
                    List<String> directTags = t.getTags() != null ? t.getTags() : List.of();
                    List<String> labelTags  = lbl != null ? lbl.tags() : List.of();
                    List<String> allTags = Stream.concat(directTags.stream(), labelTags.stream())
                            .distinct()
                            .sorted()
                            .toList();

                    return TitleSummary.builder()
                            .code(t.getCode())
                            .baseCode(t.getBaseCode())
                            .label(t.getLabel())
                            .actressId(t.getActressId())
                            .actressName(ai != null ? ai.name() : null)
                            .actressTier(ai != null ? ai.tier() : null)
                            .addedDate(t.getAddedDate() != null ? t.getAddedDate().toString() : null)
                            .coverUrl(coverUrl)
                            .companyName(lbl != null ? lbl.company() : null)
                            .labelName(lbl != null ? lbl.labelName() : null)
                            .location(t.getPath() != null ? t.getPath().toString() : null)
                            .locations(allLocations)
                            .nasPaths(nasPaths)
                            .actresses(actresses)
                            .titleEnglish(t.getTitleEnglish())
                            .titleOriginal(t.getTitleOriginal())
                            .releaseDate(t.getReleaseDate() != null ? t.getReleaseDate().toString() : null)
                            .grade(t.getGrade() != null ? t.getGrade().display : null)
                            .favorite(t.isFavorite())
                            .bookmark(t.isBookmark())
                            .lastWatchedAt(watchStatsMap.containsKey(t.getCode()) ? watchStatsMap.get(t.getCode()).lastWatchedAt().toString() : null)
                            .watchCount(watchStatsMap.containsKey(t.getCode()) ? watchStatsMap.get(t.getCode()).count() : 0)
                            .tags(allTags)
                            .build();
                })
                .toList();
    }
}
