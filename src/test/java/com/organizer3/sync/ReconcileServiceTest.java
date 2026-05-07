package com.organizer3.sync;

import com.organizer3.db.SchemaInitializer;
import com.organizer3.model.Actress;
import com.organizer3.model.Title;
import com.organizer3.model.TitleLocation;
import com.organizer3.repository.ReconcileReportRepository;
import com.organizer3.repository.ReconcileReportRepository.PersistedReport;
import com.organizer3.repository.jdbi.JdbiActressRepository;
import com.organizer3.repository.jdbi.JdbiReconcileReportRepository;
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
 * Tests for {@link ReconcileService} — case 5 from §7 of
 * {@code spec/PROPOSAL_SYNC_RECONCILIATION.md}.
 *
 * <p>Covers all four reconcile signals + sweep behaviour + persist round-trip.
 */
class ReconcileServiceTest {

    private static final int GRACE_DAYS = 90;

    private Connection connection;
    private Jdbi jdbi;
    private JdbiTitleLocationRepository locationRepo;
    private JdbiTitleRepository titleRepo;
    private JdbiActressRepository actressRepo;
    private ReconcileReportRepository reportRepo;
    private ReconcileService service;

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
        titleRepo    = new JdbiTitleRepository(jdbi, locationRepo);
        actressRepo  = new JdbiActressRepository(jdbi);
        reportRepo   = new JdbiReconcileReportRepository(jdbi);
        service      = new ReconcileService(locationRepo, titleRepo, reportRepo, GRACE_DAYS);
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Duplicate live locations
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void detectsDuplicateLiveLocations() {
        long t = saveTitle("ABP-001");
        locationRepo.save(loc(t, "vol-a", "queue", "/queue/abp-001"));
        locationRepo.save(loc(t, "vol-b", "queue", "/queue/abp-001"));

        ReconcileReport r = service.run(/*verbose=*/true);
        assertEquals(1, r.duplicateLiveLocations());
        assertEquals(1, r.duplicateLiveDetails().size());
        assertEquals(2, r.duplicateLiveDetails().get(0).locations().size(),
                "Detail must list both volumes");
    }

