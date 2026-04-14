package com.organizer3.avstars.iafd;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.Elements;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses an IAFD performer profile page into an {@link IafdResolvedProfile}.
 *
 * <p>All field extraction is based on the confirmed HTML structure from live page probes:
 * <ul>
 *   <li>Most fields: {@code <p class="bioheading">Label</p><p class="biodata">Value</p>}</li>
 *   <li>AKA: {@code <p class="bioheading">AKA</p><div class="biodata">Name<BR>Name</div>}</li>
 *   <li>Social/Platform: {@code <p class="bioheading">Social Network</p>} followed by
 *       loose {@code <a><img></a>} siblings — no biodata wrapper</li>
 *   <li>Awards: {@code <p class="bioheading">* Awards</p>} then alternating
 *       {@code <div class="showyear">YYYY</div>} / {@code <div class="biodata">text</div>}</li>
 *   <li>Comments: {@code <div id="comments"><ul><li class="cmt">text</li></ul></div>}</li>
 *   <li>Title count: {@code <a ...>Performer Credits (N)</a>} in the perftabs nav</li>
 * </ul>
 */
public class IafdProfileParser {

    // "Hair Color" or "Hair Colors"
    private static final Pattern HAIR_HEADING = Pattern.compile("Hair Colors?", Pattern.CASE_INSENSITIVE);
    // "Years Active" or "Years Active as Performer"
    private static final Pattern YEARS_PERFORMER = Pattern.compile("Years Active(?: as Performer)?", Pattern.CASE_INSENSITIVE);
    private static final Pattern YEARS_DIRECTOR = Pattern.compile("Years Active as Director", Pattern.CASE_INSENSITIVE);
    // "YYYY-YYYY" or "YYYY - YYYY" or single "YYYY"
    private static final Pattern YEAR_RANGE = Pattern.compile("(\\d{4})\\s*(?:-|–)\\s*(\\d{4})");
    private static final Pattern YEAR_SINGLE = Pattern.compile("\\b(\\d{4})\\b");
    // "Started around N years old"
    private static final Pattern HEIGHT_CM = Pattern.compile("(\\d+)\\s*cm");
    private static final Pattern WEIGHT_KG = Pattern.compile("(\\d+)\\s*kg");
    private static final Pattern PERFORMER_CREDITS = Pattern.compile("Performer Credits \\((\\d+)\\)");

    // Known icon filename → platform key mappings
    private static final Map<String, String> SOCIAL_ICONS = Map.of(
            "x.png",                                    "twitter",
            "2023_Facebook_icon.svg",                   "facebook",
            "Instagram_Glyph_Black.png",                "instagram",
            "tiktok-icon2.png",                         "tiktok"
    );
    private static final Map<String, String> PLATFORM_ICONS = Map.of(
            "OnlyFans_Social_Icon_Rounded_Blue.png",    "onlyfans",
            "mv.ico",                                   "manyvids"
    );

