package com.organizer3.sync;

import com.organizer3.db.SchemaInitializer;
import com.organizer3.javdb.enrichment.EnrichmentReviewQueueRepository;
import com.organizer3.model.Title;
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
    private SyncIdentityMatcher matcher;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        reviewQueueRepo = new EnrichmentReviewQueueRepository(jdbi);
        matcher = new SyncIdentityMatcher(jdbi, reviewQueueRepo);
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
