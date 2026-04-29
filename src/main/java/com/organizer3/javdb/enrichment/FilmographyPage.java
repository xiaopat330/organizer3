package com.organizer3.javdb.enrichment;

import java.util.List;

/**
 * One page of results from a javdb actress filmography fetch.
 *
 * <p>{@code hasNextPage} is {@code true} when the page contains a {@code rel="next"} link —
 * the caller paginates by fetching {@code /actors/{slug}?page=N} until this flag goes false.
 */
public record FilmographyPage(List<FilmographyEntry> entries, boolean hasNextPage) {}
