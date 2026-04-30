package com.organizer3.utilities.health.checks;

import com.organizer3.db.SchemaInitializer;
import com.organizer3.utilities.health.LibraryHealthCheck;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class EnrichmentReviewQueueCheckTest {

    private Connection connection;
    private Jdbi jdbi;
    private EnrichmentReviewQueueCheck check;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        jdbi.useHandle(h -> {
            h.execute("INSERT INTO titles (id, code, base_code, label, seq_num) VALUES (1, 'ABP-001', 'ABP-001', 'ABP', 1)");
            h.execute("INSERT INTO titles (id, code, base_code, label, seq_num) VALUES (2, 'ABP-002', 'ABP-002', 'ABP', 2)");
            h.execute("INSERT INTO titles (id, code, base_code, label, seq_num) VALUES (3, 'ABP-003', 'ABP-003', 'ABP', 3)");
            h.execute("INSERT INTO titles (id, code, base_code, label, seq_num) VALUES (4, 'ABP-004', 'ABP-004', 'ABP', 4)");
            h.execute("INSERT INTO titles (id, code, base_code, label, seq_num) VALUES (5, 'ABP-005', 'ABP-005', 'ABP', 5)");
        });
        check = new EnrichmentReviewQueueCheck(jdbi);
    }

    @AfterEach
    void tearDown() throws Exception { connection.close(); }

    private void enqueue(long titleId, String reason) {
        jdbi.useHandle(h -> h.execute(
                "INSERT INTO enrichment_review_queue (title_id, reason, created_at) VALUES (?, ?, '2024-01-01T00:00:00.000Z')",
                titleId, reason));
    }

    private void resolve(long titleId, String reason) {
        jdbi.useHandle(h -> h.execute(
                "UPDATE enrichment_review_queue SET resolved_at='2024-01-02T00:00:00.000Z' " +
                "WHERE title_id=? AND reason=? AND resolved_at IS NULL",
                titleId, reason));
    }

    @Test
    void reportsEmptyWhenQueueIsEmpty() {
        LibraryHealthCheck.CheckResult result = check.run();
        assertEquals(0, result.total());
        assertTrue(result.rows().isEmpty());
    }

    @Test
    void detectsSingleOpenEntry() {
        enqueue(1, "cast_anomaly");

        LibraryHealthCheck.CheckResult result = check.run();
        assertEquals(1, result.total());
        assertEquals(1, result.rows().size());
        LibraryHealthCheck.Finding f = result.rows().get(0);
        assertEquals("cast_anomaly", f.label());
        assertTrue(f.detail().contains("1 open"));
    }

    @Test
    void multiBucketAggregatesCorrectly() {
        enqueue(1, "cast_anomaly");
        enqueue(2, "cast_anomaly");
        enqueue(3, "cast_anomaly");
        enqueue(4, "no_match");

        LibraryHealthCheck.CheckResult result = check.run();
        assertEquals(4, result.total());
        assertEquals(2, result.rows().size(), "two non-zero buckets → two findings");

        Map<String, String> byLabel = result.rows().stream()
                .collect(Collectors.toMap(LibraryHealthCheck.Finding::label, LibraryHealthCheck.Finding::detail));
        assertTrue(byLabel.get("cast_anomaly").contains("3 open"));
        assertTrue(byLabel.get("no_match").contains("1 open"));
    }

    @Test
    void resolvedRowsAreExcluded() {
        enqueue(1, "cast_anomaly");
        resolve(1, "cast_anomaly");

        LibraryHealthCheck.CheckResult result = check.run();
        assertEquals(0, result.total(), "resolved row must not be counted");
        assertTrue(result.rows().isEmpty());
    }

    @Test
    void mixedResolvedAndOpenCountsOnlyOpen() {
        enqueue(1, "ambiguous");
        enqueue(2, "ambiguous");
        resolve(1, "ambiguous");

        LibraryHealthCheck.CheckResult result = check.run();
        assertEquals(1, result.total());
        assertEquals(1, result.rows().size());
        assertTrue(result.rows().get(0).detail().contains("1 open"));
    }

    @Test
    void findingIdIncludesReason() {
        enqueue(1, "fetch_failed");

        LibraryHealthCheck.CheckResult result = check.run();
        assertEquals("reason:fetch_failed", result.rows().get(0).id());
    }
}
