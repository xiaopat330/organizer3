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
    void setAiSuggestion_withPerModelSlugs_persistsPhiAndGemmaColumns() {
        repo.enqueue(1L, "slug1", "ambiguous", "sentinel_short_circuit");
        long id = jdbi.withHandle(h ->
                h.createQuery("SELECT id FROM enrichment_review_queue WHERE title_id = 1")
                        .mapTo(Long.class).one());

        Instant at = Instant.parse("2026-05-18T10:00:00Z");
        repo.setAiSuggestion(id, "phi-slug", "phi4_only", "phi4 picked uniquely", at,
                "phi-slug", null);

        EnrichmentReviewQueueRepository.OpenRow row = repo.findOpenById(id).orElseThrow();
        assertEquals("phi-slug", row.aiSuggestionSlug());
        assertEquals("phi-slug", row.aiPhi4Slug(),  "phi4 slug must persist");
        assertNull(row.aiGemmaSlug(),               "gemma slug must be null (abstained)");
    }

    @Test
    void setAiSuggestion_withBothModelSlugs_persistsBothColumns() {
        repo.enqueue(1L, "slug1", "ambiguous", "sentinel_short_circuit");
        long id = jdbi.withHandle(h ->
                h.createQuery("SELECT id FROM enrichment_review_queue WHERE title_id = 1")
                        .mapTo(Long.class).one());

        Instant at = Instant.parse("2026-05-18T10:00:00Z");
        repo.setAiSuggestion(id, "agreed-slug", "agreed", "both agreed", at,
                "agreed-slug", "agreed-slug");

        EnrichmentReviewQueueRepository.OpenRow row = repo.findOpenById(id).orElseThrow();
        assertEquals("agreed-slug", row.aiPhi4Slug());
        assertEquals("agreed-slug", row.aiGemmaSlug());
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

    // ── AI-assist dashboard aggregates ───────────────────────────────────────

    /** Helper: insert a title, enqueue an ambiguous row, then set an AI suggestion. */
    private long insertTitleWithSuggestion(long titleId, String code, String confidence, String slug, String at,
                                           boolean autoApplied) {
        jdbi.useHandle(h -> h.createUpdate(
                "INSERT OR IGNORE INTO titles(id, code, base_code, label, seq_num) VALUES (:id, :code, :bc, :bc, :id)")
                .bind("id",   titleId)
                .bind("code", code)
                .bind("bc",   code)
                .execute());
        repo.enqueue(titleId, slug, "ambiguous", "sentinel_short_circuit");
        long rowId = jdbi.withHandle(h ->
                h.createQuery("SELECT id FROM enrichment_review_queue WHERE title_id = :tid")
                        .bind("tid", titleId).mapTo(Long.class).one());
        jdbi.useHandle(h -> h.createUpdate("""
                UPDATE enrichment_review_queue
                SET ai_suggestion_slug       = :slug,
                    ai_suggestion_confidence = :confidence,
                    ai_suggestion_reason     = 'test',
                    ai_suggestion_at         = :at,
                    ai_auto_applied          = :applied
                WHERE id = :id
                """)
                .bind("slug",       slug)
                .bind("confidence", confidence)
                .bind("at",         at)
                .bind("applied",    autoApplied ? 1 : 0)
                .bind("id",         rowId)
                .execute());
        return rowId;
    }

    // ── Bulk "apply all agreed" selection set (guards against widening) ───────

    /**
     * Inserts a title + queue row with the given reason and AI-suggestion columns, then
     * optionally resolves it / marks it auto-applied. Returns the queue row id.
     */
    private long insertMatrixRow(long titleId, String reason, String confidence, String slug,
                                 boolean resolved, boolean autoApplied) {
        jdbi.useHandle(h -> h.createUpdate(
                "INSERT OR IGNORE INTO titles(id, code, base_code, label, seq_num) VALUES (:id, :code, :bc, :bc, :id)")
                .bind("id",   titleId)
                .bind("code", "M-" + titleId)
                .bind("bc",   "M")
                .execute());
        repo.enqueue(titleId, slug, reason, "sentinel_short_circuit");
        long rowId = jdbi.withHandle(h ->
                h.createQuery("SELECT id FROM enrichment_review_queue WHERE title_id = :tid")
                        .bind("tid", titleId).mapTo(Long.class).one());
        jdbi.useHandle(h -> h.createUpdate("""
                UPDATE enrichment_review_queue
                SET ai_suggestion_slug       = :slug,
                    ai_suggestion_confidence = :confidence,
                    ai_suggestion_reason     = 'test',
                    ai_suggestion_at         = '2026-05-20T10:00:00Z',
                    ai_auto_applied          = :applied,
                    resolved_at              = :resolvedAt
                WHERE id = :id
                """)
                .bind("slug",       slug)
                .bind("confidence", confidence)
                .bind("applied",    autoApplied ? 1 : 0)
                .bind("resolvedAt", resolved ? "2026-05-20T11:00:00Z" : null)
                .bind("id",         rowId)
                .execute());
        return rowId;
    }

    @Test
    void bulkApplySelection_pinsExactlyAgreedUnresolvedUnappliedAmbiguousRows() {
        java.util.Set<Long> expected = new java.util.HashSet<>();

        // Eligible: agreed + ambiguous + unresolved + not-applied + slug set.
        expected.add(insertMatrixRow(100L, "ambiguous", "agreed", "s100", false, false));

        // Excluded by outcome (all ambiguous, unresolved, not-applied, slug set):
        insertMatrixRow(101L, "ambiguous", "agreed_with_override", "s101", false, false);
        insertMatrixRow(102L, "ambiguous", "conflict",             "s102", false, false);
        insertMatrixRow(103L, "ambiguous", "both_abstain",         "s103", false, false);
        insertMatrixRow(104L, "ambiguous", "phi4_only",            "s104", false, false);
        insertMatrixRow(105L, "ambiguous", "gemma_only",           "s105", false, false);
        insertMatrixRow(106L, "ambiguous", "error",                "s106", false, false);

        // Excluded by state (agreed + ambiguous + slug set):
        long resolvedAgreed = insertMatrixRow(107L, "ambiguous", "agreed", "s107", true,  false);
        long appliedAgreed  = insertMatrixRow(108L, "ambiguous", "agreed", "s108", false, true);

        // Excluded by reason gating: agreed but reason='cast_anomaly'.
        long castAnomalyAgreed = insertMatrixRow(109L, "cast_anomaly", "agreed", "s109", false, false);

        // Defensive: agreed + ambiguous + unresolved + not-applied but NULL slug → excluded.
        insertMatrixRow(110L, "ambiguous", "agreed", null, false, false);

        List<EnrichmentReviewQueueRepository.OpenRow> rows =
                repo.listAutoApplyReady(1000, 0, Integer.MAX_VALUE);
        java.util.Set<Long> returned = new java.util.HashSet<>();
        rows.forEach(r -> returned.add(r.id()));

        assertEquals(expected, returned,
                "apply set must be exactly the agreed + unresolved + not-applied + ambiguous + slug rows");

        // Explicit absence assertions for the high-risk near-misses.
        assertFalse(returned.contains(resolvedAgreed),    "resolved agreed row must be absent");
        assertFalse(returned.contains(appliedAgreed),     "already-applied agreed row must be absent");
        assertFalse(returned.contains(castAnomalyAgreed), "cast_anomaly agreed row must be absent");

        assertEquals(expected.size(), repo.countAgreedReadyToApply(),
                "countAgreedReadyToApply must match the apply-set size");
    }

    @Test
    void countAwaitingAi_returnsOnlyAmbiguousOpenRowsWithNoSuggestion() {
        // title 1: ambiguous + open + no suggestion → counts
        repo.enqueue(1L, "slug1", "ambiguous", "sentinel_short_circuit");

        // title 2: ambiguous + open + suggestion already set → does NOT count
        jdbi.useHandle(h -> h.execute(
                "INSERT INTO titles(id, code, base_code, label, seq_num) VALUES (2, 'T-2', 'T', 'T', 2)"));
        insertTitleWithSuggestion(2L, "T-2", "agreed", "s2", "2026-05-01T12:00:00Z", false);

        // title 3: cast_anomaly (wrong reason) → does NOT count
        jdbi.useHandle(h -> h.execute(
                "INSERT OR IGNORE INTO titles(id, code, base_code, label, seq_num) VALUES (3, 'T-3', 'T', 'T', 3)"));
        repo.enqueue(3L, "slug3", "cast_anomaly", "actress_filmography");

        assertEquals(1, repo.countAwaitingAi());
    }

    @Test
    void countAwaitingAi_excludesResolvedRows() {
        repo.enqueue(1L, "slug1", "ambiguous", "sentinel_short_circuit");
        jdbi.useHandle(h -> h.execute(
                "UPDATE enrichment_review_queue SET resolved_at = '2026-05-01T00:00:00Z' WHERE title_id = 1"));

        assertEquals(0, repo.countAwaitingAi(), "resolved row must not count as awaiting");
    }

    @Test
    void countProcessed_countsAllRowsWithSuggestionAt() {
        // Two rows with suggestion; one open, one resolved
        insertTitleWithSuggestion(2L, "T-2", "agreed",   "s2", "2026-05-01T10:00:00Z", false);
        insertTitleWithSuggestion(3L, "T-3", "conflict", "s3", "2026-05-01T11:00:00Z", false);
        jdbi.useHandle(h -> h.execute(
                "INSERT OR IGNORE INTO titles(id, code, base_code, label, seq_num) VALUES (3, 'T-3', 'T', 'T', 3)"));
        jdbi.useHandle(h -> h.execute(
                "UPDATE enrichment_review_queue SET resolved_at = '2026-05-01T12:00:00Z' WHERE title_id = 3"));

        // title 1 has no suggestion → excluded
        repo.enqueue(1L, "slug1", "ambiguous", "sentinel_short_circuit");

        assertEquals(2, repo.countProcessed());
    }

    @Test
    void countAutoApplied_countsOnlyAutoAppliedRows() {
        insertTitleWithSuggestion(2L, "T-2", "agreed", "s2", "2026-05-01T10:00:00Z", true);
        insertTitleWithSuggestion(3L, "T-3", "agreed", "s3", "2026-05-01T11:00:00Z", false);

        assertEquals(1, repo.countAutoApplied());
    }

    @Test
    void outcomeCounts_groupsByConfidence_andMapsNullToUnknown() {
        insertTitleWithSuggestion(2L, "T-2", "agreed",   "s2", "2026-05-01T10:00:00Z", false);
        insertTitleWithSuggestion(3L, "T-3", "conflict", "s3", "2026-05-01T11:00:00Z", false);
        // Insert a row whose confidence is NULL (simulate old/error row)
        jdbi.useHandle(h -> {
            h.execute("INSERT OR IGNORE INTO titles(id, code, base_code, label, seq_num) VALUES (4, 'T-4', 'T', 'T', 4)");
            repo.enqueue(4L, "s4", "ambiguous", "sentinel_short_circuit");
            h.execute("UPDATE enrichment_review_queue SET ai_suggestion_at = '2026-05-01T12:00:00Z', ai_suggestion_confidence = NULL WHERE title_id = 4");
        });

        Map<String, Integer> counts = repo.outcomeCounts();

        assertEquals(1, counts.get("agreed"),   "agreed count");
        assertEquals(1, counts.get("conflict"),  "conflict count");
        assertEquals(1, counts.get("unknown"),   "null confidence mapped to 'unknown'");
        assertNull(counts.get("phi4_only"),       "absent outcome must not appear");
    }

    @Test
    void outcomeCounts_multipleRowsSameOutcome_aggregated() {
        insertTitleWithSuggestion(2L, "T-2", "agreed", "s2", "2026-05-01T10:00:00Z", false);
        insertTitleWithSuggestion(3L, "T-3", "agreed", "s3", "2026-05-01T11:00:00Z", false);

        Map<String, Integer> counts = repo.outcomeCounts();
        assertEquals(2, counts.get("agreed"));
    }

    @Test
    void listRecentlyProcessed_orderedDescByAiSuggestionAt() {
        insertTitleWithSuggestion(2L, "T-2", "agreed",   "s2", "2026-05-01T10:00:00Z", false);
        insertTitleWithSuggestion(3L, "T-3", "conflict", "s3", "2026-05-02T10:00:00Z", false);

        List<EnrichmentReviewQueueRepository.RecentProcessedRow> rows =
                repo.listRecentlyProcessed(10, null);

        assertEquals(2, rows.size());
        assertEquals("T-3", rows.get(0).code(), "most recent first");
        assertEquals("T-2", rows.get(1).code());
    }

    @Test
    void listRecentlyProcessed_limitsResults() {
        insertTitleWithSuggestion(2L, "T-2", "agreed", "s2", "2026-05-01T10:00:00Z", false);
        insertTitleWithSuggestion(3L, "T-3", "agreed", "s3", "2026-05-02T10:00:00Z", false);

        List<EnrichmentReviewQueueRepository.RecentProcessedRow> rows =
                repo.listRecentlyProcessed(1, null);

        assertEquals(1, rows.size(), "limit must be honored");
        assertEquals("T-3", rows.get(0).code(), "most recent first even with limit");
    }

    @Test
    void listRecentlyProcessed_sinceFilter_excludesOlderRows() {
        insertTitleWithSuggestion(2L, "T-2", "agreed", "s2", "2026-05-01T10:00:00Z", false);
        insertTitleWithSuggestion(3L, "T-3", "agreed", "s3", "2026-05-02T10:00:00Z", false);

        // since = 2026-05-01T10:00:00Z means rows AFTER that timestamp only
        List<EnrichmentReviewQueueRepository.RecentProcessedRow> rows =
                repo.listRecentlyProcessed(10, "2026-05-01T10:00:00Z");

        assertEquals(1, rows.size(), "only rows after since must be returned");
        assertEquals("T-3", rows.get(0).code());
    }

    @Test
    void listRecentlyProcessed_sinceBlank_treatedAsNoFilter() {
        insertTitleWithSuggestion(2L, "T-2", "agreed", "s2", "2026-05-01T10:00:00Z", false);
        insertTitleWithSuggestion(3L, "T-3", "agreed", "s3", "2026-05-02T10:00:00Z", false);

        List<EnrichmentReviewQueueRepository.RecentProcessedRow> rows =
                repo.listRecentlyProcessed(10, "  ");

        assertEquals(2, rows.size(), "blank since must behave as no filter");
    }

    @Test
    void listRecentlyProcessed_excludesRowsWithNoSuggestion() {
        // title 1: no suggestion
        repo.enqueue(1L, "slug1", "ambiguous", "sentinel_short_circuit");
        insertTitleWithSuggestion(2L, "T-2", "agreed", "s2", "2026-05-01T10:00:00Z", false);

        List<EnrichmentReviewQueueRepository.RecentProcessedRow> rows =
                repo.listRecentlyProcessed(10, null);

        assertEquals(1, rows.size());
        assertEquals("T-2", rows.get(0).code());
    }

    @Test
    void listRecentlyProcessed_recordFields_mappedCorrectly() {
        insertTitleWithSuggestion(2L, "T-2", "agreed", "abc-slug", "2026-05-01T10:00:00Z", true);

        List<EnrichmentReviewQueueRepository.RecentProcessedRow> rows =
                repo.listRecentlyProcessed(10, null);

        assertEquals(1, rows.size());
        EnrichmentReviewQueueRepository.RecentProcessedRow r = rows.get(0);
        assertEquals("T-2",              r.code());
        assertEquals("agreed",           r.outcome());
        assertEquals("abc-slug",         r.slug());
        assertEquals("test",             r.reason());
        assertTrue(r.autoApplied(),      "autoApplied must be true");
        assertEquals("2026-05-01T10:00:00Z", r.at());
        assertTrue(r.reviewQueueId() > 0, "reviewQueueId must be a positive db id");
    }

    // ── findCastAnomalyContext — cast gender filter ───────────────────────────

    @Test
    void findCastAnomalyContext_maleEntriesFilteredFromReturnedCastJson() {
        // Seed a mixed cast_json with 1 female + 2 males into title_javdb_enrichment.
        String mixedCast = "[" +
                "{\"slug\":\"f-slug\",\"name\":\"Female One\",\"gender\":\"F\"}," +
                "{\"slug\":\"m-slug\",\"name\":\"Male One\",\"gender\":\"M\"}" +
                "]";
        jdbi.useHandle(h ->
                h.execute("INSERT INTO title_javdb_enrichment(title_id, javdb_slug, cast_json, fetched_at) " +
                           "VALUES(1, 'tst-slug', ?, '2026-01-01T00:00:00Z')", mixedCast));

        var ctx = repo.findCastAnomalyContext(1L);
        assertTrue(ctx.isPresent());

        String returnedCast = ctx.get().castJson();
        assertTrue(returnedCast.contains("f-slug"),  "female slug must be present");
        assertFalse(returnedCast.contains("m-slug"), "male slug must be filtered out");

        // Stored row must be untouched.
        String storedCast = jdbi.withHandle(h ->
                h.createQuery("SELECT cast_json FROM title_javdb_enrichment WHERE title_id = 1")
                        .mapTo(String.class).one());
        assertTrue(storedCast.contains("m-slug"), "stored cast_json must retain male entry");
    }
}
