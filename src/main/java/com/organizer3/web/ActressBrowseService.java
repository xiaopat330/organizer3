package com.organizer3.web;

import com.organizer3.covers.CoverPath;
import com.organizer3.model.Label;
import com.organizer3.model.Title;
import com.organizer3.repository.ActressRepository;
import com.organizer3.repository.LabelRepository;
import com.organizer3.repository.TitleRepository;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;

/**
 * Backing service for the actress browse UI.
 *
 * <p>The prefix index groups actresses by the first letter of their canonical name
 * (which is stored given-name-first, so this is effectively first-name indexing).
 * Letters with more than {@link #SPLIT_THRESHOLD} actresses are expanded to two-character
 * prefixes — only those with at least one match are included.
 */
@RequiredArgsConstructor
public class ActressBrowseService {

    static final int SPLIT_THRESHOLD = 20;
    private static final int MAX_COVERS = 8;

    private final ActressRepository actressRepo;
    private final TitleRepository titleRepo;
    private final CoverPath coverPath;
    /** volumeId → smbPath, e.g. "a" → "//pandora/jav_A" */
    private final Map<String, String> volumeSmbPaths;
    private final LabelRepository labelRepo;

    public List<String> findPrefixIndex() {
        List<com.organizer3.model.Actress> all = actressRepo.findAll();

        Map<Character, Long> letterCounts = all.stream()
                .collect(Collectors.groupingBy(
                        a -> Character.toUpperCase(a.getCanonicalName().charAt(0)),
                        Collectors.counting()
                ));

        List<String> result = new ArrayList<>();

        new TreeMap<>(letterCounts).forEach((letter, count) -> {
            if (count <= SPLIT_THRESHOLD) {
                result.add(String.valueOf(letter));
            } else {
                Map<String, Long> twoCounts = all.stream()
                        .filter(a -> Character.toUpperCase(a.getCanonicalName().charAt(0)) == letter)
                        .collect(Collectors.groupingBy(
                                a -> twoCharPrefix(a.getCanonicalName()),
                                Collectors.counting()
                        ));
                new TreeMap<>(twoCounts).keySet().forEach(result::add);
            }
        });

        return result;
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
     * Returns all actresses at the given tier level, enriched with title count, cover URLs,
     * and SMB folder paths.
     *
     * @throws IllegalArgumentException if {@code tierName} does not match a known {@link com.organizer3.model.Actress.Tier}
     */
    public List<ActressSummary> findByTier(String tierName) {
        com.organizer3.model.Actress.Tier tier = com.organizer3.model.Actress.Tier.valueOf(tierName.toUpperCase());
        return actressRepo.findByTier(tier).stream()
                .map(this::toSummary)
                .toList();
    }

    /**
     * Returns the full {@link ActressSummary} for a single actress by ID, or empty if not found.
     */
    public Optional<ActressSummary> findById(long id) {
        return actressRepo.findById(id).map(this::toSummary);
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

        com.organizer3.model.Actress actress = actressRepo.findById(actressId).orElse(null);
        String actressName = actress != null ? actress.getCanonicalName() : null;
        String actressTier = actress != null && actress.getTier() != null ? actress.getTier().name() : null;
        return titles.stream()
                .map(t -> {
                    Label lbl = t.getLabel() != null ? labelMap.get(t.getLabel().toUpperCase()) : null;
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
                            .build();
                })
                .toList();
    }

    private ActressSummary toSummary(com.organizer3.model.Actress actress) {
        List<Title> titles = titleRepo.findByActress(actress.getId());

        List<String> coverUrls = titles.stream()
                .map(t -> coverPath.find(t)
                        .map(p -> "/covers/" + t.getLabel().toUpperCase() + "/" + p.getFileName())
                        .orElse(null))
                .filter(Objects::nonNull)
                .limit(MAX_COVERS)
                .toList();

        List<String> folderPaths = titles.stream()
                .filter(t -> t.getPartitionId() != null && t.getPartitionId().startsWith("stars"))
                .map(t -> {
                    Path actressFolder = t.getPath().getParent();
                    if (actressFolder == null) return null;
                    String smbBase = volumeSmbPaths.get(t.getVolumeId());
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
                .map(com.organizer3.model.ActressAlias::aliasName)
                .sorted()
                .toList();

        return ActressSummary.builder()
                .id(actress.getId())
                .canonicalName(actress.getCanonicalName())
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
                .build();
    }

    private static String twoCharPrefix(String name) {
        String upper = name.toUpperCase();
        return upper.length() >= 2 ? upper.substring(0, 2) : upper;
    }
}
