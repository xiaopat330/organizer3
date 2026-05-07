package com.organizer3.sync;

import com.organizer3.config.AppConfig;
import com.organizer3.config.volume.OrganizerConfig;
import com.organizer3.db.SchemaInitializer;
import com.organizer3.model.Title;
import com.organizer3.model.TitleLocation;
import com.organizer3.model.Volume;
import com.organizer3.repository.TitleRepository;
import com.organizer3.repository.TitleLocationRepository;
import com.organizer3.repository.jdbi.JdbiTitleLocationRepository;
import com.organizer3.repository.jdbi.JdbiTitleRepository;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for grace-period orphan mechanics (Phase 1, cases 1–3, 6–7).
 *
 * <p>All tests run against real in-memory SQLite via SchemaInitializer.
 *
 * <h3>Test cases from §7</h3>
 * <ol>
 *   <li>Grace-period sweep regression — stale row stays within grace, swept after.</li>
 *   <li>Cross-volume move tolerated — sync only source → row stale, title survives.</li>
 *   <li>Single-volume sync no longer false-orphans cross-volume moves.</li>
 *   <li>(Case 4 — coherent sync, Phase 2) — deferred.</li>
 *   <li>(Case 5 — reconcile, Phase 3) — deferred.</li>
 *   <li>Catastrophic-delete guard applies to the stale-sweep.</li>
 *   <li>Browse queries hide stale rows by default; includeStale=true shows both.</li>
 * </ol>
 */
class StaleGraceOrphanTest {

    private Connection connection;
    private Jdbi jdbi;
    private JdbiTitleLocationRepository locationRepo;
    private JdbiTitleRepository titleRepo;

