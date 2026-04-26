package com.organizer3.javdb;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts actress attributions from a javdb title detail page.
 *
 * <p>The info panel has a row with a {@code <strong>} label containing "Actor" (English locale)
 * or "演員" (Chinese locale). Each person is an {@code <a href="/actors/{slug}">} link immediately
 * followed by a gender symbol {@code <strong class="symbol female|male">}. Only female entries
 * are returned.
 */
public class JavdbTitleParser {

    private static final Pattern ACTRESS_HREF = Pattern.compile("/actors/([A-Za-z0-9]+)");

    /**
     * Returns all female actresses attributed on this title page, in DOM order.
     * Returns an empty list if the actor panel is absent or has no female entries.
     */
    public List<JavdbActress> parseActresses(String html) {
        if (html == null || html.isBlank()) return List.of();

        Document doc = Jsoup.parse(html);
        List<JavdbActress> results = new ArrayList<>();

        for (Element block : doc.select(".panel-block")) {
            Element label = block.selectFirst("strong");
            if (label == null) continue;
            String labelText = label.text();
            if (!labelText.contains("Actor") && !labelText.contains("演員")) continue;

            Elements actressLinks = block.select("a[href^='/actors/']");
            for (Element a : actressLinks) {
                // Gender symbol is the next sibling element after the actress link
                Element nextEl = a.nextElementSibling();
                if (nextEl == null || !nextEl.hasClass("female")) continue;

                String href = a.attr("href");
                Matcher m = ACTRESS_HREF.matcher(href);
                if (!m.find()) continue;

                String name = a.text().trim();
                if (!name.isEmpty()) {
                    results.add(new JavdbActress(name, m.group(1)));
                }
            }
            break;
        }

        return results;
    }
}
