package com.organizer3.sync;

import com.organizer3.db.SchemaInitializer;
import com.organizer3.javdb.enrichment.EnrichmentReviewQueueRepository;
import com.organizer3.model.Title;
import com.organizer3.repository.jdbi.JdbiTitlePathHistoryRepository;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link SyncIdentityMatcher} — verifies that the soft-match identity
 * heuristics correctly detect recode and actress-rename candidates, apply the
 * catastrophic guard, and write queue rows correctly.
 *
 * <p>Uses real in-memory SQLite to exercise the full SQL load and queue-write paths.
 */
class SyncIdentityMatcherTest {

    private Connection connection;
    private Jdbi jdbi;
    private EnrichmentReviewQueueRepository reviewQueueRepo;
    private JdbiTitlePathHistoryRepository pathHistoryRepo;
    private SyncIdentityMatcher matcher;
    private SyncIdentityMatcher matcherWithHistory;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        reviewQueueRepo = new EnrichmentReviewQueueRepository(jdbi);
        pathHistoryRepo = new JdbiTitlePathHistoryRepository(jdbi);
        // Default matcher without path history (backward compat)
        matcher = new SyncIdentityMatcher(jdbi, reviewQueueRepo);
        // Matcher with path history for path-history fallback tests
        matcherWithHistory = new SyncIdentityMatcher(jdbi, reviewQueueRepo, pathHistoryRepo);
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    // ── Normalization helpers ──────────────────────────────────────────────────

    @Test
    void normalizeCode_uppercasesAndStripsWhitespace() {
        assertEquals("ABC-001", SyncIdentityMatcher.normalizeCode("abc-001"));
        assertEquals("ABC-001", SyncIdentityMatcher.normalizeCode("ABC -001"));
        assertEquals("ABC-001", SyncIdentityMatcher.normalizeCode("ABC-001"));
    }

    @Test
    void baseSeqKey_normalizesLabel() {
        assertEquals("ABC-1", SyncIdentityMatcher.baseSeqKey("abc", 1));
        assertEquals("ABC-123", SyncIdentityMatcher.baseSeqKey("ABC", 123));
    }

    @Test
    void normalizeName_stripsNonLetterAndLowercases() {
        assertEquals("yuamikami", SyncIdentityMatcher.normalizeName("Yua Mikami"));
        assertEquals("yuamikami", SyncIdentityMatcher.normalizeName("Yua_Mikami"));
        assertEquals("yuamikami", SyncIdentityMatcher.normalizeName("Yua-Mikami"));
        assertEquals("yuamikami", SyncIdentityMatcher.normalizeName("yua.mikami"));
    }

    // ── Title soft-match: normalized code ────────────────────────────────────

    @Test
    void noteTitleCandidate_buffers_recode_candidate_on_normalizedCodeMatch() {
        // Orphan with lowercase code — normalizeCode matches uppercase new folder code
        insertOrphanTitle(1L, "abc-001", "ABC", 1);

        matcher.loadForSync();

        TitleCodeParser.ParsedCode parsed = new TitleCodeParser().parse("ABC-001");
        Title newTitle = Title.builder().id(99L).code("ABC-001").label("ABC").seqNum(1).build();

        matcher.noteTitleCandidate(parsed, newTitle);
        matcher.flushToQueue(100);

        assertTrue(reviewQueueRepo.hasOpen(99L, "recode_candidate"),
                "recode_candidate should be queued for normalized-code match");
    }

    @Test
    void noteTitleCandidate_buffers_recode_candidate_on_baseSeqMatch() {
        // Orphan has suffix variant — same label+seq but different code string
        insertOrphanTitle(1L, "ABC-001_U", "ABC", 1);

        matcher.loadForSync();

        // New folder "ABC-001" — normalizeCode("ABC-001") = "ABC-001" ≠ "ABC-001U" (orphan)
        // but base+seq key "ABC-1" matches both
        TitleCodeParser.ParsedCode parsed = new TitleCodeParser().parse("ABC-001");
        Title newTitle = Title.builder().id(99L).code("ABC-001").label("ABC").seqNum(1).build();

        matcher.noteTitleCandidate(parsed, newTitle);
        matcher.flushToQueue(100);

        assertTrue(reviewQueueRepo.hasOpen(99L, "recode_candidate"),
                "recode_candidate should be queued via base+seq match");
    }

