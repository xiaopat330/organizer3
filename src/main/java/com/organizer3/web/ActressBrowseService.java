package com.organizer3.web;

import com.organizer3.ai.ActressNameLookup;
import com.organizer3.covers.CoverPath;
import com.organizer3.model.Actress;
import com.organizer3.model.ActressAlias;
import com.organizer3.model.Label;
import com.organizer3.model.StudioGroup;
import com.organizer3.model.Title;
import com.organizer3.repository.ActressRepository;
import com.organizer3.repository.LabelRepository;
import com.organizer3.repository.TitleRepository;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Backing service for the actress browse UI.
 *
 * <p>The prefix index groups actresses by the first letter of their canonical name
 * (which is stored given-name-first, so this is effectively first-name indexing).
 * Letters with more than {@link #SPLIT_THRESHOLD} actresses are expanded to two-character
 * prefixes — only those with at least one match are included.
 */
@Slf4j
@RequiredArgsConstructor
public class ActressBrowseService {

    private static final int MAX_COVERS = 12;

    private final ActressRepository actressRepo;
    private final TitleRepository titleRepo;
    private final CoverPath coverPath;
    /** volumeId → smbPath, e.g. "a" → "//pandora/jav_A" */
    private final Map<String, String> volumeSmbPaths;
    private final LabelRepository labelRepo;
    private final ActressNameLookup nameLookup;
    /** Nullable — backup is skipped if not configured. */
    private final StageNameBackupFile backupFile;
    private final org.jdbi.v3.core.Jdbi jdbi;

    public List<String> findPrefixIndex() {
        List<Actress> all = actressRepo.findAll();

        return all.stream()
                .map(a -> String.valueOf(Character.toUpperCase(a.getCanonicalName().charAt(0))))
                .distinct()
                .sorted()
                .toList();
    }

    /**
     * Returns a random sample of at most {@code limit} actresses, enriched with title count,
     * cover URLs, and SMB folder paths. Each call returns a fresh random set.
     */
    public List<ActressSummary> findRandom(int limit) {
        List<Actress> actresses = actressRepo.findRandom(limit);
        return toSummaries(actresses, "random");
    }

    /**
     * Returns all actresses whose canonical name starts with {@code prefix}, enriched with
     * title count, cover image URLs, and SMB folder paths.
     */
    public List<ActressSummary> findByPrefix(String prefix) {
        return toSummaries(actressRepo.findByFirstNamePrefix(prefix), "prefix:" + prefix);
    }

    /**
     * Returns actress counts grouped by tier for the given prefix, e.g. {"GODDESS": 3, "SUPERSTAR": 12}.
     */
    public Map<String, Integer> findTierCountsByPrefix(String prefix) {
        return actressRepo.countByFirstNamePrefixGroupedByTier(prefix);
    }

    /**
     * Paginated version: returns actresses whose canonical name starts with {@code letter}
     * (a single letter), optionally filtered by tier.
     */
    public List<ActressSummary> findByPrefixPaged(String letter, String tierName, int offset, int limit) {
        Actress.Tier tier = null;
        if (tierName != null && !tierName.isBlank()) {
            tier = Actress.Tier.valueOf(tierName.toUpperCase());
        }
        return toSummaries(actressRepo.findByFirstNamePrefixPaged(letter, tier, limit, offset), "prefix-paged:" + letter);
    }

    /**
     * Paginated: actresses on the given volumes, favorites/bookmarks first. When
     * {@code company} is non-blank, narrows to actresses with ≥1 title by that company.
     */
    public List<ActressSummary> findByVolumesPaged(List<String> volumeIds, String company,
                                                   int offset, int limit) {
        long q0 = System.currentTimeMillis();
        List<Actress> hits = (company != null && !company.isBlank())
                ? actressRepo.findByVolumesAndCompaniesPaged(volumeIds, List.of(company), limit, offset)
                : actressRepo.findByVolumeIdsPaged(volumeIds, limit, offset);
        long q1 = System.currentTimeMillis();
        log.warn(">>> PERF findByVolumesPaged volumes={} company={} offset={}: actressQuery={}ms",
                volumeIds, company, offset, q1 - q0);
        return toSummaries(hits, "volumes:" + String.join(",", volumeIds));
    }

    /**
     * Returns all actresses at the given tier level, enriched with title count, cover URLs,
     * and SMB folder paths.
     *
     * @throws IllegalArgumentException if {@code tierName} does not match a known {@link Actress.Tier}
     */
    public List<ActressSummary> findByTier(String tierName) {
        Actress.Tier tier = Actress.Tier.valueOf(tierName.toUpperCase());
        return toSummaries(actressRepo.findByTier(tier), "tier:" + tierName);
    }

    /** Paginated tier query with optional company filter. */
    public List<ActressSummary> findByTierPaged(String tierName, String company, int offset, int limit) {
        Actress.Tier tier = Actress.Tier.valueOf(tierName.toUpperCase());
        List<Actress> hits = (company != null && !company.isBlank())
                ? actressRepo.findByTierAndCompaniesPaged(tier, List.of(company), limit, offset)
                : actressRepo.findByTierPaged(tier, limit, offset);
        return toSummaries(hits, "tier-paged:" + tierName);
    }

    /** Paginated all-actresses query. */
    public List<ActressSummary> findAllPaged(int offset, int limit) {
        return toSummaries(actressRepo.findAllPaged(limit, offset), "all");
    }

    /** Paginated favorites query. */
    public List<ActressSummary> findFavoritesPaged(int offset, int limit) {
        return toSummaries(actressRepo.findFavoritesPaged(limit, offset), "favorites");
    }

    /** Returns up to {@code limit} most recently visited actresses, newest visit first. */
    public List<ActressSummary> findLastVisited(int limit) {
        return toSummaries(actressRepo.findLastVisited(limit), "last-visited");
    }

    /** Returns up to {@code limit} most-visited actresses, highest visit count first. */
    public List<ActressSummary> findMostVisited(int limit) {
        return toSummaries(actressRepo.findMostVisited(limit), "most-visited");
    }

    /** Paginated bookmarks query. */
    public List<ActressSummary> findBookmarksPaged(int offset, int limit) {
        return toSummaries(actressRepo.findBookmarksPaged(limit, offset), "bookmarks");
    }

    /**
     * Paginated: actresses owning ≥1 title under any company in the given studios.yaml
     * group slug. Returns empty if the slug is unknown. When {@code companyFilter} is
     * non-blank, narrows to that company (but only if it's actually a member of the group —
     * an unknown company collapses to empty, never an unconstrained query). Sorted by
     * tier rank (GODDESS first), then canonical name.
     */
    public List<ActressSummary> findByStudioGroupPaged(String groupSlug, String companyFilter,
                                                       int offset, int limit) {
        List<String> companies = resolveQueryCompanies(groupSlug, companyFilter);
        if (companies.isEmpty()) return List.of();
        return toSummaries(actressRepo.findByStudioGroupCompaniesPaged(companies, limit, offset), "studio-group:" + groupSlug);
    }

    /**
     * Resolve the slug to its company list, then optionally narrow to a single company —
     * only if that company is actually in the group. Unknown slug, unknown company, or a
     * company not in the group all collapse to an empty list.
     */
    private List<String> resolveQueryCompanies(String groupSlug, String companyFilter) {
        List<String> all = resolveStudioGroupCompanies(groupSlug);
        if (all.isEmpty()) return List.of();
        if (companyFilter == null || companyFilter.isBlank()) return all;
        return all.contains(companyFilter) ? List.of(companyFilter) : List.of();
    }

    /** Lightweight projection of a label company plus its title count within a studio group. */
    public record CompanyCount(String company, long titleCount) {}

    /**
     * Returns the companies belonging to the given studio group, ordered by title count
     * descending (then alphabetical as a tiebreaker). Companies with zero matching titles
     * are still included so the dropdown surfaces every defined sub-label, just at the
     * bottom. Returns an empty list for an unknown slug.
     */
    public List<CompanyCount> listGroupCompaniesByTitleCount(String groupSlug) {
        List<String> companies = resolveStudioGroupCompanies(groupSlug);
        if (companies.isEmpty()) return List.of();
        Map<String, Long> counts = titleRepo.countTitlesByCompanies(companies);
        return companies.stream()
                .map(c -> new CompanyCount(c, counts.getOrDefault(c, 0L)))
                .sorted(Comparator.comparingLong(CompanyCount::titleCount).reversed()
                        .thenComparing(CompanyCount::company))
                .toList();
    }

    private List<String> resolveStudioGroupCompanies(String groupSlug) {
        if (groupSlug == null || groupSlug.isBlank()) return List.of();
        return new StudioGroupLoader().load().stream()
                .filter(g -> groupSlug.equals(g.slug()))
                .findFirst()
                .map(StudioGroup::companies)
                .orElse(List.of());
    }

    /**
     * Paginated name search: matches actresses whose canonical name (or any name token)
     * starts with {@code query}, case-insensitively.
     */
    public List<ActressSummary> searchByNamePaged(String query, int offset, int limit) {
        return toSummaries(actressRepo.searchByNamePrefixPaged(query, limit, offset), "search:" + query);
    }

    /**
     * Returns the full {@link ActressSummary} for a single actress by ID, or empty if not found.
     */
    public Optional<ActressSummary> findById(long id) {
        return actressRepo.findById(id).map(a -> {
            SummaryContext ctx = buildContext(List.of(a), "findById:" + id);
            return toSummary(a, ctx);
        });
    }

    /** Fetch full summaries for a batch of actress IDs, preserving the given order. */
    public List<ActressSummary> findByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        List<Actress> actresses = actressRepo.findByIds(ids);
        SummaryContext ctx = buildContext(actresses, "findByIds");
        Map<Long, ActressSummary> byId = actresses.stream()
                .collect(Collectors.toMap(Actress::getId, a -> toSummary(a, ctx)));
        return ids.stream().map(byId::get).filter(Objects::nonNull).toList();
    }

    /**
     * Returns all effective tags that appear across any of the given actress's titles,
     * sorted alphabetically. Reads from {@code title_effective_tags} so all three sources
     * (direct, label, enrichment) are included in a single union query.
     */
    public List<String> findTagsForActress(long actressId) {
        return jdbi.withHandle(h -> h.createQuery("""
                SELECT DISTINCT tet.tag
                FROM title_effective_tags tet
                JOIN title_actresses ta ON ta.title_id = tet.title_id
                WHERE ta.actress_id = :actressId
                ORDER BY tet.tag
                """)
                .bind("actressId", actressId)
                .mapTo(String.class)
                .list());
    }

    public record ActressEnrichmentTag(long id, String name, String curatedAlias, boolean surface, int titleCount) {}

    /**
     * Returns enrichment tag definitions that appear on at least one of the actress's titles,
     * with a title count scoped to this actress. Sorted by name.
     */
    public List<ActressEnrichmentTag> findEnrichmentTagsForActress(long actressId) {
        return jdbi.withHandle(h -> h.createQuery("""
                SELECT etd.id, etd.name, etd.curated_alias, etd.surface, COUNT(DISTINCT tet.title_id) AS title_count
                FROM enrichment_tag_definitions etd
                JOIN title_enrichment_tags tet ON tet.tag_id = etd.id
                JOIN title_actresses ta ON ta.title_id = tet.title_id
                WHERE ta.actress_id = :actressId
                GROUP BY etd.id
                ORDER BY etd.name
                """)
                .bind("actressId", actressId)
                .map((rs, ctx) -> new ActressEnrichmentTag(
                        rs.getLong("id"),
                        rs.getString("name"),
                        rs.getString("curated_alias"),
                        rs.getInt("surface") != 0,
                        rs.getInt("title_count")
                ))
                .list());
    }

    /**
     * Returns a paginated list of title summaries for the given actress, ordered newest-first.
     * If {@code company} is non-null, only titles whose label belongs to that company are returned.
     * If {@code tags} is non-empty, only titles carrying all of those tags (direct or label-derived)
     * are returned. The two filters are combined with AND.
     */
    public List<TitleSummary> findTitlesByActress(long actressId, int offset, int limit, String company, List<String> tags, List<Long> enrichmentTagIds) {
        Map<String, Label> labelMap = labelRepo.findAllAsMap();

        List<String> matchingLabels = List.of();
        if (company != null && !company.isBlank()) {
            matchingLabels = labelMap.values().stream()
                    .filter(l -> company.equals(l.company()))
                    .map(l -> l.code().toUpperCase())
                    .toList();
        }

        List<Title> titles;
        boolean hasTags        = tags != null && !tags.isEmpty();
        boolean hasEnrichTags  = enrichmentTagIds != null && !enrichmentTagIds.isEmpty();
        boolean hasLabels      = !matchingLabels.isEmpty();

        if (hasTags || hasEnrichTags) {
            // use the combined filter (handles optional labels too)
            if (company != null && !company.isBlank() && matchingLabels.isEmpty()) {
                titles = List.of(); // company specified but no known labels → no results
            } else {
                titles = titleRepo.findByActressTagsFiltered(actressId, matchingLabels,
                        tags != null ? tags : List.of(),
                        enrichmentTagIds != null ? enrichmentTagIds : List.of(),
                        limit, offset);
            }
        } else if (hasLabels) {
            titles = titleRepo.findByActressAndLabelsPaged(actressId, matchingLabels, limit, offset);
        } else {
            titles = titleRepo.findByActressPaged(actressId, limit, offset);
        }

        Actress actress = actressRepo.findById(actressId).orElse(null);
        String actressName = actress != null ? actress.getCanonicalName() : null;
        String actressTier = actress != null && actress.getTier() != null ? actress.getTier().name() : null;

        List<Long> titleIds = titles.stream()
                .map(Title::getId)
                .filter(Objects::nonNull)
                .toList();
        Map<Long, List<TitleSummary.EnrichmentTagEntry>> enrichmentTagsMap =
                titleRepo.findEnrichmentTagsByTitleIds(titleIds);

        return titles.stream()
                .map(t -> {
                    Label lbl = t.getLabel() != null ? labelMap.get(t.getLabel().toUpperCase()) : null;
                    List<String> allLocations = t.getLocations().stream()
                            .map(loc -> loc.getPath().toString())
                            .toList();
                    List<String> nasPaths = t.getLocations().stream()
                            .filter(loc -> loc.getVolumeId() != null && volumeSmbPaths.containsKey(loc.getVolumeId()))
                            .map(loc -> volumeSmbPaths.get(loc.getVolumeId()) + loc.getPath())
                            .distinct()
                            .toList();
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
                            .actressId(actressId)
                            .actressName(actressName)
                            .actressTier(actressTier)
                            .addedDate(t.getAddedDate() != null ? t.getAddedDate().toString() : null)
                            .coverUrl(coverPath.find(t)
                                    .map(p -> "/covers/" + t.getLabel().toUpperCase() + "/" + p.getFileName())
                                    .orElse(null))
                            .companyName(lbl != null ? lbl.company() : null)
                            .labelName(lbl != null ? lbl.labelName() : null)
                            .location(t.getPath() != null ? t.getPath().toString() : null)
                            .locations(allLocations)
                            .nasPaths(nasPaths)
                            .titleEnglish(t.getTitleEnglish())
                            .titleOriginal(t.getTitleOriginal())
                            .releaseDate(t.getReleaseDate() != null ? t.getReleaseDate().toString() : null)
                            .grade(t.getGrade() != null ? t.getGrade().display : null)
                            .tags(allTags)
                            .enrichmentTags(t.getId() != null
                                    ? enrichmentTagsMap.getOrDefault(t.getId(), List.of())
                                    : List.of())
                            .visitCount(t.getVisitCount())
                            .lastVisitedAt(t.getLastVisitedAt() != null ? t.getLastVisitedAt().toString() : null)
                            .build();
                })
                .toList();
    }

    /**
     * Pre-fetched data for a batch of actresses — replaces per-actress repo calls in
     * {@link #toSummary(Actress, SummaryContext)}.
     */
    private record SummaryContext(
            Map<Long, List<Title>> titlesByActress,
            Map<Long, String> coverUrlByTitleId,
            Map<String, Label> labelMap,
            Map<Long, List<ActressAlias>> aliasesByActress,
            Map<String, Long> aliasNameToActressId,
            Map<String, Actress> primaryByCanonicalName,
            Map<Long, String> localAvatarUrlByActress
    ) {}

    private List<ActressSummary> toSummaries(List<Actress> actresses, String label) {
        if (actresses.isEmpty()) return List.of();
        SummaryContext ctx = buildContext(actresses, label);
        return actresses.stream().map(a -> toSummary(a, ctx)).toList();
    }

    private SummaryContext buildContext(List<Actress> actresses, String label) {
        List<Long> ids = actresses.stream().map(Actress::getId).toList();
        long t0 = System.currentTimeMillis();

        // 1. All titles for all actresses in one UNION query.
        Map<Long, List<Title>> titlesByActress = titleRepo.findByActressIds(ids);
        long t1 = System.currentTimeMillis();

        // 2. Cover URLs: probe at most MAX_COVERS titles per actress in random order.
        Map<Long, String> coverUrlByTitleId = new HashMap<>();
        for (List<Title> titles : titlesByActress.values()) {
            List<Title> shuffled = new ArrayList<>(titles);
            Collections.shuffle(shuffled);
            int found = 0;
            for (Title t : shuffled) {
                if (found >= MAX_COVERS) break;
                if (!coverUrlByTitleId.containsKey(t.getId())) {
                    coverPath.find(t).ifPresent(p ->
                            coverUrlByTitleId.put(t.getId(),
                                    "/covers/" + t.getLabel().toUpperCase() + "/" + p.getFileName()));
                }
                if (coverUrlByTitleId.containsKey(t.getId())) found++;
            }
        }
        long t2 = System.currentTimeMillis();

        // 3. Label map — one query for the whole batch.
        Map<String, Label> labelMap = labelRepo.findAllAsMap();
        long t3 = System.currentTimeMillis();

        // 4. Aliases — one query for the whole batch.
        Map<Long, List<ActressAlias>> aliasesByActress = actressRepo.findAliasesForActresses(ids);
        long t4 = System.currentTimeMillis();

        // 5. alias name → actress id (for AliasDto.actressId resolution).
        List<String> allAliasNames = aliasesByActress.values().stream()
                .flatMap(Collection::stream)
                .map(ActressAlias::aliasName)
                .distinct()
                .toList();
        Map<String, Long> aliasNameToActressId = actressRepo.findCanonicalNameIds(allAliasNames);
        long t5 = System.currentTimeMillis();

        // 6. canonical name → primary actress (for each actress's own canonical name).
        List<String> canonicalNames = actresses.stream().map(Actress::getCanonicalName).toList();
        Map<String, Actress> primaryByCanonicalName = actressRepo.findPrimaryForAliases(canonicalNames);
        long t6 = System.currentTimeMillis();

        int totalTitles = titlesByActress.values().stream().mapToInt(List::size).sum();
        log.warn(">>> PERF buildContext [{}] n={} titles={}: findByActressIds={}ms covers={}ms labelMap={}ms aliases={}ms canonicalNameIds={}ms primaryForAliases={}ms TOTAL={}ms",
                label, ids.size(), totalTitles,
                t1-t0, t2-t1, t3-t2, t4-t3, t5-t4, t6-t5, t6-t0);

        // 7. Local avatar URLs from javdb_actress_staging — used by the actress detail page.
        Map<Long, String> localAvatarUrlByActress = ids.isEmpty()
                ? java.util.Map.of()
                : jdbi.withHandle(h -> h.createQuery("""
                        SELECT actress_id, local_avatar_path
                        FROM javdb_actress_staging
                        WHERE actress_id IN (<ids>)
                          AND local_avatar_path IS NOT NULL
                        """)
                        .bindList("ids", ids)
                        .map((rs, c) -> Map.entry(rs.getLong("actress_id"),
                                "/" + rs.getString("local_avatar_path")))
                        .list()
                        .stream()
                        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));

        return new SummaryContext(
                titlesByActress, coverUrlByTitleId, labelMap,
                aliasesByActress, aliasNameToActressId, primaryByCanonicalName,
                localAvatarUrlByActress);
    }

    private ActressSummary toSummary(Actress actress, SummaryContext ctx) {
        List<Title> titles = ctx.titlesByActress().getOrDefault(actress.getId(), List.of());

        List<String> coverUrls = titles.stream()
                .map(t -> ctx.coverUrlByTitleId().get(t.getId()))
                .filter(Objects::nonNull)
                .limit(MAX_COVERS)
                .toList();

        List<String> folderPaths = titles.stream()
                .flatMap(t -> t.getLocations().stream())
                .filter(loc -> loc.getPartitionId() != null && loc.getPartitionId().startsWith("stars"))
                .map(loc -> {
                    Path actressFolder = loc.getPath().getParent();
                    if (actressFolder == null) return null;
                    String smbBase = volumeSmbPaths.get(loc.getVolumeId());
                    if (smbBase == null) return null;
                    return smbBase + actressFolder;
                })
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        String firstAdded = actress.getFirstSeenAt() != null
                ? actress.getFirstSeenAt().toString() : null;
        String lastAdded = titles.stream()
                .map(Title::getAddedDate)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .map(Object::toString)
                .orElse(null);

        String gradeDisplay = actress.getGrade() != null ? actress.getGrade().display : null;

        // Grade rollup over this actress's titles. Insertion-ordered SSS→F using the enum order
        // so the UI can render the histogram without re-sorting; only grades present are kept.
        java.util.EnumMap<Actress.Grade, Integer> gradeCounts = new java.util.EnumMap<>(Actress.Grade.class);
        int gradedCount = 0;
        for (Title t : titles) {
            Actress.Grade g = t.getGrade();
            if (g == null) continue;
            gradeCounts.merge(g, 1, Integer::sum);
            gradedCount++;
        }
        java.util.LinkedHashMap<String, Integer> gradeBreakdown = new java.util.LinkedHashMap<>();
        for (Actress.Grade g : Actress.Grade.values()) {
            Integer n = gradeCounts.get(g);
            if (n != null) gradeBreakdown.put(g.display, n);
        }

        List<String> companies = titles.stream()
                .filter(t -> t.getLabel() != null)
                .map(t -> {
                    Label lbl = ctx.labelMap().get(t.getLabel().toUpperCase());
                    return lbl != null ? lbl.company() : null;
                })
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();

        List<ActressAlias> rawAliases = ctx.aliasesByActress().getOrDefault(actress.getId(), List.of());
        List<ActressSummary.AliasDto> aliases = rawAliases.stream()
                .map(ActressAlias::aliasName)
                .sorted()
                .map(name -> ActressSummary.AliasDto.builder()
                        .name(name)
                        .actressId(ctx.aliasNameToActressId().get(name))
                        .build())
                .toList();

        Actress primaryActress = ctx.primaryByCanonicalName().get(actress.getCanonicalName());

        String earliestTitleDate = titles.stream()
                .map(Title::getAddedDate)
                .filter(Objects::nonNull)
                .min(Comparator.naturalOrder())
                .map(Object::toString)
                .orElse(null);

        List<ActressSummary.AlternateNameDto> altNames = actress.getAlternateNames() == null
                ? List.of()
                : actress.getAlternateNames().stream()
                        .map(a -> ActressSummary.AlternateNameDto.builder()
                                .name(a.name())
                                .note(a.note())
                                .build())
                        .toList();

        List<ActressSummary.StudioTenureDto> studios = actress.getPrimaryStudios() == null
                ? List.of()
                : actress.getPrimaryStudios().stream()
                        .map(s -> ActressSummary.StudioTenureDto.builder()
                                .name(s.name())
                                .company(s.company())
                                .from(s.from() != null ? s.from().toString() : null)
                                .to(s.to() != null ? s.to().toString() : null)
                                .role(s.role())
                                .build())
                        .toList();

        List<ActressSummary.AwardDto> awardList = actress.getAwards() == null
                ? List.of()
                : actress.getAwards().stream()
                        .map(a -> ActressSummary.AwardDto.builder()
                                .event(a.event())
                                .year(a.year())
                                .category(a.category())
                                .build())
                        .toList();

        return ActressSummary.builder()
                .id(actress.getId())
                .canonicalName(actress.getCanonicalName())
                .stageName(actress.getStageName())
                .nameReading(actress.getNameReading())
                .tier(actress.getTier().name())
                .favorite(actress.isFavorite())
                .bookmark(actress.isBookmark())
                .grade(gradeDisplay)
                .rejected(actress.isRejected())
                .titleCount(titles.size())
                .gradedTitleCount(gradedCount)
                .gradeBreakdown(gradeBreakdown)
                .coverUrls(coverUrls)
                .folderPaths(folderPaths)
                .firstAddedDate(firstAdded)
                .lastAddedDate(lastAdded)
                .companies(companies)
                .aliases(aliases)
                .alternateNames(altNames)
                .activeFrom(actress.getActiveFrom() != null ? actress.getActiveFrom().toString() : earliestTitleDate)
                .activeTo(actress.getActiveTo() != null ? actress.getActiveTo().toString() : lastAdded)
                .retirementAnnounced(actress.getRetirementAnnounced() != null
                        ? actress.getRetirementAnnounced().toString() : null)
                .dateOfBirth(actress.getDateOfBirth() != null ? actress.getDateOfBirth().toString() : null)
                .birthplace(actress.getBirthplace())
                .bloodType(actress.getBloodType())
                .heightCm(actress.getHeightCm())
                .bust(actress.getBust())
                .waist(actress.getWaist())
                .hip(actress.getHip())
                .cup(actress.getCup())
                .biography(actress.getBiography())
                .legacy(actress.getLegacy())
                .primaryStudios(studios)
                .awards(awardList)
                .primaryActressId(primaryActress != null ? primaryActress.getId() : null)
                .primaryActressName(primaryActress != null ? primaryActress.getCanonicalName() : null)
                .visitCount(actress.getVisitCount())
                .lastVisitedAt(actress.getLastVisitedAt() != null ? actress.getLastVisitedAt().toString() : null)
                .localAvatarUrl(ctx.localAvatarUrlByActress().get(actress.getId()))
                .build();
    }

    /** Result of a visit record operation. */
    public record VisitStats(int visitCount, String lastVisitedAt) {}

    /**
     * Record a detail-page visit for an actress. Increments the visit counter and updates
     * the last_visited_at timestamp.
     *
     * @return the updated visit stats, or empty if the actress does not exist
     */
    public Optional<VisitStats> recordVisit(long actressId) {
        return actressRepo.findById(actressId).map(a -> {
            actressRepo.recordVisit(actressId);
            Actress updated = actressRepo.findById(actressId).orElseThrow();
            return new VisitStats(
                    updated.getVisitCount(),
                    updated.getLastVisitedAt() != null ? updated.getLastVisitedAt().toString() : null);
        });
    }

    /**
     * Result of a flag toggle operation — returns the full post-toggle state of all three
     * mutually-interacting flags so the client can stay in sync without a follow-up fetch.
     */
    public record FlagState(long id, boolean favorite, boolean bookmark, boolean rejected) {}

    /**
     * Toggle {@code favorite} for an actress. Favoriting implicitly clears {@code rejected}
     * (a rejected actress cannot also be a favorite). Bookmark is left unchanged.
     * @return the full post-toggle flag state, or empty if no such actress
     */
    public Optional<FlagState> toggleFavorite(long actressId) {
        return actressRepo.findById(actressId).map(a -> {
            boolean fav = !a.isFavorite();
            boolean bm  = a.isBookmark();
            boolean rej = fav ? false : a.isRejected();
            actressRepo.setFlags(actressId, fav, bm, rej);
            log.info("Actress modified — id={} name=\"{}\" favorite={} (bookmark={} rejected={})",
                    actressId, a.getCanonicalName(), fav, bm, rej);
            return new FlagState(actressId, fav, bm, rej);
        });
    }

    /**
     * Toggle {@code bookmark} for an actress. Bookmarking implicitly clears {@code rejected}.
     * Favorite is left unchanged.
     * @return the full post-toggle flag state, or empty if no such actress
     */
    public Optional<FlagState> toggleBookmark(long actressId) {
        return actressRepo.findById(actressId).map(a -> {
            boolean bm  = !a.isBookmark();
            boolean fav = a.isFavorite();
            boolean rej = bm ? false : a.isRejected();
            actressRepo.setFlags(actressId, fav, bm, rej);
            log.info("Actress modified — id={} name=\"{}\" bookmark={} (favorite={} rejected={})",
                    actressId, a.getCanonicalName(), bm, fav, rej);
            return new FlagState(actressId, fav, bm, rej);
        });
    }

    /**
     * Set {@code bookmark} to an explicit value for an actress. Bookmarking implicitly
     * clears {@code rejected}; un-bookmarking leaves other flags unchanged.
     */
    public Optional<FlagState> setBookmark(long actressId, boolean value) {
        return actressRepo.findById(actressId).map(a -> {
            boolean fav = a.isFavorite();
            boolean rej = value ? false : a.isRejected();
            actressRepo.setFlags(actressId, fav, value, rej);
            log.info("Actress modified — id={} name=\"{}\" bookmark={} (explicit set) (favorite={} rejected={})",
                    actressId, a.getCanonicalName(), value, fav, rej);
            return new FlagState(actressId, fav, value, rej);
        });
    }

    /**
     * Toggle {@code rejected} for an actress. Rejecting implicitly clears both
     * {@code favorite} and {@code bookmark} (a rejected actress cannot be either).
     * @return the full post-toggle flag state, or empty if no such actress
     */
    public Optional<FlagState> toggleRejected(long actressId) {
        return actressRepo.findById(actressId).map(a -> {
            boolean rej = !a.isRejected();
            boolean fav = rej ? false : a.isFavorite();
            boolean bm  = rej ? false : a.isBookmark();
            actressRepo.setFlags(actressId, fav, bm, rej);
            log.info("Actress modified — id={} name=\"{}\" rejected={} (favorite={} bookmark={})",
                    actressId, a.getCanonicalName(), rej, fav, bm);
            return new FlagState(actressId, fav, bm, rej);
        });
    }

    /**
     * Calls the AI name lookup for the given actress, persists the result to the database
     * and the YAML backup file, and returns the stage name found.
     *
     * @return the stage name if Claude could determine it, or empty if the actress was not
     *         found in the DB or Claude returned "unknown"
     */
    public Optional<String> searchStageName(long actressId) {
        Actress actress = actressRepo.findById(actressId).orElse(null);
        if (actress == null) return Optional.empty();

        List<Title> titles = titleRepo.findByActress(actressId);
        log.info("Stage name search: actress='{}' titles={}", actress.getCanonicalName(), titles.size());

        Optional<String> result = nameLookup.findJapaneseName(actress, titles);

        if (result.isPresent()) {
            String stageName = result.get();
            log.info("Stage name found: '{}' → '{}'", actress.getCanonicalName(), stageName);
            actressRepo.setStageName(actressId, stageName);
            if (backupFile != null) {
                backupFile.save(actress.getCanonicalName(), stageName);
            }
        } else {
            log.info("Stage name not found for '{}'", actress.getCanonicalName());
        }

        return result;
    }

    // -------------------------------------------------------------------------
    // Alias management
    // -------------------------------------------------------------------------

    /**
     * Result of an alias update: either success or a conflict error message. On conflicts
     * against another actress, {@code conflictActressId} / {@code conflictActressName}
     * identify that actress so the UI can offer a link to edit her directly.
     *
     * <p>{@code conflictKind} disambiguates:
     * <ul>
     *   <li>{@code "canonical"} — the alias is the canonical name of another actress. Merging
     *       that actress into the current one is a legitimate resolution.</li>
     *   <li>{@code "alias"} — the alias is already an alias of a third actress. The user must
     *       first detach it there; merging is NOT appropriate.</li>
     * </ul>
     */
    public record AliasUpdateResult(
            boolean ok, String error,
            Long conflictActressId, String conflictActressName, String conflictKind) {
        public static AliasUpdateResult success() { return new AliasUpdateResult(true, null, null, null, null); }
        public static AliasUpdateResult conflict(String msg) { return new AliasUpdateResult(false, msg, null, null, null); }
        public static AliasUpdateResult conflictCanonical(String msg, Actress other) {
            return new AliasUpdateResult(false, msg, other.getId(), other.getCanonicalName(), "canonical");
        }
        public static AliasUpdateResult conflictAlias(String msg, Actress other) {
            return new AliasUpdateResult(false, msg, other.getId(), other.getCanonicalName(), "alias");
        }
    }

    /**
     * Replace all aliases for an actress with the given list, after conflict-checking each
     * proposed alias against other actresses. Blank entries are silently dropped.
     *
     * <p>Conflicts: an alias cannot be the canonical name of a different actress, and cannot
     * be an existing alias already owned by a different actress.
     *
     * @return {@link AliasUpdateResult#success()} on success, or a conflict result with a
     *         human-readable error if any alias is already taken
     */
    public AliasUpdateResult updateAliases(long actressId, List<String> aliases) {
        Optional<Actress> target = actressRepo.findById(actressId);
        if (target.isEmpty()) {
            log.warn("AliasEditor: updateAliases rejected — unknown actressId={}", actressId);
            return AliasUpdateResult.conflict("Actress not found");
        }
        String actressName = target.get().getCanonicalName();

        List<String> cleaned = (aliases == null ? List.<String>of() : aliases).stream()
                .filter(a -> a != null && !a.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
        log.info("AliasEditor: updateAliases start — actressId={} name=\"{}\" proposed={}",
                actressId, actressName, cleaned);

        for (String alias : cleaned) {
            Optional<Actress> byCanonical = actressRepo.findByCanonicalName(alias);
            if (byCanonical.isPresent() && byCanonical.get().getId() != actressId) {
                log.warn("AliasEditor: conflict (canonical) — actressId={} alias=\"{}\" ownerId={} ownerName=\"{}\"",
                        actressId, alias, byCanonical.get().getId(), byCanonical.get().getCanonicalName());
                return AliasUpdateResult.conflictCanonical(
                        "'" + alias + "' is the canonical name of another actress", byCanonical.get());
            }
            Optional<Actress> byAlias = actressRepo.resolveByName(alias);
            if (byAlias.isPresent() && byAlias.get().getId() != actressId) {
                log.warn("AliasEditor: conflict (alias) — actressId={} alias=\"{}\" ownerId={} ownerName=\"{}\"",
                        actressId, alias, byAlias.get().getId(), byAlias.get().getCanonicalName());
                return AliasUpdateResult.conflictAlias(
                        "'" + alias + "' is already an alias for another actress", byAlias.get());
            }
        }

        actressRepo.replaceAllAliases(actressId, cleaned);
        log.info("AliasEditor: updateAliases committed — actressId={} name=\"{}\" aliasCount={}",
                actressId, actressName, cleaned.size());
        return AliasUpdateResult.success();
    }

    // -------------------------------------------------------------------------
    // Actresses dashboard composition
    // -------------------------------------------------------------------------

    /** Tiers counted as "elite" for spotlight / undiscovered modules. */
    private static final java.util.Set<Actress.Tier> SPOTLIGHT_TIERS =
            java.util.Set.of(Actress.Tier.SUPERSTAR, Actress.Tier.GODDESS);

    /** Tier floor for the Undiscovered Elites module. */
    private static final java.util.Set<Actress.Tier> UNDISCOVERED_TIERS =
            java.util.Set.of(Actress.Tier.POPULAR, Actress.Tier.SUPERSTAR, Actress.Tier.GODDESS);

    /** Visit-count cap below which an elite counts as "undiscovered". */
    private static final int UNDISCOVERED_MAX_VISITS = 2;

    /** Grades counted as top-tier signal for the Forgotten Gems module. */
    private static final java.util.Set<Actress.Grade> FORGOTTEN_GEM_GRADES = java.util.Set.of(
            Actress.Grade.SSS, Actress.Grade.SS, Actress.Grade.S, Actress.Grade.A_PLUS);

    /** Tiers counted as high signal for the Forgotten Gems module — used as a fallback
        when grades are sparse so the panel still has meaningful content. */
    private static final java.util.Set<Actress.Tier> FORGOTTEN_GEM_TIERS = java.util.Set.of(
            Actress.Tier.SUPERSTAR, Actress.Tier.GODDESS);

    /** Days since last visit before a graded/favorite actress is "forgotten". */
    private static final int FORGOTTEN_GEM_STALE_DAYS = 30;

    /** Top Groups leaderboard size. */
    private static final int TOP_GROUPS_N = 10;

    /** One leaderboard row for the Top Groups module. */
    public record TopGroupEntry(String name, String slug, double score, int actressCount) {}

    /** Pass-through DTO for the actress library footer stats. */
    public record ActressLibraryStatsDto(
            long totalActresses,
            long favorites,
            long graded,
            long elites,
            long newThisMonth,
            long researchCovered,
            long researchTotal
    ) {}

    /** One row in the Research Gaps module — actress + 4-bucket completeness flags. */
    public record ResearchGapEntry(
            ActressSummary actress,
            boolean profileFilled,
            boolean physicalFilled,
            boolean biographyFilled,
            boolean portfolioCovered
    ) {}

    /** Full Actresses dashboard payload returned by the API. */
    public record ActressDashboard(
            ActressSummary spotlight,
            List<ActressSummary> birthdaysToday,
            List<ActressSummary> newFaces,
            List<ActressSummary> bookmarks,
            List<ActressSummary> recentlyViewed,
            List<ActressSummary> undiscoveredElites,
            List<ActressSummary> forgottenGems,
            List<TopGroupEntry> topGroups,
            List<ResearchGapEntry> researchGaps,
            ActressLibraryStatsDto libraryStats
    ) {}

    /**
     * Pick a fresh spotlight actress, optionally excluding an id already on screen.
     * Used by the rotating-spotlight endpoint.
     */
    public ActressSummary getSpotlight(Long excludeId) {
        java.util.Set<Long> exclude = new java.util.HashSet<>();
        if (excludeId != null) exclude.add(excludeId);
        Actress picked = pickSpotlight(exclude);
        if (picked == null) return null;
        SummaryContext ctx = buildContext(List.of(picked), "spotlight");
        return toSummary(picked, ctx);
    }

    public ActressDashboard buildDashboard() {
        java.util.Set<Long> excludeIds = new java.util.HashSet<>();

        // 1. Spotlight: 1 weighted-random actress from the taste pool.
        Actress spotlight = pickSpotlight(excludeIds);
        if (spotlight != null) excludeIds.add(spotlight.getId());

        // 2. Birthdays Today — hidden if empty. Not added to excludeIds (a birthday actress
        //    can also legitimately appear in other modules; the date signal is the point).
        java.time.LocalDate today = java.time.LocalDate.now();
        List<Actress> birthdaysToday = actressRepo.findBirthdaysToday(
                today.getMonthValue(), today.getDayOfMonth(), 6);

        // 3. New Faces: 6 cards from last 30 days, fallback to newest overall.
        java.time.LocalDate since30 = today.minusDays(30);
        List<Actress> newFaces = actressRepo.findNewFaces(since30, 6, excludeIds);
        if (newFaces.isEmpty()) {
            newFaces = actressRepo.findNewFacesFallback(6, excludeIds);
        }
        newFaces.forEach(a -> excludeIds.add(a.getId()));

        // 4. Bookmarks: 8 cards ordered by bookmarked_at DESC.
        List<Actress> bookmarks = actressRepo.findBookmarksOrderedByBookmarkedAt(8, excludeIds);
        bookmarks.forEach(a -> excludeIds.add(a.getId()));

        // 5. Recently Viewed: 6 compact cards, dedupe-exempt (history module).
        List<Actress> recentlyViewed = actressRepo.findLastVisited(6);
        // NOT added to excludeIds.

        // 6. Undiscovered Elites: 6 cards — owned but never clicked.
        List<Actress> undiscovered = actressRepo.findUndiscoveredElites(
                UNDISCOVERED_TIERS, UNDISCOVERED_MAX_VISITS, 30, excludeIds);
        // SQL returned random order — trim to 6.
        if (undiscovered.size() > 6) undiscovered = new java.util.ArrayList<>(undiscovered.subList(0, 6));
        undiscovered.forEach(a -> excludeIds.add(a.getId()));

        // 7. Forgotten Gems: 6 cards — high-signal but stale, weighted by staleness.
        java.time.LocalDate staleBefore = today.minusDays(FORGOTTEN_GEM_STALE_DAYS);
        List<Actress> gemPool = actressRepo.findForgottenGemsCandidates(
                FORGOTTEN_GEM_GRADES, FORGOTTEN_GEM_TIERS, staleBefore, 60, excludeIds);
        List<Actress> forgottenGems = weightedSample(gemPool, this::forgottenGemWeight, 6);
        forgottenGems.forEach(a -> excludeIds.add(a.getId()));

        // 8. Top Groups: 10 leaderboard rows derived from per-(actress, label) engagement.
        List<TopGroupEntry> topGroups = computeTopGroups();

        // 9. Research Gaps: 6 row-list entries — bio is the strongest "needs research" signal.
        List<Actress> gapPool = actressRepo.findResearchGapCandidates(SPOTLIGHT_TIERS, 30);
        List<Actress> gapActresses = gapPool.stream().limit(6).toList();

        // Build one shared context for all dashboard actress objects.
        List<Actress> allDashboardActresses = Stream.of(
                        spotlight != null ? List.of(spotlight) : List.<Actress>of(),
                        birthdaysToday, newFaces, bookmarks, recentlyViewed,
                        undiscovered, forgottenGems, gapActresses)
                .flatMap(Collection::stream)
                .distinct()
                .toList();
        SummaryContext ctx = buildContext(allDashboardActresses, "dashboard");

        List<ResearchGapEntry> researchGaps = gapActresses.stream()
                .map(a -> {
                    ActressSummary s = toSummary(a, ctx);
                    return new ResearchGapEntry(
                            s,
                            isProfileFilled(s),
                            isPhysicalFilled(s),
                            isBiographyFilled(s),
                            isPortfolioCovered(s));
                })
                .toList();

        // 10. Library stats footer.
        ActressRepository.ActressLibraryStats stats = actressRepo.computeActressLibraryStats();
        ActressLibraryStatsDto statsDto = new ActressLibraryStatsDto(
                stats.totalActresses(), stats.favorites(), stats.graded(), stats.elites(),
                stats.newThisMonth(), stats.researchCovered(), stats.researchTotal());

        return new ActressDashboard(
                spotlight != null ? toSummary(spotlight, ctx) : null,
                birthdaysToday.stream().map(a -> toSummary(a, ctx)).toList(),
                newFaces.stream().map(a -> toSummary(a, ctx)).toList(),
                bookmarks.stream().map(a -> toSummary(a, ctx)).toList(),
                recentlyViewed.stream().map(a -> toSummary(a, ctx)).toList(),
                undiscovered.stream().map(a -> toSummary(a, ctx)).toList(),
                forgottenGems.stream().map(a -> toSummary(a, ctx)).toList(),
                topGroups,
                researchGaps,
                statsDto);
    }

    // --- Dashboard helpers --------------------------------------------------

    private Actress pickSpotlight(java.util.Set<Long> excludeIds) {
        List<Actress> pool = actressRepo.findSpotlightCandidates(SPOTLIGHT_TIERS, 200, excludeIds);
        if (pool.isEmpty()) return null;
        List<Actress> pick = weightedSample(pool, this::spotlightWeight, 1);
        return pick.isEmpty() ? null : pick.get(0);
    }

    private double spotlightWeight(Actress a) {
        double w = 1.0;
        if (a.isFavorite()) w += 4.0;
        if (a.isBookmark()) w += 2.0;
        if (a.getGrade() != null && FORGOTTEN_GEM_GRADES.contains(a.getGrade())) w += 3.0;
        if (a.getTier() != null && SPOTLIGHT_TIERS.contains(a.getTier())) w += 5.0;
        return w;
    }

    private double forgottenGemWeight(Actress a) {
        long days;
        if (a.getLastVisitedAt() != null) {
            days = java.time.temporal.ChronoUnit.DAYS.between(
                    a.getLastVisitedAt().toLocalDate(), java.time.LocalDate.now());
        } else {
            java.time.LocalDate first = a.getFirstSeenAt();
            days = first != null
                    ? java.time.temporal.ChronoUnit.DAYS.between(first, java.time.LocalDate.now())
                    : 90;
        }
        double w = Math.pow(Math.max(days, 1), 0.6);
        if (a.isFavorite()) w += 2.0;
        if (a.getGrade() != null && FORGOTTEN_GEM_GRADES.contains(a.getGrade())) w += 2.5;
        if (a.getTier() != null && SPOTLIGHT_TIERS.contains(a.getTier())) w += 1.5;
        return w;
    }

    /**
     * Aggregate per-(actress, label) engagement rows into per-group scores using the
     * studios.yaml mapping (label.company → group). Multiple labels owned by the same
     * actress in the same group collapse to a single contribution per (actress, group).
     */
    private List<TopGroupEntry> computeTopGroups() {
        List<ActressRepository.ActressLabelEngagement> rows = actressRepo.findActressLabelEngagements();
        if (rows.isEmpty()) return List.of();

        // Build labelCode → (groupName, groupSlug) map: studios.yaml lists company names per group;
        // each company expands to all label codes whose company matches.
        Map<String, Label> labelMap = labelRepo.findAllAsMap();
        List<StudioGroup> studioGroups = new StudioGroupLoader().load();
        record GroupRef(String name, String slug) {}
        Map<String, GroupRef> labelToGroup = new java.util.HashMap<>();
        for (StudioGroup g : studioGroups) {
            java.util.Set<String> companies = new java.util.HashSet<>(g.companies());
            for (Label lbl : labelMap.values()) {
                if (lbl.company() != null && companies.contains(lbl.company())) {
                    labelToGroup.put(lbl.code().toUpperCase(), new GroupRef(g.name(), g.slug()));
                }
            }
        }
        if (labelToGroup.isEmpty()) return List.of();

        // Collapse per (actress, group): keep the strongest engagement signal.
        record Signal(boolean favorite, boolean bookmark, int visitCount) {}
        Map<String, Map<Long, Signal>> groupActressSignal = new java.util.HashMap<>();
        Map<String, GroupRef> seenGroups = new java.util.HashMap<>();
        for (var row : rows) {
            if (row.labelCode() == null) continue;
            GroupRef ref = labelToGroup.get(row.labelCode().toUpperCase());
            if (ref == null) continue;
            seenGroups.put(ref.slug(), ref);
            Map<Long, Signal> perActress = groupActressSignal.computeIfAbsent(
                    ref.slug(), k -> new java.util.HashMap<>());
            Signal prev = perActress.get(row.actressId());
            Signal next;
            if (prev == null) {
                next = new Signal(row.favorite(), row.bookmark(), row.visitCount());
            } else {
                next = new Signal(
                        prev.favorite() || row.favorite(),
                        prev.bookmark() || row.bookmark(),
                        Math.max(prev.visitCount(), row.visitCount()));
            }
            perActress.put(row.actressId(), next);
        }

        // Score per group = sum of per-actress signal weights.
        record Scored(GroupRef ref, double score, int actressCount) {}
        List<Scored> scored = new java.util.ArrayList<>();
        for (var entry : groupActressSignal.entrySet()) {
            String slug = entry.getKey();
            GroupRef ref = seenGroups.get(slug);
            double total = 0;
            for (Signal s : entry.getValue().values()) {
                double w = 1.0;
                if (s.favorite())     w += 3.0;
                if (s.bookmark())     w += 1.5;
                if (s.visitCount() > 0) w += Math.min(s.visitCount(), 10) * 0.25;
                total += w;
            }
            scored.add(new Scored(ref, total, entry.getValue().size()));
        }
        scored.sort(java.util.Comparator.comparingDouble(Scored::score).reversed());
        if (scored.size() > TOP_GROUPS_N) scored = scored.subList(0, TOP_GROUPS_N);
        return scored.stream()
                .map(s -> new TopGroupEntry(s.ref().name(), s.ref().slug(), s.score(), s.actressCount()))
                .toList();
    }

    private static boolean isProfileFilled(ActressSummary s) {
        return s.getStageName() != null && s.getDateOfBirth() != null && s.getBirthplace() != null;
    }

    private static boolean isPhysicalFilled(ActressSummary s) {
        return s.getHeightCm() != null && s.getBust() != null && s.getWaist() != null && s.getHip() != null;
    }

    private static boolean isBiographyFilled(ActressSummary s) {
        return s.getBiography() != null && !s.getBiography().isBlank();
    }

    private static boolean isPortfolioCovered(ActressSummary s) {
        return s.getTitleCount() > 0;
    }

    /** Efraimidis-Spirakis weighted reservoir sampling: picks {@code n} items without replacement. */
    private static <T> List<T> weightedSample(List<T> items, java.util.function.ToDoubleFunction<T> weight, int n) {
        if (items.isEmpty()) return List.of();
        if (items.size() <= n) return new java.util.ArrayList<>(items);
        java.util.Random rng = new java.util.Random();
        record Keyed<T>(T item, double key) {}
        return items.stream()
                .map(t -> {
                    double w = Math.max(weight.applyAsDouble(t), 0.001);
                    double u = rng.nextDouble();
                    if (u <= 0) u = 1e-9;
                    double key = -Math.log(u) / w;
                    return new Keyed<>(t, key);
                })
                .sorted(java.util.Comparator.comparingDouble(Keyed::key))
                .limit(n)
                .map(Keyed::item)
                .toList();
    }

}
