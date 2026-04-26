package com.organizer3.javdb;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;

import java.util.Optional;

/**
 * Extracts the first title slug from a javdb search results page.
 *
 * <p>Search results contain movie cards with links of the form {@code /v/{slug}}.
 * We take the first result on the assumption that an exact product-code search
 * returns the correct title at the top.
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
}
