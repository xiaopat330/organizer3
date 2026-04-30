package com.organizer3.javdb.enrichment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.organizer3.javdb.JavdbClient;
import com.organizer3.javdb.JavdbSearchParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.Jdbi;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Builds a candidate snapshot for the disambiguation picker UI.
 *
 * <p>When the write-time gate routes a title to the {@code ambiguous} bucket, this class
 * fetches all search-result slugs for the product code and assembles them into the
 * {@code enrichment_review_queue.detail} JSON. The UI then renders side-by-side cards
 * without needing to re-fetch at render time.
 *
 * <p>Design choice: the snapshotter is a thin helper that re-searches and fetches N candidate
 * pages rather than a deep refactor of {@link EnrichmentRunner}. This adds N extra HTTP
 * requests when a title lands in the ambiguous bucket, but that path is rare (code-reuse
 * collisions), so the cost is acceptable. See spec §3A for trade-offs.
 */
@Slf4j
@RequiredArgsConstructor
public class DisambiguationSnapshotter {

    private final JavdbClient client;
    private final JavdbExtractor extractor;
    private final JavdbSearchParser searchParser;
    private final JavdbStagingRepository stagingRepo;
    private final Jdbi jdbi;
    private final ObjectMapper json;

    /**
     * Builds the candidate snapshot JSON for a product code.
     *
     * <p>The initial slug/extract (already fetched by the runner) is incorporated without
     * a second HTTP call. Any additional slugs from the search results page are fetched now
     * — one HTTP request per extra candidate.
     *
     * @param titleId        DB id of the title (used to look up linked actress slugs)
     * @param productCode    the bare product code (e.g. {@code "STAR-334"})
     * @param initialSlug    the slug already fetched by the runner (may be null for sentinel rows)
     * @param initialExtract the extract already fetched (may be null if initialSlug is null)
     * @return JSON string conforming to the snapshot schema, or null on complete failure
     */
    public String buildSnapshot(long titleId, String productCode,
                                String initialSlug, TitleExtract initialExtract) {
        try {
            // Gather all candidate slugs: initialSlug first, then any extras from search.
            LinkedHashSet<String> slugOrder = new LinkedHashSet<>();
            if (initialSlug != null) slugOrder.add(initialSlug);

            try {
                List<String> searchSlugs = searchParser.parseAllSlugs(
                        client.searchByCode(productCode));
                slugOrder.addAll(searchSlugs);
            } catch (Exception e) {
                log.warn("disambiguation: search re-fetch failed for {} — using initial slug only: {}",
                        productCode, e.getMessage());
            }

            if (slugOrder.isEmpty()) {
                log.warn("disambiguation: no candidates found for {} — snapshot will be empty", productCode);
            }

            List<String> linkedSlugs = getLinkedActressSlugs(titleId);

            ArrayNode candidates = json.createArrayNode();
            for (String slug : slugOrder) {
                TitleExtract extract;
                if (slug.equals(initialSlug) && initialExtract != null) {
                    extract = initialExtract;
                } else {
                    try {
                        String html = client.fetchTitlePage(slug);
                        extract = extractor.extractTitle(html, productCode, slug);
                    } catch (Exception e) {
                        log.warn("disambiguation: failed to fetch candidate {} for {} — skipping: {}",
                                slug, productCode, e.getMessage());
                        continue;
                    }
                }
                candidates.add(buildCandidateNode(slug, extract));
            }

            ObjectNode snapshot = json.createObjectNode();
            snapshot.put("code", productCode);
            ArrayNode linkedArr = snapshot.putArray("linked_slugs");
            linkedSlugs.forEach(linkedArr::add);
            snapshot.set("candidates", candidates);
            snapshot.put("fetched_at", Instant.now().toString());
            return json.writeValueAsString(snapshot);
        } catch (Exception e) {
            log.error("disambiguation: snapshot build failed for {}: {}", productCode, e.getMessage(), e);
            return null;
        }
    }

    private ObjectNode buildCandidateNode(String slug, TitleExtract extract) {
        ObjectNode node = json.createObjectNode();
        node.put("slug",           slug);
        node.put("title_original", extract.titleOriginal());
        node.put("release_date",   extract.releaseDate());
        node.put("maker",          extract.maker());
        node.put("publisher",      extract.publisher());
        node.put("series",         extract.series());
        node.put("cover_url",      extract.coverUrl());
        if (extract.durationMinutes() != null) node.put("duration_minutes", extract.durationMinutes());
        if (extract.ratingAvg()       != null) node.put("rating_avg",       extract.ratingAvg());
        if (extract.ratingCount()     != null) node.put("rating_count",     extract.ratingCount());
        node.put("fetched_at", extract.fetchedAt());
        node.put("cast_empty", extract.castEmpty());

        ArrayNode castArr = node.putArray("cast");
        if (extract.cast() != null) {
            for (TitleExtract.CastEntry e : extract.cast()) {
                ObjectNode ce = json.createObjectNode();
                ce.put("slug",   e.slug());
                ce.put("name",   e.name());
                ce.put("gender", e.gender());
                castArr.add(ce);
            }
        }

        ArrayNode tagsArr = node.putArray("tags");
        if (extract.tags() != null) extract.tags().forEach(tagsArr::add);

        ArrayNode thumbsArr = node.putArray("thumbnail_urls");
        if (extract.thumbnailUrls() != null) extract.thumbnailUrls().forEach(thumbsArr::add);

        return node;
    }

    /**
     * Returns the javdb actress slugs of all non-sentinel actresses linked to this title.
     * Used by the picker to highlight matching cast entries.
     */
    private List<String> getLinkedActressSlugs(long titleId) {
        try {
            return jdbi.withHandle(h -> {
                List<Long> actressIds = h.createQuery("""
                        SELECT ta.actress_id FROM title_actresses ta
                        JOIN actresses a ON a.id = ta.actress_id
                        WHERE ta.title_id = :titleId AND (a.is_sentinel IS NULL OR a.is_sentinel = 0)
                        """)
                        .bind("titleId", titleId)
                        .mapTo(Long.class)
                        .list();

                List<String> slugs = new ArrayList<>();
                for (long id : actressIds) {
                    stagingRepo.findActressStaging(id)
                            .map(JavdbActressStagingRow::javdbSlug)
                            .filter(s -> s != null && !s.isBlank())
                            .ifPresent(slugs::add);
                }
                return slugs;
            });
        } catch (Exception e) {
            log.warn("disambiguation: failed to get linked slugs for title {}: {}", titleId, e.getMessage());
            return List.of();
        }
    }
}
