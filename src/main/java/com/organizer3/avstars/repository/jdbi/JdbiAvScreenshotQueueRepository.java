package com.organizer3.avstars.repository.jdbi;

import com.organizer3.avstars.model.AvScreenshotQueueRow;
import com.organizer3.avstars.repository.AvScreenshotQueueRepository;
import lombok.RequiredArgsConstructor;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.mapper.RowMapper;

import java.time.Instant;
import java.util.Optional;

@RequiredArgsConstructor
public class JdbiAvScreenshotQueueRepository implements AvScreenshotQueueRepository {

    private static final RowMapper<AvScreenshotQueueRow> ROW_MAPPER = (rs, ctx) ->
            AvScreenshotQueueRow.builder()
                    .id(rs.getLong("id"))
                    .avVideoId(rs.getLong("av_video_id"))
                    .avActressId(rs.getLong("av_actress_id"))
                    .enqueuedAt(rs.getString("enqueued_at"))
                    .startedAt(rs.getString("started_at"))
                    .completedAt(rs.getString("completed_at"))
                    .status(rs.getString("status"))
                    .error(rs.getString("error"))
                    .build();

    private final Jdbi jdbi;

    @Override
    public boolean enqueueIfAbsent(long actressId, long videoId) {
        int inserted = jdbi.withHandle(h -> h.execute(
                "INSERT OR IGNORE INTO av_screenshot_queue (av_actress_id, av_video_id, enqueued_at, status) "
                        + "VALUES (?, ?, ?, 'PENDING')",
                actressId, videoId, Instant.now().toString()));
        return inserted > 0;
    }

    @Override
    public Optional<AvScreenshotQueueRow> claimNextPending() {
        return jdbi.withHandle(h ->
                h.createQuery("""
                        UPDATE av_screenshot_queue
                           SET status = 'IN_PROGRESS', started_at = :now
                         WHERE id = (
                             SELECT id FROM av_screenshot_queue
                              WHERE status = 'PENDING'
                              ORDER BY enqueued_at ASC
                              LIMIT 1
                         )
                        RETURNING *
                        """)
                        .bind("now", Instant.now().toString())
                        .map(ROW_MAPPER)
                        .findFirst());
    }

    @Override
    public void markDone(long id) {
        jdbi.useHandle(h -> h.execute(
                "UPDATE av_screenshot_queue SET status = 'DONE', completed_at = ? WHERE id = ?",
                Instant.now().toString(), id));
    }

    @Override
    public void markFailed(long id, String error) {
        jdbi.useHandle(h -> h.execute(
                "UPDATE av_screenshot_queue SET status = 'FAILED', completed_at = ?, error = ? WHERE id = ?",
                Instant.now().toString(), error, id));
    }

    @Override
    public int resetOrphanedInFlightJobs() {
        return jdbi.withHandle(h -> h.execute(
                "UPDATE av_screenshot_queue SET status = 'PENDING', started_at = NULL WHERE status = 'IN_PROGRESS'"));
    }

    @Override
    public int pauseActress(long actressId) {
        return jdbi.withHandle(h -> h.execute(
                "UPDATE av_screenshot_queue SET status = 'PAUSED' WHERE av_actress_id = ? AND status = 'PENDING'",
                actressId));
    }

    @Override
    public int resumeActress(long actressId) {
        return jdbi.withHandle(h -> h.execute(
                "UPDATE av_screenshot_queue SET status = 'PENDING' WHERE av_actress_id = ? AND status = 'PAUSED'",
                actressId));
    }

    @Override
    public int clearForActress(long actressId) {
        return jdbi.withHandle(h -> h.execute(
                "DELETE FROM av_screenshot_queue WHERE av_actress_id = ? AND status IN ('PENDING', 'PAUSED')",
                actressId));
    }

    @Override
    public ActressProgress progressForActress(long actressId) {
        return jdbi.withHandle(h -> h.createQuery("""
                        SELECT
                            SUM(CASE WHEN status = 'PENDING'     THEN 1 ELSE 0 END) AS pending,
                            SUM(CASE WHEN status = 'IN_PROGRESS' THEN 1 ELSE 0 END) AS in_progress,
                            SUM(CASE WHEN status = 'PAUSED'      THEN 1 ELSE 0 END) AS paused,
                            SUM(CASE WHEN status = 'DONE'        THEN 1 ELSE 0 END) AS done,
                            SUM(CASE WHEN status = 'FAILED'      THEN 1 ELSE 0 END) AS failed,
                            COUNT(*) AS total,
                            MAX(CASE WHEN status = 'IN_PROGRESS' THEN av_video_id END) AS current_video_id
                        FROM av_screenshot_queue
                        WHERE av_actress_id = :actressId
                        """)
                .bind("actressId", actressId)
                .map((rs, ctx) -> {
                    int pending    = rs.getInt("pending");
                    int inProgress = rs.getInt("in_progress");
                    int paused     = rs.getInt("paused");
                    int done       = rs.getInt("done");
                    int failed     = rs.getInt("failed");
                    int total      = rs.getInt("total");
                    long rawVid    = rs.getLong("current_video_id");
                    Long currentVideoId = rs.wasNull() ? null : rawVid;
                    return new ActressProgress(pending, inProgress, paused, done, failed, total, currentVideoId);
                })
                .one());
    }

    @Override
    public int globalDepth() {
        return jdbi.withHandle(h -> h.createQuery(
                "SELECT COUNT(*) FROM av_screenshot_queue WHERE status IN ('PENDING', 'IN_PROGRESS')")
                .mapTo(Integer.class)
                .one());
    }
}
