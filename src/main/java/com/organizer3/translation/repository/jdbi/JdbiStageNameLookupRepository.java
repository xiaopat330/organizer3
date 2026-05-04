package com.organizer3.translation.repository.jdbi;

import com.organizer3.translation.StageNameLookupRow;
import com.organizer3.translation.repository.StageNameLookupRepository;
import lombok.RequiredArgsConstructor;
import org.jdbi.v3.core.Jdbi;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

@RequiredArgsConstructor
public class JdbiStageNameLookupRepository implements StageNameLookupRepository {

    private static final DateTimeFormatter ISO_UTC =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private final Jdbi jdbi;

    @Override
    public Optional<String> findRomanizedFor(String kanjiForm) {
        return jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT romanized_form
                        FROM stage_name_lookup
                        WHERE kanji_form = :kanjiForm
                        """)
                        .bind("kanjiForm", kanjiForm)
                        .mapTo(String.class)
                        .findFirst()
        );
    }

    @Override
    public void upsert(String kanjiForm, String romanizedForm, String actressSlug, String source) {
        String now = ISO_UTC.format(Instant.now());
        jdbi.useHandle(h ->
                h.createUpdate("""
                        INSERT INTO stage_name_lookup (kanji_form, romanized_form, actress_slug, source, seeded_at)
                        VALUES (:kanjiForm, :romanizedForm, :actressSlug, :source, :seededAt)
                        ON CONFLICT(kanji_form) DO UPDATE SET
                            romanized_form = excluded.romanized_form,
                            actress_slug   = excluded.actress_slug,
                            source         = excluded.source,
                            seeded_at      = excluded.seeded_at
                        """)
                        .bind("kanjiForm", kanjiForm)
                        .bind("romanizedForm", romanizedForm)
                        .bind("actressSlug", actressSlug)
                        .bind("source", source)
                        .bind("seededAt", now)
                        .execute()
        );
    }

    @Override
    public long countAll() {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM stage_name_lookup")
                        .mapTo(Long.class)
                        .one()
        );
    }

    @Override
    public void clearAndSeed(List<StageNameLookupRow> rows) {
        String now = ISO_UTC.format(Instant.now());
        jdbi.useTransaction(h -> {
            h.execute("DELETE FROM stage_name_lookup");
            for (StageNameLookupRow row : rows) {
                h.createUpdate("""
                        INSERT INTO stage_name_lookup (kanji_form, romanized_form, actress_slug, source, seeded_at)
                        VALUES (:kanjiForm, :romanizedForm, :actressSlug, :source, :seededAt)
                        """)
                        .bind("kanjiForm", row.kanjiForm())
                        .bind("romanizedForm", row.romanizedForm())
                        .bind("actressSlug", row.actressSlug())
                        .bind("source", row.source() != null ? row.source() : "yaml_seed")
                        .bind("seededAt", now)
                        .execute();
            }
        });
    }
}
