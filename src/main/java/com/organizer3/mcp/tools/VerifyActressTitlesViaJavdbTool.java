package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.javdb.JavdbActress;
import com.organizer3.javdb.JavdbClient;
import com.organizer3.javdb.JavdbFetchException;
import com.organizer3.javdb.JavdbSearchParser;
import com.organizer3.javdb.JavdbTitleParser;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;
import com.organizer3.model.Actress;
import com.organizer3.model.Title;
import com.organizer3.repository.ActressRepository;
import com.organizer3.repository.TitleRepository;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Samples the actress's titles, fetches their javdb cast info, and reports whether all
 * titles credit the same primary javdb actor slug.
 *
 * <p>Used to distinguish "Ichijo-style" clusters (one real person with multiple stage
 * names — all titles credit the same primary slug) from "Sanada-style" phantom
 * aggregations (titles credit multiple distinct slugs).
 *
 * <p>Reuses {@link JavdbClient} for HTTP + rate limiting, {@link JavdbSearchParser} for
 * code-to-slug resolution, and {@link JavdbTitleParser} for cast extraction.
 */
@Slf4j
public class VerifyActressTitlesViaJavdbTool implements Tool {

    private static final int DEFAULT_MAX_TITLES = 10;

    private final ActressRepository actressRepo;
    private final TitleRepository titleRepo;
    private final JavdbClient javdbClient;
    private final JavdbSearchParser searchParser;
    private final JavdbTitleParser titleParser;
    private final Sleeper sleeper;

    /** Pause between HTTP fetches to be polite to javdb. Default 3s. */
    @FunctionalInterface
    public interface Sleeper { void sleep(long ms) throws InterruptedException; }

    public VerifyActressTitlesViaJavdbTool(ActressRepository actressRepo,
                                           TitleRepository titleRepo,
                                           JavdbClient javdbClient) {
        this(actressRepo, titleRepo, javdbClient,
                new JavdbSearchParser(), new JavdbTitleParser(),
                Thread::sleep);
    }

    // Test constructor: full DI for parsers + sleep.
    VerifyActressTitlesViaJavdbTool(ActressRepository actressRepo,
                                    TitleRepository titleRepo,
                                    JavdbClient javdbClient,
                                    JavdbSearchParser searchParser,
                                    JavdbTitleParser titleParser,
                                    Sleeper sleeper) {
        this.actressRepo  = actressRepo;
        this.titleRepo    = titleRepo;
        this.javdbClient  = javdbClient;
        this.searchParser = searchParser;
        this.titleParser  = titleParser;
        this.sleeper      = sleeper;
    }

    @Override public String name() { return "verify_actress_titles_via_javdb"; }

    @Override
    public String description() {
        return "Samples an actress's titles, fetches javdb cast info per title, "
             + "and reports whether all titles credit the same primary actor slug "
             + "(SAME-PERSON) or multiple distinct slugs (SPLIT). Used to distinguish "
             + "real multi-stage-name actresses from phantom misattribution clusters.";
    }

    @Override
    public JsonNode inputSchema() {
        return Schemas.object()
                .prop("actress_id", "integer", "Actress id to verify.")
                .prop("max_titles", "integer",
                        "Max number of titles to sample (default 10). Evenly-spread sample "
                      + "(first, last, and N-2 between) if actress has more titles.",
                        DEFAULT_MAX_TITLES)
                .require("actress_id")
                .build();
    }

