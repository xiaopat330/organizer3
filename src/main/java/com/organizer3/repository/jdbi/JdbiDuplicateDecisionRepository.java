package com.organizer3.repository.jdbi;

import com.organizer3.model.DuplicateDecision;
import com.organizer3.repository.DuplicateDecisionRepository;
import lombok.RequiredArgsConstructor;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.mapper.RowMapper;

import java.util.List;

@RequiredArgsConstructor
public class JdbiDuplicateDecisionRepository implements DuplicateDecisionRepository {

    private static final RowMapper<DuplicateDecision> MAPPER = (rs, ctx) ->
            DuplicateDecision.builder()
                    .titleCode(rs.getString("title_code"))
                    .volumeId(rs.getString("volume_id"))
                    .nasPath(rs.getString("nas_path"))
                    .decision(rs.getString("decision"))
                    .createdAt(rs.getString("created_at"))
                    .executedAt(rs.getString("executed_at"))
                    .build();

    private final Jdbi jdbi;

    @Override
    public void upsert(DuplicateDecision decision) {
        jdbi.useHandle(h -> h.createUpdate("""
                        INSERT INTO duplicate_decisions (title_code, volume_id, nas_path, decision, created_at)
                        VALUES (:titleCode, :volumeId, :nasPath, :decision, :createdAt)
                        ON CONFLICT(title_code, volume_id, nas_path)
                        DO UPDATE SET decision = excluded.decision
                        """)
                .bind("titleCode", decision.getTitleCode())
                .bind("volumeId", decision.getVolumeId())
                .bind("nasPath", decision.getNasPath())
                .bind("decision", decision.getDecision())
                .bind("createdAt", decision.getCreatedAt())
                .execute());
    }

    @Override
    public List<DuplicateDecision> listPending() {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT * FROM duplicate_decisions WHERE executed_at IS NULL ORDER BY title_code, volume_id, nas_path")
                        .map(MAPPER)
                        .list());
    }

    @Override
    public void delete(String titleCode, String volumeId, String nasPath) {
        jdbi.useHandle(h -> h.createUpdate("""
                        DELETE FROM duplicate_decisions
                        WHERE title_code = :titleCode AND volume_id = :volumeId AND nas_path = :nasPath
                        """)
                .bind("titleCode", titleCode)
                .bind("volumeId", volumeId)
                .bind("nasPath", nasPath)
                .execute());
    }
}
