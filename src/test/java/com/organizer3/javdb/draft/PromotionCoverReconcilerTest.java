package com.organizer3.javdb.draft;

import com.organizer3.covers.CoverPath;
import com.organizer3.db.SchemaInitializer;
import com.organizer3.javdb.draft.PromotionCoverReconciler.ReconcileResult;
import com.organizer3.web.CoverWriteService;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link PromotionCoverReconciler}.
 *
 * <p>Real in-memory SQLite (via {@link SchemaInitializer}) drives the pending-location query; a
 * Mockito-mocked {@link CoverWriteService} stands in for SMB; local cover files live under a
 * {@link TempDir}. Harness mirrors {@code PromotionFolderRenameReconcilerTest}.
 */
class PromotionCoverReconcilerTest {

    private static final String VOL = "unsorted";

    @TempDir Path dataDir;

    private Connection connection;
    private Jdbi jdbi;
    private CoverWriteService coverWriteService;
    private CoverPath coverPath;
    private PromotionCoverReconciler reconciler;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        jdbi.useHandle(h -> h.execute("PRAGMA foreign_keys = ON"));
        new SchemaInitializer(jdbi).initialize();
        jdbi.useHandle(h -> {
            h.execute("INSERT OR IGNORE INTO volumes(id, structure_type) VALUES (?, 'queue')", VOL);
            h.execute("INSERT OR IGNORE INTO volumes(id, structure_type) VALUES ('other', 'queue')");
        });

        coverWriteService = Mockito.mock(CoverWriteService.class);
        coverPath = new CoverPath(dataDir);
        reconciler = new PromotionCoverReconciler(jdbi, coverWriteService, coverPath, VOL);
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    // ── Seed helpers ────────────────────────────────────────────────────────

    /** Seeds a title + a live staging location with the given pending flag (null = confirmed). */
    private void seedLocation(long id, String code, String label, String path,
                              String volume, String pendingSince) {
        jdbi.useHandle(h -> {
            h.execute("INSERT INTO titles(id, code, base_code, label, seq_num) VALUES (?,?,?,?,?)",
                    id, code, code, label, id);
            h.execute("INSERT INTO title_locations"
                    + "(title_id, volume_id, partition_id, path, last_seen_at, cover_pending_since)"
                    + " VALUES (?,?, 'q', ?, '2024-01-01', ?)", id, volume, path, pendingSince);
        });
    }

    /** Writes a local-cache cover of the given size for a label/baseCode. */
    private void seedLocalCover(String label, String baseCode, int size) throws Exception {
        Path dir = dataDir.resolve("covers").resolve(label.toUpperCase());
        Files.createDirectories(dir);
        Files.write(dir.resolve(baseCode + ".jpg"), new byte[size]);
    }

