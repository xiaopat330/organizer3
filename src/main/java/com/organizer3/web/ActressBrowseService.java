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

/**
 * Backing service for the actress browse UI.
 *
 * <p>The prefix index groups actresses by the first letter of their canonical name
 * (which is stored given-name-first, so this is effectively first-name indexing).
 * Letters with more than {@link #SPLIT_THRESHOLD} actresses are expanded to two-character
 * prefixes — only those with at least one match are included.
 */
public class ActressBrowseService {

    static final int SPLIT_THRESHOLD = 20;
    private static final int MAX_COVERS = 8;

    private final ActressRepository actressRepo;
    private final TitleRepository titleRepo;
    private final CoverPath coverPath;
    /** volumeId → smbPath, e.g. "a" → "//pandora/jav_A" */
    private final Map<String, String> volumeSmbPaths;
    private final LabelRepository labelRepo;

    public ActressBrowseService(ActressRepository actressRepo, TitleRepository titleRepo,
                                CoverPath coverPath, Map<String, String> volumeSmbPaths,
                                LabelRepository labelRepo) {
        this.actressRepo = actressRepo;
        this.titleRepo = titleRepo;
        this.coverPath = coverPath;
        this.volumeSmbPaths = volumeSmbPaths;
        this.labelRepo = labelRepo;
    }

    /**
     * Computes the set of browse-index labels derived from the live actress data.
     * Returns a sorted list like {@code ["A", "B", "MA", "MI", "MO", "N", ...]}.
     */
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
     */
    public List<TitleSummary> findTitlesByActress(long actressId, int offset, int limit) {
        List<Title> titles = titleRepo.findByActressPaged(actressId, limit, offset);
        Map<String, Label> labelMap = labelRepo.findAllAsMap();
        String actressName = actressRepo.findById(actressId).map(a -> a.getCanonicalName()).orElse(null);
        return titles.stream()
                .map(t -> {
                    Label lbl = t.label() != null ? labelMap.get(t.label().toUpperCase()) : null;
                    return new TitleSummary(
                            t.code(), t.baseCode(), t.label(),
                            actressId, actressName,
                            t.addedDate() != null ? t.addedDate().toString() : null,
                            coverPath.find(t)
                                    .map(p -> "/covers/" + t.label().toUpperCase() + "/" + p.getFileName())
                                    .orElse(null),
                            lbl != null ? lbl.company() : null,
                            lbl != null ? lbl.labelName() : null
                    );
                })
                .toList();
    }

    private ActressSummary toSummary(com.organizer3.model.Actress actress) {
        List<Title> titles = titleRepo.findByActress(actress.getId());

        List<String> coverUrls = titles.stream()
                .map(t -> coverPath.find(t)
                        .map(p -> "/covers/" + t.label().toUpperCase() + "/" + p.getFileName())
                        .orElse(null))
                .filter(Objects::nonNull)
                .limit(MAX_COVERS)
                .toList();

        List<String> folderPaths = titles.stream()
                .filter(t -> t.partitionId() != null && t.partitionId().startsWith("stars"))
                .map(t -> {
                    Path actressFolder = t.path().getParent();
                    if (actressFolder == null) return null;
                    String smbBase = volumeSmbPaths.get(t.volumeId());
                    if (smbBase == null) return null;
                    return smbBase + actressFolder;
                })
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        String firstAdded = actress.getFirstSeenAt() != null
                ? actress.getFirstSeenAt().toString() : null;
        String lastAdded = titles.stream()
                .map(Title::addedDate)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .map(Object::toString)
                .orElse(null);

        String gradeDisplay = actress.getGrade() != null ? actress.getGrade().display : null;

        return new ActressSummary(
                actress.getId(), actress.getCanonicalName(),
                actress.getTier().name(), actress.isFavorite(),
                actress.isBookmark(), gradeDisplay, actress.isRejected(),
                titles.size(), coverUrls, folderPaths, firstAdded, lastAdded
        );
    }

    private static String twoCharPrefix(String name) {
        String upper = name.toUpperCase();
        return upper.length() >= 2 ? upper.substring(0, 2) : upper;
    }
}
