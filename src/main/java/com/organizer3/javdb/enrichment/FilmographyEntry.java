package com.organizer3.javdb.enrichment;

/**
 * One row from a javdb actress's filmography page: the product code (e.g. {@code "STAR-358"})
 * and the javdb title slug it resolves to (e.g. {@code "BzkBO"}).
 *
 * <p>This pairing is the fix for the slug-mismatch bug: code-search returns multiple slugs
 * when the code prefix has been reused across studios, but the actress's filmography
 * is unambiguously hers — so the slug here is guaranteed correct for this actress.
 */
public record FilmographyEntry(String productCode, String titleSlug) {}
