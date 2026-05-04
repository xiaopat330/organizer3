package com.organizer3.translation.repository.jdbi;

import com.organizer3.translation.TranslationCacheRow;
import com.organizer3.translation.repository.TranslationCacheRepository;
import lombok.RequiredArgsConstructor;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.mapper.RowMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

@RequiredArgsConstructor
public class JdbiTranslationCacheRepository implements TranslationCacheRepository {

    private static final DateTimeFormatter ISO_UTC =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private static final RowMapper<TranslationCacheRow> MAPPER = (rs, ctx) -> {
        // eval_duration_ns is a LONG column, but SQLite may return Integer for small values.
        // Use rs.getObject() → Number → longValue() to handle both safely.
        Object evalDurObj = rs.getObject("eval_duration_ns");
        Long evalDurationNs = evalDurObj != null ? ((Number) evalDurObj).longValue() : null;

        Object latencyObj = rs.getObject("latency_ms");
        Integer latencyMs = latencyObj != null ? ((Number) latencyObj).intValue() : null;

        Object promptObj = rs.getObject("prompt_tokens");
        Integer promptTokens = promptObj != null ? ((Number) promptObj).intValue() : null;

        Object evalObj = rs.getObject("eval_tokens");
        Integer evalTokens = evalObj != null ? ((Number) evalObj).intValue() : null;

        return new TranslationCacheRow(
                rs.getLong("id"),
                rs.getString("source_hash"),
                rs.getString("source_text"),
                rs.getLong("strategy_id"),
                rs.getString("english_text"),
                rs.getString("human_corrected_text"),
                rs.getString("human_corrected_at"),
                rs.getString("failure_reason"),
                rs.getString("retry_after"),
                latencyMs,
                promptTokens,
                evalTokens,
                evalDurationNs,
                rs.getString("cached_at")
        );
    };

    private final Jdbi jdbi;