    /**
     * Parses the raw profile page HTML.
     *
     * @param iafdId the performer UUID (stored as-is; not re-extracted from the page)
     * @param html   raw HTML body from IAFD
     * @return parsed profile; all unresolved fields are null
     */
    public IafdResolvedProfile parse(String iafdId, String html) {
        if (html == null || html.isBlank()) {
            return IafdResolvedProfile.builder().iafdId(iafdId).build();
        }

        Document doc = Jsoup.parse(html);
        IafdResolvedProfile.IafdResolvedProfileBuilder b = IafdResolvedProfile.builder()
                .iafdId(iafdId);

        // --- Profile headshot from div#headshot img ---
        Element headshotImg = doc.selectFirst("div#headshot img");
        if (headshotImg != null) {
            String src = headshotImg.absUrl("src");
            if (src.isBlank()) src = headshotImg.attr("src");
            if (!src.isBlank()) b.headshotUrl(src);
        }

        // --- Performer Credits count from perftabs nav ---
        Element perftabs = doc.selectFirst("div#perftabs, div.perftabs");
        if (perftabs != null) {
            Matcher m = PERFORMER_CREDITS.matcher(perftabs.text());
            if (m.find()) b.iafdTitleCount(Integer.parseInt(m.group(1)));
        }

        // --- Walk bioheading / biodata pairs ---
        // Collect all bioheading <p> elements in document order
        Elements headings = doc.select("p.bioheading");

        for (int i = 0; i < headings.size(); i++) {
            Element heading = headings.get(i);
            String label = heading.text().trim();

            if (label.equalsIgnoreCase("AKA")) {
                b.akaNamesJson(parseAkaBlock(heading));
                continue;
            }

            if (label.equalsIgnoreCase("Social Network")) {
                b.socialJson(parseLinkIconBlock(heading, SOCIAL_ICONS));
                continue;
            }

            if (label.equalsIgnoreCase("Digital distribution platform")) {
                b.platformsJson(parseLinkIconBlock(heading, PLATFORM_ICONS));
                continue;
            }

            if (label.matches("(?i).*Awards.*") || label.matches("(?i).*Hall of Fame.*")) {
                String awards = parseAwardsBlock(heading, label);
                if (awards != null) {
                    b.awardsJson(mergeAwardsJson(
                            getField(b, "awardsJson"), label, awards));
                }
                continue;
            }

            // External DB links (EGAFD, StashDB, etc.)
            if (isExternalDbHeading(label)) {
                String refJson = parseExternalRef(heading, label);
                if (refJson != null) {
                    // Accumulate
                }
                continue;
            }

            if (YEARS_DIRECTOR.matcher(label).matches()) {
                Element biodata = nextBiodata(heading);
                if (biodata != null) {
                    String text = biodata.text().trim();
                    Matcher rm = YEAR_RANGE.matcher(text);
                    if (rm.find()) {
                        b.directorFrom(Integer.parseInt(rm.group(1)));
                        b.directorTo(Integer.parseInt(rm.group(2)));
                    } else {
                        Matcher sm = YEAR_SINGLE.matcher(text);
                        if (sm.find()) {
                            int y = Integer.parseInt(sm.group(1));
                            b.directorFrom(y);
                            b.directorTo(y);
                        }
                    }
                }
                continue;
            }

            if (YEARS_PERFORMER.matcher(label).matches()) {
                Element biodata = nextBiodata(heading);
                if (biodata != null) {
                    String text = biodata.text().trim();
                    Matcher rm = YEAR_RANGE.matcher(text);
                    if (rm.find()) {
                        b.activeFrom(Integer.parseInt(rm.group(1)));
                        b.activeTo(Integer.parseInt(rm.group(2)));
                    } else {
                        Matcher sm = YEAR_SINGLE.matcher(text);
                        if (sm.find()) b.activeFrom(Integer.parseInt(sm.group(1)));
                    }
                }
                continue;
            }

            if (HAIR_HEADING.matcher(label).matches()) {
                Element biodata = nextBiodata(heading);
                if (biodata != null) b.hairColor(biodata.text().trim());
                continue;
            }

            // Simple string fields
            Element biodata = nextBiodata(heading);
            if (biodata == null) continue;
            String value = biodata.text().trim();
            if (value.isEmpty()) continue;

            switch (label.toLowerCase()) {
                case "birthday"         -> b.dateOfBirth(value);
                case "date of death"    -> b.dateOfDeath(value);
                case "birthplace"       -> b.birthplace(value);
                case "gender"           -> b.gender(value);
                case "ethnicity"        -> b.ethnicity(value);
                case "nationality"      -> b.nationality(value);
                case "eye color"        -> b.eyeColor(value);
                case "measurements"     -> b.measurements(parseMeasurements(value, b));
                case "cup size"         -> b.cup(value);
                case "shoe size"        -> b.shoeSize(value);
                case "tattoos"          -> b.tattoos(value);
                case "piercings"        -> b.piercings(value);
                case "website"          -> b.websiteUrl(biodata.selectFirst("a") != null
                        ? biodata.selectFirst("a").attr("href") : value);
                case "height" -> {
                    Matcher hm = HEIGHT_CM.matcher(value);
                    if (hm.find()) b.heightCm(Integer.parseInt(hm.group(1)));
                }
                case "weight" -> {
                    Matcher wm = WEIGHT_KG.matcher(value);
                    if (wm.find()) b.weightKg(Integer.parseInt(wm.group(1)));
                }
            }
        }

        // External refs — collect them all into one JSON object
        b.externalRefsJson(parseAllExternalRefs(doc));

        // Comments tab
        b.iafdCommentsJson(parseComments(doc));

        // Awards — parse all award org headings into a single JSON object
        b.awardsJson(parseAllAwards(doc));

        return b.build();
    }

    // ── AKA block ─────────────────────────────────────────────────────────────

