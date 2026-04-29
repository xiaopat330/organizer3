package com.organizer3.javdb.enrichment;

import com.organizer3.javdb.JavdbClient;
import com.organizer3.javdb.JavdbConfig;
import com.organizer3.javdb.JavdbSearchParser;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Picks the correct javdb title slug for a product code, anchoring on the linked actress
 * when possible.
 *
 * <p>The slug-mismatch bug (see {@code spec/PROPOSAL_JAVDB_SLUG_VERIFICATION.md}) comes from
 * code-search returning multiple slugs for codes whose prefix has been reused across
 * studios/decades. This resolver fixes it by:
 *
 * <ul>
 *   <li><b>Actress-anchored path</b> (preferred): if the title is linked to an actress
 *     who has a known javdb actress slug, fetch her filmography and look up the code in
 *     <i>her</i> map — the slug is guaranteed unambiguous because each filmography entry
 *     is uniquely a title she's credited on.</li>
 *   <li><b>Code-search fallback</b>: if no actress slug is available (sentinel actresses,
 *     unsorted titles, or actress profile not yet fetched), fall back to the original
 *     {@code searchByCode → first slug} behavior. This preserves enrichment coverage
 *     for titles that can't use the actress anchor.</li>
 * </ul>
 *
 * <p>Two-level cache: an in-process {@code ConcurrentHashMap} (L1) avoids repeated lookups
 * within the same JVM; a persistent SQLite table (L2) avoids repeated HTTP fetches across
 * sessions. L1 is always checked first; on a miss, L2 is consulted if the entry is within
 * the configured TTL; otherwise the filmography is re-fetched over HTTP and both levels
 * are populated.
 */
@Slf4j
public class JavdbSlugResolver {

    private final JavdbClient client;
    private final JavdbFilmographyParser filmographyParser;
    private final JavdbSearchParser searchParser;
    private final JavdbActressFilmographyRepository filmographyRepo;
    private final int ttlDays;
    private final int maxPages;
    private final Clock clock;

    /**
     * Per-process filmography cache (L1). An actress's full {@code code → slug} map is
     * kept here for the JVM's lifetime once loaded. Checked before hitting L2 or HTTP.
     */
    private final Map<String, Map<String, String>> filmographyCache = new ConcurrentHashMap<>();

    /**
     * Convenience constructor for tests and callers that don't need L2 persistence.
     * Uses a no-op repository — filmography is cached only in L1 for the JVM lifetime.
     */
    public JavdbSlugResolver(JavdbClient client) {
        this(client, new JavdbFilmographyParser(), new JavdbSearchParser(),
                NoOpJavdbActressFilmographyRepository.INSTANCE,
                JavdbConfig.DEFAULTS.filmographyTtlDaysOrDefault(),
                JavdbConfig.DEFAULTS.filmographyMaxPagesOrDefault(),
                Clock.systemUTC());
    }

    /** Production constructor: wires L2 persistence, TTL, and max-pages from config. */
    public JavdbSlugResolver(JavdbClient client,
                             JavdbActressFilmographyRepository filmographyRepo,
                             JavdbConfig config) {
        this(client, new JavdbFilmographyParser(), new JavdbSearchParser(),
                filmographyRepo,
                config.filmographyTtlDaysOrDefault(),
                config.filmographyMaxPagesOrDefault(),
                Clock.systemUTC());
    }

    /** Full constructor for testing — all dependencies injectable. */
    JavdbSlugResolver(JavdbClient client,
                      JavdbFilmographyParser filmographyParser,
                      JavdbSearchParser searchParser,
                      JavdbActressFilmographyRepository filmographyRepo,
                      int ttlDays,
                      int maxPages,
                      Clock clock) {
        this.client = client;
        this.filmographyParser = filmographyParser;
        this.searchParser = searchParser;
        this.filmographyRepo = filmographyRepo;
        this.ttlDays = ttlDays;
        this.maxPages = maxPages;
        this.clock = clock;
    }

    /** Outcome of a slug-resolution attempt. */
    public sealed interface Resolution permits Success, NoMatchInFilmography, CodeNotFound {}

    /** A correct slug was found. {@code source} indicates the path used. */
    public record Success(String slug, Source source) implements Resolution {}

    /**
     * The actress was anchored, but her filmography does not contain this product code.
     * Likely causes: wrong-folder filing, javdb gap, alias-only credit, or a code typo.
     * Triage in the no_match resolver UI (step 8 of the proposal).
     */
    public record NoMatchInFilmography(String actressSlug) implements Resolution {}

    /**
     * Code-search returned no results — the code doesn't exist on javdb at all.
     * This is the same outcome as the pre-fix code path's {@code not_found} status.
     */
    public record CodeNotFound() implements Resolution {}

