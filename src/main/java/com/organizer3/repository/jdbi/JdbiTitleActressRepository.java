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
