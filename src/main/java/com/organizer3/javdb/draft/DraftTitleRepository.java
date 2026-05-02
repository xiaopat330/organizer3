package com.organizer3.javdb.draft;

import lombok.RequiredArgsConstructor;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * Persistence layer for {@code draft_titles}.
 *
 * <p>One active draft per title is enforced by a unique index on
 * {@code draft_titles(title_id)}; {@link #insert} will throw a
 * {@link org.jdbi.v3.core.statement.UnableToExecuteStatementException}
 * (unique-constraint violation) if a draft already exists for the given title.
 *
 * <p>Updates use optimistic locking: the caller supplies the last-known
 * {@code updatedAt} token; if the row has been modified since, an
 * {@link OptimisticLockException} is thrown.
 *
 * <p>See spec/PROPOSAL_DRAFT_MODE.md §12.4.
 */
@RequiredArgsConstructor
public class DraftTitleRepository {

    private final Jdbi jdbi;

    static final RowMapper<DraftTitle> ROW_MAPPER = new RowMapper<DraftTitle>() {
        @Override
        public DraftTitle map(ResultSet rs, StatementContext ctx) throws SQLException {
            return DraftTitle.builder()
                    .id(rs.getLong("id"))
                    .titleId(rs.getLong("title_id"))
                    .code(rs.getString("code"))
                    .titleOriginal(rs.getString("title_original"))
                    .titleEnglish(rs.getString("title_english"))
                    .releaseDate(rs.getString("release_date"))
                    .notes(rs.getString("notes"))
                    .grade(rs.getString("grade"))
                    .gradeSource(rs.getString("grade_source"))
                    .upstreamChanged(rs.getInt("upstream_changed") == 1)
                    .lastValidationError(rs.getString("last_validation_error"))
                    .createdAt(rs.getString("created_at"))
                    .updatedAt(rs.getString("updated_at"))
                    .build();
        }
    };

    /**
     * Inserts a new draft title row and returns its generated id.
     *
     * @throws org.jdbi.v3.core.statement.UnableToExecuteStatementException if a draft
     *         already exists for {@code draft.titleId()} (unique index violation).
     */
    public long insert(DraftTitle draft) {
        return jdbi.withHandle(h ->
                h.createUpdate("""
                        INSERT INTO draft_titles
                            (title_id, code, title_original, title_english, release_date,
                             notes, grade, grade_source, upstream_changed,
                             last_validation_error, created_at, updated_at)
                        VALUES
                            (:titleId, :code, :titleOriginal, :titleEnglish, :releaseDate,
                             :notes, :grade, :gradeSource, :upstreamChanged,
                             :lastValidationError, :createdAt, :updatedAt)
                        """)
                        .bind("titleId",             draft.getTitleId())
                        .bind("code",                draft.getCode())
                        .bind("titleOriginal",        draft.getTitleOriginal())
                        .bind("titleEnglish",         draft.getTitleEnglish())
                        .bind("releaseDate",          draft.getReleaseDate())
                        .bind("notes",                draft.getNotes())
                        .bind("grade",                draft.getGrade())
                        .bind("gradeSource",          draft.getGradeSource())
                        .bind("upstreamChanged",      draft.isUpstreamChanged() ? 1 : 0)
                        .bind("lastValidationError",  draft.getLastValidationError())
                        .bind("createdAt",            draft.getCreatedAt())
                        .bind("updatedAt",            draft.getUpdatedAt())
                        .executeAndReturnGeneratedKeys("id")
                        .mapTo(Long.class)
                        .one());
    }

    /** Returns the draft with the given surrogate id, if present. */
    public Optional<DraftTitle> findById(long id) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT * FROM draft_titles WHERE id = :id")
                        .bind("id", id)
                        .map(ROW_MAPPER)
                        .findOne());
    }

    /** Returns the active draft for the given canonical title id, if present. */
    public Optional<DraftTitle> findByTitleId(long titleId) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT * FROM draft_titles WHERE title_id = :titleId")
                        .bind("titleId", titleId)
                        .map(ROW_MAPPER)
                        .findOne());
    }

    /**
     * Updates all editable columns on the draft row identified by {@code draft.id()}.
     *
     * <p>Optimistic locking: the update is conditional on the current
     * {@code updated_at} equalling {@code expectedUpdatedAt}. If zero rows are
     * affected the row was either missing or modified concurrently — an
     * {@link OptimisticLockException} is thrown in either case.
     *
     * @throws OptimisticLockException if the row was not found or was modified concurrently.
     */
    public void update(DraftTitle draft, String expectedUpdatedAt) {
        int rows = jdbi.withHandle(h ->
                h.createUpdate("""
                        UPDATE draft_titles SET
                            title_original        = :titleOriginal,
                            title_english         = :titleEnglish,
                            release_date          = :releaseDate,
                            notes                 = :notes,
                            grade                 = :grade,
                            grade_source          = :gradeSource,
                            upstream_changed      = :upstreamChanged,
                            last_validation_error = :lastValidationError,
                            updated_at            = :updatedAt
                        WHERE id = :id
                          AND updated_at = :expectedUpdatedAt
                        """)
                        .bind("id",                   draft.getId())
                        .bind("titleOriginal",         draft.getTitleOriginal())
                        .bind("titleEnglish",          draft.getTitleEnglish())
                        .bind("releaseDate",           draft.getReleaseDate())
                        .bind("notes",                 draft.getNotes())
                        .bind("grade",                 draft.getGrade())
                        .bind("gradeSource",           draft.getGradeSource())
                        .bind("upstreamChanged",       draft.isUpstreamChanged() ? 1 : 0)
                        .bind("lastValidationError",   draft.getLastValidationError())
                        .bind("updatedAt",             draft.getUpdatedAt())
                        .bind("expectedUpdatedAt",     expectedUpdatedAt)
                        .execute());
        if (rows == 0) {
            throw new OptimisticLockException(
                    "draft_titles update failed for id=" + draft.getId() +
                    ": row not found or updated_at mismatch (expected " + expectedUpdatedAt + ")");
        }
    }

    /** Deletes the draft row with the given id. Cascades to child rows. */
    public void delete(long id) {
        jdbi.useHandle(h ->
                h.createUpdate("DELETE FROM draft_titles WHERE id = :id")
                        .bind("id", id)
                        .execute());
    }

    /**
     * Returns a page of all active draft titles ordered by {@code created_at} descending.
     *
     * @param offset zero-based row offset.
     * @param limit  maximum rows to return.
     */
    public List<DraftTitle> listAll(int offset, int limit) {
        return jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT * FROM draft_titles
                        ORDER BY created_at DESC
                        LIMIT :limit OFFSET :offset
                        """)
                        .bind("limit",  limit)
                        .bind("offset", offset)
                        .map(ROW_MAPPER)
                        .list());
    }

    /**
     * Marks {@code upstream_changed = 1} for any active draft whose canonical title
     * matches the given {@code titleId}.
     *
     * <p>Called by the sync hook when a synced title has an active draft (Phase 5).
     * Updates {@code updated_at} so the optimistic-lock token reflects the change.
     */
    public void setUpstreamChanged(long titleId, String nowIso) {
        jdbi.useHandle(h ->
                h.createUpdate("""
                        UPDATE draft_titles
                        SET upstream_changed = 1,
                            updated_at       = :now
                        WHERE title_id = :titleId
                        """)
                        .bind("titleId", titleId)
                        .bind("now",     nowIso)
                        .execute());
    }

    /**
     * Deletes draft rows older than {@code maxAgeDays} days.
     *
     * @return the number of rows deleted.
     */
    public int reapStale(int maxAgeDays) {
        // Filter on updated_at, not created_at, so drafts the user is actively
        // editing survive even if first created long ago. See spec §9.2.
        return jdbi.withHandle(h ->
                h.createUpdate("""
                        DELETE FROM draft_titles
                        WHERE updated_at < datetime('now', '-' || :days || ' days')
                        """)
                        .bind("days", maxAgeDays)
                        .execute());
    }
}
