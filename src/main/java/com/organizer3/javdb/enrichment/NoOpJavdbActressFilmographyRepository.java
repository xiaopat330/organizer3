package com.organizer3.javdb.enrichment;

import java.time.Clock;
import java.util.Map;
import java.util.Optional;

/**
 * No-op filmography repository used when L2 persistence is not wired in.
 *
 * <p>Always reports no cached data so the resolver falls through to HTTP.
 * Used by the single-arg {@link JavdbSlugResolver} constructor for tests and
 * callers that don't need cross-session caching.
 */
final class NoOpJavdbActressFilmographyRepository implements JavdbActressFilmographyRepository {

    static final NoOpJavdbActressFilmographyRepository INSTANCE =
            new NoOpJavdbActressFilmographyRepository();

    private NoOpJavdbActressFilmographyRepository() {}

    @Override public Optional<FilmographyMeta> findMeta(String actressSlug)              { return Optional.empty(); }
    @Override public Map<String, String> getCodeToSlug(String actressSlug)                { return Map.of(); }
    @Override public Optional<String> findTitleSlug(String actressSlug, String code)      { return Optional.empty(); }
    @Override public void upsertFilmography(String actressSlug, FetchResult result)       {}
    @Override public void evict(String actressSlug)                                        {}
    @Override public int markNotFound(String actressSlug, String fetchedAt)               { return 0; }
    @Override public java.util.List<String> findAllActressSlugs()                         { return java.util.List.of(); }
    @Override public boolean isStale(String actressSlug, int ttlDays, Clock clock)        { return true; }
}
