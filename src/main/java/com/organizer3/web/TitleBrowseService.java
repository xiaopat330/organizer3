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
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
/**
 * Read-only query service backing the browse home page.
 */
public class TitleBrowseService {

    private final TitleRepository titleRepo;
    private final ActressRepository actressRepo;
    private final CoverPath coverPath;
    private final LabelRepository labelRepo;
    private final TitleActressRepository titleActressRepo;
    private final WatchHistoryRepository watchHistoryRepo;
    /** volumeId → smbPath, e.g. "a" → "//pandora/jav_A" */
    private final Map<String, String> volumeSmbPaths;
    /** Hard cap on rows returned per page query; driven by {@code maxBrowseTitles} in config. */
    private final int maxLimit;

    /** Dashboard assembly; wired after all repo deps are set. */
    private final TitleDashboardBuilder dashboardBuilder;

    public TitleBrowseService(
            TitleRepository titleRepo, ActressRepository actressRepo, CoverPath coverPath,
            LabelRepository labelRepo, TitleActressRepository titleActressRepo,
            WatchHistoryRepository watchHistoryRepo, Map<String, String> volumeSmbPaths,
            int maxLimit) {
        this.titleRepo = titleRepo;
        this.actressRepo = actressRepo;
        this.coverPath = coverPath;
        this.labelRepo = labelRepo;
        this.titleActressRepo = titleActressRepo;
        this.watchHistoryRepo = watchHistoryRepo;
        this.volumeSmbPaths = volumeSmbPaths;
        this.maxLimit = maxLimit;
        this.dashboardBuilder = new TitleDashboardBuilder(titleRepo, actressRepo, labelRepo, this);
    }

    public int maxLimit() { return maxLimit; }

    private int cappedLimit(int limit) {
        return Math.min(limit, maxLimit);
    }

    /**
     * Returns at most {@code limit} titles starting at {@code offset}, ordered newest-first.
     * Hard-capped at {@link #maxLimit} total regardless of requested limit.
     */
    public List<TitleSummary> findRecent(int offset, int limit) {
        limit = cappedLimit(limit);
        return toSummaries(titleRepo.findRecent(limit, offset));
    }

    /**
     * Searches titles by a product-number fragment using {@link TitleCodeQuery} normalization.
     * Returns an empty list if the query can't be parsed into at least a label prefix.
     */
    public List<TitleSummary> searchByCodePaged(String rawQuery, int offset, int limit) {
        limit = cappedLimit(limit);
        TitleCodeQuery.ParsedQuery parsed = TitleCodeQuery.parse(rawQuery);
        if (parsed.labelPrefix().isEmpty()) return List.of();
        return toSummaries(titleRepo.findByCodePrefixPaged(
                parsed.labelPrefix(), parsed.seqPrefix(), limit, offset));
    }

    /** Returns favorited titles, ordered newest-first. */
    public List<TitleSummary> findFavoritesPaged(int offset, int limit) {
        limit = cappedLimit(limit);
        return toSummaries(titleRepo.findFavoritesPaged(limit, offset));
    }

