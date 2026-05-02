package com.organizer3.javdb.draft;

import lombok.RequiredArgsConstructor;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/**
 * Persistence layer for {@code draft_title_javdb_enrichment}.
 *
 * <p>One enrichment row per draft title (1:1 via PK). Rows are upserted by
 * the DraftPopulator and cascade-deleted when the parent draft title is deleted.
 *
 * <p>Raw javdb tags are stored as JSON and resolved against the current alias
 * map only at promotion time (spec §6).
 *
 * <p>See spec/PROPOSAL_DRAFT_MODE.md §2 and §12.4.
 */
@RequiredArgsConstructor
public class DraftTitleEnrichmentRepository {

    private final Jdbi jdbi;

    static final RowMapper<DraftEnrichment> ROW_MAPPER = new RowMapper<DraftEnrichment>() {
        @Override
        public DraftEnrichment map(ResultSet rs, StatementContext ctx) throws SQLException {
            double ratingAvgRaw  = rs.getDouble("rating_avg");
            Double ratingAvg     = rs.wasNull() ? null : ratingAvgRaw;
            int    ratingCountRaw = rs.getInt("rating_count");
            Integer ratingCount  = rs.wasNull() ? null : ratingCountRaw;
            return DraftEnrichment.builder()
                    .draftTitleId(rs.getLong("draft_title_id"))
                    .javdbSlug(rs.getString("javdb_slug"))
                    .castJson(rs.getString("cast_json"))
                    .maker(rs.getString("maker"))
                    .series(rs.getString("series"))
                    .coverUrl(rs.getString("cover_url"))
                    .tagsJson(rs.getString("tags_json"))
                    .ratingAvg(ratingAvg)
                    .ratingCount(ratingCount)
                    .resolverSource(rs.getString("resolver_source"))
                    .updatedAt(rs.getString("updated_at"))
                    .build();
        }
    };

    /**
     * Inserts or replaces the enrichment row for the given draft title.
     *
     * <p>Uses {@code INSERT OR REPLACE} — the entire row is replaced on conflict.
     */
    public void upsert(long draftTitleId, DraftEnrichment enrichment) {
        jdbi.useHandle(h ->
                h.createUpdate("""
                        INSERT OR REPLACE INTO draft_title_javdb_enrichment
                            (draft_title_id, javdb_slug, cast_json, maker, series,
                             cover_url, tags_json, rating_avg, rating_count,
                             resolver_source, updated_at)
                        VALUES
                            (:draftTitleId, :javdbSlug, :castJson, :maker, :series,
                             :coverUrl, :tagsJson, :ratingAvg, :ratingCount,
                             :resolverSource, :updatedAt)
                        """)
                        .bind("draftTitleId",   draftTitleId)
                        .bind("javdbSlug",      enrichment.getJavdbSlug())
                        .bind("castJson",       enrichment.getCastJson())
                        .bind("maker",          enrichment.getMaker())
                        .bind("series",         enrichment.getSeries())
                        .bind("coverUrl",       enrichment.getCoverUrl())
                        .bind("tagsJson",       enrichment.getTagsJson())
                        .bind("ratingAvg",      enrichment.getRatingAvg())
                        .bind("ratingCount",    enrichment.getRatingCount())
                        .bind("resolverSource", enrichment.getResolverSource())
                        .bind("updatedAt",      enrichment.getUpdatedAt())
                        .execute());
    }

    /** Returns the enrichment row for the given draft title id, if present. */
    public Optional<DraftEnrichment> findByDraftId(long draftTitleId) {
        return jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT * FROM draft_title_javdb_enrichment
                        WHERE draft_title_id = :id
                        """)
                        .bind("id", draftTitleId)
                        .map(ROW_MAPPER)
                        .findOne());
    }
}