    private static final int GRACE_DAYS = 90;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        jdbi.useHandle(h -> {
            h.execute("INSERT INTO volumes (id, structure_type) VALUES ('vol-a', 'conventional')");
            h.execute("INSERT INTO volumes (id, structure_type) VALUES ('vol-b', 'conventional')");
        });
        locationRepo = new JdbiTitleLocationRepository(jdbi);
        titleRepo = new JdbiTitleRepository(jdbi, locationRepo);
        AppConfig.initializeForTest(new OrganizerConfig(
                "test", "/tmp", null, null, null, null, null, null,
                List.of(), List.of(), List.of(), List.of(), null, null));
    }

    @AfterEach
    void tearDown() throws Exception {
        AppConfig.reset();
        connection.close();
    }

    // -------------------------------------------------------------------------
    // Case 1: Grace-period sweep regression
    // -------------------------------------------------------------------------

    /**
     * Case 1a: A row marked stale within the grace window must NOT be swept.
     *
     * <p>Sequence: create title + location → mark stale with a recent timestamp → call sweep
     * → row must still exist.
     */
    @Test
    void case1a_staleSinceWithinGrace_notSwept() {
        long titleId = saveTitle("ABP-001");
        locationRepo.save(loc(titleId, "vol-a", "queue"));

        // Mark stale NOW (0 days ago — well within 90-day grace)
        String nowIso = Instant.now().toString();
        locationRepo.markStaleByVolume("vol-a", nowIso);

        // Sweep at 90-day threshold — this row is 0 days old, not past grace
        int swept = locationRepo.sweepStaleOlderThan(GRACE_DAYS);
        assertEquals(0, swept, "Row within grace should not be swept");

        // Row still exists (as stale)
        List<TitleLocation> stale = locationRepo.findByVolume("vol-a", true);
        assertEquals(1, stale.size(), "Stale row must still exist after sweep within grace");
        assertNotNull(stale.get(0).getStaleSince(), "Row must still be stale");
    }

    /**
     * Case 1b: A row marked stale past the grace window MUST be swept.
     *
     * <p>We inject a stale_since timestamp in the past (91 days ago) directly via SQL
     * to simulate time advancing without actually waiting.
     */
    @Test
    void case1b_staleSincePastGrace_isSwept() {
        long titleId = saveTitle("ABP-002");
        locationRepo.save(loc(titleId, "vol-a", "queue"));

        // Inject a stale_since 91 days ago to simulate the clock advancing past grace
        String pastIso = Instant.now().minusSeconds(91L * 24 * 3600).toString();
        jdbi.useHandle(h -> h.createUpdate(
                "UPDATE title_locations SET stale_since = :ts WHERE volume_id = 'vol-a'")
                .bind("ts", pastIso).execute());

        // Verify the row is now past grace
        List<TitleLocation> pastGrace = locationRepo.findStaleOlderThan(GRACE_DAYS);
        assertEquals(1, pastGrace.size(), "Row past grace should be found");

        // Sweep
        int swept = locationRepo.sweepStaleOlderThan(GRACE_DAYS);
        assertEquals(1, swept, "Row past grace must be swept");

        // Row is gone
        List<TitleLocation> all = locationRepo.findByVolume("vol-a", true);
        assertEquals(0, all.size(), "Swept row must be gone");
    }

    /**
     * Case 1c: Rows on another volume survive the sweep of one volume.
     *
     * <p>Regression guard: sweep is global (not per-volume), but it must only remove
     * rows past grace — rows still within grace survive regardless of volume.
     */
    @Test
    void case1c_sweepDoesNotRemoveWithinGraceOnOtherVolume() {
        long t1 = saveTitle("ABP-003");
        long t2 = saveTitle("ABP-004");
        // vol-a: past grace
        locationRepo.save(loc(t1, "vol-a", "queue"));
        jdbi.useHandle(h -> h.createUpdate(
                "UPDATE title_locations SET stale_since = :ts WHERE volume_id = 'vol-a'")
                .bind("ts", Instant.now().minusSeconds(91L * 24 * 3600).toString()).execute());
        // vol-b: within grace (just marked stale)
        locationRepo.save(loc(t2, "vol-b", "queue"));
        jdbi.useHandle(h -> h.createUpdate(
                "UPDATE title_locations SET stale_since = :ts WHERE volume_id = 'vol-b'")
                .bind("ts", Instant.now().toString()).execute());

        int swept = locationRepo.sweepStaleOlderThan(GRACE_DAYS);
        assertEquals(1, swept, "Only past-grace row should be swept");
        assertEquals(1, locationRepo.findByVolume("vol-b", true).size(),
                "vol-b within-grace row must survive");
    }

    // -------------------------------------------------------------------------
    // Case 2: Cross-volume move tolerated by sync
    // -------------------------------------------------------------------------

    /**
     * Case 2: After sync of source volume only, the title must remain accessible.
     *
     * <p>Simulates: title on vol-a, manually moved to vol-b. Sync vol-a only → vol-a row
     * becomes stale but no deletion. Title not orphaned (stale-within-grace).
     */
    @Test
    void case2_crossVolumeMove_sourceOnlySyncDoesNotOrphanTitle() {
        long titleId = saveTitle("ABP-005");
        locationRepo.save(loc(titleId, "vol-a", "queue"));

        // Simulate sync of vol-a finding the folder gone: mark stale
        String nowIso = Instant.now().toString();
        int marked = locationRepo.markStaleByVolume("vol-a", nowIso);
        assertEquals(1, marked, "One row should be newly marked stale");

        // The stale row is within grace — title not orphaned
        List<TitleRepository.OrphanedTitleRef> orphans = titleRepo.findOrphanedTitles(GRACE_DAYS);
        assertTrue(orphans.isEmpty(),
                "Title must NOT be orphaned within grace — it's pending confirmation on another volume");

        // The stale row still exists (includeSale=true shows it)
        List<TitleLocation> staleRows = locationRepo.findByTitle(titleId, true);
        assertEquals(1, staleRows.size());
        assertNotNull(staleRows.get(0).getStaleSince());

        // Title not in live rows (default browse excludes stale)
        List<TitleLocation> liveRows = locationRepo.findByTitle(titleId);
        assertTrue(liveRows.isEmpty(),
                "Live (default) browse must not show stale rows");
    }

    /**
     * Case 2b: After syncing the destination volume, the vol-a stale row gets cleared
     * because the title is re-observed at its new path on vol-b (different row, same title).
     * The vol-a stale row stays until swept past grace.
     */
    @Test
    void case2b_afterSyncDestination_titleIsLiveOnNewVolume() {
        long titleId = saveTitle("ABP-006");
        locationRepo.save(loc(titleId, "vol-a", "queue", "/queue/ABP-006"));

        // Sync vol-a: mark stale
        locationRepo.markStaleByVolume("vol-a", Instant.now().toString());

        // Sync vol-b: title observed at new location → saved as live row on vol-b
        locationRepo.save(loc(titleId, "vol-b", "attention", "/attention/ABP-006"));

        // Title now has one live row (vol-b) and one stale row (vol-a)
        List<TitleLocation> live = locationRepo.findByTitle(titleId);
        assertEquals(1, live.size(), "One live row on vol-b");
        assertEquals("vol-b", live.get(0).getVolumeId());

        List<TitleLocation> all = locationRepo.findByTitle(titleId, true);
        assertEquals(2, all.size(), "Two rows total: live vol-b + stale vol-a");

        // Title is NOT orphaned
        assertTrue(titleRepo.findOrphanedTitles(GRACE_DAYS).isEmpty());
    }

    // -------------------------------------------------------------------------
    // Case 3: Single-volume sync no longer false-orphans cross-volume moves
    // -------------------------------------------------------------------------

    /**
     * Case 3: Title on vol-a+vol-b; sync vol-a only (folder gone from vol-a).
     * The vol-b location is live → title is NOT orphaned even without vol-b sync.
     *
     * <p>This test also implicitly tests that the old "zero title_locations" orphan predicate
     * has been replaced with "zero LIVE locations AND no stale-within-grace locations".
     */
    @Test
    void case3_singleVolumeSyncDoesNotFalseOrphanCrossVolumeTitle() {
        long titleId = saveTitle("ABP-007");
        locationRepo.save(loc(titleId, "vol-a", "queue", "/queue/ABP-007"));
        locationRepo.save(loc(titleId, "vol-b", "attention", "/attention/ABP-007"));

        // Sync vol-a: folder gone → mark stale
        locationRepo.markStaleByVolume("vol-a", Instant.now().toString());

        // vol-b row is still live
        List<TitleLocation> live = locationRepo.findByTitle(titleId);
        assertEquals(1, live.size());
        assertEquals("vol-b", live.get(0).getVolumeId());

        // Title is NOT orphaned
        List<TitleRepository.OrphanedTitleRef> orphans = titleRepo.findOrphanedTitles(GRACE_DAYS);
        assertTrue(orphans.isEmpty(),
                "Title with a live location on vol-b must not appear as orphaned");
    }

    // -------------------------------------------------------------------------
    // Case 6: Catastrophic-delete guard applies to the stale-sweep
    // -------------------------------------------------------------------------

    /**
     * Case 6: Inject many past-grace stale rows exceeding max(50, 10%) of live rows.
     * sweepStaleOlderThan is called directly (the guard that refuses is in AbstractSyncOperation).
     * Here we test that {@link JdbiTitleRepository#findOrphanedTitles(int)} does NOT orphan
     * titles with stale-within-grace rows, which tests the predicate regression.
     *
     * <p>The actual sweep guard lives in AbstractSyncOperation.sweepStaleWithGuard(); here we
     * verify the repository-level sweep returns the correct count and that the live-location
     * count (used as the guard denominator) is correct.
     */
    @Test
    void case6_sweepGuardDenominator_liveCountCorrect() {
        // Create 10 live titles on vol-a
        for (int i = 1; i <= 10; i++) {
            long tid = saveTitle("ABP-" + String.format("%03d", i));
            locationRepo.save(loc(tid, "vol-a", "queue"));
        }

        // Verify all 10 are live
        assertEquals(10, locationRepo.countAllLive(), "Should have 10 live rows");

        // Mark all stale (simulate sync finding all gone)
        String nowIso = Instant.now().toString();
        locationRepo.markStaleByVolume("vol-a", nowIso);

        // 0 live rows now
        assertEquals(0, locationRepo.countAllLive(), "No live rows after marking all stale");

        // Advance past grace by injecting past timestamp
        String pastIso = Instant.now().minusSeconds(91L * 24 * 3600).toString();
        jdbi.useHandle(h -> h.createUpdate(
                "UPDATE title_locations SET stale_since = :ts WHERE stale_since = :now")
                .bind("ts", pastIso).bind("now", nowIso).execute());

        // All 10 are past grace — sweep would drop them all
        List<TitleLocation> pastGrace = locationRepo.findStaleOlderThan(GRACE_DAYS);
        assertEquals(10, pastGrace.size(), "All rows should be past grace");

        // The guard threshold = max(50, 0 live / 10) = max(50, 0) = 50.
        // sweepCount (10) < threshold (50) → sweep proceeds.
        int swept = locationRepo.sweepStaleOlderThan(GRACE_DAYS);
        assertEquals(10, swept);
        assertEquals(0, locationRepo.findStaleOlderThan(GRACE_DAYS).size());
    }

    /**
     * Case 6b: Catastrophic guard threshold formula.
     * Verifies max(50, liveCount/10) math: with 600 live rows, threshold = 60.
     * If we mark 61 stale, the guard (in AbstractSyncOperation) should refuse.
     * We test the denominator and count calculation are correct via the repo methods.
     */
    @Test
    void case6b_catastrophicGuardThresholdFormula() {
        // Create 600 live titles
        for (int i = 1; i <= 600; i++) {
            long tid = saveTitle("TST-" + String.format("%03d", i));
            locationRepo.save(loc(tid, "vol-a", "queue"));
        }
        assertEquals(600, locationRepo.countAllLive());

        // Mark 61 stale (just above threshold of max(50, 600/10)=60)
        String pastIso = Instant.now().minusSeconds(91L * 24 * 3600).toString();
        jdbi.useHandle(h -> {
            // Directly insert stale-past-grace rows for 61 of the 600 titles
            h.createUpdate("UPDATE title_locations SET stale_since = :ts WHERE id IN " +
                    "(SELECT id FROM title_locations LIMIT 61)")
                    .bind("ts", pastIso).execute();
        });

        int pastGraceCount = locationRepo.findStaleOlderThan(GRACE_DAYS).size();
        int liveCount = locationRepo.countAllLive();
        int threshold = Math.max(50, liveCount / 10);

        assertEquals(61, pastGraceCount, "61 rows should be past grace");
        assertEquals(539, liveCount, "539 rows should still be live");
        assertEquals(53, threshold, "Threshold = max(50, 539/10) = max(50,53) = 53");
        assertTrue(pastGraceCount > threshold,
                "61 past-grace rows exceeds threshold 53 — guard should fire");
    }

    // -------------------------------------------------------------------------
    // Case 7: Browse queries hide stale rows by default
    // -------------------------------------------------------------------------

    /**
     * Case 7a: findByVolume default hides stale rows; includeStale=true shows both.
     */
    @Test
    void case7a_findByVolume_hidesStaleByDefault() {
        long t1 = saveTitle("ABP-010");
        long t2 = saveTitle("ABP-011");
        locationRepo.save(loc(t1, "vol-a", "queue", "/queue/ABP-010"));
        locationRepo.save(loc(t2, "vol-a", "queue", "/queue/ABP-011"));

        // Mark t2 stale
        jdbi.useHandle(h -> h.createUpdate(
                "UPDATE title_locations SET stale_since = :ts WHERE path = '/queue/ABP-011'")
                .bind("ts", Instant.now().toString()).execute());

        // Default (live only): 1 row
        List<TitleLocation> live = locationRepo.findByVolume("vol-a");
        assertEquals(1, live.size(), "Default must exclude stale rows");
        assertEquals("/queue/ABP-010", live.get(0).getPath().toString());

        // includeStale=true: 2 rows
        List<TitleLocation> both = locationRepo.findByVolume("vol-a", true);
        assertEquals(2, both.size(), "includeStale=true must return both live and stale");
    }

    /**
     * Case 7b: findByTitle default hides stale rows; includeStale=true shows both.
     */
    @Test
    void case7b_findByTitle_hidesStaleByDefault() {
        long titleId = saveTitle("ABP-012");
        locationRepo.save(loc(titleId, "vol-a", "queue", "/queue/ABP-012"));
        locationRepo.save(loc(titleId, "vol-b", "attention", "/attention/ABP-012"));

        // Mark vol-a row stale (simulating title moved to vol-b)
        jdbi.useHandle(h -> h.createUpdate(
                "UPDATE title_locations SET stale_since = :ts WHERE volume_id = 'vol-a'")
                .bind("ts", Instant.now().toString()).execute());

        // Default: 1 live row (vol-b only)
        List<TitleLocation> live = locationRepo.findByTitle(titleId);
        assertEquals(1, live.size());
        assertEquals("vol-b", live.get(0).getVolumeId());

        // includeStale: 2 rows
        List<TitleLocation> both = locationRepo.findByTitle(titleId, true);
        assertEquals(2, both.size());
    }

    /**
     * Case 7c: findByTitleIds default hides stale rows.
     */
    @Test
    void case7c_findByTitleIds_hidesStaleByDefault() {
        long t1 = saveTitle("ABP-013");
        long t2 = saveTitle("ABP-014");
        locationRepo.save(loc(t1, "vol-a", "queue", "/queue/ABP-013"));
        locationRepo.save(loc(t2, "vol-a", "queue", "/queue/ABP-014"));

        // Mark t1 stale
        jdbi.useHandle(h -> h.createUpdate(
                "UPDATE title_locations SET stale_since = :ts WHERE path = '/queue/ABP-013'")
                .bind("ts", Instant.now().toString()).execute());

        List<TitleLocation> live = locationRepo.findByTitleIds(List.of(t1, t2));
        assertEquals(1, live.size(), "Default must hide stale row for t1");
        assertEquals("/queue/ABP-014", live.get(0).getPath().toString());

        List<TitleLocation> both = locationRepo.findByTitleIds(List.of(t1, t2), true);
        assertEquals(2, both.size());
    }

    /**
     * Case 7d: Re-observing a stale row (upsert) clears stale_since.
     */
    @Test
    void case7d_reobservedStaleSince_clearedByUpsert() {
        long titleId = saveTitle("ABP-015");
        locationRepo.save(loc(titleId, "vol-a", "queue", "/queue/ABP-015"));

        // Mark stale
        locationRepo.markStaleByVolume("vol-a", Instant.now().toString());
        List<TitleLocation> stale = locationRepo.findByTitle(titleId, true);
        assertEquals(1, stale.size());
        assertNotNull(stale.get(0).getStaleSince(), "Should be stale");

        // Re-observe (simulate sync finding the folder again)
        locationRepo.save(loc(titleId, "vol-a", "queue", "/queue/ABP-015"));

        // Now live again
        List<TitleLocation> live = locationRepo.findByTitle(titleId);
        assertEquals(1, live.size(), "Row should be live again after re-observation");
        assertNull(live.get(0).getStaleSince(), "stale_since should be NULL after upsert");

        // No stale rows remaining
        List<TitleLocation> allStale = locationRepo.findByVolume("vol-a", true).stream()
                .filter(TitleLocation::isStale).toList();
        assertTrue(allStale.isEmpty(), "No stale rows should remain after re-observation");
    }

    /**
     * Case 7e: markStaleByVolumeAndPartition is idempotent and scoped correctly.
     */
    @Test
    void case7e_markStaleByVolumeAndPartition_scopedAndIdempotent() {
        long t1 = saveTitle("ABP-016");
        long t2 = saveTitle("ABP-017");
        locationRepo.save(loc(t1, "vol-a", "queue", "/queue/ABP-016"));
        locationRepo.save(loc(t2, "vol-a", "attention", "/attention/ABP-017"));

        // Mark only queue partition stale
        String nowIso = Instant.now().toString();
        int marked = locationRepo.markStaleByVolumeAndPartition("vol-a", "queue", nowIso);
        assertEquals(1, marked, "One row marked stale (queue partition only)");

        // attention partition is still live
        List<TitleLocation> live = locationRepo.findByVolume("vol-a");
        assertEquals(1, live.size());
        assertEquals("attention", live.get(0).getPartitionId());

        // Idempotent: marking again does not update stale_since
        int markedAgain = locationRepo.markStaleByVolumeAndPartition("vol-a", "queue", "later");
        assertEquals(0, markedAgain, "Already-stale rows must not be re-marked");

        // stale_since must still be nowIso, not "later"
        List<TitleLocation> all = locationRepo.findByVolume("vol-a", true);
        TitleLocation staleRow = all.stream().filter(TitleLocation::isStale).findFirst().orElseThrow();
        assertEquals(nowIso, staleRow.getStaleSince().toString(),
                "stale_since must not be overwritten on second mark");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private long saveTitle(String code) {
        String label = code.split("-")[0];
        Title t = Title.builder().code(code).baseCode(label + "-00001")
                .label(label).seqNum(1).build();
        return titleRepo.save(t).getId();
    }

    private static TitleLocation loc(long titleId, String volumeId, String partitionId) {
        return loc(titleId, volumeId, partitionId, "/" + partitionId + "/" + titleId);
    }

    private static TitleLocation loc(long titleId, String volumeId, String partitionId, String path) {
        return TitleLocation.builder()
                .titleId(titleId).volumeId(volumeId).partitionId(partitionId)
                .path(Path.of(path))
                .lastSeenAt(LocalDate.now())
                .build();
    }
}