    @Test
    void duplicateDetection_ignoresStaleSibling() {
        long t = saveTitle("ABP-002");
        locationRepo.save(loc(t, "vol-a", "queue", "/queue/abp-002"));
        long staleId = locationRepo.save(loc(t, "vol-b", "queue", "/queue/abp-002")).getId();
        // Mark vol-b row stale → not a "duplicate live" anymore
        markStaleNow(staleId);

        ReconcileReport r = service.run(false);
        assertEquals(0, r.duplicateLiveLocations(),
                "A title with one live + one stale row is not a duplicate");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Pending grace + past grace
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void detectsPendingGrace() {
        long t = saveTitle("ABP-010");
        long id = locationRepo.save(loc(t, "vol-a", "queue", "/queue/abp-010")).getId();
        // 30 days stale — within 90-day grace
        injectStale(id, Instant.now().minusSeconds(30L * 86400));

        ReconcileReport r = service.run(/*verbose=*/true);
        assertEquals(1, r.pendingGrace());
        assertEquals(0, r.pastGraceStragglers());
        assertTrue(r.oldestPendingGraceDays() >= 29 && r.oldestPendingGraceDays() <= 31,
                "Days-stale should be ~30 (got " + r.oldestPendingGraceDays() + ")");
        assertEquals(1, r.pendingGraceDetails().size());
    }

    @Test
    void detectsPastGraceStragglers() {
        long t = saveTitle("ABP-011");
        long id = locationRepo.save(loc(t, "vol-a", "queue", "/queue/abp-011")).getId();
        // 100 days stale — past 90-day grace
        injectStale(id, Instant.now().minusSeconds(100L * 86400));

        ReconcileReport r = service.run(/*verbose=*/true);
        assertEquals(0, r.pendingGrace());
        assertEquals(1, r.pastGraceStragglers());
        assertEquals(1, r.pastGraceDetails().size());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Actress folder mismatch
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void detectsActressFolderMismatch() {
        Actress a = actressRepo.save(Actress.builder()
                .canonicalName("Yui Hatano")
                .tier(Actress.Tier.LIBRARY)
                .firstSeenAt(LocalDate.now())
                .build());
        long titleId = saveTitleWithActress("ABP-020", a.getId());
        // Path does NOT contain "yui hatano" — mismatch
        locationRepo.save(loc(titleId, "vol-a", "stars/library", "/stars/library/Some Other Person/ABP-020"));

        ReconcileReport r = service.run(/*verbose=*/true);
        assertEquals(1, r.actressFolderMismatches());
        assertEquals("Yui Hatano", r.mismatchDetails().get(0).actressName());
    }

    @Test
    void mismatchDetection_skipsTitleWithMatchingPath() {
        Actress a = actressRepo.save(Actress.builder()
                .canonicalName("Yui Hatano")
                .tier(Actress.Tier.LIBRARY)
                .firstSeenAt(LocalDate.now())
                .build());
        long titleId = saveTitleWithActress("ABP-021", a.getId());
        // Path contains canonical name (case-insensitive match) — no mismatch
        locationRepo.save(loc(titleId, "vol-a", "stars/library", "/stars/library/yui hatano/ABP-021"));

        ReconcileReport r = service.run(false);
        assertEquals(0, r.actressFolderMismatches());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Sweep
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void sweep_happyPath_deletesPastGraceRows() {
        // 5 past-grace rows, 100 live rows — sweep allowed (5 < max(50, 10))
        for (int i = 0; i < 100; i++) {
            long t = saveTitle("LIV-" + String.format("%03d", i));
            locationRepo.save(loc(t, "vol-a", "queue", "/queue/liv-" + i));
        }
        for (int i = 0; i < 5; i++) {
            long t = saveTitle("PST-" + String.format("%03d", i));
            long id = locationRepo.save(loc(t, "vol-a", "queue", "/queue/pst-" + i)).getId();
            injectStale(id, Instant.now().minusSeconds(100L * 86400));
        }
        int deleted = service.sweepPastGraceStragglers();
        assertEquals(5, deleted);
    }

    @Test
    void sweep_catastrophicGuard_refusesMassDelete() {
        // 80 past-grace rows, 100 live rows — sweep refused (80 > max(50, 10))
        for (int i = 0; i < 100; i++) {
            long t = saveTitle("LIV-" + String.format("%03d", i));
            locationRepo.save(loc(t, "vol-a", "queue", "/queue/liv-" + i));
        }
        for (int i = 0; i < 80; i++) {
            long t = saveTitle("PST-" + String.format("%03d", i));
            long id = locationRepo.save(loc(t, "vol-a", "queue", "/queue/pst-" + i)).getId();
            injectStale(id, Instant.now().minusSeconds(100L * 86400));
        }
        int deleted = service.sweepPastGraceStragglers();
        assertEquals(-1, deleted, "Guard should refuse and return -1");
        // Stale rows still present
        assertEquals(80, locationRepo.findPastGraceStragglers(GRACE_DAYS).size());
    }

    @Test
    void sweep_noStragglers_returnsZero() {
        long t = saveTitle("ABP-030");
        locationRepo.save(loc(t, "vol-a", "queue", "/queue/abp-030"));
        assertEquals(0, service.sweepPastGraceStragglers());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Persist round-trip
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void persist_roundTrip_findRecentReturnsIt() {
        long t = saveTitle("ABP-040");
        locationRepo.save(loc(t, "vol-a", "queue", "/queue/abp-040"));
        locationRepo.save(loc(t, "vol-b", "queue", "/queue/abp-040"));

        ReconcileReport report = service.run(/*verbose=*/true);
        long id = service.persist(report, "manual", "{}");

        List<PersistedReport> recent = reportRepo.findRecent(10);
        assertEquals(1, recent.size());
        PersistedReport p = recent.get(0);
        assertEquals(id, p.id());
        assertEquals(1, p.duplicateLiveLocations());
        assertEquals("manual", p.triggeredBy());
    }

    @Test
    void verboseFalse_omitsDetailLists() {
        long t = saveTitle("ABP-050");
        locationRepo.save(loc(t, "vol-a", "queue", "/queue/abp-050"));
        locationRepo.save(loc(t, "vol-b", "queue", "/queue/abp-050"));

        ReconcileReport r = service.run(/*verbose=*/false);
        assertEquals(1, r.duplicateLiveLocations(), "Counts populated regardless of verbose");
        assertTrue(r.duplicateLiveDetails().isEmpty(), "Details empty when verbose=false");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private long saveTitle(String code) {
        String label = code.split("-")[0];
        Title t = Title.builder().code(code).baseCode(label + "-00001")
                .label(label).seqNum(1).build();
        return titleRepo.save(t).getId();
    }

    private long saveTitleWithActress(String code, long actressId) {
        String label = code.split("-")[0];
        Title t = Title.builder().code(code).baseCode(label + "-00001")
                .label(label).seqNum(1).actressId(actressId).build();
        return titleRepo.save(t).getId();
    }

    private static TitleLocation loc(long titleId, String volumeId, String partitionId, String path) {
        return TitleLocation.builder()
                .titleId(titleId).volumeId(volumeId).partitionId(partitionId)
                .path(Path.of(path))
                .lastSeenAt(LocalDate.now())
                .build();
    }

    private void injectStale(long locationId, Instant when) {
        jdbi.useHandle(h -> h.createUpdate(
                "UPDATE title_locations SET stale_since = :ts WHERE id = :id")
                .bind("ts", when.toString())
                .bind("id", locationId)
                .execute());
    }

    private void markStaleNow(long locationId) {
        injectStale(locationId, Instant.now());
    }
}
