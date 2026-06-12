package com.organizer3.javdb.enrichment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.organizer3.db.SchemaInitializer;
import com.organizer3.repository.AttributionFindingsRepository;
import com.organizer3.repository.jdbi.JdbiAttributionFindingsRepository;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that AttributionAuditService.refreshFindings() aggregates per-actress and
 * produces correct metric fractions, with no title_id column anywhere in the findings.
 */
class AttributionAuditServiceAggregationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Connection connection;
    private Jdbi jdbi;
    private AttributionFindingsRepository findingsRepo;
    private AttributionAuditService service;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        findingsRepo = new JdbiAttributionFindingsRepository(jdbi);
        service = new AttributionAuditService(jdbi, findingsRepo, MAPPER);
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    @Test
    void actressWithMismatchesShouldProduceOneFindingWithCorrectMetric() throws Exception {
        // Create actress with kanji stage_name
        long actressId = seedActress("花咲いろは", "花咲いろは");

        // 3 mismatched titles (cast does NOT contain the stage_name)
        long t1 = seedEnrichedTitle("TEST-001", "[{\"name\":\"別人\",\"slug\":\"x1\"}]");
        long t2 = seedEnrichedTitle("TEST-002", "[{\"name\":\"別人\",\"slug\":\"x2\"}]");
        long t3 = seedEnrichedTitle("TEST-003", "[{\"name\":\"別人\",\"slug\":\"x3\"}]");
        // 1 matching title (cast DOES contain the stage_name)
        long t4 = seedEnrichedTitle("TEST-004", "[{\"name\":\"花咲いろは\",\"slug\":\"x4\"}]");

        linkActress(t1, actressId);
        linkActress(t2, actressId);
        linkActress(t3, actressId);
        linkActress(t4, actressId);

        int processed = service.refreshFindings(100);
        assertTrue(processed > 0, "should process at least one actress");

        // Verify exactly one cast_mismatch finding with metric = 3/4
        List<AttributionFindingsRepository.Finding> findings = findingsRepo.list("open", 100);
        List<AttributionFindingsRepository.Finding> castMismatches = findings.stream()
                .filter(f -> f.actressId() == actressId && "cast_mismatch".equals(f.findingClass()))
                .toList();

        assertEquals(1, castMismatches.size(), "should produce exactly one cast_mismatch finding");
        AttributionFindingsRepository.Finding f = castMismatches.get(0);
        assertEquals(0.75, f.metric(), 0.001, "metric should be 3/4 = 0.75");
        assertNotNull(f.sampleJson(), "sample_json should not be null");
        assertNotNull(f.firstSeenAt());
        assertNotNull(f.lastSeenAt());
        assertEquals("open", f.status());

        // HARD CONSTRAINT: verify NO title_id column exists in the attribution_findings table
        boolean hasTitleId = jdbi.withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM pragma_table_info('attribution_findings') WHERE name='title_id'")
                        .mapTo(Integer.class).one() > 0);
        assertFalse(hasTitleId, "attribution_findings table must NOT have a title_id column");
    }

    @Test
    void actressWithNoMismatchShouldNotGenerateFinding() throws Exception {
        long actressId = seedActress("完全一致の人", "完全一致の人");
        long t1 = seedEnrichedTitle("OK-001", "[{\"name\":\"完全一致の人\",\"slug\":\"ok1\"}]");
        linkActress(t1, actressId);

        service.refreshFindings(100);

        List<AttributionFindingsRepository.Finding> findings = findingsRepo.list(null, 100);
        List<AttributionFindingsRepository.Finding> forActress = findings.stream()
                .filter(f -> f.actressId() == actressId && "cast_mismatch".equals(f.findingClass()))
                .toList();
        assertEquals(0, forActress.size(), "no mismatch should produce no finding");
    }

    @Test
    void vanishedFindingIsMarkedResolved() throws Exception {
        long actressId = seedActress("消える人", "消える人");
        long t1 = seedEnrichedTitle("VAN-001", "[{\"name\":\"別人\",\"slug\":\"v1\"}]");
        linkActress(t1, actressId);

        // First pass: creates the finding
        service.refreshFindings(100);
        assertEquals(1, findingsRepo.count("open"));

        // Fix the mismatch: add the stage_name to cast
        jdbi.useHandle(h ->
                h.createUpdate("UPDATE title_javdb_enrichment SET cast_json = '[{\"name\":\"消える人\",\"slug\":\"v1\"}]' WHERE title_id = :id")
                        .bind("id", t1).execute());

        // Second pass: the finding should be marked resolved
        service.refreshFindings(100);
        assertEquals(0, findingsRepo.count("open"));
        assertEquals(1, findingsRepo.count("resolved"));
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private long seedActress(String canonicalName, String stageName) {
        return jdbi.withHandle(h -> h.createUpdate("""
                INSERT INTO actresses (canonical_name, stage_name, tier, first_seen_at)
                VALUES (:cn, :sn, 'LIBRARY', '2024-01-01')
                """)
                .bind("cn", canonicalName)
                .bind("sn", stageName)
                .executeAndReturnGeneratedKeys("id").mapTo(Long.class).one());
    }

    private long seedEnrichedTitle(String code, String castJson) {
        long titleId = jdbi.withHandle(h -> h.createUpdate("""
                INSERT INTO titles (code, base_code, label, seq_num) VALUES (?, ?, ?, ?)
                """)
                .bind(0, code).bind(1, code).bind(2, code.split("-")[0]).bind(3, 1)
                .executeAndReturnGeneratedKeys("id").mapTo(Long.class).one());
        jdbi.useHandle(h -> h.createUpdate("""
                INSERT INTO title_javdb_enrichment (title_id, javdb_slug, fetched_at, cast_json)
                VALUES (?, ?, '2026-01-01T00:00:00Z', ?)
                """)
                .bind(0, titleId).bind(1, "slug-" + code).bind(2, castJson)
                .execute());
        return titleId;
    }

    private void linkActress(long titleId, long actressId) {
        jdbi.useHandle(h -> h.createUpdate(
                "INSERT INTO title_actresses (title_id, actress_id) VALUES (?, ?)")
                .bind(0, titleId).bind(1, actressId).execute());
    }
}
