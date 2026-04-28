package com.organizer3.javdb.enrichment;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses a single javdb actress-page HTML into a {@link FilmographyPage}.
 *
 * <p>Page structure per fixture {@code src/test/resources/javdb/actress_filmography.html}:
 * <pre>{@code
 *   <div class="item">
 *     <a href="/v/<titleSlug>" class="box" title="...">
 *       <div class="cover ">...</div>
 *       <div class="video-title"><strong><productCode></strong> ...</div>
 *       <div class="score">...</div>
 *       <div class="meta">...</div>
 *     </a>
 *   </div>
 * }</pre>
 *
 * <p>Pagination: a {@code <a rel="next">} link inside {@code nav.pagination} marks the
 * presence of a next page. The last page omits this link.
 *
 * <p>This class is pure parsing — fetching and pagination orchestration live in the runner.
 */
public class JavdbFilmographyParser {

    /**
     * Parse one page of HTML. Items missing a slug or product code are silently skipped —
     * they're never the bottleneck for slug resolution and a noisy page (e.g. an ad insertion)
     * shouldn't break parsing the surrounding entries.
     */
    public FilmographyPage parsePage(String html) {
        if (html == null || html.isBlank()) {
            return new FilmographyPage(List.of(), false);
        }
        Document doc = Jsoup.parse(html);

        List<FilmographyEntry> entries = new ArrayList<>();
        for (Element item : doc.select("div.item")) {
            Element link = item.selectFirst("a.box[href^=/v/]");
            if (link == null) continue;
            String slug = stripPrefix(link.attr("href"), "/v/");
            if (slug.isBlank()) continue;

            Element codeEl = item.selectFirst(".video-title strong");
            if (codeEl == null) continue;
            String code = codeEl.text().trim();
            if (code.isEmpty()) continue;

            entries.add(new FilmographyEntry(code, slug));
        }

        // Pagination: rel="next" inside nav.pagination is the canonical "more pages exist" marker.
        boolean hasNextPage = doc.selectFirst("nav.pagination a[rel=next]") != null;

        return new FilmographyPage(List.copyOf(entries), hasNextPage);
    }

    private static String stripPrefix(String s, String prefix) {
        return s.startsWith(prefix) ? s.substring(prefix.length()) : s;
    }
}