    @Test
    void noteTitleCandidate_skipsWhenNoOrphanMatch() {
        insertOrphanTitle(1L, "XYZ-001", "XYZ", 1);

        matcher.loadForSync();

        TitleCodeParser.ParsedCode parsed = new TitleCodeParser().parse("ABC-001");
        Title newTitle = Title.builder().id(99L).code("ABC-001").label("ABC").seqNum(1).build();

        matcher.noteTitleCandidate(parsed, newTitle);
        matcher.flushToQueue(100);

        assertFalse(reviewQueueRepo.hasOpen(99L, "recode_candidate"),
                "no queue entry when no orphan matches");
    }

    @Test
    void noteTitleCandidate_skipsNullLabel() {
        // Folder name that doesn't parse to a recognizable code (no label/seqNum)
        matcher.loadForSync();

        TitleCodeParser.ParsedCode parsed = new TitleCodeParser().parse("NotACode");
        Title newTitle = Title.builder().id(99L).code("NotACode").build();

        matcher.noteTitleCandidate(parsed, newTitle);
        matcher.flushToQueue(100);

        assertFalse(reviewQueueRepo.hasOpen(99L, "recode_candidate"),
                "null label: no queue entry expected");
    }

    @Test
    void noteTitleCandidate_queuesEntry_forFirstMatchingOrphan() {
        // Two orphans with same base+seq — first-loaded one wins
        insertOrphanTitle(1L, "ABC-001_U", "ABC", 1);
        insertOrphanTitle(2L, "ABC-001_V", "ABC", 1);

        matcher.loadForSync();

        TitleCodeParser.ParsedCode parsed = new TitleCodeParser().parse("ABC-001");
        Title newTitle = Title.builder().id(99L).code("ABC-001").label("ABC").seqNum(1).build();

        matcher.noteTitleCandidate(parsed, newTitle);
        matcher.flushToQueue(100);

        assertTrue(reviewQueueRepo.hasOpen(99L, "recode_candidate"),
                "recode_candidate queued for first matching orphan");
    }

    @Test
    void noteTitleCandidate_zeroOrphans_producesNoQueues() {
        // No orphans in DB
        matcher.loadForSync();

        TitleCodeParser.ParsedCode parsed = new TitleCodeParser().parse("ABC-001");
        Title newTitle = Title.builder().id(99L).code("ABC-001").label("ABC").seqNum(1).build();

        matcher.noteTitleCandidate(parsed, newTitle);
        matcher.flushToQueue(100);

        assertEquals(0, reviewQueueRepo.countOpen("recode_candidate"),
                "no orphans → no candidates");
    }

    // ── Title soft-match: catastrophic guard ─────────────────────────────────

    @Test
    void flushToQueue_recodeCandidateGuard_refusesWhenAboveThreshold() {
        // Load 60 orphans (each with a suffix variant to force base+seq matching)
        for (int i = 0; i < 60; i++) {
            insertOrphanTitle(100L + i, "L" + i + "-001_U", "L" + i, 1);
        }
        matcher.loadForSync();

        // Buffer 60 candidates: each new title "L{i}-001" matches orphan "L{i}-001_U" via base+seq
        for (int i = 0; i < 60; i++) {
            TitleCodeParser.ParsedCode parsed = new TitleCodeParser().parse("L" + i + "-001");
            Title newTitle = Title.builder().id(200L + i).code("L" + i + "-001")
                    .label("L" + i).seqNum(1).build();
            matcher.noteTitleCandidate(parsed, newTitle);
        }

        // totalTitles=1 → threshold = max(50, 0) = 50. 60 > 50 → guard trips.
        matcher.flushToQueue(1);

        assertEquals(0, reviewQueueRepo.countOpen("recode_candidate"),
                "catastrophic guard must refuse to write 60 candidates when threshold is 50");
    }