    /** Which path succeeded — useful for logging and provenance. */
    public enum Source {
        /** Found via actress-anchored filmography lookup (the preferred path). */
        ACTRESS_FILMOGRAPHY,
        /** Found via code-search fallback because no actress slug was available. */
        CODE_SEARCH_FALLBACK
    }

    /**
     * Resolve the slug for a product code.
     *
     * @param productCode      the title's product code (e.g. {@code "STAR-334"})
     * @param actressJavdbSlug the linked actress's javdb actress slug, or {@code null}
     *                         when no actress is linked or the actress profile hasn't
     *                         been fetched yet
     */
    public Resolution resolve(String productCode, String actressJavdbSlug) {
        if (actressJavdbSlug != null && !actressJavdbSlug.isBlank()) {
            Map<String, String> filmography = filmographyOf(actressJavdbSlug);
            String slug = filmography.get(productCode);
            if (slug != null) {
                log.info("javdb: resolved {} via filmography of actress {} → {}",
                        productCode, actressJavdbSlug, slug);
                return new Success(slug, Source.ACTRESS_FILMOGRAPHY);
            }
            log.info("javdb: {} not in filmography of actress {} — marking no_match",
                    productCode, actressJavdbSlug);
            return new NoMatchInFilmography(actressJavdbSlug);
        }
        return resolveByCodeSearch(productCode);
    }

    /**
     * Code-search fallback. Used when no actress anchor is available. Preserves the original
     * pre-fix behavior so enrichment coverage of unsorted/sentinel titles doesn't regress.
     */
    private Resolution resolveByCodeSearch(String productCode) {
        Optional<String> maybe = searchParser.parseFirstSlug(client.searchByCode(productCode));
        if (maybe.isEmpty()) {
            return new CodeNotFound();
        }
        log.info("javdb: resolved {} via code-search fallback → {}", productCode, maybe.get());
        return new Success(maybe.get(), Source.CODE_SEARCH_FALLBACK);
    }

    /**
     * Returns the full {@code code → slug} map for an actress, using the two-level cache:
     * L1 (in-memory) → L2 (database, if within TTL) → HTTP fetch (+ persist to both levels).
     */
    public Map<String, String> filmographyOf(String actressSlug) {
        // L1 hit — fastest path, no DB or network touch.
        Map<String, String> l1 = filmographyCache.get(actressSlug);
        if (l1 != null) return l1;

        // L2 hit — valid cached data in the DB; populate L1 and return.
        if (!filmographyRepo.isStale(actressSlug, ttlDays, clock)) {
            Map<String, String> l2 = filmographyRepo.getCodeToSlug(actressSlug);
            log.debug("javdb: filmography for {} loaded from L2 ({} entries)", actressSlug, l2.size());
            filmographyCache.put(actressSlug, l2);
            return l2;
        }

        // L2 miss or stale — fetch from HTTP, persist to L2, populate L1.
        Map<String, String> fetched = fetchAndPersist(actressSlug);
        filmographyCache.put(actressSlug, fetched);
        return fetched;
    }

    /** Test/admin hook: drop the in-memory (L1) filmography cache. */
    public void clearFilmographyCache() {
        filmographyCache.clear();
    }

    private Map<String, String> fetchAndPersist(String actressSlug) {
        Map<String, String> codeToSlug = new HashMap<>();
        List<FilmographyEntry> entries = new ArrayList<>();
        int pageCount = 0;
        int page = 1;

        while (page <= maxPages) {
            String html = client.fetchActressPage(actressSlug, page);
            FilmographyPage parsed = filmographyParser.parsePage(html);
            for (FilmographyEntry entry : parsed.entries()) {
                // putIfAbsent: if the same code shows up across pages (shouldn't happen but
                // defensive), keep the first occurrence — actress pages are typically newest-first.
                if (codeToSlug.putIfAbsent(entry.productCode(), entry.titleSlug()) == null) {
                    entries.add(entry);
                }
            }
            pageCount = page;
            if (!parsed.hasNextPage()) break;
            page++;
        }
        if (page > maxPages) {
            log.warn("javdb: filmography for {} hit maxPages={} cap — truncating", actressSlug, maxPages);
        }

        String fetchedAt = Instant.now(clock).toString();
        // lastReleaseDate: the current FilmographyParser doesn't extract release dates,
        // so this is null for all HTTP fetches. A future parser enhancement can populate it.
        FetchResult result = new FetchResult(fetchedAt, pageCount, null, "http", entries);
        filmographyRepo.upsertFilmography(actressSlug, result);

        log.info("javdb: fetched filmography for {} — {} pages, {} entries",
                actressSlug, pageCount, codeToSlug.size());
        return codeToSlug;
    }
}
