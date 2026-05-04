package com.organizer3.web;

import com.organizer3.model.Actress;
import com.organizer3.model.Label;
import com.organizer3.model.StudioGroup;
import com.organizer3.repository.ActressRepository;
import com.organizer3.repository.LabelRepository;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Assembles the Actresses dashboard payload.
 *
 * <p>Uses package-private helpers on {@link ActressBrowseService} ({@code buildContext},
 * {@code toSummary}, {@code SummaryContext}) to produce summary DTOs without
 * duplicating the summary-assembly logic.
 */
public class ActressDashboardBuilder {

    // ----- constants -------------------------------------------------------

    /** Tiers counted as "elite" for spotlight / undiscovered modules. */
    static final Set<Actress.Tier> SPOTLIGHT_TIERS =
            Set.of(Actress.Tier.SUPERSTAR, Actress.Tier.GODDESS);

    /** Tier floor for the Undiscovered Elites module. */
    static final Set<Actress.Tier> UNDISCOVERED_TIERS =
            Set.of(Actress.Tier.POPULAR, Actress.Tier.SUPERSTAR, Actress.Tier.GODDESS);

    /** Visit-count cap below which an elite counts as "undiscovered". */
    static final int UNDISCOVERED_MAX_VISITS = 2;

    /** Grades counted as top-tier signal for the Forgotten Gems module. */
    static final Set<Actress.Grade> FORGOTTEN_GEM_GRADES = Set.of(
            Actress.Grade.SSS, Actress.Grade.SS, Actress.Grade.S, Actress.Grade.A_PLUS);

    /** Tiers counted as high signal for the Forgotten Gems module — used as a fallback
        when grades are sparse so the panel still has meaningful content. */
    static final Set<Actress.Tier> FORGOTTEN_GEM_TIERS = Set.of(
            Actress.Tier.SUPERSTAR, Actress.Tier.GODDESS);

    /** Days since last visit before a graded/favorite actress is "forgotten". */
    static final int FORGOTTEN_GEM_STALE_DAYS = 30;

    /** Top Groups leaderboard size. */
    static final int TOP_GROUPS_N = 10;

    // ----- records ---------------------------------------------------------

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

    // ----- fields ----------------------------------------------------------

    private final ActressRepository actressRepo;
    private final LabelRepository labelRepo;
    private final ActressBrowseService service;

    ActressDashboardBuilder(ActressRepository actressRepo, LabelRepository labelRepo,
                            ActressBrowseService service) {
        this.actressRepo = actressRepo;
        this.labelRepo = labelRepo;
        this.service = service;
    }

    // ----- public API ------------------------------------------------------

    /**
     * Pick a fresh spotlight actress, optionally excluding an id already on screen.
     * Used by the rotating-spotlight endpoint.
     */
    ActressSummary getSpotlight(Long excludeId) {
        java.util.HashSet<Long> exclude = new java.util.HashSet<>();
        if (excludeId != null) exclude.add(excludeId);
        Actress picked = pickSpotlight(exclude);
        if (picked == null) return null;
        ActressBrowseService.SummaryContext ctx = service.buildContext(List.of(picked), "spotlight");
        return service.toSummary(picked, ctx);
    }

