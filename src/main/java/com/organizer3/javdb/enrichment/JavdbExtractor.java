package com.organizer3.javdb.enrichment;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts all available fields from javdb HTML pages into structured POJOs.
 *
 * <p>Every field extraction is best-effort — null is returned for any field
 * that cannot be parsed rather than throwing. This makes the extractor tolerant
 * of layout changes and ensures partial data is never lost.
 */
public class JavdbExtractor {

    private static final Pattern DURATION_PATTERN = Pattern.compile("(\\d+)\\s*minute");
    private static final Pattern RATING_AVG_PATTERN = Pattern.compile("([\\d.]+)\\s*/\\s*5");
    private static final Pattern RATING_COUNT_PATTERN = Pattern.compile("by\\s+(\\d+)\\s+user");
    private static final Pattern TITLE_COUNT_PATTERN = Pattern.compile("(\\d+)\\s*movie");
    private static final Pattern BACKGROUND_URL_PATTERN = Pattern.compile("url\\(([^)]+)\\)");
    private static final Pattern ACTRESS_HREF = Pattern.compile("/actors/([A-Za-z0-9]+)");

    /**
     * Extracts all fields from a javdb title detail page.
     *
     * @param html    raw HTML of the title page
     * @param code    the product code (e.g. "DV-948") — passed in since it may not appear in the page
     * @param slug    the javdb title slug used to fetch the page
     * @return extracted title data; all optional fields may be null
     */
    public TitleExtract extractTitle(String html, String code, String slug) {
        if (html == null || html.isBlank()) {
            return new TitleExtract(code, slug, null, null, null, null, null, null,
                    null, null, List.of(), List.of(), null, List.of(), now());
        }

        Document doc = Jsoup.parse(html);
        String titleOriginal = extractCurrentTitle(doc);
        String coverUrl = extractCoverUrl(doc);
        List<String> thumbnailUrls = extractThumbnailUrls(doc);
        Double ratingAvg = extractRatingAvg(doc);
        Integer ratingCount = extractRatingCount(doc);

        String releaseDate = null;
        Integer durationMinutes = null;
        String maker = null;
        String publisher = null;
        String series = null;
        List<String> tags = new ArrayList<>();
        List<TitleExtract.CastEntry> cast = new ArrayList<>();

        for (Element block : doc.select(".panel-block")) {
            Element label = block.selectFirst("strong");
            if (label == null) continue;
            String labelText = label.text().trim();

            if (labelText.startsWith("Released Date")) {
                releaseDate = panelValue(block);
            } else if (labelText.startsWith("Duration")) {
                durationMinutes = parseDuration(panelValue(block));
            } else if (labelText.startsWith("Maker")) {
                maker = panelLinkText(block);
            } else if (labelText.startsWith("Publisher")) {
                publisher = panelLinkText(block);
            } else if (labelText.startsWith("Series")) {
                series = panelLinkText(block);
            } else if (labelText.startsWith("Tags") || labelText.startsWith("Genre")) {
                for (Element tag : block.select("a.tag, a[href*='/tags']")) {
                    String t = tag.text().trim();
                    if (!t.isEmpty()) tags.add(t);
                }
            } else if (labelText.contains("Actor")) {
                cast = extractCast(block);
            }
        }

        return new TitleExtract(code, slug, titleOriginal, releaseDate, durationMinutes,
                maker, publisher, series, ratingAvg, ratingCount,
                List.copyOf(tags), List.copyOf(cast), coverUrl, thumbnailUrls, now());
    }

    /**
     * Extracts all fields from a javdb actress profile page.
     *
     * @param html raw HTML of the actress page
     * @param slug the javdb actress slug
     * @return extracted actress data; all optional fields may be null
     */
    public ActressExtract extractActress(String html, String slug) {
        if (html == null || html.isBlank()) {
            return new ActressExtract(slug, List.of(), null, null, null, null, now());
        }

        Document doc = Jsoup.parse(html);
        List<String> nameVariants = extractNameVariants(doc);
        String avatarUrl = extractAvatarUrl(doc);
        Integer titleCount = extractTitleCount(doc);
        String twitterHandle = extractSocialHandle(doc, "twitter.com");
        String instagramHandle = extractSocialHandle(doc, "instagram.com");

        return new ActressExtract(slug, nameVariants, avatarUrl, twitterHandle, instagramHandle, titleCount, now());
    }

    // --- title helpers ---

    private String extractCurrentTitle(Document doc) {
        Element el = doc.selectFirst("strong.current-title");
        return el != null ? el.text().trim() : null;
    }

