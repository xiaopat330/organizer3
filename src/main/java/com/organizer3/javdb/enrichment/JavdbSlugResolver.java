package com.organizer3.javdb.enrichment;

import com.organizer3.javdb.JavdbClient;
import com.organizer3.javdb.JavdbSearchParser;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
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
 * <p>Pagination caveat: the actress filmography is fetched eagerly (all pages) on each
 * call. The disk cache in step 4 of the proposal will memoize across calls.
 */
@Slf4j
public class JavdbSlugResolver {

    private final JavdbClient client;
    private final JavdbFilmographyParser filmographyParser;
    private final JavdbSearchParser searchParser;

    /**
     * Per-process filmography cache. An actress's full {@code code → slug} map is fetched once
     * and reused for the rest of the JVM's lifetime. Eliminates the N×{@code filmography pages}
     * cost during bulk re-enrichment of titles whose actress already had her filmography fetched.
     *
     * <p>{@code computeIfAbsent} on {@code ConcurrentHashMap} coalesces concurrent fetches for
     * the same actress: the second caller blocks until the first completes, then both see the
     * cached result. Single fetch per actress per process.
     *
     * <p>Trade-off: a title published on javdb mid-run stays invisible until app restart.
     * Acceptable for the slug-verification cleanup; a TTL'd disk cache (Step 4 of the proposal)
     * is the durable answer.
     */
    private final Map<String, Map<String, String>> filmographyCache = new ConcurrentHashMap<>();

    public JavdbSlugResolver(JavdbClient client) {
        this(client, new JavdbFilmographyParser(), new JavdbSearchParser());
    }

    JavdbSlugResolver(JavdbClient client,
                      JavdbFilmographyParser filmographyParser,
                      JavdbSearchParser searchParser) {
        this.client = client;
        this.filmographyParser = filmographyParser;
        this.searchParser = searchParser;
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
     * Eagerly fetch all pages of an actress's filmography and build a {@code code → slug} map.
     *
     * <p>Pages are fetched sequentially; the runner's rate limiter (in {@link JavdbClient})
     * applies to each call. Stops when {@link FilmographyPage#hasNextPage()} returns false
     * or a hard cap of {@link #MAX_PAGES} is reached (defensive — should never hit in practice).
     */
    public Map<String, String> filmographyOf(String actressSlug) {
        return filmographyCache.computeIfAbsent(actressSlug, this::fetchFilmography);
    }

    /** Test/admin hook: drop the in-memory filmography cache. */
    public void clearFilmographyCache() {
        filmographyCache.clear();
    }

    private Map<String, String> fetchFilmography(String actressSlug) {
        Map<String, String> codeToSlug = new HashMap<>();
        int page = 1;
        while (page <= MAX_PAGES) {
            String html = client.fetchActressPage(actressSlug, page);
            FilmographyPage parsed = filmographyParser.parsePage(html);
            for (FilmographyEntry entry : parsed.entries()) {
                // putIfAbsent: if the same code shows up across pages (shouldn't happen but
                // defensive), keep the first occurrence — actress pages are typically newest-first.
                codeToSlug.putIfAbsent(entry.productCode(), entry.titleSlug());
            }
            if (!parsed.hasNextPage()) break;
            page++;
        }
        if (page > MAX_PAGES) {
            log.warn("javdb: filmography for {} hit MAX_PAGES={} cap — truncating",
                    actressSlug, MAX_PAGES);
        }
        return codeToSlug;
    }

    /** Defensive cap on filmography pages — even the most prolific actresses are well under this. */
    private static final int MAX_PAGES = 50;
}
