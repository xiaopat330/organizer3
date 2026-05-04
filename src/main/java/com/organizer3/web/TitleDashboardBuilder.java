package com.organizer3.web;

import com.organizer3.model.Label;
import com.organizer3.model.Title;
import com.organizer3.repository.ActressRepository;
import com.organizer3.repository.LabelRepository;
import com.organizer3.repository.TitleRepository;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Assembles the Titles dashboard payload.
 *
 * <p>Uses the package-private {@link TitleBrowseService#toSummaries(List)} helper
 * to produce summary DTOs without duplicating summary-assembly logic.
 */
public class TitleDashboardBuilder {

    // ----- constants -------------------------------------------------------

    /** Tier names counted as "superstar or above" for the Spotlight module. */
    static final Set<String> SUPERSTAR_TIERS = Set.of("SUPERSTAR", "GODDESS");

    /** How many loved labels to derive from computeLabelScores(). */
    static final int LOVED_LABEL_N = 15;

    // ----- records ---------------------------------------------------------

    /** Leaderboard: Top N labels with raw score (no randomness applied). */
    public record TopLabelEntry(String code, String labelName, String company, double score) {}

    /** Scalar library stats for the footer strip. */
    public record LibraryStatsDto(long totalTitles, long totalLabels, long unseen, long addedThisMonth, long addedThisYear) {}

    /** The entire Titles dashboard payload returned by the API. */
    public record TitleDashboard(
            List<TitleSummary> onDeck,
            List<TitleSummary> justAdded,
            List<TitleSummary> fromFavoriteLabels,
            List<TitleSummary> recentlyViewed,
            TitleSummary spotlight,
            List<TitleSummary> forgottenAttic,
            List<TitleSummary> forgottenFavorites,
            List<TitleSummary> onThisDay,
            List<TopLabelEntry> topLabels,
            LibraryStatsDto libraryStats
    ) {}

    // ----- fields ----------------------------------------------------------

    private final TitleRepository titleRepo;
    private final ActressRepository actressRepo;
    private final LabelRepository labelRepo;
    private final TitleBrowseService service;

    TitleDashboardBuilder(TitleRepository titleRepo, ActressRepository actressRepo,
                          LabelRepository labelRepo, TitleBrowseService service) {
        this.titleRepo = titleRepo;
        this.actressRepo = actressRepo;
        this.labelRepo = labelRepo;
        this.service = service;
    }

    // ----- public API ------------------------------------------------------

    /**
     * Pick a fresh spotlight title, optionally excluding a code already on screen.
     * Used by the rotating-spotlight endpoint.
     */
    TitleSummary getSpotlight(String excludeCode) {
        List<TitleRepository.LabelScore> labelScores = titleRepo.computeLabelScores(LOVED_LABEL_N);
        Set<String> lovedLabels = labelScores.stream()
                .map(TitleRepository.LabelScore::code)
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        Set<Long> lovedActressIds = actressRepo.findFavorites().stream()
                .map(com.organizer3.model.Actress::getId)
                .collect(Collectors.toCollection(java.util.HashSet::new));
        Set<String> excludeCodes = new java.util.HashSet<>();
        if (excludeCode != null && !excludeCode.isBlank()) excludeCodes.add(excludeCode.trim().toUpperCase());
        Title t = pickSpotlight(lovedLabels, lovedActressIds, excludeCodes);
        return t == null ? null : service.toSummaries(List.of(t)).get(0);
    }

    TitleDashboard buildDashboard() {
        // 1. Derive "loved" sets.
        List<TitleRepository.LabelScore> labelScores = titleRepo.computeLabelScores(LOVED_LABEL_N);
        Set<String> lovedLabels = labelScores.stream()
                .map(TitleRepository.LabelScore::code)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<Long> lovedActressIds = actressRepo.findFavorites().stream()
                .map(com.organizer3.model.Actress::getId)
                .collect(Collectors.toCollection(java.util.HashSet::new));

        Set<String> excludeCodes = new java.util.HashSet<>();

        // 2. On Deck (hero): 8 bookmarks, uniform shuffle, soft unseen-first (up to 4).
        List<Title> allBookmarks = titleRepo.findBookmarksPaged(200, 0);
        List<Title> onDeckTitles = pickUniformSoftUnseen(allBookmarks, 8, 4);
        onDeckTitles.forEach(t -> excludeCodes.add(t.getCode()));

        // 3. Just Added: 8 cards, last 30 days, unseen-first soft guarantee (up to 5 of 8).
        LocalDate since30 = LocalDate.now().minusDays(30);
        List<Title> justAddedPool = titleRepo.findAddedSince(since30, 60, excludeCodes);
        List<Title> justAdded = pickByAddedDateSoftUnseen(justAddedPool, 8, 5);
        if (justAdded.isEmpty()) {
            justAdded = titleRepo.findAddedSince(LocalDate.now().minusYears(5), 8, excludeCodes);
        }
        justAdded.forEach(t -> excludeCodes.add(t.getCode()));

        // 4. From Favorite Labels: 6 cards, last 90 days, loved labels only.
        List<Title> fromFavLabels;
        if (lovedLabels.isEmpty()) {
            fromFavLabels = List.of();
        } else {
            LocalDate since90 = LocalDate.now().minusDays(90);
            List<Title> pool = titleRepo.findAddedSinceByLabels(since90, lovedLabels, 60, excludeCodes);
            fromFavLabels = pickByAddedDateSoftUnseen(pool, 6, 4);
            if (fromFavLabels.isEmpty()) {
                fromFavLabels = titleRepo.findAddedSinceByLabels(
                        LocalDate.now().minusYears(5), lovedLabels, 6, excludeCodes);
            }
        }
        fromFavLabels.forEach(t -> excludeCodes.add(t.getCode()));

        // 5. Recently Viewed: 6 compact cards, dedupe-exempt.
        List<Title> recentlyViewed = titleRepo.findLastVisited(6);

        // 6. Spotlight: 1 big card, weighted random from taste pool.
        Title spotlightTitle = pickSpotlight(lovedLabels, lovedActressIds, excludeCodes);
        if (spotlightTitle != null) excludeCodes.add(spotlightTitle.getCode());

        // 7. Forgotten Attic: 6 cards, weighted by age.
        List<Title> atticPool = titleRepo.findForgottenAtticCandidates(300, excludeCodes);
        List<Title> forgottenAttic = weightedSample(atticPool, this::forgottenAtticWeight, 6);
        forgottenAttic.forEach(t -> excludeCodes.add(t.getCode()));

        // 8. Forgotten Favorites: 6 cards, weighted by staleness.
        List<Title> ffPool = titleRepo.findForgottenFavoritesCandidates(200, excludeCodes);
        List<Title> forgottenFavorites = weightedSample(
                ffPool,
                t -> forgottenFavoritesWeight(t, lovedLabels, lovedActressIds),
                6);
        forgottenFavorites.forEach(t -> excludeCodes.add(t.getCode()));

        // 9. On This Day: 5 cards, dedupe-exempt.
        LocalDate today = LocalDate.now();
        List<Title> onThisDay = titleRepo.findAnniversary(today.getMonthValue(), today.getDayOfMonth(), 5);

        // 10. Top Labels leaderboard: 15 rows, weighted randomness applied to score ordering.
        List<TitleRepository.LabelScore> leaderboardPool = titleRepo.computeLabelScores(50);
        List<TitleRepository.LabelScore> leaderboardShuffled = weightedSample(
                leaderboardPool, TitleRepository.LabelScore::score, 15);
        Map<String, Label> labelMap = labelRepo.findAllAsMap();
        List<TopLabelEntry> topLabels = leaderboardShuffled.stream()
                .map(ls -> {
                    Label lbl = labelMap.get(ls.code().toUpperCase());
                    return new TopLabelEntry(
                            ls.code(),
                            lbl != null ? lbl.labelName() : ls.code(),
                            lbl != null ? lbl.company() : null,
                            ls.score());
                })
                .toList();

        // 11. Library stats.
        TitleRepository.LibraryStats stats = titleRepo.computeLibraryStats();
        LibraryStatsDto statsDto = new LibraryStatsDto(
                stats.totalTitles(), stats.totalLabels(), stats.unseen(),
                stats.addedThisMonth(), stats.addedThisYear());

        return new TitleDashboard(
                service.toSummaries(onDeckTitles),
                service.toSummaries(justAdded),
                service.toSummaries(fromFavLabels),
                service.toSummaries(recentlyViewed),
                spotlightTitle != null ? service.toSummaries(List.of(spotlightTitle)).get(0) : null,
                service.toSummaries(forgottenAttic),
                service.toSummaries(forgottenFavorites),
                service.toSummaries(onThisDay),
                topLabels,
                statsDto);
    }

    // ----- private helpers -------------------------------------------------

    /**
     * Pick a single spotlight title, excluding any codes in {@code excludeCodes}.
     */
    private Title pickSpotlight(Set<String> lovedLabels, Set<Long> lovedActressIds,
                                Set<String> excludeCodes) {
        List<Title> pool = titleRepo.findSpotlightCandidates(
                lovedLabels, lovedActressIds, SUPERSTAR_TIERS, 200, excludeCodes);
        if (pool.isEmpty()) return null;
        List<Title> pick = weightedSample(pool, t -> spotlightWeight(t, lovedLabels, lovedActressIds), 1);
        return pick.isEmpty() ? null : pick.get(0);
    }

    /** Shuffle pool uniformly, then pull up to {@code targetUnseen} unseen titles first. */
    static List<Title> pickUniformSoftUnseen(List<Title> pool, int n, int targetUnseen) {
        if (pool.isEmpty()) return List.of();
        ArrayList<Title> shuffled = new ArrayList<>(pool);
        Collections.shuffle(shuffled);
        return applySoftUnseen(shuffled, n, targetUnseen);
    }

    /**
     * Take titles from {@code sortedPool} (already in preferred order, e.g. by addedDate DESC),
     * enforcing a soft unseen-first guarantee.
     */
    static List<Title> pickByAddedDateSoftUnseen(List<Title> sortedPool, int n, int targetUnseen) {
        return applySoftUnseen(sortedPool, n, targetUnseen);
    }

    static List<Title> applySoftUnseen(List<Title> ordered, int n, int targetUnseen) {
        if (ordered.isEmpty()) return List.of();
        List<Title> unseen = ordered.stream().filter(t -> t.getVisitCount() == 0).toList();
        int unseenPicks = Math.min(targetUnseen, Math.min(unseen.size(), n));
        LinkedHashSet<Title> result = new LinkedHashSet<>(unseen.subList(0, unseenPicks));
        for (Title t : ordered) {
            if (result.size() >= n) break;
            result.add(t);
        }
        return new ArrayList<>(result);
    }

    /** Efraimidis-Spirakis weighted reservoir sampling: picks {@code n} items without replacement. */
    static <T> List<T> weightedSample(List<T> items, java.util.function.ToDoubleFunction<T> weight, int n) {
        if (items.isEmpty()) return List.of();
        if (items.size() <= n) return new ArrayList<>(items);
        Random rng = new Random();
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

    private double spotlightWeight(Title t, Set<String> lovedLabels, Set<Long> lovedActresses) {
        double w = 1.0;
        if (t.isFavorite()) w += 3.0;
        if (t.isBookmark()) w += 2.0;
        if (t.getActressId() != null && lovedActresses.contains(t.getActressId())) w += 4.0;
        if (t.getLabel() != null && lovedLabels.contains(t.getLabel().toUpperCase())) w += 2.0;
        if (t.getActressId() != null) {
            var a = actressRepo.findById(t.getActressId()).orElse(null);
            if (a != null && a.getTier() != null && SUPERSTAR_TIERS.contains(a.getTier().name())) {
                w += 6.0;
            }
        }
        return w;
    }

    private double forgottenAtticWeight(Title t) {
        LocalDate added = t.getAddedDate();
        if (added == null) return 1.0;
        long days = ChronoUnit.DAYS.between(added, LocalDate.now());
        return Math.max(Math.sqrt(days), 1.0);
    }

    private double forgottenFavoritesWeight(Title t, Set<String> lovedLabels, Set<Long> lovedActresses) {
        long days;
        if (t.getLastVisitedAt() != null) {
            days = ChronoUnit.DAYS.between(t.getLastVisitedAt().toLocalDate(), LocalDate.now());
        } else {
            LocalDate added = t.getAddedDate();
            days = added != null ? ChronoUnit.DAYS.between(added, LocalDate.now()) : 30;
        }
        double w = Math.pow(Math.max(days, 1), 0.6);
        if (t.getActressId() != null && lovedActresses.contains(t.getActressId())) w += 2.0;
        if (t.getLabel() != null && lovedLabels.contains(t.getLabel().toUpperCase())) w += 1.5;
        if (t.getActressId() != null) {
            var a = actressRepo.findById(t.getActressId()).orElse(null);
            if (a != null && a.getTier() != null && SUPERSTAR_TIERS.contains(a.getTier().name())) {
                w += 3.0;
            }
        }
        return w;
    }
}