    @Test
    void flushToQueue_recodeCandidateGuard_writesWhenBelowThreshold() {
        // 3 candidates, totalTitles=100 → threshold=max(50,10)=50 → 3 < 50 → writes OK
        for (int i = 0; i < 3; i++) {
            insertOrphanTitle(100L + i, "L" + i + "-001_U", "L" + i, 1);
            // Each new title needs its own DB row so enqueueWithDetail has a valid FK
            insertTitleRecord(200L + i, "L" + i + "-001");
        }
        matcher.loadForSync();

        for (int i = 0; i < 3; i++) {
            TitleCodeParser.ParsedCode parsed = new TitleCodeParser().parse("L" + i + "-001");
            Title newTitle = Title.builder().id(200L + i).code("L" + i + "-001")
                    .label("L" + i).seqNum(1).build();
            matcher.noteTitleCandidate(parsed, newTitle);
        }

        matcher.flushToQueue(100);

        assertEquals(3, reviewQueueRepo.countOpen("recode_candidate"),
                "all 3 candidates written when below threshold");
    }

    // ── Actress soft-match ────────────────────────────────────────────────────

    @Test
    void noteActressCandidate_buffers_actress_rename_candidate_on_normalizedNameMatch() {
        long actressId     = insertActress(1L, "Yua Mikami");
        long anchorTitleId = insertTitle(10L, "ABC-001", actressId);

        matcher.loadForSync();

        // Observed folder name uses underscore instead of space
        matcher.noteActressCandidate("Yua_Mikami");
        matcher.flushToQueue(100);

        assertTrue(reviewQueueRepo.hasOpen(anchorTitleId, "actress_rename_candidate"),
                "actress_rename_candidate should be queued anchored to the actress's title");
    }

    @Test
    void noteActressCandidate_skipsWhenNoMatch() {
        insertActress(1L, "Yua Mikami");
        insertTitle(10L, "ABC-001", 1L);

        matcher.loadForSync();

        matcher.noteActressCandidate("Completely Different Name");
        matcher.flushToQueue(100);

        assertEquals(0, reviewQueueRepo.countOpen("actress_rename_candidate"),
                "no queue entry when name doesn't match any actress");
    }

    @Test
    void noteActressCandidate_skipsActressWithNoAnchorTitle() {
        // Actress exists but has no titles linked — excluded from soft-match map
        insertActress(1L, "Yua Mikami");
        // No title inserted

        matcher.loadForSync();

        matcher.noteActressCandidate("Yua_Mikami");
        matcher.flushToQueue(100);

        assertEquals(0, reviewQueueRepo.countOpen("actress_rename_candidate"),
                "actress with no anchor title: not in map, no queue entry");
    }

    @Test
    void noteActressCandidate_isIdempotent_forSameAnchorTitle() {
        long actressId = insertActress(1L, "Yua Mikami");
        insertTitle(10L, "ABC-001", actressId);

        matcher.loadForSync();

        matcher.noteActressCandidate("Yua_Mikami");
        matcher.noteActressCandidate("Yua_Mikami");
        matcher.flushToQueue(100);

        // enqueueWithDetail uses INSERT OR IGNORE → only one open row per (title_id, reason)
        assertEquals(1, reviewQueueRepo.countOpen("actress_rename_candidate"),
                "duplicate note for same actress yields single queue row");
    }

    // ── Guard: actress rename ─────────────────────────────────────────────────

    @Test
    void flushToQueue_actressRenameGuard_refusesWhenAboveThreshold() {
        // 60 actresses, each with one title. Loaded map size=60.
        // threshold = max(50, 60/10=6) = 50. 60 candidates > 50 → guard trips.
        for (int i = 0; i < 60; i++) {
            long aId = 100L + i;
            long tId = 200L + i;
            String name = "Actress" + i + " Test";
            insertActress(aId, name);
            insertTitle(tId, "ZZZ-" + (i + 1), aId);
        }

        matcher.loadForSync();

        for (int i = 0; i < 60; i++) {
            // Underscore variant normalizes to same string as canonical
            matcher.noteActressCandidate("Actress" + i + "_Test");
        }
        matcher.flushToQueue(100);

        assertEquals(0, reviewQueueRepo.countOpen("actress_rename_candidate"),
                "actress rename guard tripped: 0 queue entries written");
    }