    @Override
    public Object call(JsonNode args) {
        long actressId = Schemas.requireLong(args, "actress_id");
        int maxTitles  = Schemas.optInt(args, "max_titles", DEFAULT_MAX_TITLES);
        if (maxTitles < 1) maxTitles = DEFAULT_MAX_TITLES;

        Actress actress = actressRepo.findById(actressId)
                .orElseThrow(() -> new IllegalArgumentException("No actress with id=" + actressId));

        List<Title> all = titleRepo.findByActress(actressId);
        List<Title> sampled = evenlySpread(all, maxTitles);

        List<TitleResult> results = new ArrayList<>(sampled.size());
        for (int i = 0; i < sampled.size(); i++) {
            Title t = sampled.get(i);
            if (i > 0) maybeSleep();           // pace between titles
            TitleResult tr = fetchOne(t);
            results.add(tr);
        }

        // Aggregate verdict
        Map<String, Integer> slugCounts = new LinkedHashMap<>();
        Map<String, String> slugKanji   = new LinkedHashMap<>();
        int nonEmpty = 0;
        for (TitleResult r : results) {
            if (r.actors() == null || r.actors().isEmpty()) continue;
            nonEmpty++;
            for (ActorEntry a : r.actors()) {
                slugCounts.merge(a.slug(), 1, Integer::sum);
                slugKanji.putIfAbsent(a.slug(), a.kanji());
            }
        }

        String verdict;
        String primarySlug = null;
        String primaryKanji = null;
        int primaryMatch = 0;
        List<SlugCount> otherSlugs = new ArrayList<>();

        if (nonEmpty == 0) {
            verdict = "UNRESOLVABLE";
        } else {
            // Most frequent slug wins.
            var topEntry = slugCounts.entrySet().stream()
                    .max(Comparator.comparingInt(Map.Entry::getValue))
                    .orElseThrow();
            primarySlug = topEntry.getKey();
            primaryKanji = slugKanji.get(primarySlug);
            // primary_match_count = # of non-empty-cast results where primarySlug appears in actors list
            for (TitleResult r : results) {
                if (r.actors() == null || r.actors().isEmpty()) continue;
                for (ActorEntry a : r.actors()) {
                    if (primarySlug.equals(a.slug())) { primaryMatch++; break; }
                }
            }
            for (var e : slugCounts.entrySet()) {
                if (e.getKey().equals(primarySlug)) continue;
                otherSlugs.add(new SlugCount(e.getKey(), slugKanji.get(e.getKey()), e.getValue()));
            }
            otherSlugs.sort(Comparator.comparingInt(SlugCount::count).reversed());

            verdict = otherSlugs.isEmpty() && primaryMatch == nonEmpty ? "SAME-PERSON" : "SPLIT";
        }

        return new Result(
                actress.getId(),
                actress.getCanonicalName(),
                all.size(),
                sampled.size(),
                results,
                verdict,
                primarySlug,
                primaryKanji,
                primaryMatch,
                otherSlugs
        );
    }

    private TitleResult fetchOne(Title title) {
        String code = title.getCode();
        try {
            String searchHtml = javdbClient.searchByCode(code);
            Optional<String> slugOpt = searchParser.parseFirstSlug(searchHtml);
            if (slugOpt.isEmpty()) {
                return new TitleResult(code, null, List.of(), "no search result");
            }
            String videoSlug = slugOpt.get();
            maybeSleep();
            String detailHtml = javdbClient.fetchTitlePage(videoSlug);
            List<JavdbActress> actors = titleParser.parseActresses(detailHtml);
            List<ActorEntry> entries = actors.stream()
                    .map(a -> new ActorEntry(a.actressSlug(), a.kanjiName()))
                    .toList();
            return new TitleResult(code, videoSlug, entries, null);
        } catch (JavdbFetchException e) {
            return new TitleResult(code, null, List.of(), "fetch error: " + e.getMessage());
        } catch (RuntimeException e) {
            return new TitleResult(code, null, List.of(), "error: " + e.getMessage());
        }
    }

    private void maybeSleep() {
        try { sleeper.sleep(3000); }
        catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }

    /**
     * Returns up to {@code max} titles spread evenly: first, last, and (max-2) between.
     * For lists with size <= max, returns the input list verbatim.
     */
    static <T> List<T> evenlySpread(List<T> all, int max) {
        if (all.size() <= max) return List.copyOf(all);
        List<T> sorted = new ArrayList<>(all);
        // Sort by id for deterministic ordering when caller list order is arbitrary.
        sorted.sort(Comparator.comparing(t -> ((Title) t).getId()));
        List<T> out = new ArrayList<>(max);
        int n = sorted.size();
        // Pick indices 0..n-1 distributed evenly across [0, n-1].
        for (int i = 0; i < max; i++) {
            int idx = (int) Math.round((double) i * (n - 1) / (max - 1));
            T pick = sorted.get(idx);
            if (!out.contains(pick)) out.add(pick);
        }
        return out;
    }

    // ── result records ──────────────────────────────────────────────────────

    public record ActorEntry(String slug, String kanji) {}

    public record TitleResult(
            String titleCode,
            String videoSlug,
            List<ActorEntry> actors,
            String error
    ) {}

    public record SlugCount(String slug, String kanji, int count) {}

    public record Result(
            long actressId,
            String canonicalName,
            int totalTitles,
            int sampled,
            List<TitleResult> results,
            String verdict,
            String primarySlug,
            String primaryKanji,
            int primaryMatchCount,
            List<SlugCount> otherSlugs
    ) {}
}
