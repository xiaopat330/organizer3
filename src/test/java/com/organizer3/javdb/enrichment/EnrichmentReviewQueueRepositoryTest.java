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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EnrichmentReviewQueueRepositoryTest {

    private Jdbi jdbi;
    private Connection connection;
    private EnrichmentReviewQueueRepository repo;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        repo = new EnrichmentReviewQueueRepository(jdbi);
        jdbi.useHandle(h -> h.execute(
                "INSERT INTO titles(id, code, base_code, label, seq_num) VALUES (1, 'T-1', 'T', 'T', 1)"));
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    @Test
    void enqueue_insertsRow() {
        repo.enqueue(1L, "slug1", "cast_anomaly", "actress_filmography");

        assertTrue(repo.hasOpen(1L, "cast_anomaly"));
        assertEquals(1, repo.countOpen("cast_anomaly"));
    }

    @Test
    void enqueue_isIdempotent_forSameTitleAndReason() {
        repo.enqueue(1L, "slug1", "cast_anomaly", "actress_filmography");
        repo.enqueue(1L, "slug1", "cast_anomaly", "actress_filmography");

        assertEquals(1, repo.countOpen("cast_anomaly"), "duplicate enqueue must be a no-op");
    }

    @Test
    void enqueue_allowsDifferentReasons_forSameTitle() {
        repo.enqueue(1L, "slug1", "cast_anomaly",  "actress_filmography");
        repo.enqueue(1L, "slug1", "fetch_failed",  "code_search_fallback");

        assertEquals(1, repo.countOpen("cast_anomaly"));
        assertEquals(1, repo.countOpen("fetch_failed"));
        assertEquals(2, repo.countTotal("cast_anomaly") + repo.countTotal("fetch_failed"));
    }

    @Test
    void countOpen_excludesResolvedRows() {
        repo.enqueue(1L, "slug1", "ambiguous", "sentinel_short_circuit");
        jdbi.useHandle(h -> h.execute(
                "UPDATE enrichment_review_queue SET resolved_at = '2026-04-29T00:00:00Z' WHERE title_id = 1"));

        assertEquals(0, repo.countOpen("ambiguous"), "resolved row should not count as open");
        assertEquals(1, repo.countTotal("ambiguous"), "countTotal includes resolved rows");
    }

    @Test
    void hasOpen_returnsFalse_whenNoneOpen() {
        assertFalse(repo.hasOpen(1L, "cast_anomaly"));
    }

    @Test
    void hasOpen_returnsTrue_whenOpenRowExists() {
        repo.enqueue(1L, "slug1", "cast_anomaly", "actress_filmography");
        assertTrue(repo.hasOpen(1L, "cast_anomaly"));
    }

    @Test
    void enqueue_allowsReenqueue_afterResolution() {
        repo.enqueue(1L, "slug1", "cast_anomaly", "actress_filmography");
        jdbi.useHandle(h -> h.execute(
                "UPDATE enrichment_review_queue SET resolved_at = '2026-04-29T00:00:00Z'"));

        // A new open entry is allowed once the old one is resolved.
        repo.enqueue(1L, "slug1-v2", "cast_anomaly", "actress_filmography");

        assertEquals(1, repo.countOpen("cast_anomaly"));
        assertEquals(2, repo.countTotal("cast_anomaly"));
    }

    // ── listOpen ──────────────────────────────────────────────────────────────

    @Test
    void listOpen_returnsOnlyUnresolvedRows_orderedByCreatedAtDesc() {
        repo.enqueue(1L, "slug1", "ambiguous",   "sentinel_short_circuit");
        jdbi.useHandle(h -> h.execute(
                "INSERT INTO titles(id, code, base_code, label, seq_num) VALUES (2, 'T-2', 'T', 'T', 2)"));
        repo.enqueue(2L, "slug2", "cast_anomaly", "actress_filmography");
        // Resolve the ambiguous row
        jdbi.useHandle(h -> h.execute(
                "UPDATE enrichment_review_queue SET resolved_at = '2026-04-29T00:00:00Z' WHERE reason = 'ambiguous'"));

        List<EnrichmentReviewQueueRepository.OpenRow> rows = repo.listOpen(null, 100, 0);

        assertEquals(1, rows.size(), "only unresolved rows should be returned");
        assertEquals("cast_anomaly", rows.get(0).reason());
        assertEquals("T-2", rows.get(0).titleCode());
    }

    @Test
    void listOpen_filterByReason_returnsOnlyMatchingRows() {
        repo.enqueue(1L, "slug1", "ambiguous",   "sentinel_short_circuit");
        jdbi.useHandle(h -> h.execute(
                "INSERT INTO titles(id, code, base_code, label, seq_num) VALUES (2, 'T-2', 'T', 'T', 2)"));
        repo.enqueue(2L, "slug2", "cast_anomaly", "actress_filmography");

        List<EnrichmentReviewQueueRepository.OpenRow> filtered = repo.listOpen("ambiguous", 100, 0);

        assertEquals(1, filtered.size());
        assertEquals("ambiguous", filtered.get(0).reason());
        assertEquals("T-1", filtered.get(0).titleCode());
    }

    // ── countOpenByReason ─────────────────────────────────────────────────────

    @Test
    void countOpenByReason_returnsCorrectMap_excludesResolved() {
        repo.enqueue(1L, "slug1", "ambiguous", "sentinel_short_circuit");
        jdbi.useHandle(h -> {
            h.execute("INSERT INTO titles(id, code, base_code, label, seq_num) VALUES (2, 'T-2', 'T', 'T', 2)");
            h.execute("INSERT INTO titles(id, code, base_code, label, seq_num) VALUES (3, 'T-3', 'T', 'T', 3)");
        });
        repo.enqueue(2L, "slug2", "cast_anomaly", "actress_filmography");
        repo.enqueue(3L, "slug3", "ambiguous",    "code_search_fallback");
        // Resolve one ambiguous row
        jdbi.useHandle(h -> h.execute(
                "UPDATE enrichment_review_queue SET resolved_at = '2026-04-29T00:00:00Z' WHERE title_id = 3"));

        Map<String, Integer> counts = repo.countOpenByReason();

        assertEquals(1, counts.get("ambiguous"),   "one resolved row must be excluded");
        assertEquals(1, counts.get("cast_anomaly"));
        assertNull(counts.get("fetch_failed"), "absent reason must not appear");
    }

    // ── resolveOne ────────────────────────────────────────────────────────────

    @Test
    void resolveOne_marksRowResolved_andExcludesItFromListOpen() {
        repo.enqueue(1L, "slug1", "ambiguous", "sentinel_short_circuit");
        long id = jdbi.withHandle(h ->
                h.createQuery("SELECT id FROM enrichment_review_queue WHERE title_id = 1")
                        .mapTo(Long.class).one());

        boolean ok = repo.resolveOne(id, "accepted_gap");

        assertTrue(ok, "resolveOne should return true for an open row");

        String resolvedAt = jdbi.withHandle(h ->
                h.createQuery("SELECT resolved_at FROM enrichment_review_queue WHERE id = :id")
                        .bind("id", id).mapTo(String.class).one());
        assertNotNull(resolvedAt, "resolved_at must be set");

        String resolution = jdbi.withHandle(h ->
                h.createQuery("SELECT resolution FROM enrichment_review_queue WHERE id = :id")
                        .bind("id", id).mapTo(String.class).one());
        assertEquals("accepted_gap", resolution);

        // listOpen should no longer return the row
        List<EnrichmentReviewQueueRepository.OpenRow> open = repo.listOpen(null, 100, 0);
        assertTrue(open.stream().noneMatch(r -> r.id() == id), "resolved row must not appear in listOpen");
    }

    // ── resolveAllOpenForTitle ────────────────────────────────────────────────

    @Test
    void resolveAllOpenForTitle_resolvesAllOpenRowsForTitle_leavesOtherTitlesUntouched() {
        jdbi.useHandle(h -> h.execute(
                "INSERT INTO titles(id, code, base_code, label, seq_num) VALUES (2, 'T-2', 'T', 'T', 2)"));
        repo.enqueue(1L, "slug1", "cast_anomaly",  "actress_filmography");
        repo.enqueue(1L, "slug1", "ambiguous",     "code_search_fallback");
        repo.enqueue(2L, "slug2", "fetch_failed",  "code_search_fallback");

        jdbi.useHandle(h -> repo.resolveAllOpenForTitle(1L, "manual_override", h));

        assertEquals(0, repo.countOpen("cast_anomaly"), "cast_anomaly for title 1 must be resolved");
        assertEquals(0, repo.countOpen("ambiguous"),    "ambiguous for title 1 must be resolved");
        assertEquals(1, repo.countOpen("fetch_failed"), "row for title 2 must be untouched");

        String resolution = jdbi.withHandle(h ->
                h.createQuery("SELECT resolution FROM enrichment_review_queue WHERE title_id = 1 AND reason = 'cast_anomaly'")
                        .mapTo(String.class).one());
        assertEquals("manual_override", resolution);
    }

    @Test
    void resolveAllOpenForTitle_noOpenRows_returnsZero() {
        // No rows at all for title 1 → should not throw, just return 0
        int count = jdbi.withHandle(h -> repo.resolveAllOpenForTitle(1L, "manual_override", h));
        assertEquals(0, count);
    }

    @Test
    void resolveOne_alreadyResolved_returnsFalse_doesNotTouchRow() {
        repo.enqueue(1L, "slug1", "ambiguous", "sentinel_short_circuit");
        long id = jdbi.withHandle(h ->
                h.createQuery("SELECT id FROM enrichment_review_queue WHERE title_id = 1")
                        .mapTo(Long.class).one());
        repo.resolveOne(id, "accepted_gap");

        // Second call on same row
        boolean ok = repo.resolveOne(id, "marked_resolved");

        assertFalse(ok, "resolveOne on already-resolved row must return false");

        // resolution must remain the original value
        String resolution = jdbi.withHandle(h ->
                h.createQuery("SELECT resolution FROM enrichment_review_queue WHERE id = :id")
                        .bind("id", id).mapTo(String.class).one());
        assertEquals("accepted_gap", resolution, "already-resolved row must not be modified");
    }

    // ── enqueueWithDetail ─────────────────────────────────────────────────────

    @Test
    void enqueueWithDetail_insertsRowWithDetail() {
        String detail = "{\"code\":\"T-1\",\"candidates\":[]}";
        repo.enqueueWithDetail(1L, "slug1", "ambiguous", "code_search_fallback", detail);

        assertTrue(repo.hasOpen(1L, "ambiguous"));
        String stored = jdbi.withHandle(h ->
                h.createQuery("SELECT detail FROM enrichment_review_queue WHERE title_id = 1")
                        .mapTo(String.class).one());
        assertEquals(detail, stored);
    }

    @Test
    void enqueueWithDetail_isIdempotent_doesNotOverwriteExistingDetail() {
        String detail1 = "{\"code\":\"T-1\",\"candidates\":[{\"slug\":\"A\"}]}";
        String detail2 = "{\"code\":\"T-1\",\"candidates\":[{\"slug\":\"B\"}]}";
        repo.enqueueWithDetail(1L, "slug1", "ambiguous", "code_search_fallback", detail1);
        // Second call: INSERT OR IGNORE fires, detail2 must NOT overwrite detail1
        repo.enqueueWithDetail(1L, "slug1", "ambiguous", "code_search_fallback", detail2);

        String stored = jdbi.withHandle(h ->
                h.createQuery("SELECT detail FROM enrichment_review_queue WHERE title_id = 1")
                        .mapTo(String.class).one());
        assertEquals(detail1, stored, "existing detail must not be overwritten");
        assertEquals(1, repo.countOpen("ambiguous"), "still only one open row");
    }

    @Test
    void enqueueWithDetail_nullDetail_insertsRowWithoutDetail() {
        repo.enqueueWithDetail(1L, "slug1", "ambiguous", "code_search_fallback", null);

        assertTrue(repo.hasOpen(1L, "ambiguous"));
        String stored = jdbi.withHandle(h ->
                h.createQuery("SELECT detail FROM enrichment_review_queue WHERE title_id = 1")
                        .mapTo(String.class).one());
        assertNull(stored, "detail should remain NULL when null is supplied");
    }

    // ── findOpenById ──────────────────────────────────────────────────────────

    @Test
    void findOpenById_returnsRow_withDetail() {
        String detail = "{\"code\":\"T-1\",\"candidates\":[]}";
        repo.enqueueWithDetail(1L, "slug1", "ambiguous", "code_search_fallback", detail);
        long id = jdbi.withHandle(h ->
                h.createQuery("SELECT id FROM enrichment_review_queue WHERE title_id = 1")
                        .mapTo(Long.class).one());

        var found = repo.findOpenById(id);

        assertTrue(found.isPresent());
        assertEquals(1L, found.get().titleId());
        assertEquals("ambiguous", found.get().reason());
        assertEquals(detail, found.get().detail());
    }

    @Test
    void findOpenById_returnsEmpty_forResolvedRow() {
        repo.enqueue(1L, "slug1", "ambiguous", "code_search_fallback");
        long id = jdbi.withHandle(h ->
                h.createQuery("SELECT id FROM enrichment_review_queue WHERE title_id = 1")
                        .mapTo(Long.class).one());
        repo.resolveOne(id, "accepted_gap");

        assertTrue(repo.findOpenById(id).isEmpty(), "resolved row must not be returned");
    }

    @Test
    void findOpenById_returnsEmpty_forNonExistentId() {
        assertTrue(repo.findOpenById(9999L).isEmpty());
    }

    // ── findById (open + resolved) ────────────────────────────────────────────

    @Test
    void findById_returnsOpenRow() {
        repo.enqueue(1L, "slug1", "ambiguous", "code_search_fallback");
        long id = jdbi.withHandle(h ->
                h.createQuery("SELECT id FROM enrichment_review_queue WHERE title_id = 1")
                        .mapTo(Long.class).one());

        var found = repo.findById(id);

        assertTrue(found.isPresent());
        assertEquals(1L, found.get().titleId());
        assertEquals("ambiguous", found.get().reason());
    }

    @Test
    void findById_returnsResolvedRow() {
        repo.enqueue(1L, "slug1", "ambiguous", "code_search_fallback");
        long id = jdbi.withHandle(h ->
                h.createQuery("SELECT id FROM enrichment_review_queue WHERE title_id = 1")
                        .mapTo(Long.class).one());
        repo.resolveOne(id, "accepted_gap");

        var found = repo.findById(id);
        assertTrue(found.isPresent(), "findById must return resolved rows too");
        assertEquals(1L, found.get().titleId());
    }

    @Test
    void findById_returnsEmpty_forNonExistentId() {
        assertTrue(repo.findById(9999L).isEmpty());
    }

    // ── updateDetail ──────────────────────────────────────────────────────────

    @Test
    void updateDetail_setsDetailAndLastSeenAt() {
        repo.enqueue(1L, "slug1", "ambiguous", "code_search_fallback");
        long id = jdbi.withHandle(h ->
                h.createQuery("SELECT id FROM enrichment_review_queue WHERE title_id = 1")
                        .mapTo(Long.class).one());

        String freshDetail = "{\"code\":\"T-1\",\"candidates\":[{\"slug\":\"Z\"}]}";
        repo.updateDetail(id, freshDetail);

        var row = repo.findOpenById(id);
        assertTrue(row.isPresent());
        assertEquals(freshDetail, row.get().detail());

        String lastSeen = jdbi.withHandle(h ->
                h.createQuery("SELECT last_seen_at FROM enrichment_review_queue WHERE id = :id")
                        .bind("id", id).mapTo(String.class).one());
        assertNotNull(lastSeen, "last_seen_at should be set after updateDetail");
    }

    @Test
    void updateDetail_noopOnResolvedRow() {
        repo.enqueue(1L, "slug1", "ambiguous", "code_search_fallback");
        long id = jdbi.withHandle(h ->
                h.createQuery("SELECT id FROM enrichment_review_queue WHERE title_id = 1")
                        .mapTo(Long.class).one());
        repo.resolveOne(id, "accepted_gap");

        // updateDetail on a resolved row should not throw and should not update
        repo.updateDetail(id, "{\"fresh\":true}");

        String detail = jdbi.withHandle(h ->
                h.createQuery("SELECT detail FROM enrichment_review_queue WHERE id = :id")
                        .bind("id", id).mapTo(String.class).one());
        assertNull(detail, "resolved row detail must remain unchanged");
    }

    // --- enqueueOrphanFlag ---

    @Test
    void enqueueOrphanFlag_insertsOrphanEnrichedRow() {
        jdbi.useHandle(h -> repo.enqueueOrphanFlag(1L, "t-001", "{\"prior_path\":null}", h));

        assertTrue(repo.hasOpen(1L, "orphan_enriched"));
        var row = repo.findOpenById(
                jdbi.withHandle(h -> h.createQuery("SELECT id FROM enrichment_review_queue WHERE title_id = 1")
                        .mapTo(Long.class).one()));
        assertTrue(row.isPresent());
        assertEquals("orphan_enriched", row.get().reason());
        assertEquals("sync_orphan",     row.get().resolverSource());
        assertEquals("t-001",           row.get().slug());
        assertNotNull(row.get().detail());
    }

    @Test
    void enqueueOrphanFlag_isIdempotent() {
        jdbi.useHandle(h -> {
            repo.enqueueOrphanFlag(1L, "t-001", "{}", h);
            repo.enqueueOrphanFlag(1L, "t-001", "{}", h);
        });

        assertEquals(1, repo.countOpen("orphan_enriched"), "duplicate flag must be ignored");
    }

    @Test
    void enqueueOrphanFlag_nullDetail_allowed() {
        jdbi.useHandle(h -> repo.enqueueOrphanFlag(1L, "t-001", null, h));

        assertTrue(repo.hasOpen(1L, "orphan_enriched"));
        var row = repo.findOpenById(
                jdbi.withHandle(h -> h.createQuery("SELECT id FROM enrichment_review_queue WHERE title_id = 1")
                        .mapTo(Long.class).one()));
        assertTrue(row.isPresent());
        assertNull(row.get().detail());
    }

    // ── purgeStale ────────────────────────────────────────────────────────────

    // Use strftime to match the T-format that production rows use (schema DEFAULT) and
    // that Java's Instant.toString() produces. datetime() alone returns space-separated
    // format, which would always sort before a T-format string of the same date.
    private String isoAgo(String modifier) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT strftime('%Y-%m-%dT%H:%M:%fZ', datetime('now', :mod))")
                        .bind("mod", modifier)
                        .mapTo(String.class).one());
    }

    @Test
    void purgeStale_resolvesUnrecoverableRows_after72Hours() {
        repo.enqueue(1L, "slug1", "no_match", "actress_filmography");
        jdbi.useHandle(h -> h.createUpdate(
                "UPDATE enrichment_review_queue SET created_at = :ts WHERE title_id = 1")
                .bind("ts", isoAgo("-73 hours")).execute());

        int purged = repo.purgeStale();

        assertEquals(1, purged);
        assertFalse(repo.hasOpen(1L, "no_match"), "aged-out unrecoverable row must be resolved");
        String resolution = jdbi.withHandle(h ->
                h.createQuery("SELECT resolution FROM enrichment_review_queue WHERE title_id = 1")
                        .mapTo(String.class).one());
        assertEquals("aged_out", resolution);
    }

    @Test
    void purgeStale_doesNotResolve_unrecoverableRows_before72Hours() {
        repo.enqueue(1L, "slug1", "orphan_enriched", "sync_orphan");
        jdbi.useHandle(h -> h.createUpdate(
                "UPDATE enrichment_review_queue SET created_at = :ts WHERE title_id = 1")
                .bind("ts", isoAgo("-71 hours")).execute());

        int purged = repo.purgeStale();

        assertEquals(0, purged);
        assertTrue(repo.hasOpen(1L, "orphan_enriched"), "row within TTL must not be touched");
    }

    @Test
    void purgeStale_resolvesRecoverableRows_after7Days() {
        repo.enqueue(1L, "slug1", "cast_anomaly", "actress_filmography");
        jdbi.useHandle(h -> h.createUpdate(
                "UPDATE enrichment_review_queue SET created_at = :ts WHERE title_id = 1")
                .bind("ts", isoAgo("-8 days")).execute());

        int purged = repo.purgeStale();

        assertEquals(1, purged);
        assertFalse(repo.hasOpen(1L, "cast_anomaly"), "aged-out recoverable row must be resolved");
        String resolution = jdbi.withHandle(h ->
                h.createQuery("SELECT resolution FROM enrichment_review_queue WHERE title_id = 1")
                        .mapTo(String.class).one());
        assertEquals("aged_out", resolution);
    }

    @Test
    void purgeStale_doesNotResolve_recoverableRows_before7Days() {
        repo.enqueue(1L, "slug1", "ambiguous", "code_search_fallback");
        jdbi.useHandle(h -> h.createUpdate(
                "UPDATE enrichment_review_queue SET created_at = :ts WHERE title_id = 1")
                .bind("ts", isoAgo("-6 days")).execute());

        int purged = repo.purgeStale();

        assertEquals(0, purged);
        assertTrue(repo.hasOpen(1L, "ambiguous"), "row within TTL must not be touched");
    }

    @Test
    void purgeStale_doesNotTouchAlreadyResolvedRows() {
        repo.enqueue(1L, "slug1", "no_match", "actress_filmography");
        jdbi.useHandle(h -> h.createUpdate(
                "UPDATE enrichment_review_queue SET created_at = :ts, resolved_at = '2026-04-01T00:00:00Z', resolution = 'marked_resolved' WHERE title_id = 1")
                .bind("ts", isoAgo("-80 hours")).execute());

        int purged = repo.purgeStale();

        assertEquals(0, purged, "already-resolved rows must not be touched");
        String resolution = jdbi.withHandle(h ->
                h.createQuery("SELECT resolution FROM enrichment_review_queue WHERE title_id = 1")
                        .mapTo(String.class).one());
        assertEquals("marked_resolved", resolution, "existing resolution must not be overwritten");
    }

    // ── AI suggestion (Wave 2.D) ──────────────────────────────────────────────

    @Test
    void setAiSuggestion_persistsAllFourColumns_andExcludesRowFromAwaitingAi() {
        repo.enqueue(1L, "slug1", "ambiguous", "sentinel_short_circuit");
        long id = jdbi.withHandle(h ->
                h.createQuery("SELECT id FROM enrichment_review_queue WHERE title_id = 1")
                        .mapTo(Long.class).one());

        // Before: row appears in awaiting list with no AI fields set.
        List<EnrichmentReviewQueueRepository.OpenRow> before = repo.listOpenAwaitingAi(10);
        assertEquals(1, before.size());
        assertNull(before.get(0).aiSuggestionSlug());
        assertNull(before.get(0).aiSuggestionAt());
        assertFalse(before.get(0).aiAutoApplied());

        Instant at = Instant.parse("2026-05-17T12:00:00Z");
        repo.setAiSuggestion(id, "abc123", "high", "matched code prefix", at);

        // After: row is excluded from awaiting list.
        assertTrue(repo.listOpenAwaitingAi(10).isEmpty(),
                "row with ai_suggestion_at set must be excluded from awaiting-AI list");

        // Round-trip via findOpenById which now includes AI columns.
        EnrichmentReviewQueueRepository.OpenRow row = repo.findOpenById(id).orElseThrow();
        assertEquals("abc123", row.aiSuggestionSlug());
        assertEquals("high",   row.aiSuggestionConfidence());
        assertEquals("matched code prefix", row.aiSuggestionReason());
        assertEquals(at.toString(), row.aiSuggestionAt());
        assertFalse(row.aiAutoApplied());
    }

    @Test
    void setAiSuggestion_nullSlug_persistsAbstainCleanly() {
        repo.enqueue(1L, "slug1", "ambiguous", "sentinel_short_circuit");
        long id = jdbi.withHandle(h ->
                h.createQuery("SELECT id FROM enrichment_review_queue WHERE title_id = 1")
                        .mapTo(Long.class).one());

        Instant at = Instant.parse("2026-05-17T12:00:00Z");
        repo.setAiSuggestion(id, null, "abstain", "no confident match", at);

        EnrichmentReviewQueueRepository.OpenRow row = repo.findOpenById(id).orElseThrow();
        assertNull(row.aiSuggestionSlug(), "slug should persist as null for abstain");
        assertEquals("abstain", row.aiSuggestionConfidence());
        assertEquals("no confident match", row.aiSuggestionReason());
        assertEquals(at.toString(), row.aiSuggestionAt());

        // ai_suggestion_at IS NOT NULL, so abstained row is also excluded from awaiting list.
        assertTrue(repo.listOpenAwaitingAi(10).isEmpty());
    }

    @Test
    void markAiAutoApplied_setsFlagToOne() {
        repo.enqueue(1L, "slug1", "ambiguous", "sentinel_short_circuit");
        long id = jdbi.withHandle(h ->
                h.createQuery("SELECT id FROM enrichment_review_queue WHERE title_id = 1")
                        .mapTo(Long.class).one());

        repo.markAiAutoApplied(id);

        int flag = jdbi.withHandle(h ->
                h.createQuery("SELECT ai_auto_applied FROM enrichment_review_queue WHERE id = :id")
                        .bind("id", id).mapTo(Integer.class).one());
        assertEquals(1, flag);
    }

    @Test
    void listOpenAwaitingAi_returnsOnlyAmbiguousOpenRowsWithoutAiSuggestion() {
        // ambiguous, no AI suggestion → should appear
        repo.enqueue(1L, "slug1", "ambiguous", "sentinel_short_circuit");

        // cast_anomaly row → wrong reason, excluded
        jdbi.useHandle(h -> h.execute(
                "INSERT INTO titles(id, code, base_code, label, seq_num) VALUES (2, 'T-2', 'T', 'T', 2)"));
        repo.enqueue(2L, "slug2", "cast_anomaly", "actress_filmography");

        // ambiguous + resolved → excluded
        jdbi.useHandle(h -> h.execute(
                "INSERT INTO titles(id, code, base_code, label, seq_num) VALUES (3, 'T-3', 'T', 'T', 3)"));
        repo.enqueue(3L, "slug3", "ambiguous", "sentinel_short_circuit");
        jdbi.useHandle(h -> h.execute(
                "UPDATE enrichment_review_queue SET resolved_at = '2026-04-29T00:00:00Z' WHERE title_id = 3"));

        List<EnrichmentReviewQueueRepository.OpenRow> rows = repo.listOpenAwaitingAi(10);
        assertEquals(1, rows.size());
        assertEquals(1L, rows.get(0).titleId());
        assertEquals("ambiguous", rows.get(0).reason());
    }

    @Test
    void listOpenAwaitingAi_respectsLimit_andAscendingCreatedAtOrder() {
        // Three ambiguous open rows, controlled created_at so order is deterministic.
        jdbi.useHandle(h -> {
            h.execute("INSERT INTO titles(id, code, base_code, label, seq_num) VALUES (2, 'T-2', 'T', 'T', 2)");
            h.execute("INSERT INTO titles(id, code, base_code, label, seq_num) VALUES (3, 'T-3', 'T', 'T', 3)");
        });
        repo.enqueue(1L, "slug1", "ambiguous", "sentinel_short_circuit");
        repo.enqueue(2L, "slug2", "ambiguous", "sentinel_short_circuit");
        repo.enqueue(3L, "slug3", "ambiguous", "sentinel_short_circuit");
        jdbi.useHandle(h -> {
            h.execute("UPDATE enrichment_review_queue SET created_at = '2026-05-01T00:00:00Z' WHERE title_id = 1");
            h.execute("UPDATE enrichment_review_queue SET created_at = '2026-05-02T00:00:00Z' WHERE title_id = 2");
            h.execute("UPDATE enrichment_review_queue SET created_at = '2026-05-03T00:00:00Z' WHERE title_id = 3");
        });

        List<EnrichmentReviewQueueRepository.OpenRow> rows = repo.listOpenAwaitingAi(2);
        assertEquals(2, rows.size(), "limit must be honored");
        assertEquals(1L, rows.get(0).titleId(), "oldest first");
        assertEquals(2L, rows.get(1).titleId());
    }

    // ── findContextForAssist ──────────────────────────────────────────────────

    @Test
    void findContextForAssist_returnsLivePathAndCanonicalNames() {
        jdbi.useHandle(h -> {
            h.execute("INSERT INTO volumes(id, structure_type) VALUES ('vol-a', 'flat')");
            // One live location + one stale location — only the live one is returned.
            h.execute("INSERT INTO title_locations(title_id, volume_id, partition_id, path, last_seen_at) "
                    + "VALUES (1, 'vol-a', 'p1', '/live/Yu Tano/T-1', '2026-05-17T00:00:00Z')");
            h.execute("INSERT INTO title_locations(title_id, volume_id, partition_id, path, last_seen_at, stale_since) "
                    + "VALUES (1, 'vol-a', 'p1', '/stale/old/T-1', '2026-04-01T00:00:00Z', '2026-04-02T00:00:00Z')");
            // Two linked actresses + one sentinel — sentinel must be excluded, names sorted.
            h.execute("INSERT INTO actresses(id, canonical_name, tier, first_seen_at, is_sentinel) "
                    + "VALUES (10, 'Yu Tano', 'A', '2026-01-01T00:00:00Z', 0)");
            h.execute("INSERT INTO actresses(id, canonical_name, tier, first_seen_at, is_sentinel) "
                    + "VALUES (11, 'Mika Azuma', 'A', '2026-01-01T00:00:00Z', 0)");
            h.execute("INSERT INTO actresses(id, canonical_name, tier, first_seen_at, is_sentinel) "
                    + "VALUES (12, 'Various', 'A', '2026-01-01T00:00:00Z', 1)");
            h.execute("INSERT INTO title_actresses(title_id, actress_id) VALUES (1, 10)");
            h.execute("INSERT INTO title_actresses(title_id, actress_id) VALUES (1, 11)");
            h.execute("INSERT INTO title_actresses(title_id, actress_id) VALUES (1, 12)");
        });

        var ctx = repo.findContextForAssist(1L);

        assertEquals("/live/Yu Tano/T-1", ctx.folderPath());
        assertEquals(List.of("Mika Azuma", "Yu Tano"), ctx.actressNames(),
                "sentinel actress must be excluded; results sorted by canonical_name");
    }

    @Test
    void findContextForAssist_orphanTitle_nullPathButReturnsActresses() {
        // Title 1 has no title_locations at all. Still return any linked actress names.
        jdbi.useHandle(h -> {
            h.execute("INSERT INTO actresses(id, canonical_name, tier, first_seen_at, is_sentinel) "
                    + "VALUES (20, 'Hana Ito', 'A', '2026-01-01T00:00:00Z', 0)");
            h.execute("INSERT INTO title_actresses(title_id, actress_id) VALUES (1, 20)");
        });

        var ctx = repo.findContextForAssist(1L);

        assertNull(ctx.folderPath(), "no live location → null path");
        assertEquals(List.of("Hana Ito"), ctx.actressNames());
    }

    @Test
    void findContextForAssist_unknownTitle_returnsEmptyContext() {
        var ctx = repo.findContextForAssist(9999L);
        assertNull(ctx.folderPath());
        assertTrue(ctx.actressNames().isEmpty());
    }

    // ── listAutoApplyReady (Phase 3 Track A) ──────────────────────────────────

    /** Sets ai_suggestion_* columns on a queue row, with ai_suggestion_at backdated by N seconds. */
    private void setAgedSuggestion(long titleId, String slug, String confidence, int ageSeconds) {
        long id = jdbi.withHandle(h ->
                h.createQuery("SELECT id FROM enrichment_review_queue WHERE title_id = :tid")
                        .bind("tid", titleId).mapTo(Long.class).one());
        String at = Instant.now().minusSeconds(ageSeconds).toString();
        jdbi.useHandle(h -> h.createUpdate("""
                        UPDATE enrichment_review_queue
                        SET ai_suggestion_slug = :slug,
                            ai_suggestion_confidence = :confidence,
                            ai_suggestion_reason = 'test',
                            ai_suggestion_at = :at
                        WHERE id = :id
                        """)
                .bind("slug",       slug)
                .bind("confidence", confidence)
                .bind("at",         at)
                .bind("id",         id)
                .execute());
    }

    @Test
    void listAutoApplyReady_returnsEligibleAgedAgreedRow() {
        repo.enqueue(1L, "slug1", "ambiguous", "sentinel_short_circuit");
        setAgedSuggestion(1L, "abc123", "agreed", 600); // 10 min old

        List<EnrichmentReviewQueueRepository.OpenRow> rows = repo.listAutoApplyReady(10, 300, Integer.MAX_VALUE);
        assertEquals(1, rows.size());
        assertEquals(1L, rows.get(0).titleId());
        assertEquals("abc123", rows.get(0).aiSuggestionSlug());
    }

    @Test
    void listAutoApplyReady_excludesRowYoungerThanMinAge() {
        repo.enqueue(1L, "slug1", "ambiguous", "sentinel_short_circuit");
        setAgedSuggestion(1L, "abc123", "agreed", 60); // 1 min old

        assertTrue(repo.listAutoApplyReady(10, 300, Integer.MAX_VALUE).isEmpty(),
                "row younger than minAgeSeconds must be excluded");
    }

    @Test
    void listAutoApplyReady_excludesConflictOutcome() {
        repo.enqueue(1L, "slug1", "ambiguous", "sentinel_short_circuit");
        setAgedSuggestion(1L, "abc123", "conflict", 600);

        assertTrue(repo.listAutoApplyReady(10, 300, Integer.MAX_VALUE).isEmpty(),
                "confidence='conflict' must be excluded");
    }

    @Test
    void listAutoApplyReady_excludesPhi4OnlyOutcome() {
        repo.enqueue(1L, "slug1", "ambiguous", "sentinel_short_circuit");
        setAgedSuggestion(1L, "abc123", "phi4_only", 600);

        assertTrue(repo.listAutoApplyReady(10, 300, Integer.MAX_VALUE).isEmpty(),
                "confidence='phi4_only' must be excluded");
    }

    @Test
    void listAutoApplyReady_excludesAlreadyAppliedRow() {
        repo.enqueue(1L, "slug1", "ambiguous", "sentinel_short_circuit");
        setAgedSuggestion(1L, "abc123", "agreed", 600);
        long id = jdbi.withHandle(h ->
                h.createQuery("SELECT id FROM enrichment_review_queue WHERE title_id = 1")
                        .mapTo(Long.class).one());
        repo.markAiAutoApplied(id);

        assertTrue(repo.listAutoApplyReady(10, 300, Integer.MAX_VALUE).isEmpty(),
                "ai_auto_applied=1 must be excluded");
    }

    @Test
    void listAutoApplyReady_excludesResolvedRow() {
        repo.enqueue(1L, "slug1", "ambiguous", "sentinel_short_circuit");
        setAgedSuggestion(1L, "abc123", "agreed", 600);
        jdbi.useHandle(h -> h.execute(
                "UPDATE enrichment_review_queue SET resolved_at = '2026-05-17T00:00:00Z' WHERE title_id = 1"));

        assertTrue(repo.listAutoApplyReady(10, 300, Integer.MAX_VALUE).isEmpty(),
                "resolved row must be excluded");
    }

    @Test
    void listAutoApplyReady_excludesAgreedRowWithNullSlug() {
        repo.enqueue(1L, "slug1", "ambiguous", "sentinel_short_circuit");
        // Defensive: ai_suggestion_slug NULL but confidence='agreed' shouldn't happen,
        // but the predicate must still reject it.
        setAgedSuggestion(1L, null, "agreed", 600);

        assertTrue(repo.listAutoApplyReady(10, 300, Integer.MAX_VALUE).isEmpty(),
                "ai_suggestion_slug IS NULL must be excluded");
    }

    @Test
    void listAutoApplyReady_honorsLimit_andOrdersBySuggestionAtAsc() {
        jdbi.useHandle(h -> {
            h.execute("INSERT INTO titles(id, code, base_code, label, seq_num) VALUES (2, 'T-2', 'T', 'T', 2)");
            h.execute("INSERT INTO titles(id, code, base_code, label, seq_num) VALUES (3, 'T-3', 'T', 'T', 3)");
        });
        repo.enqueue(1L, "slug1", "ambiguous", "sentinel_short_circuit");
        repo.enqueue(2L, "slug2", "ambiguous", "sentinel_short_circuit");
        repo.enqueue(3L, "slug3", "ambiguous", "sentinel_short_circuit");
        setAgedSuggestion(1L, "a1", "agreed", 900); // oldest
        setAgedSuggestion(2L, "a2", "agreed", 600);
        setAgedSuggestion(3L, "a3", "agreed", 400); // youngest

        List<EnrichmentReviewQueueRepository.OpenRow> rows = repo.listAutoApplyReady(2, 300, Integer.MAX_VALUE);
        assertEquals(2, rows.size(), "limit must be honored");
        assertEquals(1L, rows.get(0).titleId(), "oldest ai_suggestion_at first");
        assertEquals(2L, rows.get(1).titleId());
    }

    // ── Phase 4 Track D: ai_auto_apply_attempts max-attempt guard ─────────────

    private long queueRowIdForTitle(long titleId) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT id FROM enrichment_review_queue WHERE title_id = :tid")
                        .bind("tid", titleId).mapTo(Long.class).one());
    }

    private int attemptsForTitle(long titleId) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT ai_auto_apply_attempts FROM enrichment_review_queue WHERE title_id = :tid")
                        .bind("tid", titleId).mapTo(Integer.class).one());
    }

    @Test
    void listAutoApplyReady_attemptsZero_isEligibleWithMaxAttemptsThree() {
        repo.enqueue(1L, "slug1", "ambiguous", "sentinel_short_circuit");
        setAgedSuggestion(1L, "abc123", "agreed", 600);

        var rows = repo.listAutoApplyReady(10, 300, 3);
        assertEquals(1, rows.size(), "row with attempts=0 must be eligible under maxAttempts=3");
        assertEquals(0, rows.get(0).aiAutoApplyAttempts());
    }

    @Test
    void listAutoApplyReady_attemptsTwo_isStillEligibleWithMaxAttemptsThree() {
        repo.enqueue(1L, "slug1", "ambiguous", "sentinel_short_circuit");
        setAgedSuggestion(1L, "abc123", "agreed", 600);
        long id = queueRowIdForTitle(1L);
        repo.incrementAutoApplyAttempts(id);
        repo.incrementAutoApplyAttempts(id);

        assertEquals(2, attemptsForTitle(1L));
        var rows = repo.listAutoApplyReady(10, 300, 3);
        assertEquals(1, rows.size(), "row with attempts=2 must still be eligible under maxAttempts=3");
    }

    @Test
    void listAutoApplyReady_attemptsAtMax_isExcluded() {
        repo.enqueue(1L, "slug1", "ambiguous", "sentinel_short_circuit");
        setAgedSuggestion(1L, "abc123", "agreed", 600);
        long id = queueRowIdForTitle(1L);
        repo.incrementAutoApplyAttempts(id);
        repo.incrementAutoApplyAttempts(id);
        repo.incrementAutoApplyAttempts(id);

        assertEquals(3, attemptsForTitle(1L));
        assertTrue(repo.listAutoApplyReady(10, 300, 3).isEmpty(),
                "row with attempts=3 must be excluded under maxAttempts=3");
    }

    @Test
    void incrementAutoApplyAttempts_walksZeroToThree_andRowDropsOutAtMax() {
        repo.enqueue(1L, "slug1", "ambiguous", "sentinel_short_circuit");
        setAgedSuggestion(1L, "abc123", "agreed", 600);
        long id = queueRowIdForTitle(1L);

        assertEquals(0, attemptsForTitle(1L));
        assertFalse(repo.listAutoApplyReady(10, 300, 3).isEmpty(), "eligible at attempts=0");

        repo.incrementAutoApplyAttempts(id);
        assertEquals(1, attemptsForTitle(1L));
        assertFalse(repo.listAutoApplyReady(10, 300, 3).isEmpty(), "eligible at attempts=1");

        repo.incrementAutoApplyAttempts(id);
        assertEquals(2, attemptsForTitle(1L));
        assertFalse(repo.listAutoApplyReady(10, 300, 3).isEmpty(), "eligible at attempts=2");

        repo.incrementAutoApplyAttempts(id);
        assertEquals(3, attemptsForTitle(1L));
        assertTrue(repo.listAutoApplyReady(10, 300, 3).isEmpty(),
                "ineligible at attempts=3 (reached maxAttempts)");
    }
}
