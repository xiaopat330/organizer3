package com.organizer3.web;

import com.organizer3.ai.ActressNameLookup;
import com.organizer3.covers.CoverPath;
import com.organizer3.model.Actress;
import com.organizer3.model.ActressAlias;
import com.organizer3.model.Label;
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
     * Paginated version: returns actresses on the given volumes, ordered by canonical name.
     */
    public List<ActressSummary> findByVolumesPaged(List<String> volumeIds, int offset, int limit) {
        return actressRepo.findByVolumeIdsPaged(volumeIds, limit, offset).stream()
                .map(this::toSummary)
                .toList();
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

    /** Paginated bookmarks query. */
    public List<ActressSummary> findBookmarksPaged(int offset, int limit) {
        return actressRepo.findBookmarksPaged(limit, offset).stream()
                .map(this::toSummary)
                .toList();
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
     * Returns a paginated list of title summaries for the given actress, ordered newest-first.
     * If {@code company} is non-null, only titles whose label belongs to that company are returned.
     */
    public List<TitleSummary> findTitlesByActress(long actressId, int offset, int limit, String company) {
        Map<String, Label> labelMap = labelRepo.findAllAsMap();

        List<Title> titles;
        if (company != null && !company.isBlank()) {
            List<String> matchingLabels = labelMap.values().stream()
                    .filter(l -> company.equals(l.company()))
                    .map(l -> l.code().toUpperCase())
                    .toList();
            titles = matchingLabels.isEmpty()
                    ? List.of()
                    : titleRepo.findByActressAndLabelsPaged(actressId, matchingLabels, limit, offset);
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

        List<String> aliases = actressRepo.findAliases(actress.getId()).stream()
                .map(ActressAlias::aliasName)
                .sorted()
                .toList();

        String earliestTitleDate = titles.stream()
                .map(Title::getAddedDate)
                .filter(Objects::nonNull)
                .min(Comparator.naturalOrder())
                .map(Object::toString)
                .orElse(null);

        return ActressSummary.builder()
                .id(actress.getId())
                .canonicalName(actress.getCanonicalName())
                .stageName(actress.getStageName())
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
                .activeFrom(actress.getActiveFrom() != null ? actress.getActiveFrom().toString() : earliestTitleDate)
                .activeTo(actress.getActiveTo() != null ? actress.getActiveTo().toString() : lastAdded)
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

}
