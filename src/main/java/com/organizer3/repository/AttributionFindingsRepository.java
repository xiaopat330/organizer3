package com.organizer3.repository;

import java.util.List;

/**
 * Persistence interface for actress-level attribution audit findings.
 *
 * <p>Each row represents one (actress_id, finding_class) aggregate — NOT a per-title row.
 * The {@code metric} is a fraction (e.g. mismatch_count / enriched_title_count).
 * Status lifecycle: open → suppressed or resolved.
 */
public interface AttributionFindingsRepository {

    record Finding(
            long actressId,
            String findingClass,
            Double metric,
            String sampleJson,
            String firstSeenAt,
            String lastSeenAt,
            String status,
            String note,
            String stageNameAtSuppress,
            String slugAtSuppress
    ) {}

    /**
     * Upsert: insert new row as 'open' or refresh metric/sample/last_seen on existing.
     * Does NOT change the status of an existing row (suppressed stays suppressed).
     */
    void upsert(long actressId, String findingClass, double metric, String sampleJson, String now);

    /** List findings by status (null = all statuses), ordered by actress_id. */
    List<Finding> list(String statusFilter, int limit);

    /** Count findings by status (null = all statuses). */
    int count(String statusFilter);

    /** Mark finding as suppressed, recording current stage_name and slug for drift detection. */
    void suppress(long actressId, String findingClass, String note,
                  String currentStageName, String currentSlug);

    /**
     * If the finding is suppressed AND either the current stage_name OR slug differs from
     * what was recorded at suppress time, reopen it (set status='open').
     */
    void reopenSuppressedIfChanged(long actressId, String findingClass,
                                   String currentStageName, String currentSlug);

    /** Mark finding as resolved (was present, now gone). */
    void markResolved(long actressId, String findingClass, String now);
}
