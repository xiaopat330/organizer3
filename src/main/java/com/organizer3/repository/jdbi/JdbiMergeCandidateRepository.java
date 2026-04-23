package com.organizer3.repository.jdbi;

import com.organizer3.model.MergeCandidate;
import com.organizer3.repository.MergeCandidateRepository;
import lombok.RequiredArgsConstructor;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.mapper.RowMapper;

import java.util.List;
import java.util.Optional;

@RequiredArgsConstructor
public class JdbiMergeCandidateRepository implements MergeCandidateRepository {

    private static final RowMapper<MergeCandidate> MAPPER = (rs, ctx) ->
            MergeCandidate.builder()
                    .id(rs.getLong("id"))
                    .titleCodeA(rs.getString("title_code_a"))
                    .titleCodeB(rs.getString("title_code_b"))
                    .confidence(rs.getString("confidence"))
                    .detectedAt(rs.getString("detected_at"))
                    .decision(rs.getString("decision"))
                    .decidedAt(rs.getString("decided_at"))
                    .winnerCode(rs.getString("winner_code"))
                    .executedAt(rs.getString("executed_at"))
                    .build();

    private final Jdbi jdbi;

    @Override
    public void insertIfAbsent(String codeA, String codeB, String confidence, String detectedAt) {
        // Canonical order: lexicographically smaller code is always A
        String a = codeA.compareTo(codeB) <= 0 ? codeA : codeB;
        String b = codeA.compareTo(codeB) <= 0 ? codeB : codeA;
        jdbi.useHandle(h -> h.createUpdate("""
                        INSERT OR IGNORE INTO merge_candidates
                            (title_code_a, title_code_b, confidence, detected_at)
                        VALUES (:a, :b, :confidence, :detectedAt)
                        """)
                .bind("a", a)
                .bind("b", b)
                .bind("confidence", confidence)
                .bind("detectedAt", detectedAt)
                .execute());
    }

    @Override
    public List<MergeCandidate> listPending() {
        return jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT * FROM merge_candidates
                        WHERE decision IS NULL
                        ORDER BY confidence, title_code_a, title_code_b
                        """)
                        .map(MAPPER)
                        .list());
    }

    @Override
    public List<MergeCandidate> listPendingMerge() {
        return jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT * FROM merge_candidates
                        WHERE decision = 'MERGE' AND executed_at IS NULL
                        ORDER BY title_code_a, title_code_b
                        """)
                        .map(MAPPER)
                        .list());
    }

    @Override
    public Optional<MergeCandidate> find(String codeA, String codeB) {
        String a = codeA.compareTo(codeB) <= 0 ? codeA : codeB;
        String b = codeA.compareTo(codeB) <= 0 ? codeB : codeA;
        return jdbi.withHandle(h ->
                h.createQuery("SELECT * FROM merge_candidates WHERE title_code_a = :a AND title_code_b = :b")
                        .bind("a", a)
                        .bind("b", b)
                        .map(MAPPER)
                        .findFirst());
    }

    @Override
    public void decide(long id, String decision, String winnerCode, String decidedAt) {
        jdbi.useHandle(h -> h.createUpdate("""
                        UPDATE merge_candidates
                        SET decision = :decision, winner_code = :winnerCode, decided_at = :decidedAt
                        WHERE id = :id
                        """)
                .bind("id", id)
                .bind("decision", decision)
                .bind("winnerCode", winnerCode)
                .bind("decidedAt", decidedAt)
                .execute());
    }

    @Override
    public void markExecuted(long id, String executedAt) {
        jdbi.useHandle(h -> h.createUpdate("""
                        UPDATE merge_candidates SET executed_at = :executedAt WHERE id = :id
                        """)
                .bind("id", id)
                .bind("executedAt", executedAt)
                .execute());
    }

    @Override
    public void deleteUndecided() {
        jdbi.useHandle(h -> h.execute("DELETE FROM merge_candidates WHERE decision IS NULL"));
    }
}