    private String parseAkaBlock(Element heading) {
        // AKA uses <div class="biodata"> with <BR>-separated names
        Element div = nextElement(heading, "div.biodata");
        if (div == null) return "[]";

        String text = div.text().trim();
        if (text.equalsIgnoreCase("No known aliases")) return "[]";

        // Split on <br> nodes by iterating children
        List<String> names = new ArrayList<>();
        for (Node child : div.childNodes()) {
            if (child instanceof TextNode tn) {
                String name = tn.text().trim();
                if (!name.isEmpty()) names.add(name);
            }
            // <BR> elements act as separators — the text before/after is in TextNodes
        }

        if (names.isEmpty()) {
            // Fallback: split on whitespace lines or commas (shouldn't happen)
            for (String s : text.split("(?i)<br>|\\n")) {
                s = s.trim();
                if (!s.isEmpty()) names.add(s);
            }
        }

        return toJsonArray(names);
    }

    // ── Social / Platform icon blocks ─────────────────────────────────────────

    private String parseLinkIconBlock(Element heading, Map<String, String> iconMap) {
        // Links are loose siblings after the bioheading </p>, not wrapped in biodata
        Map<String, String> result = new LinkedHashMap<>();
        Element sib = heading.nextElementSibling();
        while (sib != null && !sib.hasClass("bioheading")) {
            Elements links = sib.tagName().equals("a") ? new Elements(sib) : sib.select("a");
            for (Element a : links) {
                Element img = a.selectFirst("img");
                if (img == null) continue;
                String src = img.attr("src");
                String filename = src.contains("/") ? src.substring(src.lastIndexOf('/') + 1) : src;
                String platform = iconMap.get(filename);
                if (platform != null) {
                    result.put(platform, a.attr("href"));
                }
            }
            sib = sib.nextElementSibling();
            if (sib != null && sib.is("p.bioheading")) break;
        }
        if (result.isEmpty()) return null;
        return toJsonObject(result);
    }

    // ── Awards block ──────────────────────────────────────────────────────────

    private String parseAllAwards(Document doc) {
        Map<String, List<Map<String, String>>> byOrg = new LinkedHashMap<>();
        Elements headings = doc.select("p.bioheading");
        for (Element h : headings) {
            String label = h.text().trim();
            if (!label.matches("(?i).*Awards.*") && !label.matches("(?i).*Hall of Fame.*")) continue;
            List<Map<String, String>> entries = parseAwardEntries(h);
            if (!entries.isEmpty()) byOrg.put(label, entries);
        }
        if (byOrg.isEmpty()) return null;
        return awardsMapToJson(byOrg);
    }

    private String parseAwardsBlock(Element heading, String orgName) {
        List<Map<String, String>> entries = parseAwardEntries(heading);
        if (entries.isEmpty()) return null;
        return awardsMapToJson(Map.of(orgName, entries));
    }

    private List<Map<String, String>> parseAwardEntries(Element heading) {
        List<Map<String, String>> entries = new ArrayList<>();
        String currentYear = null;
        Element sib = heading.nextElementSibling();
        while (sib != null) {
            if (sib.is("p.bioheading")) break;
            if (sib.is("div.showyear")) {
                currentYear = sib.text().trim();
            } else if (sib.is("div.biodata")) {
                String text = sib.text().trim();
                if (text.isEmpty()) { sib = sib.nextElementSibling(); continue; }

                String status;
                String rest;
                if (text.startsWith("Winner:")) {
                    status = "winner";
                    rest = text.substring("Winner:".length()).trim();
                } else if (text.startsWith("Nominee:")) {
                    status = "nominee";
                    rest = text.substring("Nominee:".length()).trim();
                } else if (text.startsWith("Inducted:")) {
                    status = "inducted";
                    rest = text.substring("Inducted:".length()).trim();
                } else {
                    status = "nominee";
                    rest = text;
                }

                Map<String, String> entry = new LinkedHashMap<>();
                if (currentYear != null) entry.put("year", currentYear);
                entry.put("status", status);
                entry.put("category", rest);

                // Check for a title link
                Element titleLink = sib.selectFirst("a[href*='title.rme']");
                if (titleLink != null) {
                    entry.put("title", titleLink.text().trim());
                }

                entries.add(entry);
            }
            sib = sib.nextElementSibling();
        }
        return entries;
    }

    // ── External refs ─────────────────────────────────────────────────────────

    private static final List<String> KNOWN_EXTERNAL_DBS = List.of(
            "egafd", "stashdb", "wikidata", "indexxx", "adultfilmdatabase"
    );

    private boolean isExternalDbHeading(String label) {
        String lower = label.toLowerCase();
        return KNOWN_EXTERNAL_DBS.stream().anyMatch(lower::contains);
    }