    private String pendingSince(long titleId) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT cover_pending_since FROM title_locations WHERE title_id = :t")
                        .bind("t", titleId)
                        .mapTo(String.class).findOne().orElse(null));
    }

    // ── pushed + cleared ─────────────────────────────────────────────────────

    @Test
    void pendingWithIntactLocalCover_pushedAndCleared() throws Exception {
        seedLocation(1L, "ABP-527", "ABP", "/fresh/Mana (ABP-527)", VOL, "2026-07-10T00:00:00Z");
        seedLocalCover("ABP", "ABP-527", 1024);
        when(coverWriteService.saveToNasBestEffort(any(), any(), any(), any())).thenReturn(true);

        ReconcileResult r = reconciler.reconcile(100);

        assertEquals(1, r.candidates());
        assertEquals(1, r.pushed());
        assertEquals(0, r.noLocalCover());
        assertEquals(0, r.stillPending());
        assertEquals(0, r.failed());
        verify(coverWriteService).saveToNasBestEffort(
                eq("/fresh/Mana (ABP-527)"), eq("ABP-527"), any(byte[].class), eq(VOL));
        assertNull(pendingSince(1L), "flag must be cleared after a successful push");
    }

    // ── no / zero-byte local cover ────────────────────────────────────────────

    @Test
    void pendingWithNoLocalCover_leftPending() {
        seedLocation(1L, "ABP-527", "ABP", "/fresh/Mana (ABP-527)", VOL, "2026-07-10T00:00:00Z");
        // No local cover on disk.

        ReconcileResult r = reconciler.reconcile(100);

        assertEquals(1, r.candidates());
        assertEquals(1, r.noLocalCover());
        assertEquals(0, r.pushed());
        assertNotNull(pendingSince(1L), "source-less row must stay pending");
        verify(coverWriteService, never()).saveToNasBestEffort(any(), any(), any(), any());
    }

    @Test
    void pendingWithZeroByteLocalCover_leftPending() throws Exception {
        seedLocation(1L, "ABP-527", "ABP", "/fresh/Mana (ABP-527)", VOL, "2026-07-10T00:00:00Z");
        seedLocalCover("ABP", "ABP-527", 0);  // zero-byte stub

        ReconcileResult r = reconciler.reconcile(100);

        assertEquals(1, r.noLocalCover(), "zero-byte cover counts as no local cover");
        assertEquals(0, r.pushed());
        assertNotNull(pendingSince(1L));
        verify(coverWriteService, never()).saveToNasBestEffort(any(), any(), any(), any());
    }

    // ── transient push failure ─────────────────────────────────────────────────

    @Test
    void pendingWithFailedPush_stillPending() throws Exception {
        seedLocation(1L, "ABP-527", "ABP", "/fresh/Mana (ABP-527)", VOL, "2026-07-10T00:00:00Z");
        seedLocalCover("ABP", "ABP-527", 512);
        when(coverWriteService.saveToNasBestEffort(any(), any(), any(), any())).thenReturn(false);

        ReconcileResult r = reconciler.reconcile(100);

        assertEquals(1, r.candidates());
        assertEquals(1, r.stillPending());
        assertEquals(0, r.pushed());
        assertNotNull(pendingSince(1L), "a failed push must leave the flag pending for the next pass");
    }

    // ── confirmed (NULL) rows never selected ───────────────────────────────────

    @Test
    void confirmedRows_neverSelected() throws Exception {
        seedLocation(1L, "ABP-527", "ABP", "/fresh/Mana (ABP-527)", VOL, null);  // confirmed
        seedLocalCover("ABP", "ABP-527", 1024);
        when(coverWriteService.saveToNasBestEffort(any(), any(), any(), any())).thenReturn(true);

        ReconcileResult r = reconciler.reconcile(100);

        assertEquals(0, r.candidates(), "NULL cover_pending_since rows must not be selected");
        verify(coverWriteService, never()).saveToNasBestEffort(any(), any(), any(), any());
    }

    // ── scope: volume + stale gating ───────────────────────────────────────────

    @Test
    void offServiceableVolume_notSelected() throws Exception {
        seedLocation(1L, "ABP-527", "ABP", "/fresh/Mana (ABP-527)", "other", "2026-07-10T00:00:00Z");
        seedLocalCover("ABP", "ABP-527", 1024);

        ReconcileResult r = reconciler.reconcile(100);

        assertEquals(0, r.candidates(), "locations off the serviceable volume set must be excluded");
    }

    @Test
    void staleLocation_notSelected() throws Exception {
        seedLocation(1L, "ABP-527", "ABP", "/fresh/Mana (ABP-527)", VOL, "2026-07-10T00:00:00Z");
        seedLocalCover("ABP", "ABP-527", 1024);
        jdbi.useHandle(h -> h.execute(
                "UPDATE title_locations SET stale_since = '2026-01-01' WHERE title_id = 1"));

        ReconcileResult r = reconciler.reconcile(100);

        assertEquals(0, r.candidates(), "stale locations must be excluded");
    }

    // ── one row's failure never aborts the batch ───────────────────────────────

    @Test
    void oneRowThrows_doesNotAbortBatch() throws Exception {
        seedLocation(1L, "ABP-527", "ABP", "/fresh/Mana (ABP-527)", VOL, "2026-07-10T00:00:01Z");
        seedLocation(2L, "SSIS-001", "SSIS", "/fresh/Yua (SSIS-001)", VOL, "2026-07-10T00:00:02Z");
        seedLocalCover("ABP", "ABP-527", 512);
        seedLocalCover("SSIS", "SSIS-001", 512);
        // First title throws inside the push; second succeeds.
        when(coverWriteService.saveToNasBestEffort(eq("/fresh/Mana (ABP-527)"), any(), any(), any()))
                .thenThrow(new RuntimeException("boom"));
        when(coverWriteService.saveToNasBestEffort(eq("/fresh/Yua (SSIS-001)"), any(), any(), any()))
                .thenReturn(true);

        ReconcileResult r = reconciler.reconcile(100);

        assertEquals(2, r.candidates());
        assertEquals(1, r.pushed(), "the non-throwing title still pushes");
        assertEquals(1, r.failed(), "the throwing title counts as failed");
        assertNotNull(pendingSince(1L), "failed row stays pending");
        assertNull(pendingSince(2L), "succeeded row is cleared");
    }
}
