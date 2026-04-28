package com.organizer3.javdb;

public interface JavdbClient {

    /** Returns the raw HTML of the search results page for the given product code (e.g. "ABP-123"). */
    String searchByCode(String code);

    /** Returns the raw HTML of a title detail page given its slug (e.g. "AbXy12"). */
    String fetchTitlePage(String slug);

    /** Returns the raw HTML of an actress profile page given her slug (e.g. "OpzD"). */
    String fetchActressPage(String slug);

    /**
     * Returns the raw HTML of a specific page of an actress's filmography (paginated).
     * Page 1 is equivalent to {@link #fetchActressPage(String)}; pages 2+ append
     * {@code ?page=N} to the URL.
     */
    default String fetchActressPage(String slug, int page) {
        if (page <= 1) return fetchActressPage(slug);
        // Default fallback: callers that override should provide a real impl.
        throw new UnsupportedOperationException("Pagination not supported by this JavdbClient");
    }
}
