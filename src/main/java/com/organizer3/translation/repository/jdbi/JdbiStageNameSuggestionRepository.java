package com.organizer3.translation.repository.jdbi;

import com.organizer3.translation.StageNameSuggestionRow;
import com.organizer3.translation.repository.StageNameSuggestionRepository;
import lombok.RequiredArgsConstructor;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.mapper.RowMapper;

import java.util.List;
import java.util.Optional;

@RequiredArgsConstructor
public class JdbiStageNameSuggestionRepository implements StageNameSuggestionRepository {

    private static final RowMapper<StageNameSuggestionRow> MAPPER = (rs, ctx) -> new StageNameSuggestionRow(
            rs.getLong("id"),
            rs.getString("kanji_form"),
            rs.getString("suggested_romaji"),
            rs.getString("suggested_at"),
            rs.getString("reviewed_at"),
            rs.getString("review_decision"),
            rs.getString("final_romaji")
    );

    private final Jdbi jdbi;

    @Override
    public void recordSuggestion(String kanjiForm, String suggestedRomaji, String suggestedAt) {
        // Idempotent: ignore duplicate (kanji_form, suggested_romaji) pairs.
        jdbi.useHandle(h ->
                h.createUpdate("""
                        INSERT OR IGNORE INTO stage_name_suggestion
                            (kanji_form, suggested_romaji, suggested_at)
                        VALUES (:kanjiForm, :suggestedRomaji, :suggestedAt)
                        """)
                        .bind("kanjiForm", kanjiForm)
                        .bind("suggestedRomaji", suggestedRomaji)
                        .bind("suggestedAt", suggestedAt)
                        .execute()
        );
    }

    @Override
    public List<StageNameSuggestionRow> findByKanji(String kanjiForm) {
        return jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT id, kanji_form, suggested_romaji, suggested_at,
                               reviewed_at, review_decision, final_romaji
                        FROM stage_name_suggestion
                        WHERE kanji_form = :kanjiForm
                        ORDER BY suggested_at DESC
                        """)
                        .bind("kanjiForm", kanjiForm)
                        .map(MAPPER)
                        .list()
        );
    }

    @Override
    public Optional<String> findAcceptedRomaji(String kanjiForm) {
        return jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT COALESCE(final_romaji, suggested_romaji)
                        FROM stage_name_suggestion
                        WHERE kanji_form = :kanjiForm
                          AND review_decision = 'accepted'
                        ORDER BY reviewed_at DESC
                        LIMIT 1
                        """)
                        .bind("kanjiForm", kanjiForm)
                        .mapTo(String.class)
                        .findFirst()
        );
    }

    @Override
    public List<StageNameSuggestionRow> findUnreviewed(int limit) {
        return jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT id, kanji_form, suggested_romaji, suggested_at,
                               reviewed_at, review_decision, final_romaji
                        FROM stage_name_suggestion
                        WHERE review_decision IS NULL
                        ORDER BY suggested_at DESC
                        LIMIT :limit
                        """)
                        .bind("limit", limit)
                        .map(MAPPER)
                        .list()
        );
    }

    @Override
    public long countUnreviewed() {
        return jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT COUNT(*)
                        FROM stage_name_suggestion
                        WHERE review_decision IS NULL
                        """)
                        .mapTo(Long.class)
                        .one()
        );
    }
}