    // ── Path-history fallback ─────────────────────────────────────────────────

    /**
     * Scenario: title previously at /queue/XYZ-001 was deleted (or renamed).
     * A new title re-appears at the same path (possibly with a different code).
     * Path-history lookup should surface it as a path_history_match candidate.
     */
    @Test
    void noteTitleCandidate_pathHistoryFallback_buffersCandidate_whenCodeMissAndHistoryHit() {
        // Seed a title row (to satisfy the title-existence check in findByPath)
        insertTitleRecord(55L, "XYZ-001");

        // Record that title 55 used to live at this path
        pathHistoryRepo.recordPath(55L, "vol-a", "queue", "/queue/XYZ-001", "2026-01-01T00:00:00Z");

        // The new title has a different code — no orphan match via Phase-3
        insertTitleRecord(99L, "DIFFERENT-001");
        matcherWithHistory.loadForSync();

        TitleCodeParser.ParsedCode parsed = new TitleCodeParser().parse("DIFFERENT-001");
        Title newTitle = Title.builder().id(99L).code("DIFFERENT-001").label("DIFFERENT").seqNum(1).build();

        matcherWithHistory.noteTitleCandidate(parsed, newTitle, "vol-a", "queue", "/queue/XYZ-001");
        matcherWithHistory.flushToQueue(100);

        assertTrue(reviewQueueRepo.hasOpen(99L, "path_history_match"),
                "path_history_match must be queued when code misses but path history hits");
    }

    @Test
    void noteTitleCandidate_pathHistoryFallback_skipsWhenNoHistoryRow() {
        matcherWithHistory.loadForSync();

        insertTitleRecord(99L, "ABC-001");
        TitleCodeParser.ParsedCode parsed = new TitleCodeParser().parse("ABC-001");
        Title newTitle = Title.builder().id(99L).code("ABC-001").label("ABC").seqNum(1).build();

        matcherWithHistory.noteTitleCandidate(parsed, newTitle, "vol-a", "queue", "/queue/ABC-001");
        matcherWithHistory.flushToQueue(100);

        assertFalse(reviewQueueRepo.hasOpen(99L, "path_history_match"),
                "no path_history_match when no history row exists for this path");
    }

    @Test
    void noteTitleCandidate_pathHistoryFallback_skipsWhenHistoricTitleDeleted() {
        // Path history row exists but the title_id it points to is gone
        pathHistoryRepo.recordPath(777L, "vol-a", "queue", "/queue/GONE-001", "2026-01-01T00:00:00Z");
        // No INSERT into titles for 777 — simulates a deleted title

        matcherWithHistory.loadForSync();

        insertTitleRecord(99L, "NEW-001");
        TitleCodeParser.ParsedCode parsed = new TitleCodeParser().parse("NEW-001");
        Title newTitle = Title.builder().id(99L).code("NEW-001").label("NEW").seqNum(1).build();

        matcherWithHistory.noteTitleCandidate(parsed, newTitle, "vol-a", "queue", "/queue/GONE-001");
        matcherWithHistory.flushToQueue(100);

        assertFalse(reviewQueueRepo.hasOpen(99L, "path_history_match"),
                "path_history_match must be skipped when historic title_id no longer exists");
    }

    @Test
    void noteTitleCandidate_withPathCoords_stillDoesPhase3SoftMatchFirst() {
        // Phase-3 match should win before path-history is tried
        insertOrphanTitle(10L, "ABC-001_U", "ABC", 1);
        insertTitleRecord(55L, "XYZ-001");
        pathHistoryRepo.recordPath(55L, "vol-a", "queue", "/queue/ABC-001", "2026-01-01T00:00:00Z");

        matcherWithHistory.loadForSync();

        insertTitleRecord(99L, "ABC-001");
        TitleCodeParser.ParsedCode parsed = new TitleCodeParser().parse("ABC-001");
        Title newTitle = Title.builder().id(99L).code("ABC-001").label("ABC").seqNum(1).build();

        matcherWithHistory.noteTitleCandidate(parsed, newTitle, "vol-a", "queue", "/queue/ABC-001");
        matcherWithHistory.flushToQueue(100);

        // Phase-3 base+seq match wins → recode_candidate, NOT path_history_match
        assertTrue(reviewQueueRepo.hasOpen(99L, "recode_candidate"),
                "Phase-3 base+seq match must win over path-history fallback");
        assertFalse(reviewQueueRepo.hasOpen(99L, "path_history_match"),
                "path_history_match must not be generated when Phase-3 already matched");
    }