    private String extractCoverUrl(Document doc) {
        Element img = doc.selectFirst("img.video-cover");
        return img != null ? img.attr("src") : null;
    }

    private List<String> extractThumbnailUrls(Document doc) {
        List<String> urls = new ArrayList<>();
        for (Element img : doc.select("img[src*='jdbstatic.com/thumbs']")) {
            String src = img.attr("src").trim();
            if (!src.isEmpty()) urls.add(src);
        }
        return List.copyOf(urls);
    }

    private Double extractRatingAvg(Document doc) {
        Element scoreTitle = doc.selectFirst(".score-title");
        if (scoreTitle != null) {
            try { return Double.parseDouble(scoreTitle.text().trim()); } catch (NumberFormatException ignored) {}
        }
        // Fallback: find text matching "X.X / 5"
        String scoreText = doc.select(".score").text();
        Matcher m = RATING_AVG_PATTERN.matcher(scoreText);
        if (m.find()) {
            try { return Double.parseDouble(m.group(1)); } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    private Integer extractRatingCount(Document doc) {
        String scoreText = doc.select(".score").text();
        Matcher m = RATING_COUNT_PATTERN.matcher(scoreText);
        if (m.find()) {
            try { return Integer.parseInt(m.group(1)); } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    private String panelValue(Element block) {
        Element span = block.selectFirst("span.value");
        return span != null ? span.text().trim() : null;
    }

    private String panelLinkText(Element block) {
        Element span = block.selectFirst("span.value");
        if (span == null) return null;
        Element link = span.selectFirst("a");
        if (link != null) return link.text().trim();
        String text = span.text().trim();
        return text.isEmpty() ? null : text;
    }

    private Integer parseDuration(String text) {
        if (text == null) return null;
        Matcher m = DURATION_PATTERN.matcher(text);
        if (m.find()) {
            try { return Integer.parseInt(m.group(1)); } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    private List<TitleExtract.CastEntry> extractCast(Element actorBlock) {
        List<TitleExtract.CastEntry> entries = new ArrayList<>();
        for (Element a : actorBlock.select("a[href^='/actors/']")) {
            Matcher m = ACTRESS_HREF.matcher(a.attr("href"));
            if (!m.find()) continue;
            String actorSlug = m.group(1);
            String name = a.text().trim();
            if (name.isEmpty()) continue;

            Element genderEl = a.nextElementSibling();
            String gender = "U"; // unknown
            if (genderEl != null) {
                if (genderEl.hasClass("female")) gender = "F";
                else if (genderEl.hasClass("male")) gender = "M";
            }
            entries.add(new TitleExtract.CastEntry(actorSlug, name, gender));
        }
        return entries;
    }

    // --- actress helpers ---

    private List<String> extractNameVariants(Document doc) {
        Element el = doc.selectFirst("span.actor-section-name");
        if (el == null) return List.of();
        List<String> variants = new ArrayList<>();
        for (String part : el.text().split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) variants.add(trimmed);
        }
        return List.copyOf(variants);
    }

    private String extractAvatarUrl(Document doc) {
        Element el = doc.selectFirst(".actor-section-avatar");
        if (el == null) return null;
        String style = el.attr("style");
        Matcher m = BACKGROUND_URL_PATTERN.matcher(style);
        if (m.find()) {
            String url = m.group(1).replaceAll("[\"']", "").trim();
            return url.isEmpty() ? null : url;
        }
        return null;
    }

    private Integer extractTitleCount(Document doc) {
        for (Element el : doc.select("span.section-meta")) {
            Matcher m = TITLE_COUNT_PATTERN.matcher(el.text());
            if (m.find()) {
                try { return Integer.parseInt(m.group(1)); } catch (NumberFormatException ignored) {}
            }
        }
        return null;
    }

    private String extractSocialHandle(Document doc, String domain) {
        Elements links = doc.select("a[href*='" + domain + "']");
        for (Element a : links) {
            String href = a.attr("href");
            Optional<String> handle = parseSocialHandle(href, domain);
            if (handle.isPresent()) return handle.get();
        }
        return null;
    }

    private Optional<String> parseSocialHandle(String href, String domain) {
        int idx = href.indexOf(domain);
        if (idx < 0) return Optional.empty();
        String path = href.substring(idx + domain.length()).replaceAll("^/+", "").replaceAll("/+$", "");
        // strip query/fragment
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        int hash = path.indexOf('#');
        if (hash >= 0) path = path.substring(0, hash);
        return path.isEmpty() ? Optional.empty() : Optional.of(path);
    }

    private String now() {
        return Instant.now().toString();
    }
}
