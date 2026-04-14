package com.organizer3.avstars.iafd;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses IAFD search results HTML into a list of {@link IafdSearchResult} candidates.
 *
 * <p>Each result row in the table contains:
 * <ul>
 *   <li>A headshot {@code <img>}</li>
 *   <li>A name link: {@code <a href="/person.rme/id=<uuid>">Name</a>}</li>
 *   <li>AKAs cell (comma-separated string)</li>
 *   <li>Active from / active to year cells</li>
 *   <li>Title count cell</li>
 * </ul>
 */
public class IafdSearchParser {

    // Extracts UUID from "/person.rme/id=<uuid>"
    private static final Pattern UUID_PATTERN =
            Pattern.compile("/person\\.rme/id=([0-9a-f\\-]{36})", Pattern.CASE_INSENSITIVE);

    /**
     * Parses the raw HTML from a search results page and returns all candidate rows.
     *
     * @param html raw HTML body from IAFD search
     * @return list of candidates (may be empty if no results)
     */
    public List<IafdSearchResult> parse(String html) {
        if (html == null || html.isBlank()) return List.of();

        Document doc = Jsoup.parse(html);
        List<IafdSearchResult> results = new ArrayList<>();

        // Results are inside <table id="tblFeatured"> or similar; fall back to any
        // <tr> containing a person.rme link.
        Elements rows = doc.select("table#tblFeatured tr");
        if (rows.isEmpty()) {
            // Try generic approach — any table row with a person link
            rows = doc.select("tr:has(a[href*='person.rme'])");
        }

        for (Element row : rows) {
            Element nameLink = row.selectFirst("a[href*='person.rme/id=']");
            if (nameLink == null) continue;

            String href = nameLink.attr("href");
            Matcher m = UUID_PATTERN.matcher(href);
            if (!m.find()) continue;
            String uuid = m.group(1);

            String name = nameLink.text().trim();

            // Headshot img in same row
            Element img = row.selectFirst("img");
            String headshotUrl = img != null ? img.absUrl("src") : null;
            if (headshotUrl == null && img != null) {
                headshotUrl = img.attr("src");
            }

            // Collect all <td> text values.
            // Expected layout: [0]=photo, [1]=name link, [2]=akas, [3]=activeFrom, [4]=activeTo, [5]=titleCount
            Elements cells = row.select("td");
            String akasText = cells.size() > 2 ? cells.get(2).text().trim() : "";
            String activeFromText = cells.size() > 3 ? cells.get(3).text().trim() : "";
            String activeToText = cells.size() > 4 ? cells.get(4).text().trim() : "";
            String titleCountText = cells.size() > 5 ? cells.get(5).text().trim() : "";

            List<String> akas = parseAkas(akasText);
            Integer activeFrom = parseYear(activeFromText);
            Integer activeTo = parseYear(activeToText);
            Integer titleCount = parseInteger(titleCountText);

            results.add(new IafdSearchResult(uuid, name, akas, activeFrom, activeTo, titleCount, headshotUrl));
        }

        return results;
    }

    private List<String> parseAkas(String text) {
        if (text == null || text.isBlank()) return List.of();
        return Arrays.stream(text.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private Integer parseYear(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            return Integer.parseInt(text.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Integer parseInteger(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            return Integer.parseInt(text.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
