package com.organizer3.repository.jdbi;

import com.organizer3.repository.TitleTagRepository;
import lombok.RequiredArgsConstructor;
import org.jdbi.v3.core.Jdbi;

import java.util.List;

@RequiredArgsConstructor
public class JdbiTitleTagRepository implements TitleTagRepository {

    private final Jdbi jdbi;

    @Override
    public List<String> findTagsForTitle(long titleId) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT tag FROM title_tags WHERE title_id = :titleId ORDER BY tag")
                        .bind("titleId", titleId)
                        .mapTo(String.class)
                        .list()
        );
    }

    @Override
    public void replaceTagsForTitle(long titleId, List<String> tags) {
        jdbi.useTransaction(h -> {
            h.createUpdate("DELETE FROM title_tags WHERE title_id = :titleId")
                    .bind("titleId", titleId)
                    .execute();
            for (String tag : tags) {
                h.createUpdate("INSERT INTO title_tags (title_id, tag) VALUES (:titleId, :tag)")
                        .bind("titleId", titleId)
                        .bind("tag", tag)
                        .execute();
            }
        });
    }

    @Override
    public List<Long> findTitleIdsByTag(String tag) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT title_id FROM title_tags WHERE tag = :tag")
                        .bind("tag", tag)
                        .mapTo(Long.class)
                        .list()
        );
    }

    @Override
    public List<Long> findTitleIdsByTagAndActress(String tag, long actressId) {
        return jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT tt.title_id FROM title_tags tt
                        JOIN titles t ON t.id = tt.title_id
                        WHERE tt.tag = :tag AND t.actress_id = :actressId
                        """)
                        .bind("tag", tag)
                        .bind("actressId", actressId)
                        .mapTo(Long.class)
                        .list()
        );
    }
}
