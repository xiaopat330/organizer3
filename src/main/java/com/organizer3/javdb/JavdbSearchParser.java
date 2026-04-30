package com.organizer3.javdb;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;

import java.util.Optional;

/**
 * Extracts title slugs from a javdb search results page.
 *
 * <p>Search results contain movie cards with links of the form {@code /v/{slug}}.
 */
public class JavdbSearchParser {

    /**
     * Returns the slug of the first title result (e.g. {@code "AbXy12"} from {@code /v/AbXy12}),
     * or empty if the page has no results.
     */
    public Optional<String> parseFirstSlug(String html) {
        if (html == null || html.isBlank()) return Optional.empty();

        Element link = Jsoup.parse(html).selectFirst("a[href^='/v/']");
        if (link == null) return Optional.empty();

        String href = link.attr("href"); // e.g. "/v/AbXy12"
        String slug = href.substring("/v/".length());
        return slug.isBlank() ? Optional.empty() : Optional.of(slug);
    }

    /**
     * Returns all title slugs from the search results page, preserving order.
     * Returns an empty list if the page has no results.
     */
    public java.util.List<String> parseAllSlugs(String html) {
        if (html == null || html.isBlank()) return java.util.List.of();

        java.util.List<String> slugs = new java.util.ArrayList<>();
        for (Element link : Jsoup.parse(html).select("a[href^='/v/']")) {
            String href = link.attr("href");
            String slug = href.substring("/v/".length());
            if (!slug.isBlank() && !slugs.contains(slug)) {
                slugs.add(slug);
            }
        }
        return java.util.List.copyOf(slugs);
    }
}