    @Override
    public Optional<TranslationCacheRow> findByHashAndStrategy(String sourceHash, long strategyId) {
        return jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT id, source_hash, source_text, strategy_id,
                               english_text, human_corrected_text, human_corrected_at,
                               failure_reason, retry_after,
                               latency_ms, prompt_tokens, eval_tokens, eval_duration_ns,
                               cached_at
                        FROM translation_cache
                        WHERE source_hash = :sourceHash AND strategy_id = :strategyId
                        """)
                        .bind("sourceHash", sourceHash)
                        .bind("strategyId", strategyId)
                        .map(MAPPER)
                        .findFirst()
        );
    }

    @Override
    public long insert(TranslationCacheRow row) {
        return jdbi.withHandle(h ->
                h.createUpdate("""
                        INSERT INTO translation_cache
                            (source_hash, source_text, strategy_id,
                             english_text, human_corrected_text, human_corrected_at,
                             failure_reason, retry_after,
                             latency_ms, prompt_tokens, eval_tokens, eval_duration_ns,
                             cached_at)
                        VALUES (:sourceHash, :sourceText, :strategyId,
                                :englishText, :humanCorrectedText, :humanCorrectedAt,
                                :failureReason, :retryAfter,
                                :latencyMs, :promptTokens, :evalTokens, :evalDurationNs,
                                :cachedAt)
                        """)
                        .bind("sourceHash", row.sourceHash())
                        .bind("sourceText", row.sourceText())
                        .bind("strategyId", row.strategyId())
                        .bind("englishText", row.englishText())
                        .bind("humanCorrectedText", row.humanCorrectedText())
                        .bind("humanCorrectedAt", row.humanCorrectedAt())
                        .bind("failureReason", row.failureReason())
                        .bind("retryAfter", row.retryAfter())
                        .bind("latencyMs", row.latencyMs())
                        .bind("promptTokens", row.promptTokens())
                        .bind("evalTokens", row.evalTokens())
                        .bind("evalDurationNs", row.evalDurationNs())
                        .bind("cachedAt", row.cachedAt())
                        .executeAndReturnGeneratedKeys("id")
                        .mapTo(Long.class)
                        .one()
        );
    }

    @Override
    public void updateOutcome(long cacheRowId,
                              String englishText,
                              String failureReason,
                              String retryAfter,
                              Integer latencyMs,
                              Integer promptTokens,
                              Integer evalTokens,
                              Long evalDurationNs) {
        jdbi.useHandle(h ->
                h.createUpdate("""
                        UPDATE translation_cache SET
                            english_text = :englishText,
                            failure_reason = :failureReason,
                            retry_after = :retryAfter,
                            latency_ms = :latencyMs,
                            prompt_tokens = :promptTokens,
                            eval_tokens = :evalTokens,
                            eval_duration_ns = :evalDurationNs,
                            cached_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
                        WHERE id = :id
                        """)
                        .bind("id", cacheRowId)
                        .bind("englishText", englishText)
                        .bind("failureReason", failureReason)
                        .bind("retryAfter", retryAfter)
                        .bind("latencyMs", latencyMs)
                        .bind("promptTokens", promptTokens)
                        .bind("evalTokens", evalTokens)
                        .bind("evalDurationNs", evalDurationNs)
                        .execute()
        );
    }

    @Override
    public void updateHumanCorrection(long cacheRowId, String humanCorrectedText, String humanCorrectedAt) {
        jdbi.useHandle(h ->
                h.createUpdate("""
                        UPDATE translation_cache SET
                            human_corrected_text = :humanCorrectedText,
                            human_corrected_at = :humanCorrectedAt
                        WHERE id = :id
                        """)
                        .bind("id", cacheRowId)
                        .bind("humanCorrectedText", humanCorrectedText)
                        .bind("humanCorrectedAt", humanCorrectedAt)
                        .execute()
        );
    }

    @Override
    public long countTotal() {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM translation_cache")
                        .mapTo(Long.class)
                        .one()
        );
    }

    @Override
    public long countSuccessful() {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM translation_cache WHERE english_text IS NOT NULL")
                        .mapTo(Long.class)
                        .one()
        );
    }

    @Override
    public long countFailed() {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM translation_cache WHERE failure_reason IS NOT NULL")
                        .mapTo(Long.class)
                        .one()
        );
    }

    @Override
    public long recentThroughputCount(Duration window) {
        String threshold = ISO_UTC.format(Instant.now().minus(window));
        return jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT COUNT(*) FROM translation_cache
                        WHERE english_text IS NOT NULL
                          AND cached_at >= :threshold
                        """)
                        .bind("threshold", threshold)
                        .mapTo(Long.class)
                        .one()
        );
    }

    @Override
    public List<TranslationCacheRow> findRecentFailures(int limit) {
        return jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT id, source_hash, source_text, strategy_id,
                               english_text, human_corrected_text, human_corrected_at,
                               failure_reason, retry_after,
                               latency_ms, prompt_tokens, eval_tokens, eval_duration_ns,
                               cached_at
                        FROM translation_cache
                        WHERE failure_reason IS NOT NULL
                        ORDER BY cached_at DESC
                        LIMIT :limit
                        """)
                        .bind("limit", limit)
                        .map(MAPPER)
                        .list()
        );
    }

    @Override
    public Long latencyP95(int sampleSize) {
        // Fetch recent successful latencies, sort, take p95
        List<Integer> latencies = jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT latency_ms FROM translation_cache
                        WHERE english_text IS NOT NULL
                          AND latency_ms IS NOT NULL
                        ORDER BY cached_at DESC
                        LIMIT :limit
                        """)
                        .bind("limit", sampleSize)
                        .mapTo(Integer.class)
                        .list()
        );
        if (latencies.size() < 5) return null;
        List<Integer> sorted = latencies.stream().sorted().toList();
        int idx = (int) Math.ceil(sorted.size() * 0.95) - 1;
        return sorted.get(Math.min(idx, sorted.size() - 1)).longValue();
    }
}
