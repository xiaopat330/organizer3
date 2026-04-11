package com.organizer3.web;

import com.organizer3.ai.ActressNameLookup;
import com.organizer3.covers.CoverPath;
import com.organizer3.model.Actress;
import com.organizer3.model.ActressAlias;
import com.organizer3.model.Label;
import com.organizer3.model.StudioGroup;
import com.organizer3.model.Title;
import com.organizer3.model.TitleLocation;
import com.organizer3.repository.ActressRepository;
import com.organizer3.repository.LabelRepository;
import com.organizer3.repository.TitleRepository;

import java.nio.file.Path;
import java.util.Comparator;
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

    private static final int MAX_COVERS = 24;

    private final ActressRepository actressRepo;
    private final TitleRepository titleRepo;
    private final CoverPath coverPath;
    /** volumeId → smbPath, e.g. "a" → "//pandora/jav_A" */
    private final Map<String, String> volumeSmbPaths;
    private final LabelRepository labelRepo;
    private final ActressNameLookup nameLookup;
    /** Nullable — backup is skipped if not configured. */
    private final StageNameBackupFile backupFile;

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
        return actressRepo.findRandom(limit).stream()
                .map(this::toSummary)
                .toList();
    }

    /**
     * Returns all actresses whose canonical name starts with {@code prefix}, enriched with
     * title count, cover image URLs, and SMB folder paths.
     */
    public List<ActressSummary> findByPrefix(String prefix) {
        return actressRepo.findByFirstNamePrefix(prefix).stream()
                .map(this::toSummary)
                .toList();
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
        return actressRepo.findByFirstNamePrefixPaged(letter, tier, limit, offset).stream()
                .map(this::toSummary)
                .toList();
    }

    /**
     * Returns all actresses that have at least one title on any of the given volumes,
     * enriched with title count, cover URLs, and SMB folder paths.
     */
    public List<ActressSummary> findByVolumes(List<String> volumeIds) {
        return actressRepo.findByVolumeIds(volumeIds).stream()
                .map(this::toSummary)
                .toList();
    }

    /**
     * Paginated version: returns actresses on the given volumes, favorites/bookmarks first.
     */
    public List<ActressSummary> findByVolumesPaged(List<String> volumeIds, int offset, int limit) {
        return actressRepo.findByVolumeIdsPaged(volumeIds, limit, offset).stream()
                .map(this::toSummary)
                .toList();
    }

    /**
     * Paginated version filtered by company: returns actresses on the given volumes who also
     * have ≥1 title produced by {@code company}. Favorites/bookmarks sort first.
     */
    public List<ActressSummary> findByVolumesPaged(List<String> volumeIds, String company,
                                                   int offset, int limit) {
        if (company != null && !company.isBlank()) {
            return actressRepo.findByVolumesAndCompaniesPaged(volumeIds, List.of(company), limit, offset)
                    .stream().map(this::toSummary).toList();
        }
        return findByVolumesPaged(volumeIds, offset, limit);
    }

    /**
     * Returns all actresses at the given tier level, enriched with title count, cover URLs,
     * and SMB folder paths.
     *
     * @throws IllegalArgumentException if {@code tierName} does not match a known {@link Actress.Tier}
     */
    public List<ActressSummary> findByTier(String tierName) {
        Actress.Tier tier = Actress.Tier.valueOf(tierName.toUpperCase());
        return actressRepo.findByTier(tier).stream()
                .map(this::toSummary)
                .toList();
    }

    /** Paginated tier query. */
    public List<ActressSummary> findByTierPaged(String tierName, int offset, int limit) {
        Actress.Tier tier = Actress.Tier.valueOf(tierName.toUpperCase());
        return actressRepo.findByTierPaged(tier, limit, offset).stream()
                .map(this::toSummary)
                .toList();
    }

    /** Paginated tier query with optional company filter. */
    public List<ActressSummary> findByTierPaged(String tierName, String company, int offset, int limit) {
        if (company != null && !company.isBlank()) {
            Actress.Tier tier = Actress.Tier.valueOf(tierName.toUpperCase());
            return actressRepo.findByTierAndCompaniesPaged(tier, List.of(company), limit, offset).stream()
                    .map(this::toSummary)
                    .toList();
        }
        return findByTierPaged(tierName, offset, limit);
    }

    /** Paginated all-actresses query. */
    public List<ActressSummary> findAllPaged(int offset, int limit) {
        return actressRepo.findAllPaged(limit, offset).stream()
                .map(this::toSummary)
                .toList();
    }

    /** Paginated favorites query. */
    public List<ActressSummary> findFavoritesPaged(int offset, int limit) {
        return actressRepo.findFavoritesPaged(limit, offset).stream()
                .map(this::toSummary)
                .toList();
    }

    /** Returns up to {@code limit} most recently visited actresses, newest visit first. */
    public List<ActressSummary> findLastVisited(int limit) {
        return actressRepo.findLastVisited(limit).stream()
                .map(this::toSummary)
                .toList();
    }

    /** Returns up to {@code limit} most-visited actresses, highest visit count first. */
    public List<ActressSummary> findMostVisited(int limit) {
        return actressRepo.findMostVisited(limit).stream()
                .map(this::toSummary)
                .toList();
    }

    /** Paginated bookmarks query. */
    public List<ActressSummary> findBookmarksPaged(int offset, int limit) {
        return actressRepo.findBookmarksPaged(limit, offset).stream()
                .map(this::toSummary)
                .toList();
    }

    /**
     * Paginated query: actresses owning ≥1 title under any company belonging to the given
     * studios.yaml group slug. Returns an empty list if the slug is unknown. Sorted by
     * tier rank (GODDESS first), then canonical name.
     */
    public List<ActressSummary> findByStudioGroupPaged(String groupSlug, int offset, int limit) {
        return findByStudioGroupPaged(groupSlug, null, offset, limit);
    }

    /**
     * Same as {@link #findByStudioGroupPaged(String, int, int)} but optionally narrowed to a
     * single company within the group. If {@code companyFilter} is non-blank, the result is
     * restricted to actresses with ≥1 title under that company — but only if the company is
     * actually a member of the group (otherwise an empty list is returned, never an
     * unconstrained query).
     */
    public List<ActressSummary> findByStudioGroupPaged(String groupSlug, String companyFilter,
                                                       int offset, int limit) {
        List<String> companies = resolveQueryCompanies(groupSlug, companyFilter);
        if (companies.isEmpty()) return List.of();
        return actressRepo.findByStudioGroupCompaniesPaged(companies, limit, offset).stream()
                .map(this::toSummary)
                .toList();
    }

    /** Total count of actresses matching {@link #findByStudioGroupPaged}. */
    public long countByStudioGroup(String groupSlug) {
        return countByStudioGroup(groupSlug, null);
    }

    /** Total count narrowed by an optional company filter (validated against the group). */
    public long countByStudioGroup(String groupSlug, String companyFilter) {
        List<String> companies = resolveQueryCompanies(groupSlug, companyFilter);
        if (companies.isEmpty()) return 0L;
        return actressRepo.countByStudioGroupCompanies(companies);
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
        return actressRepo.searchByNamePrefixPaged(query, limit, offset).stream()
                .map(this::toSummary)
                .toList();
    }

    /**
     * Returns the full {@link ActressSummary} for a single actress by ID, or empty if not found.
     */
    public Optional<ActressSummary> findById(long id) {
        return actressRepo.findById(id).map(this::toSummary);
    }

    /** Fetch full summaries for a batch of actress IDs, preserving the given order. */
    public List<ActressSummary> findByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        Map<Long, ActressSummary> byId = actressRepo.findByIds(ids).stream()
                .collect(java.util.stream.Collectors.toMap(
                        a -> a.getId(),
                        this::toSummary));
        return ids.stream().map(byId::get).filter(java.util.Objects::nonNull).toList();
    }

    /**
     * Returns all tags (direct and label-derived) that appear across any of the given actress's
     * titles, sorted alphabetically. Used to populate the actress detail page tag filter.
     */
    public List<String> findTagsForActress(long actressId) {
        Map<String, Label> labelMap = labelRepo.findAllAsMap();
        return titleRepo.findByActress(actressId).stream()
                .flatMap(t -> {
                    List<String> direct = t.getTags() != null ? t.getTags() : List.of();
                    Label lbl = t.getLabel() != null ? labelMap.get(t.getLabel().toUpperCase()) : null;
                    List<String> labelTags = lbl != null ? lbl.tags() : List.of();
                    return Stream.concat(direct.stream(), labelTags.stream());
                })
                .distinct()
                .sorted()
                .toList();
    }

    /**
     * Returns a paginated list of title summaries for the given actress, ordered newest-first.
     * If {@code company} is non-null, only titles whose label belongs to that company are returned.
     * If {@code tags} is non-empty, only titles carrying all of those tags (direct or label-derived)
     * are returned. The two filters are combined with AND.
     */
    public List<TitleSummary> findTitlesByActress(long actressId, int offset, int limit, String company, List<String> tags) {
        Map<String, Label> labelMap = labelRepo.findAllAsMap();

        List<String> matchingLabels = List.of();
        if (company != null && !company.isBlank()) {
            matchingLabels = labelMap.values().stream()
                    .filter(l -> company.equals(l.company()))
                    .map(l -> l.code().toUpperCase())
                    .toList();
        }

        List<Title> titles;
        boolean hasTags   = tags != null && !tags.isEmpty();
        boolean hasLabels = !matchingLabels.isEmpty();

        if (hasTags) {
            // new path: use the combined filter (handles optional labels too)
            if (company != null && !company.isBlank() && matchingLabels.isEmpty()) {
                titles = List.of(); // company specified but no known labels → no results
            } else {
                titles = titleRepo.findByActressTagsFiltered(actressId, matchingLabels, tags, limit, offset);
            }
        } else if (hasLabels) {
            titles = titleRepo.findByActressAndLabelsPaged(actressId, matchingLabels, limit, offset);
        } else {
            titles = titleRepo.findByActressPaged(actressId, limit, offset);
        }

        Actress actress = actressRepo.findById(actressId).orElse(null);
        String actressName = actress != null ? actress.getCanonicalName() : null;
        String actressTier = actress != null && actress.getTier() != null ? actress.getTier().name() : null;
        return titles.stream()
                .map(t -> {
                    Label lbl = t.getLabel() != null ? labelMap.get(t.getLabel().toUpperCase()) : null;
                    List<String> allLocations = t.getLocations().stream()
                            .map(loc -> loc.getPath().toString())
                            .toList();
                    List<String> nasPaths = t.getLocations().stream()
                            .filter(loc -> loc.getVolumeId() != null && volumeSmbPaths.containsKey(loc.getVolumeId()))
                            .map(loc -> volumeSmbPaths.get(loc.getVolumeId()) + "/" + loc.getPath())
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
                            .visitCount(t.getVisitCount())
                            .lastVisitedAt(t.getLastVisitedAt() != null ? t.getLastVisitedAt().toString() : null)
                            .build();
                })
                .toList();
    }

    private ActressSummary toSummary(Actress actress) {
        List<Title> titles = titleRepo.findByActress(actress.getId());

        List<String> coverUrls = titles.stream()
                .map(t -> coverPath.find(t)
                        .map(p -> "/covers/" + t.getLabel().toUpperCase() + "/" + p.getFileName())
                        .orElse(null))
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

        Map<String, Label> labelMap = labelRepo.findAllAsMap();
        List<String> companies = titles.stream()
                .filter(t -> t.getLabel() != null)
                .map(t -> {
                    Label lbl = labelMap.get(t.getLabel().toUpperCase());
                    return lbl != null ? lbl.company() : null;
                })
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();

        List<ActressSummary.AliasDto> aliases = actressRepo.findAliases(actress.getId()).stream()
                .map(ActressAlias::aliasName)
                .sorted()
                .map(name -> ActressSummary.AliasDto.builder()
                        .name(name)
                        .actressId(actressRepo.findByCanonicalName(name)
                                .map(Actress::getId)
                                .orElse(null))
                        .build())
                .toList();

        Actress primaryActress = actressRepo.findPrimaryForAlias(actress.getCanonicalName()).orElse(null);

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
        return picked == null ? null : toSummary(picked);
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
        List<ResearchGapEntry> researchGaps = gapPool.stream()
                .limit(6)
                .map(a -> {
                    ActressSummary s = toSummary(a);
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
                spotlight != null ? toSummary(spotlight) : null,
                birthdaysToday.stream().map(this::toSummary).toList(),
                newFaces.stream().map(this::toSummary).toList(),
                bookmarks.stream().map(this::toSummary).toList(),
                recentlyViewed.stream().map(this::toSummary).toList(),
                undiscovered.stream().map(this::toSummary).toList(),
                forgottenGems.stream().map(this::toSummary).toList(),
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
