package com.organizer3.repository.jdbi;

import com.organizer3.repository.TitleActressRepository;
import lombok.RequiredArgsConstructor;
import org.jdbi.v3.core.Jdbi;

import java.util.List;

@RequiredArgsConstructor
public class JdbiTitleActressRepository implements TitleActressRepository {

    private final Jdbi jdbi;

    @Override
    public void link(long titleId, long actressId) {
        jdbi.useHandle(h ->
                h.createUpdate("""
                        INSERT OR IGNORE INTO title_actresses (title_id, actress_id)
                        VALUES (:titleId, :actressId)
                        """)
                        .bind("titleId", titleId)
                        .bind("actressId", actressId)
                        .execute()
        );
    }

    @Override
    public void linkAll(long titleId, List<Long> actressIds) {
        if (actressIds.isEmpty()) return;
        jdbi.useHandle(h -> {
            for (long actressId : actressIds) {
                h.createUpdate("""
                        INSERT OR IGNORE INTO title_actresses (title_id, actress_id)
                        VALUES (:titleId, :actressId)
                        """)
                        .bind("titleId", titleId)
                        .bind("actressId", actressId)
                        .execute();
            }
        });
    }

    @Override
    public List<Long> findActressIdsByTitle(long titleId) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT actress_id FROM title_actresses WHERE title_id = :titleId")
                        .bind("titleId", titleId)
                        .mapTo(Long.class)
                        .list()
        );
    }

    @Override
    public List<TitleActressRepository.CreditEntry> findCreditsByTitle(long titleId) {
        return jdbi.withHandle(h -> {
            // age_at_release was added in schema V69; defend against older in-memory test DBs
            // by checking if the column exists before trying to read it.
            boolean hasAgeColumn = h.createQuery(
                            "SELECT COUNT(*) FROM pragma_table_info('title_actresses') WHERE name='age_at_release'")
                    .mapTo(Integer.class).one() > 0;
            String sql = hasAgeColumn
                    ? "SELECT actress_id, age_at_release FROM title_actresses WHERE title_id = :titleId"
                    : "SELECT actress_id, NULL AS age_at_release FROM title_actresses WHERE title_id = :titleId";
            return h.createQuery(sql)
                    .bind("titleId", titleId)
                    .map((rs, ctx) -> new TitleActressRepository.CreditEntry(
                            rs.getLong("actress_id"),
                            rs.getObject("age_at_release") != null ? rs.getInt("age_at_release") : null))
                    .list();
        });
    }

    @Override
    public int unlink(long titleId, long actressId) {
        return jdbi.withHandle(h ->
                h.createUpdate("""
                        DELETE FROM title_actresses
                        WHERE title_id = :titleId AND actress_id = :actressId
                        """)
                        .bind("titleId", titleId)
                        .bind("actressId", actressId)
                        .execute()
        );
    }

    @Override
    public void deleteOrphaned() {
        jdbi.useHandle(h ->
                h.createUpdate("""
                        DELETE FROM title_actresses
                        WHERE title_id NOT IN (SELECT id FROM titles)
                        """)
                        .execute()
        );
    }
}