    /** Returns bookmarked titles, ordered newest-first. */
    public List<TitleSummary> findBookmarksPaged(int offset, int limit) {
        limit = cappedLimit(limit);
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

    /**
     * Returns up to 20 distinct label codes from the database that start with {@code prefix}
     * (case-insensitive). Used by the Library mode code-input autocomplete.
     */
    public List<String> labelAutocomplete(String prefix) {
        return titleRepo.findLabelCodesWithPrefix(prefix);
    }

    /**
     * Full-library query combining code, company, tag, sort, and order filters.
     * All filters are optional and combine via AND.
     *
     * @param code    raw user-typed code string (parsed by {@link com.organizer3.sync.TitleCodeQuery})
     * @param company company name to filter by (null = no filter)
     * @param tags    tags that must ALL be present (empty = no filter)
     * @param sort    "productCode" | "actressName" | "addedDate" (null/other → addedDate)
     * @param order   "asc" | "desc" (null → desc)
     */
    public List<TitleSummary> findLibraryPaged(String code, String company,
                                                List<String> tags, List<Long> enrichmentTagIds,
                                                String sort, String order,
                                                int offset, int limit) {
        limit = cappedLimit(limit);
        com.organizer3.sync.TitleCodeQuery.ParsedQuery parsed =
                com.organizer3.sync.TitleCodeQuery.parse(code);
        Map<String, Label> labelMap = labelRepo.findAllAsMap();
        List<String> companyLabels  = resolveCompanyLabels(labelMap, company);
        if (company != null && !company.isBlank() && companyLabels.isEmpty()) return List.of();
        boolean asc = "asc".equalsIgnoreCase(order);
        return toSummaries(titleRepo.findLibraryPaged(
                parsed.labelPrefix(), parsed.seqPrefix(),
                companyLabels, tags != null ? tags : List.of(),
                enrichmentTagIds != null ? enrichmentTagIds : List.of(),
                sort, asc, limit, offset));
    }

    /** Returns a map of curated tag name → distinct title count from {@code title_effective_tags}. */
    public Map<String, Long> getTagCounts() {
        return titleRepo.getTagCounts();
    }

    /** Total number of rows in the titles table. */
    public long countAll() {
        return titleRepo.countAll();
    }

    /** Returns titles having ALL of the given tags, ordered newest-first. */
    public List<TitleSummary> findByTagsPaged(List<String> tags, int offset, int limit) {
        limit = cappedLimit(limit);
        return toSummaries(titleRepo.findByTagsPaged(tags, limit, offset));
    }

    /**
     * Returns a random sample of at most {@code limit} titles with an actress attribution.
     * Each call returns a fresh random set — offset-based pagination is intentionally not
     * supported here, so the frontend just keeps requesting more until it hits the cap.
     */
    public List<TitleSummary> findRandom(int limit) {
        limit = cappedLimit(limit);
        return toSummaries(titleRepo.findRandom(limit));
    }

    /**
     * Returns titles in the queue partition of the given volume, ordered newest-first.
     * Hard-capped at {@link #maxLimit} total regardless of requested limit.
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
        limit = cappedLimit(limit);
        List<Title> titles = titleRepo.findByVolumePaged(volumeId, limit, offset);
        return toSummaries(titles);
    }

    public List<TitleSummary> findByVolumePartition(String volumeId, String partition, int offset, int limit) {
        limit = cappedLimit(limit);
        return toSummaries(inferActresses(titleRepo.findByVolumeAndPartition(volumeId, partition, limit, offset)));
    }

    /**
     * Like {@link #findByVolumePaged} but with optional company and/or tag filters.
     * Pass {@code null}/{@code ""} company and empty tags to use the unfiltered path.
     */
    public List<TitleSummary> findByVolumePagedFiltered(String volumeId, String company, List<String> tags, int offset, int limit) {
        limit = cappedLimit(limit);
        Map<String, Label> labelMap = labelRepo.findAllAsMap();
        List<String> matchingLabels = resolveCompanyLabels(labelMap, company);
        if (company != null && !company.isBlank() && matchingLabels.isEmpty()) return List.of();
        boolean hasTags   = tags != null && !tags.isEmpty();
        boolean hasLabels = !matchingLabels.isEmpty();
        List<Title> titles = (hasTags || hasLabels)
                ? titleRepo.findByVolumeFiltered(volumeId, matchingLabels, hasTags ? tags : List.of(), limit, offset)
                : titleRepo.findByVolumePaged(volumeId, limit, offset);
        return toSummaries(titles);
    }

    /**
     * Like {@link #findByVolumePartition} but with optional company and/or tag filters.
     * Preserves actress inference for unattributed pool titles.
     */
    public List<TitleSummary> findByVolumePartitionFiltered(String volumeId, String partition, String company, List<String> tags, int offset, int limit) {
        limit = cappedLimit(limit);
        Map<String, Label> labelMap = labelRepo.findAllAsMap();
        List<String> matchingLabels = resolveCompanyLabels(labelMap, company);
        if (company != null && !company.isBlank() && matchingLabels.isEmpty()) return List.of();
        boolean hasTags   = tags != null && !tags.isEmpty();
        boolean hasLabels = !matchingLabels.isEmpty();
        List<Title> titles = (hasTags || hasLabels)
                ? titleRepo.findByVolumeAndPartitionFiltered(volumeId, partition, matchingLabels, hasTags ? tags : List.of(), limit, offset)
                : titleRepo.findByVolumeAndPartition(volumeId, partition, limit, offset);
        return toSummaries(inferActresses(titles));
    }

    /** Returns all distinct company names from the label catalog, sorted. */
    public List<String> listAllCompanies() {
        return labelRepo.findAllAsMap().values().stream()
                .map(Label::company)
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
    }

    /** Returns all distinct tags (direct + label-derived) for titles in the collections volume. */
    public List<String> findTagsForCollections() {
        return titleRepo.findTagsByVolume("collections");
    }

    /** Returns all distinct tags (direct + label-derived) for titles in the pool partition of a volume. */
    public List<String> findTagsForPool(String volumeId) {
        return titleRepo.findTagsByVolumeAndPartition(volumeId, "pool");
    }

    private List<String> resolveCompanyLabels(Map<String, Label> labelMap, String company) {
        if (company == null || company.isBlank()) return List.of();
        return labelMap.values().stream()
                .filter(l -> company.equals(l.company()))
                .map(l -> l.code().toUpperCase())
                .toList();
    }

    /**
     * Infers actress attribution for unattributed titles by matching on base_code against
     * attributed copies in the DB. Used by pool/archive browse to show actress info.
     */
    private List<Title> inferActresses(List<Title> titles) {
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
        if (actressIdByBaseCode.isEmpty()) return titles;
        return titles.stream()
                .map(t -> {
                    if (t.getActressId() != null) return t;
                    Long inferred = t.getBaseCode() != null ? actressIdByBaseCode.get(t.getBaseCode()) : null;
                    return inferred != null ? t.toBuilder().actressId(inferred).build() : t;
                })
                .toList();
    }

    List<TitleSummary> toSummaries(List<Title> titles) {
        record ActressInfo(String name, String tier, String dateOfBirth) {}

        Map<Long, ActressInfo> actressInfo = new HashMap<>(titles.stream()
                .map(Title::getActressId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toMap(
                        id -> id,
                        id -> actressRepo.findById(id)
                                .map(a -> new ActressInfo(a.getCanonicalName(),
                                        a.getTier() != null ? a.getTier().name() : null,
                                        a.getDateOfBirth() != null ? a.getDateOfBirth().toString() : null))
                                .orElse(new ActressInfo(null, null, null))
                )));

        Map<String, Label> labelMap = labelRepo.findAllAsMap();

        List<String> codes = titles.stream().map(Title::getCode).toList();
        Map<String, WatchHistoryRepository.WatchStats> watchStatsMap = watchHistoryRepo.findWatchStatsBatch(codes);

        List<Long> titleIds = titles.stream().map(Title::getId).filter(Objects::nonNull).toList();
        Map<Long, List<TitleSummary.EnrichmentTagEntry>> enrichmentTagsMap =
                titleRepo.findEnrichmentTagsByTitleIds(titleIds);
        Map<Long, com.organizer3.repository.TitleRepository.RatingData> ratingDataMap =
                titleRepo.findRatingDataByTitleIds(titleIds);

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
                    List<TitleSummary.LocationEntry> locationEntries = t.getLocations().stream()
                            .filter(loc -> loc.getVolumeId() != null && volumeSmbPaths.containsKey(loc.getVolumeId()))
                            .map(loc -> TitleSummary.LocationEntry.builder()
                                    .volumeId(loc.getVolumeId())
                                    .nasPath(volumeSmbPaths.get(loc.getVolumeId()) + loc.getPath())
                                    .locPath(loc.getPath().toString())
                                    .build())
                            .distinct()
                            .toList();
                    List<String> nasPaths = locationEntries.stream()
                            .map(TitleSummary.LocationEntry::getNasPath)
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
                                                                a.getTier() != null ? a.getTier().name() : null,
                                                                a.getDateOfBirth() != null ? a.getDateOfBirth().toString() : null))
                                                        .orElse(new ActressInfo(null, null, null)));
                                        return TitleSummary.ActressEntry.builder()
                                                .id(id)
                                                .name(linked.name())
                                                .tier(linked.tier())
                                                .dateOfBirth(linked.dateOfBirth())
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

                    com.organizer3.repository.TitleRepository.RatingData rd =
                            t.getId() != null ? ratingDataMap.get(t.getId()) : null;

                    return TitleSummary.builder()
                            .code(t.getCode())
                            .baseCode(t.getBaseCode())
                            .label(t.getLabel())
                            .actressId(t.getActressId())
                            .actressName(ai != null ? ai.name() : null)
                            .actressTier(ai != null ? ai.tier() : null)
                            .actressDateOfBirth(ai != null ? ai.dateOfBirth() : null)
                            .addedDate(t.getAddedDate() != null ? t.getAddedDate().toString() : null)
                            .coverUrl(coverUrl)
                            .companyName(lbl != null ? lbl.company() : null)
                            .labelName(lbl != null ? lbl.labelName() : null)
                            .location(t.getPath() != null ? t.getPath().toString() : null)
                            .locations(allLocations)
                            .nasPaths(nasPaths)
                            .locationEntries(locationEntries)
                            .actresses(actresses)
                            .titleEnglish(t.getTitleEnglish())
                            .titleOriginal(t.getTitleOriginal())
                            .releaseDate(t.getReleaseDate() != null ? t.getReleaseDate().toString() : null)
                            .grade(t.getGrade() != null ? t.getGrade().display : null)
                            .gradeSource(t.getGradeSource())
                            .ratingAvg(rd != null ? rd.ratingAvg() : null)
                            .ratingCount(rd != null ? rd.ratingCount() : null)
                            .favorite(t.isFavorite())
                            .bookmark(t.isBookmark())
                            .lastWatchedAt(watchStatsMap.containsKey(t.getCode()) ? watchStatsMap.get(t.getCode()).lastWatchedAt().toString() : null)
                            .watchCount(watchStatsMap.containsKey(t.getCode()) ? watchStatsMap.get(t.getCode()).count() : 0)
                            .visitCount(t.getVisitCount())
                            .lastVisitedAt(t.getLastVisitedAt() != null ? t.getLastVisitedAt().toString() : null)
                            .tags(allTags)
                            .enrichmentTags(t.getId() != null
                                    ? enrichmentTagsMap.getOrDefault(t.getId(), List.of())
                                    : List.of())
                            .build();
                })
                .toList();
    }

    // ── Duplication management ───────────────────────────────────────────────

    /** Result page for the duplicates tool. */
    public record DuplicatePage(List<TitleSummary> titles, int total) {}

    /** One entry in the volumes dropdown: stable id and display SMB path. */
    public record VolumeEntry(String id, String smbPath) {}

    /** Returns all configured volumes, sorted by smbPath, for the filter dropdown. */
    public List<VolumeEntry> listVolumes() {
        return volumeSmbPaths.entrySet().stream()
                .map(e -> new VolumeEntry(e.getKey(), e.getValue()))
                .sorted(java.util.Comparator.comparing(VolumeEntry::smbPath))
                .toList();
    }

    /**
     * Returns titles that exist in more than one location, ordered by code ascending.
     * If {@code volumeId} is non-null, only titles with at least one location on that volume
     * are included. Each {@link TitleSummary} includes the full {@code nasPaths} list.
     */
    public DuplicatePage findDuplicatesPaged(int offset, int limit, String volumeId) {
        limit = cappedLimit(limit);
        List<TitleSummary> titles = toSummaries(titleRepo.findWithMultipleLocationsPaged(limit, offset, volumeId));
        int total = titleRepo.countWithMultipleLocations(volumeId);
        return new DuplicatePage(titles, total);
    }

    /** Returns the most recently visited titles (visit_count > 0), ordered by last_visited_at DESC. */
    public List<TitleSummary> findLastVisited(int limit) {
        return toSummaries(titleRepo.findLastVisited(limit));
    }

    /** Returns the most-visited titles (visit_count > 0), ordered by visit_count DESC. */
    public List<TitleSummary> findMostVisited(int limit) {
        return toSummaries(titleRepo.findMostVisited(limit));
    }

    // -------------------------------------------------------------------------
    // Titles dashboard composition — delegates to TitleDashboardBuilder
    // -------------------------------------------------------------------------

    /**
     * Pick a fresh spotlight title, optionally excluding a code already on screen.
     * Used by the rotating-spotlight endpoint.
     */
    public TitleSummary getSpotlight(String excludeCode) {
        return dashboardBuilder.getSpotlight(excludeCode);
    }

    public TitleDashboardBuilder.TitleDashboard buildDashboard() {
        return dashboardBuilder.buildDashboard();
    }

    /** Result of a visit record operation. */
    public record VisitStats(int visitCount, String lastVisitedAt) {}

    /**
     * Record a detail-page visit for a title. Increments the visit counter and updates
     * the last_visited_at timestamp.
     *
     * @return the updated visit stats, or empty if the title does not exist
     */
    public Optional<VisitStats> recordVisit(String code) {
        return titleRepo.findByCode(code).map(t -> {
            titleRepo.recordVisit(t.getId());
            Title updated = titleRepo.findById(t.getId()).orElseThrow();
            return new VisitStats(
                    updated.getVisitCount(),
                    updated.getLastVisitedAt() != null ? updated.getLastVisitedAt().toString() : null);
        });
    }
}
