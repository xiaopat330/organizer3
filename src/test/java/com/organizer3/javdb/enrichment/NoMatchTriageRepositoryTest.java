package com.organizer3.javdb.enrichment;

import com.organizer3.db.SchemaInitializer;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class NoMatchTriageRepositoryTest {

    private Connection connection;
    private Jdbi jdbi;
    private NoMatchTriageRepository repo;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        repo = new NoMatchTriageRepository(jdbi);
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    // ── seed helpers ─────────────────────────────────────────────────────────

    private void seedVolume() {
        jdbi.useHandle(h ->
                h.execute("INSERT INTO volumes(id, structure_type) VALUES ('vol1','actress')"));
    }

    private long seedTitle(String code) {
        return jdbi.withHandle(h -> {
            h.execute("INSERT INTO titles(code, base_code, label, seq_num) VALUES (?,?,?,?)",
                    code, code, "LABEL", 1);
            return h.createQuery("SELECT id FROM titles WHERE code = ?").bind(0, code)
                    .mapTo(Long.class).one();
        });
    }

    private long seedActress(String stageName) {
        return jdbi.withHandle(h -> {
            h.execute("INSERT INTO actresses(canonical_name, stage_name, tier, first_seen_at) "
                    + "VALUES (?,?,'S','2024-01-01T00:00:00Z')", stageName, stageName);
            return h.createQuery("SELECT id FROM actresses WHERE stage_name = ?").bind(0, stageName)
                    .mapTo(Long.class).one();
        });
    }

    private void seedTitleActress(long titleId, long actressId) {
        jdbi.useHandle(h ->
                h.execute("INSERT INTO title_actresses(title_id, actress_id) VALUES (?,?)",
                        titleId, actressId));
    }

    private void seedNoMatchQueueRow(long titleId, Long actressId) {
        jdbi.useHandle(h ->
                h.execute("""
                        INSERT INTO javdb_enrichment_queue
                            (job_type, target_id, actress_id, source, priority, status, attempts,
                             next_attempt_at, last_error, created_at, updated_at)
                        VALUES ('fetch_title',?,?,'actress','NORMAL','failed',1,?,
                                'no_match_in_filmography',?,?)
                        """,
                        titleId, actressId,
                        Instant.now().toString(),
                        Instant.now().toString(),
                        Instant.now().toString()));
    }

    private void seedFilmographyEntry(long actressId, String actressSlug, String productCode, String titleSlug) {
        jdbi.useHandle(h -> {
            // javdb_actress_staging — links actress_id to javdb_slug
            h.execute("INSERT OR IGNORE INTO javdb_actress_staging(actress_id, javdb_slug, status) VALUES (?,?,'done')",
                    actressId, actressSlug);
            // javdb_actress_filmography (metadata row required by FK)
            h.execute("INSERT OR IGNORE INTO javdb_actress_filmography"
                    + "(actress_slug, fetched_at, page_count, source) "
                    + "VALUES (?,'2026-01-01T00:00:00Z',1,'http')", actressSlug);
            // filmography entry
            h.execute("INSERT OR IGNORE INTO javdb_actress_filmography_entry"
                    + "(actress_slug, product_code, title_slug, stale) VALUES (?,?,?,0)",
                    actressSlug, productCode, titleSlug);
        });
    }

    private void seedTitleLocation(long titleId, String path) {
        seedVolume();
        jdbi.useHandle(h ->
                h.execute("""
                        INSERT INTO title_locations(title_id, volume_id, partition_id, path, last_seen_at)
                        VALUES (?, 'vol1', 'p1', ?, '2026-01-01T00:00:00Z')
                        """, titleId, path));
    }

    // ── listNoMatchRows ───────────────────────────────────────────────────────

    @Test
    void listNoMatchRows_returnsNoMatchRow() {
        long titleId = seedTitle("STAR-334");
        long actressId = seedActress("Mana Sakura");
        seedTitleActress(titleId, actressId);
        seedNoMatchQueueRow(titleId, actressId);

        List<NoMatchTriageRepository.NoMatchRow> rows = repo.listNoMatchRows();
        assertEquals(1, rows.size());
        NoMatchTriageRepository.NoMatchRow row = rows.get(0);
        assertEquals(titleId, row.titleId());
        assertEquals("STAR-334", row.code());
        assertEquals(actressId, row.actressId());
        assertEquals("Mana Sakura", row.actressStageName());
    }

    @Test
    void listNoMatchRows_orphanRowHasNullActress() {
        long titleId = seedTitle("MIST-001");
        seedNoMatchQueueRow(titleId, null);  // no actress link

        List<NoMatchTriageRepository.NoMatchRow> rows = repo.listNoMatchRows();
        assertEquals(1, rows.size());
        assertNull(rows.get(0).actressId());
        assertNull(rows.get(0).actressStageName());
    }

    @Test
    void listNoMatchRows_excludesNonNoMatchRows() {
        long titleId = seedTitle("STAR-500");
        seedNoMatchQueueRow(titleId, null);
        // Insert a "done" row for the same title — should not appear
        jdbi.useHandle(h -> h.execute("""
                INSERT INTO javdb_enrichment_queue
                    (job_type, target_id, actress_id, source, priority, status, attempts,
                     next_attempt_at, last_error, created_at, updated_at)
                VALUES ('fetch_title',999,null,'actress','NORMAL','done',0,?,null,?,?)
                """, Instant.now().toString(), Instant.now().toString(), Instant.now().toString()));

        List<NoMatchTriageRepository.NoMatchRow> rows = repo.listNoMatchRows();
        assertEquals(1, rows.size());
        assertEquals(titleId, rows.get(0).titleId());
    }

    @Test
    void listNoMatchRows_excludesSentinelActresses() {
        long titleId = seedTitle("STAR-600");
        // Create a sentinel actress
        long sentinelId = jdbi.withHandle(h -> {
            h.execute("INSERT INTO actresses(canonical_name, stage_name, tier, first_seen_at, is_sentinel) "
                    + "VALUES ('__sentinel__','Sentinel','S','2024-01-01T00:00:00Z',1)");
            return h.createQuery("SELECT id FROM actresses WHERE canonical_name = '__sentinel__'")
                    .mapTo(Long.class).one();
        });
        seedTitleActress(titleId, sentinelId);
        seedNoMatchQueueRow(titleId, sentinelId);

        List<NoMatchTriageRepository.NoMatchRow> rows = repo.listNoMatchRows();
        assertEquals(1, rows.size());
        // The row should appear (it's a no-match) but the actress join should yield null
        // because sentinel is excluded from the actress join.
        assertNull(rows.get(0).actressId());
    }

    // ── findActressesByFilmographyCode ─────────────────────────────────────────

    @Test
    void findActressesByFilmographyCode_returnsMatchingActress() {
        long actressId = seedActress("Sora Aoi");
        seedFilmographyEntry(actressId, "sora-slug", "STAR-334", "title-slug-x");

        List<NoMatchTriageRepository.FilmographyCandidate> candidates =
                repo.findActressesByFilmographyCode("STAR-334");

        assertEquals(1, candidates.size());
        NoMatchTriageRepository.FilmographyCandidate c = candidates.get(0);
        assertEquals(actressId, c.actressId());
        assertEquals("Sora Aoi", c.stageName());
        assertEquals("sora-slug", c.javdbSlug());
        assertEquals("title-slug-x", c.titleSlug());
    }

    @Test
    void findActressesByFilmographyCode_returnsEmptyWhenNoMatch() {
        List<NoMatchTriageRepository.FilmographyCandidate> candidates =
                repo.findActressesByFilmographyCode("UNKNOWN-999");
        assertTrue(candidates.isEmpty());
    }

    @Test
    void findActressesByFilmographyCode_excludesSentinelActresses() {
        // Sentinel actress has filmography entry but should be excluded.
        long sentinelId = jdbi.withHandle(h -> {
            h.execute("INSERT INTO actresses(canonical_name, stage_name, tier, first_seen_at, is_sentinel) "
                    + "VALUES ('__s2__','SentinelS','S','2024-01-01T00:00:00Z',1)");
            return h.createQuery("SELECT id FROM actresses WHERE canonical_name = '__s2__'")
                    .mapTo(Long.class).one();
        });
        seedFilmographyEntry(sentinelId, "sentinel-slug", "CODE-001", "slugX");

        List<NoMatchTriageRepository.FilmographyCandidate> candidates =
                repo.findActressesByFilmographyCode("CODE-001");
        assertTrue(candidates.isEmpty(), "Sentinel actress must not appear as candidate");
    }

    @Test
    void findActressesByFilmographyCode_multipleActresses() {
        long a1 = seedActress("Alice");
        long a2 = seedActress("Betty");
        seedFilmographyEntry(a1, "alice-slug", "CODE-007", "title-a");
        seedFilmographyEntry(a2, "betty-slug", "CODE-007", "title-b");

        List<NoMatchTriageRepository.FilmographyCandidate> candidates =
                repo.findActressesByFilmographyCode("CODE-007");
        assertEquals(2, candidates.size());
    }

    // ── markResolved ─────────────────────────────────────────────────────────

    @Test
    void markResolved_setsCancelledStatus() {
        long titleId = seedTitle("ABCD-001");
        seedNoMatchQueueRow(titleId, null);

        boolean updated = repo.markResolved(titleId);
        assertTrue(updated);

        jdbi.withHandle(h -> {
            var row = h.createQuery("""
                    SELECT status, last_error FROM javdb_enrichment_queue
                    WHERE target_id = ? AND job_type = 'fetch_title'
                    """).bind(0, titleId)
                    .map((rs, ctx) -> new String[]{rs.getString("status"), rs.getString("last_error")})
                    .one();
            assertEquals("cancelled", row[0]);
            assertEquals("user_marked_no_javdb_data", row[1]);
            return null;
        });
    }

    @Test
    void markResolved_doesNotSetDone_regressionTest() {
        // Regression: markResolved must set status='cancelled', NEVER 'done'.
        long titleId = seedTitle("ABCD-002");
        seedNoMatchQueueRow(titleId, null);

        repo.markResolved(titleId);

        String status = jdbi.withHandle(h ->
                h.createQuery("SELECT status FROM javdb_enrichment_queue WHERE target_id = ?")
                        .bind(0, titleId).mapTo(String.class).one());
        assertNotEquals("done", status, "markResolved must never set status='done'");
        assertEquals("cancelled", status);
    }

    @Test
    void markResolved_returnsFalseWhenNotFound() {
        boolean updated = repo.markResolved(99999L);
        assertFalse(updated);
    }

    // ── clearNoMatchAndReQueue ────────────────────────────────────────────────

    @Test
    void clearNoMatchAndReQueue_removesFailedRowAndCreatesNewPending() {
        long titleId = seedTitle("STAR-200");
        long actressId = seedActress("Re-Queue Actress");
        seedTitleActress(titleId, actressId);
        seedNoMatchQueueRow(titleId, actressId);

        repo.clearNoMatchAndReQueue(titleId, null);

        jdbi.withHandle(h -> {
            // No more failed no-match row
            long failedCount = h.createQuery("""
                    SELECT COUNT(*) FROM javdb_enrichment_queue
                    WHERE target_id = ? AND status = 'failed' AND last_error = 'no_match_in_filmography'
                    """).bind(0, titleId).mapTo(Long.class).one();
            assertEquals(0L, failedCount, "Failed no-match row must be removed");

            // New pending row at HIGH priority
            String status = h.createQuery("""
                    SELECT status FROM javdb_enrichment_queue
                    WHERE target_id = ? AND job_type = 'fetch_title' AND status = 'pending'
                    """).bind(0, titleId).mapTo(String.class).one();
            assertEquals("pending", status);

            String priority = h.createQuery("""
                    SELECT priority FROM javdb_enrichment_queue
                    WHERE target_id = ? AND status = 'pending'
                    """).bind(0, titleId).mapTo(String.class).one();
            assertEquals("HIGH", priority);
            return null;
        });
    }

    @Test
    void clearNoMatchAndReQueue_withActressOverride_addsActressLink() {
        long titleId = seedTitle("STAR-201");
        seedNoMatchQueueRow(titleId, null);
        long actressId = seedActress("New Actress");

        repo.clearNoMatchAndReQueue(titleId, actressId);

        long linkCount = jdbi.withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM title_actresses WHERE title_id = ? AND actress_id = ?")
                        .bind(0, titleId).bind(1, actressId).mapTo(Long.class).one());
        assertEquals(1L, linkCount, "Actress link must be added when actressIdOverride is provided");
    }

    @Test
    void clearNoMatchAndReQueue_isIdempotentOnExistingLink() {
        long titleId = seedTitle("STAR-202");
        long actressId = seedActress("Existing Actress");
        seedTitleActress(titleId, actressId);
        seedNoMatchQueueRow(titleId, actressId);

        // Re-queuing with same actress should not create duplicate title_actresses row
        repo.clearNoMatchAndReQueue(titleId, actressId);

        long linkCount = jdbi.withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM title_actresses WHERE title_id = ? AND actress_id = ?")
                        .bind(0, titleId).bind(1, actressId).mapTo(Long.class).one());
        assertEquals(1L, linkCount, "title_actresses must not have duplicate rows");
    }

    // ── findFolderInfo ────────────────────────────────────────────────────────

    @Test
    void findFolderInfo_returnsPath() {
        long titleId = seedTitle("FIND-001");
        seedTitleLocation(titleId, "/stars/ActressName/FIND-001");

        Optional<NoMatchTriageRepository.FolderInfo> info = repo.findFolderInfo(titleId);
        assertTrue(info.isPresent());
        assertEquals("/stars/ActressName/FIND-001", info.get().path());
        assertEquals("vol1", info.get().volumeId());
    }

    @Test
    void findFolderInfo_returnsEmptyWhenNoLocation() {
        long titleId = seedTitle("FIND-002");
        Optional<NoMatchTriageRepository.FolderInfo> info = repo.findFolderInfo(titleId);
        assertTrue(info.isEmpty());
    }
}
