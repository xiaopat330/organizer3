package com.organizer3.repository.jdbi;

import com.organizer3.repository.ReconcileReportRepository;
import com.organizer3.sync.ReconcileReport;
import lombok.RequiredArgsConstructor;
import org.jdbi.v3.core.Jdbi;

import java.util.List;
import java.util.Optional;

@RequiredArgsConstructor
public class JdbiReconcileReportRepository implements ReconcileReportRepository {

    private final Jdbi jdbi;

    @Override
    public long save(ReconcileReport report, String triggeredBy, String detailJson) {
        return jdbi.withHandle(h ->
                h.createUpdate("""
                        INSERT INTO reconcile_reports
                            (generated_at, duplicate_live_locations, pending_grace,
                             oldest_pending_grace_days, past_grace_stragglers,
                             actress_folder_mismatches, triggered_by, detail_json)
                        VALUES
                            (:generatedAt, :dupLive, :pendingGrace,
                             :oldestDays, :pastGrace,
                             :mismatches, :triggeredBy, :detailJson)
                        """)
                        .bind("generatedAt",  report.generatedAt().toString())
                        .bind("dupLive",      report.duplicateLiveLocations())
                        .bind("pendingGrace", report.pendingGrace())
                        .bind("oldestDays",   report.oldestPendingGraceDays())
                        .bind("pastGrace",    report.pastGraceStragglers())
                        .bind("mismatches",   report.actressFolderMismatches())
                        .bind("triggeredBy",  triggeredBy)
                        .bind("detailJson",   detailJson)
                        .executeAndReturnGeneratedKeys("id")
                        .mapTo(Long.class)
                        .one()
        );
    }

    @Override
    public List<PersistedReport> findRecent(int limit) {
        return jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT id, generated_at, duplicate_live_locations, pending_grace,
                               oldest_pending_grace_days, past_grace_stragglers,
                               actress_folder_mismatches, triggered_by, detail_json
                        FROM reconcile_reports
                        ORDER BY generated_at DESC
                        LIMIT :lim
                        """)
                        .bind("lim", limit)
                        .map(MAPPER)
                        .list()
        );
    }

    @Override
    public Optional<PersistedReport> findById(long id) {
        return jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT id, generated_at, duplicate_live_locations, pending_grace,
                               oldest_pending_grace_days, past_grace_stragglers,
                               actress_folder_mismatches, triggered_by, detail_json
                        FROM reconcile_reports
                        WHERE id = :id
                        """)
                        .bind("id", id)
                        .map(MAPPER)
                        .findFirst()
        );
    }

    private static final org.jdbi.v3.core.mapper.RowMapper<PersistedReport> MAPPER =
            (rs, ctx) -> new PersistedReport(
                    rs.getLong("id"),
                    rs.getString("generated_at"),
                    rs.getInt("duplicate_live_locations"),
                    rs.getInt("pending_grace"),
                    rs.getInt("oldest_pending_grace_days"),
                    rs.getInt("past_grace_stragglers"),
                    rs.getInt("actress_folder_mismatches"),
                    rs.getString("triggered_by"),
                    rs.getString("detail_json")
            );
}
