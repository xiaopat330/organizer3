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

    /** One search-result movie card: its product code and title slug. */
    public record Result(String code, String slug) {}

    /**
     * Returns the ordered list of {@code (code, slug)} pairs from a search results page.
     *
     * <p>Search results use the same movie-card markup as the actress filmography page:
     * each {@code div.item} contains an {@code a[href^='/v/']} (slug after {@code /v/}) and
     * a {@code .video-title strong} (the product code). Items missing either are skipped.
     *
     * <p>Unlike {@link #parseFirstSlug}, the code is captured so callers can validate that a
     * result actually matches the queried code rather than blindly taking the first hit.
     */
    public java.util.List<Result> parseResults(String html) {
        if (html == null || html.isBlank()) return java.util.List.of();

        java.util.List<Result> results = new java.util.ArrayList<>();
        for (Element item : Jsoup.parse(html).select("div.item")) {
            Element link = item.selectFirst("a[href^='/v/']");
            if (link == null) continue;
            String slug = link.attr("href").substring("/v/".length());
            if (slug.isBlank()) continue;

            Element codeEl = item.selectFirst(".video-title strong");
            if (codeEl == null) continue;
            String code = codeEl.text().trim();
            if (code.isBlank()) continue;

            results.add(new Result(code, slug));
        }
        return java.util.List.copyOf(results);
    }
}
