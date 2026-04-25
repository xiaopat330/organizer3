package com.organizer3.javdb;

import java.util.List;
import java.util.Optional;

/**
 * Looks up an actress's kanji stage name from javdb using a known solo product code.
 *
 * <p>The lookup is two HTTP requests: search by code → get title slug, then fetch the
 * title detail page → extract the single actress attribution.
 */
public class JavdbStageLookup {

    private final JavdbClient client;
    private final JavdbSearchParser searchParser;
    private final JavdbTitleParser titleParser;

    public JavdbStageLookup(JavdbClient client) {
        this(client, new JavdbSearchParser(), new JavdbTitleParser());
    }

    JavdbStageLookup(JavdbClient client, JavdbSearchParser searchParser, JavdbTitleParser titleParser) {
        this.client = client;
        this.searchParser = searchParser;
        this.titleParser = titleParser;
    }

    /**
     * Given a product code (e.g. {@code "ABP-123"}), searches javdb, fetches the title page,
     * and returns the single actress if exactly one is attributed. Returns empty if the title
     * is not found, or if the title lists zero or more than one actress (not a solo title).
     */
    public Optional<JavdbActress> lookupSoloActress(String code) {
        Optional<String> slug = searchParser.parseFirstSlug(client.searchByCode(code));
        if (slug.isEmpty()) return Optional.empty();

        List<JavdbActress> actresses = titleParser.parseActresses(client.fetchTitlePage(slug.get()));
        return actresses.size() == 1 ? Optional.of(actresses.get(0)) : Optional.empty();
    }
}
