package com.organizer3.javdb.draft;

import lombok.RequiredArgsConstructor;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;

import java.sql.ResultSet;
import java.sql.SQLException;
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
