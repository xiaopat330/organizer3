package com.organizer3.javdb.draft;

import lombok.RequiredArgsConstructor;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * Persistence layer for {@code draft_actresses}.
 *
 * <p>Rows are keyed by {@code javdb_slug} and shared across all active drafts.
 * The ref-count is maintained implicitly via {@code draft_title_actresses} foreign
 * keys. Orphan rows (zero references) are cleaned up by {@link #reapOrphans()}.
 *
 * <p>See spec/PROPOSAL_DRAFT_MODE.md §12.4.
 */
@RequiredArgsConstructor
public class DraftActressRepository {

    private final Jdbi jdbi;

    static final RowMapper<DraftActress> ROW_MAPPER = new RowMapper<DraftActress>() {
        @Override
        public DraftActress map(ResultSet rs, StatementContext ctx) throws SQLException {
            long linkIdRaw = rs.getLong("link_to_existing_id");
            Long linkId    = rs.wasNull() ? null : linkIdRaw;
            return DraftActress.builder()
                    .javdbSlug(rs.getString("javdb_slug"))
                    .stageName(rs.getString("stage_name"))
                    .englishFirstName(rs.getString("english_first_name"))
                    .englishLastName(rs.getString("english_last_name"))
                    .linkToExistingId(linkId)
                    .linkToDraftSlug(rs.getString("link_to_draft_slug"))
                    .createdAt(rs.getString("created_at"))
                    .updatedAt(rs.getString("updated_at"))
                    .lastValidationError(rs.getString("last_validation_error"))
                    .build();
        }
    };

    /**
     * Inserts or updates a draft actress row by slug (upsert).
     *
     * <p>On conflict the existing row is replaced wholesale. The
     * {@code stage_name} is treated as immutable once first written — callers
     * should populate it from javdb and not overwrite it on subsequent upserts.
     * All other fields are updated to the supplied values.
     */
    public void upsertBySlug(DraftActress actress) {
        jdbi.useHandle(h ->
                h.createUpdate("""
                        INSERT INTO draft_actresses
                            (javdb_slug, stage_name, english_first_name, english_last_name,
                             link_to_existing_id, created_at, updated_at, last_validation_error)
                        VALUES
                            (:javdbSlug, :stageName, :englishFirstName, :englishLastName,
                             :linkToExistingId, :createdAt, :updatedAt, :lastValidationError)
                        ON CONFLICT(javdb_slug) DO UPDATE SET
                            english_first_name    = excluded.english_first_name,
                            english_last_name     = excluded.english_last_name,
                            link_to_existing_id   = excluded.link_to_existing_id,
                            updated_at            = excluded.updated_at,
                            last_validation_error = excluded.last_validation_error
                        """)
                        .bind("javdbSlug",           actress.getJavdbSlug())
                        .bind("stageName",            actress.getStageName())
                        .bind("englishFirstName",     actress.getEnglishFirstName())
                        .bind("englishLastName",      actress.getEnglishLastName())
                        .bind("linkToExistingId",     actress.getLinkToExistingId())
                        .bind("createdAt",            actress.getCreatedAt())
                        .bind("updatedAt",            actress.getUpdatedAt())
                        .bind("lastValidationError",  actress.getLastValidationError())
                        .execute());
    }

    /** Returns the draft actress with the given javdb slug, if present. */
    public Optional<DraftActress> findBySlug(String javdbSlug) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT * FROM draft_actresses WHERE javdb_slug = :slug")
                        .bind("slug", javdbSlug)
                        .map(ROW_MAPPER)
                        .findOne());
    }

    private static final DateTimeFormatter ISO_UTC =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    /**
     * Cosmetic fill of English name fields for drafts already linked to an existing Actress.
     * Returns the number of rows updated.
     *
     * <p>The {@code link_to_existing_id IS NOT NULL} guard is load-bearing — see
     * PROPOSAL_NEAR_MISS_RESOLVER.md §12. Without it, unlinked drafts would auto-fill
     * English fields and then promote as new canonical Actresses, producing one duplicate
     * per draft for the same kanji. Only already-linked drafts get cosmetic fill here;
     * unlinked drafts must go through the near-miss tool.
     */
    public int fillEnglishNameByKanji(String kanji, String englishFirst, String englishLast) {
        String now = ISO_UTC.format(Instant.now());
        return jdbi.withHandle(h ->
                h.createUpdate("""
                        UPDATE draft_actresses
                           SET english_first_name = :first,
                               english_last_name  = :last,
                               updated_at         = :now
                         WHERE stage_name           = :kanji
                           AND english_last_name    IS NULL
                           AND link_to_existing_id IS NOT NULL
                        """)
                        .bind("first", englishFirst)
                        .bind("last", englishLast)
                        .bind("now", now)
                        .bind("kanji", kanji)
                        .execute());
    }

    /**
     * Cascade UPDATE for outcome ALIAS: links all unresolved drafts for a kanji to an existing actress.
     * Only rows where both {@code link_to_existing_id} and {@code link_to_draft_slug} are NULL
     * are updated — already-resolved drafts are never overwritten.
     *
     * @return number of rows updated
     */
    public int cascadeAliasResolution(String normalizedKanji, long existingActressId,
                                      String englishFirst, String englishLast) {
        String now = ISO_UTC.format(Instant.now());
        return jdbi.withHandle(h ->
                h.createUpdate("""
                        UPDATE draft_actresses
                           SET link_to_existing_id  = :xId,
                               english_first_name   = :first,
                               english_last_name    = :last,
                               updated_at           = :now
                         WHERE stage_name           = :kanji
                           AND link_to_existing_id  IS NULL
                           AND link_to_draft_slug   IS NULL
                        """)
                        .bind("xId",   existingActressId)
                        .bind("first", englishFirst)
                        .bind("last",  englishLast)
                        .bind("now",   now)
                        .bind("kanji", normalizedKanji)
                        .execute());
    }

    /**
     * Cascade UPDATE for outcome CANONICAL: updates the primary draft and all unresolved siblings.
     *
     * <p>The primary draft must exist and be unresolved before calling; verify via
     * {@link #findBySlug} beforehand. Returns the total number of rows updated
     * (1 for the primary + N for siblings).
     *
     * @return total rows updated
     */
    public int cascadeCanonicalResolution(String normalizedKanji, String primarySlug,
                                          String englishFirst, String englishLast) {
        String now = ISO_UTC.format(Instant.now());
        return jdbi.inTransaction(h -> {
            int primary = h.createUpdate("""
                            UPDATE draft_actresses
                               SET english_first_name = :first,
                                   english_last_name  = :last,
                                   updated_at         = :now
                             WHERE javdb_slug         = :primarySlug
                            """)
                    .bind("first",       englishFirst)
                    .bind("last",        englishLast)
                    .bind("now",         now)
                    .bind("primarySlug", primarySlug)
                    .execute();

            int siblings = h.createUpdate("""
                            UPDATE draft_actresses
                               SET link_to_draft_slug = :primarySlug,
                                   english_first_name = :first,
                                   english_last_name  = :last,
                                   updated_at         = :now
                             WHERE stage_name          = :kanji
                               AND javdb_slug         <> :primarySlug
                               AND link_to_existing_id IS NULL
                               AND link_to_draft_slug  IS NULL
                            """)
                    .bind("primarySlug", primarySlug)
                    .bind("first",       englishFirst)
                    .bind("last",        englishLast)
                    .bind("now",         now)
                    .bind("kanji",       normalizedKanji)
                    .execute();

            return primary + siblings;
        });
    }

    /**
     * Count of unresolved draft rows for a kanji (those with no identity link and no last name).
     * Used by the confirm step in the near-miss modal and by the pending-kanji aggregator.
     */
    public int countUnresolvedByKanji(String normalizedKanji) {
        return jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT COUNT(*) FROM draft_actresses
                         WHERE stage_name           = :kanji
                           AND link_to_existing_id  IS NULL
                           AND link_to_draft_slug   IS NULL
                           AND english_last_name    IS NULL
                        """)
                        .bind("kanji", normalizedKanji)
                        .mapTo(Integer.class)
                        .one());
    }

    /**
     * Aggregate of all pending-kanji groups: distinct unresolved stage names, their draft counts,
     * and the oldest {@code created_at} in each group. Sorted by count DESC, oldest_seen ASC.
     */
    public java.util.List<PendingKanjiRow> findPendingKanjiGroups() {
        return jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT stage_name   AS kanji,
                               COUNT(*)     AS cnt,
                               MIN(created_at) AS oldest_seen
                          FROM draft_actresses
                         WHERE link_to_existing_id  IS NULL
                           AND link_to_draft_slug   IS NULL
                           AND english_last_name    IS NULL
                         GROUP BY stage_name
                         ORDER BY cnt DESC, oldest_seen ASC
                        """)
                        .map((rs, ctx) -> new PendingKanjiRow(
                                rs.getString("kanji"),
                                rs.getInt("cnt"),
                                rs.getString("oldest_seen")))
                        .list());
    }

    /** Lightweight projection for the pending-kanji aggregator. */
    public record PendingKanjiRow(String kanji, int count, String oldestSeen) {}

    /**
     * Deletes {@code draft_actresses} rows that are no longer referenced by any
     * {@code draft_title_actresses} row (ref-count = 0).
     *
     * <p>This is the GC sweep for orphan actress records. Should be run after
     * any draft deletion (or periodically via the GC cron).
     *
     * @return the number of orphan rows deleted.
     */
    public int reapOrphans() {
        return jdbi.withHandle(h ->
                h.createUpdate("""
                        DELETE FROM draft_actresses
                        WHERE javdb_slug NOT IN (
                            SELECT DISTINCT javdb_slug FROM draft_title_actresses
                        )
                        """)
                        .execute());
    }
}