    ActressDashboard buildDashboard() {
        java.util.HashSet<Long> excludeIds = new java.util.HashSet<>();

        // 1. Spotlight: 1 weighted-random actress from the taste pool.
        Actress spotlight = pickSpotlight(excludeIds);
        if (spotlight != null) excludeIds.add(spotlight.getId());

        // 2. Birthdays Today — hidden if empty.
        LocalDate today = LocalDate.now();
        List<Actress> birthdaysToday = actressRepo.findBirthdaysToday(
                today.getMonthValue(), today.getDayOfMonth(), 6);

        // 3. New Faces: 6 cards from last 30 days, fallback to newest overall.
        LocalDate since30 = today.minusDays(30);
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

        // 6. Undiscovered Elites: 6 cards — owned but never clicked.
        List<Actress> undiscovered = actressRepo.findUndiscoveredElites(
                UNDISCOVERED_TIERS, UNDISCOVERED_MAX_VISITS, 30, excludeIds);
        if (undiscovered.size() > 6) undiscovered = new ArrayList<>(undiscovered.subList(0, 6));
        undiscovered.forEach(a -> excludeIds.add(a.getId()));

        // 7. Forgotten Gems: 6 cards — high-signal but stale, weighted by staleness.
        LocalDate staleBefore = today.minusDays(FORGOTTEN_GEM_STALE_DAYS);
        List<Actress> gemPool = actressRepo.findForgottenGemsCandidates(
                FORGOTTEN_GEM_GRADES, FORGOTTEN_GEM_TIERS, staleBefore, 60, excludeIds);
        List<Actress> forgottenGems = weightedSample(gemPool, this::forgottenGemWeight, 6);
        forgottenGems.forEach(a -> excludeIds.add(a.getId()));

        // 8. Top Groups: 10 leaderboard rows.
        List<TopGroupEntry> topGroups = computeTopGroups();

        // 9. Research Gaps: 6 row-list entries.
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
        ActressBrowseService.SummaryContext ctx = service.buildContext(allDashboardActresses, "dashboard");

        List<ResearchGapEntry> researchGaps = gapActresses.stream()
                .map(a -> {
                    ActressSummary s = service.toSummary(a, ctx);
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
                spotlight != null ? service.toSummary(spotlight, ctx) : null,
                birthdaysToday.stream().map(a -> service.toSummary(a, ctx)).toList(),
                newFaces.stream().map(a -> service.toSummary(a, ctx)).toList(),
                bookmarks.stream().map(a -> service.toSummary(a, ctx)).toList(),
                recentlyViewed.stream().map(a -> service.toSummary(a, ctx)).toList(),
                undiscovered.stream().map(a -> service.toSummary(a, ctx)).toList(),
                forgottenGems.stream().map(a -> service.toSummary(a, ctx)).toList(),
                topGroups,
                researchGaps,
                statsDto);
    }

    // ----- private helpers -------------------------------------------------

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
            days = ChronoUnit.DAYS.between(a.getLastVisitedAt().toLocalDate(), LocalDate.now());
        } else {
            LocalDate first = a.getFirstSeenAt();
            days = first != null ? ChronoUnit.DAYS.between(first, LocalDate.now()) : 90;
        }
        double w = Math.pow(Math.max(days, 1), 0.6);
        if (a.isFavorite()) w += 2.0;
        if (a.getGrade() != null && FORGOTTEN_GEM_GRADES.contains(a.getGrade())) w += 2.5;
        if (a.getTier() != null && SPOTLIGHT_TIERS.contains(a.getTier())) w += 1.5;
        return w;
    }

    /**
     * Aggregate per-(actress, label) engagement rows into per-group scores using the
     * studios.yaml mapping (label.company → group).
     */
    private List<TopGroupEntry> computeTopGroups() {
        List<ActressRepository.ActressLabelEngagement> rows = actressRepo.findActressLabelEngagements();
        if (rows.isEmpty()) return List.of();

        Map<String, Label> labelMap = labelRepo.findAllAsMap();
        List<StudioGroup> studioGroups = new StudioGroupLoader().load();
        record GroupRef(String name, String slug) {}
        Map<String, GroupRef> labelToGroup = new HashMap<>();
        for (StudioGroup g : studioGroups) {
            java.util.Set<String> companies = new java.util.HashSet<>(g.companies());
            for (Label lbl : labelMap.values()) {
                if (lbl.company() != null && companies.contains(lbl.company())) {
                    labelToGroup.put(lbl.code().toUpperCase(), new GroupRef(g.name(), g.slug()));
                }
            }
        }
        if (labelToGroup.isEmpty()) return List.of();

        record Signal(boolean favorite, boolean bookmark, int visitCount) {}
        Map<String, Map<Long, Signal>> groupActressSignal = new HashMap<>();
        Map<String, GroupRef> seenGroups = new HashMap<>();
        for (var row : rows) {
            if (row.labelCode() == null) continue;
            GroupRef ref = labelToGroup.get(row.labelCode().toUpperCase());
            if (ref == null) continue;
            seenGroups.put(ref.slug(), ref);
            Map<Long, Signal> perActress = groupActressSignal.computeIfAbsent(
                    ref.slug(), k -> new HashMap<>());
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

        record Scored(GroupRef ref, double score, int actressCount) {}
        List<Scored> scored = new ArrayList<>();
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

    static boolean isProfileFilled(ActressSummary s) {
        return s.getStageName() != null && s.getDateOfBirth() != null && s.getBirthplace() != null;
    }

    static boolean isPhysicalFilled(ActressSummary s) {
        return s.getHeightCm() != null && s.getBust() != null && s.getWaist() != null && s.getHip() != null;
    }

    static boolean isBiographyFilled(ActressSummary s) {
        return s.getBiography() != null && !s.getBiography().isBlank();
    }

    static boolean isPortfolioCovered(ActressSummary s) {
        return s.getTitleCount() > 0;
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
}
