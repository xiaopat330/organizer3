package com.organizer3.javdb.draft;

import lombok.RequiredArgsConstructor;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * Persistence layer for {@code draft_title_actresses}.
 *
 * <p>Each row represents one cast-slot resolution for a draft title.
 * The {@link #replaceForDraft} operation deletes all existing rows for the
 * given draft and inserts the new set atomically — callers always supply the
 * full resolved cast list.
 *
 * <p>See spec/PROPOSAL_DRAFT_MODE.md §5.2 and §12.4.
 */
@RequiredArgsConstructor
public class DraftTitleActressesRepository {

    private final Jdbi jdbi;

    static final RowMapper<DraftTitleActress> ROW_MAPPER = new RowMapper<DraftTitleActress>() {
        @Override
        public DraftTitleActress map(ResultSet rs, StatementContext ctx) throws SQLException {
            return new DraftTitleActress(
                    rs.getLong("draft_title_id"),
                    rs.getString("javdb_slug"),
                    rs.getString("resolution"));
        }
    };

    /**
     * Atomically replaces all cast-slot rows for the given draft title.
     *
     * <p>All existing {@code draft_title_actresses} rows for {@code draftTitleId}
     * are deleted, then the supplied {@code resolutions} list is inserted in order.
     * If {@code resolutions} is empty the result is an empty cast list.
     *
     * @param draftTitleId FK to {@code draft_titles.id}.
     * @param resolutions  new complete cast-slot list; must not be null.
     */
    public void replaceForDraft(long draftTitleId, List<DraftTitleActress> resolutions) {
        jdbi.useTransaction(h -> {
            h.createUpdate("DELETE FROM draft_title_actresses WHERE draft_title_id = :id")
                    .bind("id", draftTitleId)
                    .execute();

            for (DraftTitleActress r : resolutions) {
                h.createUpdate("""
                        INSERT INTO draft_title_actresses (draft_title_id, javdb_slug, resolution)
                        VALUES (:draftTitleId, :javdbSlug, :resolution)
                        """)
                        .bind("draftTitleId", draftTitleId)
                        .bind("javdbSlug",    r.getJavdbSlug())
                        .bind("resolution",   r.getResolution())
                        .execute();
            }
        });
    }

    /**
     * Returns all cast-slot rows for the given draft title, in insertion order.
     */
    public List<DraftTitleActress> findByDraftTitleId(long draftTitleId) {
        return jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT * FROM draft_title_actresses
                        WHERE draft_title_id = :id
                        """)
                        .bind("id", draftTitleId)
                        .map(ROW_MAPPER)
                        .list());
    }
}