    @Test
    void noteTitleCandidate_withoutPathCoords_backwardCompatSkipsPathHistory() {
        insertTitleRecord(55L, "XYZ-001");
        pathHistoryRepo.recordPath(55L, "vol-a", "queue", "/queue/XYZ-001", "2026-01-01T00:00:00Z");

        matcherWithHistory.loadForSync();

        insertTitleRecord(99L, "DIFFERENT-001");
        TitleCodeParser.ParsedCode parsed = new TitleCodeParser().parse("DIFFERENT-001");
        Title newTitle = Title.builder().id(99L).code("DIFFERENT-001").label("DIFFERENT").seqNum(1).build();

        // Old 2-arg overload — no path coords, path-history fallback skipped
        matcherWithHistory.noteTitleCandidate(parsed, newTitle);
        matcherWithHistory.flushToQueue(100);

        assertFalse(reviewQueueRepo.hasOpen(99L, "path_history_match"),
                "backward-compat 2-arg noteTitleCandidate must not trigger path-history fallback");
    }

    @Test
    void flushPathHistoryCandidates_catastrophicGuard_refusesAboveThreshold() {
        // Create 60 path-history candidates above the FLOOR(50)
        for (int i = 0; i < 60; i++) {
            insertTitleRecord(100L + i, "H-" + i);
            insertTitleRecord(200L + i, "N-" + i);
            String path = "/queue/N-" + i;
            pathHistoryRepo.recordPath(100L + i, "vol-a", "queue", path, "2026-01-01T00:00:00Z");
        }
        matcherWithHistory.loadForSync();

        for (int i = 0; i < 60; i++) {
            TitleCodeParser.ParsedCode parsed = new TitleCodeParser().parse("N-" + i);
            Title newTitle = Title.builder().id(200L + i).code("N-" + i).label("N").seqNum(i).build();
            matcherWithHistory.noteTitleCandidate(parsed, newTitle, "vol-a", "queue", "/queue/N-" + i);
        }

        // totalTitles=1 → threshold = max(50, 0) = 50. 60 > 50 → guard trips.
        matcherWithHistory.flushToQueue(1);

        assertEquals(0, reviewQueueRepo.countOpen("path_history_match"),
                "catastrophic guard must refuse to write 60 path_history_match candidates when threshold is 50");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void insertOrphanTitle(long id, String code, String label, Integer seqNum) {
        jdbi.useHandle(h -> h.createUpdate(
                        "INSERT INTO titles(id, code, base_code, label, seq_num) "
                        + "VALUES (:id, :code, :code, :label, :seqNum)")
                .bind("id",     id)
                .bind("code",   code)
                .bind("label",  label)
                .bind("seqNum", seqNum)
                .execute());
        // No title_locations row → orphan
    }

    private void insertTitleRecord(long id, String code) {
        jdbi.useHandle(h -> h.createUpdate(
                        "INSERT INTO titles(id, code, base_code) VALUES (:id, :code, :code)")
                .bind("id",   id)
                .bind("code", code)
                .execute());
    }

    private long insertActress(long id, String canonicalName) {
        jdbi.useHandle(h -> h.createUpdate(
                        "INSERT INTO actresses(id, canonical_name, tier, first_seen_at) "
                        + "VALUES (:id, :name, 'LIBRARY', '2024-01-01')")
                .bind("id",   id)
                .bind("name", canonicalName)
                .execute());
        return id;
    }

    private long insertTitle(long id, String code, long actressId) {
        jdbi.useHandle(h -> h.createUpdate(
                        "INSERT INTO titles(id, code, base_code, actress_id) VALUES (:id, :code, :code, :actressId)")
                .bind("id",        id)
                .bind("code",      code)
                .bind("actressId", actressId)
                .execute());
        return id;
    }
}