    private String parseExternalRef(Element heading, String label) {
        Element next = heading.nextElementSibling();
        if (next == null) return null;
        Element link = next.selectFirst("a");
        if (link == null) return null;
        return link.attr("href");
    }

    private String parseAllExternalRefs(Document doc) {
        Map<String, String> refs = new LinkedHashMap<>();
        for (Element h : doc.select("p.bioheading")) {
            String label = h.text().trim();
            if (!isExternalDbHeading(label)) continue;
            String url = parseExternalRef(h, label);
            if (url != null) refs.put(label.toLowerCase(), url);
        }
        return refs.isEmpty() ? null : toJsonObject(refs);
    }

    // ── Comments ──────────────────────────────────────────────────────────────

    private String parseComments(Document doc) {
        Element commentDiv = doc.selectFirst("div#comments");
        if (commentDiv == null) return null;
        List<String> comments = new ArrayList<>();
        for (Element li : commentDiv.select("li.cmt")) {
            String text = li.text().trim();
            if (!text.isEmpty()) comments.add(text);
        }
        return comments.isEmpty() ? null : toJsonArray(comments);
    }

    // ── Measurements helper ───────────────────────────────────────────────────

    private static final Pattern CUP_IN_MEASUREMENTS = Pattern.compile("(\\d+)([A-Z]+)-(\\d+)-(\\d+)");

    private String parseMeasurements(String value, IafdResolvedProfile.IafdResolvedProfileBuilder b) {
        // e.g. "32B-24-34" — cup letter can be extracted here if not present separately
        Matcher m = CUP_IN_MEASUREMENTS.matcher(value);
        if (m.find()) {
            b.cup(m.group(2));
        }
        return value;
    }

    // ── DOM helpers ───────────────────────────────────────────────────────────

    /** Returns the next sibling {@code <p class="biodata">} or {@code <div class="biodata">}. */
    private Element nextBiodata(Element heading) {
        Element sib = heading.nextElementSibling();
        if (sib == null) return null;
        if (sib.hasClass("biodata")) return sib;
        return null;
    }

    /** Returns the next sibling matching {@code cssSelector}. */
    private Element nextElement(Element el, String cssSelector) {
        Element sib = el.nextElementSibling();
        while (sib != null) {
            if (sib.is(cssSelector)) return sib;
            if (sib.is("p.bioheading")) break; // stop at next section
            sib = sib.nextElementSibling();
        }
        return null;
    }

    // ── JSON serialization (no Jackson dependency needed for simple structures) ─

    private String toJsonArray(List<String> items) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(jsonEscape(items.get(i))).append("\"");
        }
        sb.append("]");
        return sb.toString();
    }

    private String toJsonObject(Map<String, String> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> e : map.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(jsonEscape(e.getKey())).append("\"");
            sb.append(":");
            sb.append("\"").append(jsonEscape(e.getValue())).append("\"");
        }
        sb.append("}");
        return sb.toString();
    }

    private String awardsMapToJson(Map<String, List<Map<String, String>>> byOrg) {
        StringBuilder sb = new StringBuilder("{");
        boolean firstOrg = true;
        for (Map.Entry<String, List<Map<String, String>>> orgEntry : byOrg.entrySet()) {
            if (!firstOrg) sb.append(",");
            firstOrg = false;
            sb.append("\"").append(jsonEscape(orgEntry.getKey())).append("\":[");
            boolean firstEntry = true;
            for (Map<String, String> entry : orgEntry.getValue()) {
                if (!firstEntry) sb.append(",");
                firstEntry = false;
                sb.append("{");
                boolean firstField = true;
                for (Map.Entry<String, String> field : entry.entrySet()) {
                    if (!firstField) sb.append(",");
                    firstField = false;
                    sb.append("\"").append(jsonEscape(field.getKey())).append("\":");
                    sb.append("\"").append(jsonEscape(field.getValue())).append("\"");
                }
                sb.append("}");
            }
            sb.append("]");
        }
        sb.append("}");
        return sb.toString();
    }

    private String mergeAwardsJson(String existing, String org, String newEntriesJson) {
        // If we're accumulating via parseAllAwards, this method is not used in the main path.
        // Kept for potential incremental use.
        return newEntriesJson;
    }

    private String getField(IafdResolvedProfile.IafdResolvedProfileBuilder b, String field) {
        // Reflection not worth it; awardsJson is rebuilt fresh via parseAllAwards
        return null;
    }

    private String jsonEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
