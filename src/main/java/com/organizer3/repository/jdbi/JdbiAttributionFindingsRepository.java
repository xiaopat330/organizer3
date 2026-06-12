package com.organizer3.repository.jdbi;

import com.organizer3.repository.AttributionFindingsRepository;
import lombok.RequiredArgsConstructor;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.mapper.RowMapper;

import java.util.List;

@RequiredArgsConstructor
public class JdbiAttributionFindingsRepository implements AttributionFindingsRepository {

    private final Jdbi jdbi;

    private static final RowMapper<Finding> MAPPER =
            (rs, ctx) -> new Finding(
                    rs.getLong("actress_id"),
                    rs.getString("finding_class"),
                    rs.getObject("metric") != null ? rs.getDouble("metric") : null,
                    rs.getString("sample_json"),
                    rs.getString("first_seen_at"),
                    rs.getString("last_seen_at"),
                    rs.getString("status"),
                    rs.getString("note"),
                    rs.getString("stage_name_at_suppress"),
                    rs.getString("slug_at_suppress")
            );

    @Override
    public void upsert(long actressId, String findingClass, double metric, String sampleJson, String now) {
        jdbi.useHandle(h -> h.createUpdate("""
                INSERT INTO attribution_findings
                    (actress_id, finding_class, metric, sample_json, first_seen_at, last_seen_at, status)
                VALUES
                    (:aid, :fc, :metric, :sample, :now, :now, 'open')
                ON CONFLICT(actress_id, finding_class) DO UPDATE SET
                    metric       = excluded.metric,
                    sample_json  = excluded.sample_json,
                    last_seen_at = excluded.last_seen_at
                """)
                .bind("aid", actressId)
                .bind("fc", findingClass)
                .bind("metric", metric)
                .bind("sample", sampleJson)
                .bind("now", now)
                .execute());
    }

    @Override
    public List<Finding> list(String statusFilter, int limit) {
        return jdbi.withHandle(h -> {
            String sql = statusFilter != null
                    ? "SELECT * FROM attribution_findings WHERE status = :status ORDER BY actress_id LIMIT :lim"
                    : "SELECT * FROM attribution_findings ORDER BY actress_id LIMIT :lim";
            var q = h.createQuery(sql).bind("lim", limit);
            if (statusFilter != null) q = q.bind("status", statusFilter);
            return q.map(MAPPER).list();
        });
    }

    @Override
    public int count(String statusFilter) {
        return jdbi.withHandle(h -> {
            String sql = statusFilter != null
                    ? "SELECT COUNT(*) FROM attribution_findings WHERE status = :status"
                    : "SELECT COUNT(*) FROM attribution_findings";
            var q = h.createQuery(sql);
            if (statusFilter != null) q = q.bind("status", statusFilter);
            return q.mapTo(Integer.class).one();
        });
    }

    @Override
    public void suppress(long actressId, String findingClass, String note,
                         String currentStageName, String currentSlug) {
        jdbi.useHandle(h -> h.createUpdate("""
                UPDATE attribution_findings
                SET status                 = 'suppressed',
                    note                   = :note,
                    stage_name_at_suppress = :stageName,
                    slug_at_suppress       = :slug
                WHERE actress_id = :aid AND finding_class = :fc
                """)
                .bind("aid", actressId)
                .bind("fc", findingClass)
                .bind("note", note)
                .bind("stageName", currentStageName)
                .bind("slug", currentSlug)
                .execute());
    }

    @Override
    public void reopenSuppressedIfChanged(long actressId, String findingClass,
                                          String currentStageName, String currentSlug) {
        jdbi.useHandle(h -> h.createUpdate("""
                UPDATE attribution_findings
                SET status = 'open'
                WHERE actress_id = :aid
                  AND finding_class = :fc
                  AND status = 'suppressed'
                  AND (
                      (stage_name_at_suppress IS NOT :stageName)
                      OR (slug_at_suppress IS NOT :slug)
                  )
                """)
                .bind("aid", actressId)
                .bind("fc", findingClass)
                .bind("stageName", currentStageName)
                .bind("slug", currentSlug)
                .execute());
    }

    @Override
    public void markResolved(long actressId, String findingClass, String now) {
        jdbi.useHandle(h -> h.createUpdate("""
                UPDATE attribution_findings
                SET status = 'resolved', last_seen_at = :now
                WHERE actress_id = :aid AND finding_class = :fc
                """)
                .bind("aid", actressId)
                .bind("fc", findingClass)
                .bind("now", now)
                .execute());
    }
}
