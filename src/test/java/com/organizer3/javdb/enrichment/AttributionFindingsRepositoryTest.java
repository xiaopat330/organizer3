package com.organizer3.javdb.enrichment;

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
 * Tests the full lifecycle of AttributionFindingsRepository against an in-memory SQLite schema.
 */
class AttributionFindingsRepositoryTest {

    private Connection connection;
    private AttributionFindingsRepository repo;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        Jdbi jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        repo = new JdbiAttributionFindingsRepository(jdbi);
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    @Test
    void newFindingHasStatusOpen() {
        repo.upsert(1L, "cast_mismatch", 0.5, "[\"A-001\"]", "2026-01-01T00:00:00Z");

        List<AttributionFindingsRepository.Finding> findings = repo.list(null, 100);
        assertEquals(1, findings.size());
        AttributionFindingsRepository.Finding f = findings.get(0);
        assertEquals(1L, f.actressId());
        assertEquals("cast_mismatch", f.findingClass());
        assertEquals(0.5, f.metric(), 0.001);
        assertEquals("open", f.status());
        assertNotNull(f.firstSeenAt());
    }

    @Test
    void upsertRefreshesMetricAndSampleWithoutChangingStatus() {
        repo.upsert(2L, "cast_mismatch", 0.25, "[\"B-001\"]", "2026-01-01T00:00:00Z");
        repo.suppress(2L, "cast_mismatch", "known issue", null, null);

        // Re-upsert with new metric — status should remain 'suppressed'
        repo.upsert(2L, "cast_mismatch", 0.75, "[\"B-001\",\"B-002\"]", "2026-01-02T00:00:00Z");

        List<AttributionFindingsRepository.Finding> findings = repo.list(null, 100);
        assertEquals(1, findings.size());
        assertEquals("suppressed", findings.get(0).status());
        assertEquals(0.75, findings.get(0).metric(), 0.001);
        assertEquals("2026-01-02T00:00:00Z", findings.get(0).lastSeenAt());
    }

    @Test
    void markResolvedSetsStatusResolved() {
        repo.upsert(3L, "cast_mismatch", 0.5, "[]", "2026-01-01T00:00:00Z");
        repo.markResolved(3L, "cast_mismatch", "2026-02-01T00:00:00Z");

        List<AttributionFindingsRepository.Finding> resolved = repo.list("resolved", 100);
        assertEquals(1, resolved.size());
        assertEquals("resolved", resolved.get(0).status());

        List<AttributionFindingsRepository.Finding> open = repo.list("open", 100);
        assertEquals(0, open.size());
    }

    @Test
    void suppressStaysSuppressedAcrossUpsertRefresh() {
        repo.upsert(4L, "cast_mismatch", 0.5, "[]", "2026-01-01T00:00:00Z");
        repo.suppress(4L, "cast_mismatch", "approved by user", "花咲いろは", "slugABC");

        // Re-upsert should NOT change status back to 'open'
        repo.upsert(4L, "cast_mismatch", 0.6, "[]", "2026-01-02T00:00:00Z");

        List<AttributionFindingsRepository.Finding> findings = repo.list(null, 100);
        assertEquals(1, findings.size());
        assertEquals("suppressed", findings.get(0).status());
    }

    @Test
    void reopenSuppressedIfChangedReopensWhenStageNameDiffers() {
        repo.upsert(5L, "cast_mismatch", 0.5, "[]", "2026-01-01T00:00:00Z");
        repo.suppress(5L, "cast_mismatch", "ok", "花咲いろは", "slugXYZ");

        // Stage name changed
        repo.reopenSuppressedIfChanged(5L, "cast_mismatch", "新しい名前", "slugXYZ");

        List<AttributionFindingsRepository.Finding> findings = repo.list("open", 100);
        assertEquals(1, findings.size());
        assertEquals("open", findings.get(0).status());
    }

    @Test
    void reopenSuppressedIfChangedReopensWhenSlugDiffers() {
        repo.upsert(6L, "suspect_credit", 0.3, "[]", "2026-01-01T00:00:00Z");
        repo.suppress(6L, "suspect_credit", "ok", "花咲いろは", "oldSlug");

        // Slug changed
        repo.reopenSuppressedIfChanged(6L, "suspect_credit", "花咲いろは", "newSlug");

        List<AttributionFindingsRepository.Finding> findings = repo.list("open", 100);
        assertEquals(1, findings.size());
    }

    @Test
    void reopenSuppressedIfChangedDoesNotReopenWhenNothingChanged() {
        repo.upsert(7L, "cast_mismatch", 0.5, "[]", "2026-01-01T00:00:00Z");
        repo.suppress(7L, "cast_mismatch", "ok", "花咲いろは", "slugABC");

        // No change in stage_name or slug
        repo.reopenSuppressedIfChanged(7L, "cast_mismatch", "花咲いろは", "slugABC");

        List<AttributionFindingsRepository.Finding> findings = repo.list("suppressed", 100);
        assertEquals(1, findings.size(), "should remain suppressed when nothing changed");
    }

    @Test
    void countFiltersByStatus() {
        repo.upsert(10L, "cast_mismatch", 0.5, "[]", "2026-01-01T00:00:00Z");
        repo.upsert(11L, "cast_mismatch", 0.3, "[]", "2026-01-01T00:00:00Z");
        repo.suppress(10L, "cast_mismatch", "ok", null, null);

        assertEquals(1, repo.count("open"));
        assertEquals(1, repo.count("suppressed"));
        assertEquals(0, repo.count("resolved"));
        assertEquals(2, repo.count(null));
    }
}
