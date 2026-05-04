package com.organizer3.translation.repository.jdbi;

import com.organizer3.translation.TranslationQueueRow;
import com.organizer3.translation.repository.TranslationQueueRepository;
import lombok.RequiredArgsConstructor;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.mapper.RowMapper;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

@RequiredArgsConstructor
public class JdbiTranslationQueueRepository implements TranslationQueueRepository {

    private static final DateTimeFormatter ISO_UTC =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private static final RowMapper<TranslationQueueRow> MAPPER = (rs, ctx) -> {
        Object callbackIdObj = rs.getObject("callback_id");
        Long callbackId = callbackIdObj != null ? ((Number) callbackIdObj).longValue() : null;

        return new TranslationQueueRow(
                rs.getLong("id"),
                rs.getString("source_text"),
                rs.getLong("strategy_id"),
                rs.getString("submitted_at"),
                rs.getString("started_at"),
                rs.getString("completed_at"),
                rs.getString("status"),
                rs.getString("callback_kind"),
                callbackId,
                rs.getInt("attempt_count"),
                rs.getString("last_error")
        );
    };

    private final Jdbi jdbi;

    @Override
    public long enqueue(String sourceText,
                        long strategyId,
                        String submittedAt,
                        String status,
                        String callbackKind,
                        Long callbackId) {
        return jdbi.withHandle(h ->
                h.createUpdate("""
                        INSERT INTO translation_queue
                            (source_text, strategy_id, submitted_at, status,
                             callback_kind, callback_id)
                        VALUES (:sourceText, :strategyId, :submittedAt, :status,
                                :callbackKind, :callbackId)
                        """)
                        .bind("sourceText", sourceText)
                        .bind("strategyId", strategyId)
                        .bind("submittedAt", submittedAt)
                        .bind("status", status)
                        .bind("callbackKind", callbackKind)
                        .bind("callbackId", callbackId)
                        .executeAndReturnGeneratedKeys("id")
                        .mapTo(Long.class)
                        .one()
        );
    }

    @Override
    public Optional<TranslationQueueRow> claimNext() {
        String now = ISO_UTC.format(Instant.now());
        // Use inTransaction so the SELECT + UPDATE happen atomically.
        // SQLite WAL mode with BEGIN IMMEDIATE is used by default in JDBI inTransaction.
        return jdbi.inTransaction(h -> {
            Optional<Long> id = h.createQuery("""
                            SELECT id FROM translation_queue
                            WHERE status = 'pending'
                            ORDER BY submitted_at
                            LIMIT 1
                            """)
                    .mapTo(Long.class)
                    .findFirst();

            if (id.isEmpty()) {
                return Optional.<TranslationQueueRow>empty();
            }

            int updated = h.createUpdate("""
                            UPDATE translation_queue
                            SET status = 'in_flight', started_at = :now
                            WHERE id = :id AND status = 'pending'
                            """)
                    .bind("id", id.get())
                    .bind("now", now)
                    .execute();

            if (updated == 0) {
                // Lost the race — another worker claimed it first
                return Optional.<TranslationQueueRow>empty();
            }

            return h.createQuery("""
                            SELECT id, source_text, strategy_id, submitted_at, started_at,
                                   completed_at, status, callback_kind, callback_id,
                                   attempt_count, last_error
                            FROM translation_queue
                            WHERE id = :id
                            """)
                    .bind("id", id.get())
                    .map(MAPPER)
                    .findFirst();
        });
    }

    @Override
    public void markDone(long id, String completedAt) {
        jdbi.useHandle(h ->
                h.createUpdate("""
                        UPDATE translation_queue
                        SET status = 'done', completed_at = :completedAt
                        WHERE id = :id
                        """)
                        .bind("id", id)
                        .bind("completedAt", completedAt)
                        .execute()
        );
    }

    @Override
    public void markFailed(long id, String error, String completedAt) {
        jdbi.useHandle(h ->
                h.createUpdate("""
                        UPDATE translation_queue
                        SET status = 'failed', last_error = :error, completed_at = :completedAt
                        WHERE id = :id
                        """)
                        .bind("id", id)
                        .bind("error", error)
                        .bind("completedAt", completedAt)
                        .execute()
        );
    }

    @Override
    public void incrementAttempt(long id, String lastError) {
        jdbi.useHandle(h ->
                h.createUpdate("""
                        UPDATE translation_queue
                        SET attempt_count = attempt_count + 1,
                            last_error = :lastError,
                            status = 'pending',
                            started_at = NULL
                        WHERE id = :id
                        """)
                        .bind("id", id)
                        .bind("lastError", lastError)
                        .execute()
        );
    }

    @Override
    public List<TranslationQueueRow> findStuckInFlight(int thresholdSeconds) {
        String threshold = ISO_UTC.format(Instant.now().minusSeconds(thresholdSeconds));
        return jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT id, source_text, strategy_id, submitted_at, started_at,
                               completed_at, status, callback_kind, callback_id,
                               attempt_count, last_error
                        FROM translation_queue
                        WHERE status = 'in_flight'
                          AND started_at < :threshold
                        ORDER BY started_at
                        """)
                        .bind("threshold", threshold)
                        .map(MAPPER)
                        .list()
        );
    }

    @Override
    public int resetStuckToRetry(int thresholdSeconds) {
        String threshold = ISO_UTC.format(Instant.now().minusSeconds(thresholdSeconds));
        return jdbi.withHandle(h ->
                h.createUpdate("""
                        UPDATE translation_queue
                        SET status = 'pending', started_at = NULL,
                            last_error = 'reset by sweeper after stuck in_flight'
                        WHERE status = 'in_flight'
                          AND started_at < :threshold
                        """)
                        .bind("threshold", threshold)
                        .execute()
        );
    }

    @Override
    public int countByStatus(String status) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM translation_queue WHERE status = :status")
                        .bind("status", status)
                        .mapTo(Integer.class)
                        .one()
        );
    }
}
