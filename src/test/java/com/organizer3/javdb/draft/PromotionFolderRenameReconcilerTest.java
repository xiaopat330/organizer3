package com.organizer3.javdb.draft;

import com.organizer3.db.SchemaInitializer;
import com.organizer3.javdb.draft.PromotionFolderRenameReconciler.Candidate;
import com.organizer3.javdb.draft.PromotionFolderRenameReconciler.ReconcileResult;
import com.organizer3.web.TitleFolderRenamer;
import com.organizer3.web.TitleFolderRenamer.RenameOutcome;

import java.util.List;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link PromotionFolderRenameReconciler}.
 *
 * <p>Uses real in-memory SQLite (via {@link SchemaInitializer}) for the candidate query, and a
 * Mockito-mocked {@link TitleFolderRenamer} so we never touch SMB. Schema-init harness mirrors
 * {@code DraftPromotionServiceTest}.
 */
class PromotionFolderRenameReconcilerTest {

    private static final String VOL = "unsorted";

    private Connection connection;
    private Jdbi jdbi;
    private TitleFolderRenamer renamer;
    private PromotionFolderRenameReconciler reconciler;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        jdbi.useHandle(h -> h.execute("PRAGMA foreign_keys = ON"));
        new SchemaInitializer(jdbi).initialize();
        jdbi.useHandle(h -> {
            h.execute("INSERT OR IGNORE INTO volumes(id, structure_type) VALUES (?, 'queue')", VOL);
            h.execute("INSERT INTO actresses(id, canonical_name, tier, first_seen_at) VALUES (10,'Mana Sakura','LIBRARY','2024-01-01')");
        });

        renamer = Mockito.mock(TitleFolderRenamer.class);
        reconciler = new PromotionFolderRenameReconciler(jdbi, renamer, VOL);
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    // ── Seed helpers ────────────────────────────────────────────────────────

    /** Promoted (grade_source='enrichment') title with an assigned actress and a live staging row. */
    private void seedPromoted(long id, String code, Long actressId, String path) {
        jdbi.useHandle(h -> {
            h.execute("INSERT INTO titles(id, code, base_code, label, seq_num, grade_source, actress_id) "
                    + "VALUES (?,?,?,?,?, 'enrichment', ?)", id, code, code, "TST", id, actressId);
            h.execute("INSERT INTO title_locations(title_id, volume_id, partition_id, path, last_seen_at) "
                    + "VALUES (?,?, 'q', ?, '2024-01-01')", id, VOL, path);
        });
    }

    /** Non-promoted (grade_source NULL) title with a live staging row. */
    private void seedUnpromoted(long id, String code, Long actressId, String path) {
        jdbi.useHandle(h -> {
            h.execute("INSERT INTO titles(id, code, base_code, label, seq_num, actress_id) "
                    + "VALUES (?,?,?,?,?, ?)", id, code, code, "TST", id, actressId);
            h.execute("INSERT INTO title_locations(title_id, volume_id, partition_id, path, last_seen_at) "
                    + "VALUES (?,?, 'q', ?, '2024-01-01')", id, VOL, path);
        });
    }

    // ── findCandidates ──────────────────────────────────────────────────────

    @Test
    void findCandidates_includesOnlyPromoted_withNeedsRename() {
        seedPromoted(1L, "ABP-527", 10L, "/fresh/(ABP-527)");
        seedUnpromoted(2L, "DUP-001", 10L, "/fresh/(DUP-001)");

        List<Candidate> candidates = reconciler.findCandidates(100);

        assertEquals(1, candidates.size(), "non-promoted (grade_source NULL) must be excluded");
        Candidate c = candidates.get(0);
        assertEquals(1L, c.titleId());
        assertEquals("ABP-527", c.code());
        assertEquals("Mana Sakura", c.actressName());
        assertEquals("Mana Sakura (ABP-527)", c.targetName());
        assertTrue(c.needsRename(), "un-normalized /fresh/(CODE) folder should need rename");
    }

    @Test
    void findCandidates_alreadyCorrectFolder_needsRenameFalse() {
        seedPromoted(1L, "ABP-527", 10L, "/fresh/Mana Sakura (ABP-527)");

        List<Candidate> candidates = reconciler.findCandidates(100);

        assertEquals(1, candidates.size());
        Candidate c = candidates.get(0);
        assertFalse(c.needsRename(), "already-canonical folder should not need rename");
        assertEquals("Mana Sakura (ABP-527)", c.targetName());
    }

    // ── reconcile ───────────────────────────────────────────────────────────

    @Test
    void reconcile_rename_countsRenamed() {
        seedPromoted(1L, "ABP-527", 10L, "/fresh/(ABP-527)");
        when(renamer.renamePreservingDescriptor(eq(1L), eq(List.of("Mana Sakura")), eq("ABP-527")))
                .thenReturn(new RenameOutcome("/fresh/Mana Sakura (ABP-527)", true));

        ReconcileResult r = reconciler.reconcile(100);

        assertEquals(1, r.candidates());
        assertEquals(1, r.renamed());
        assertEquals(0, r.alreadyOk());
        assertEquals(0, r.failed());
        verify(renamer).renamePreservingDescriptor(1L, List.of("Mana Sakura"), "ABP-527");
    }

    @Test
    void reconcile_collisionOnOne_doesNotAbortBatch() {
        // Second actress so both titles resolve a canonical_name.
        jdbi.useHandle(h -> h.execute(
                "INSERT INTO actresses(id, canonical_name, tier, first_seen_at) VALUES (11,'Yua Mikami','LIBRARY','2024-01-01')"));
        seedPromoted(1L, "ABP-527", 10L, "/fresh/(ABP-527)");   // will collide
        seedPromoted(2L, "SSIS-001", 11L, "/fresh/(SSIS-001)"); // will succeed
        seedPromoted(3L, "ABP-900", 10L, "/fresh/Mana Sakura (ABP-900)"); // already correct → alreadyOk

        when(renamer.renamePreservingDescriptor(eq(1L), anyList(), eq("ABP-527")))
                .thenThrow(new IllegalStateException("Target folder already exists: /fresh/Mana Sakura (ABP-527)"));
        when(renamer.renamePreservingDescriptor(eq(2L), anyList(), eq("SSIS-001")))
                .thenReturn(new RenameOutcome("/fresh/Yua Mikami (SSIS-001)", true));

        ReconcileResult r = reconciler.reconcile(100);

        assertEquals(3, r.candidates());
        assertEquals(1, r.renamed(), "the non-colliding title should still rename");
        assertEquals(1, r.failed(), "the collision counts as a failure");
        assertEquals(1, r.alreadyOk(), "the already-correct title needs no rename");
        // title 3 already-correct → renamer never invoked for it
        verify(renamer, never()).renamePreservingDescriptor(eq(3L), anyList(), any());
    }

    @Test
    void reconcile_renamerReturnsFalse_countsAlreadyOk() {
        seedPromoted(1L, "ABP-527", 10L, "/fresh/(ABP-527)");
        // Renamer's own no-op (e.g. concurrently fixed): renamed=false.
        when(renamer.renamePreservingDescriptor(eq(1L), anyList(), eq("ABP-527")))
                .thenReturn(new RenameOutcome("/fresh/(ABP-527)", false));

        ReconcileResult r = reconciler.reconcile(100);

        assertEquals(1, r.candidates());
        assertEquals(0, r.renamed());
        assertEquals(1, r.alreadyOk());
        assertEquals(0, r.failed());
    }

    // ── Multi-name: 2-cast candidate ─────────────────────────────────────────

    @Test
    void findCandidates_twoCast_producesTwoNameTarget() {
        // Actress 11 is a co-credit of title 1; actress 10 is the filing actress.
        jdbi.useHandle(h -> h.execute(
                "INSERT INTO actresses(id, canonical_name, tier, first_seen_at) "
                + "VALUES (11,'Miyu Aizawa','LIBRARY','2024-01-01')"));
        seedPromoted(1L, "ADN-778", 10L, "/fresh/(ADN-778)");
        // Link filing actress first (lower rowid), then co-credit.
        jdbi.useHandle(h -> {
            h.execute("INSERT INTO title_actresses(title_id, actress_id) VALUES (1,10)");
            h.execute("INSERT INTO title_actresses(title_id, actress_id) VALUES (1,11)");
        });

        List<Candidate> candidates = reconciler.findCandidates(100);

        assertEquals(1, candidates.size());
        Candidate c = candidates.get(0);
        assertEquals(List.of("Mana Sakura", "Miyu Aizawa"), c.actressNames());
        assertEquals("Mana Sakura, Miyu Aizawa (ADN-778)", c.targetName());
        assertTrue(c.needsRename());
    }

    // ── Idempotency: already-correct multi-name folder ──────────────────────

    @Test
    void findCandidates_alreadyCorrectMultiName_needsRenameFalse() {
        jdbi.useHandle(h -> h.execute(
                "INSERT INTO actresses(id, canonical_name, tier, first_seen_at) "
                + "VALUES (11,'Miyu Aizawa','LIBRARY','2024-01-01')"));
        seedPromoted(1L, "ADN-778", 10L, "/fresh/Mana Sakura, Miyu Aizawa (ADN-778)");
        jdbi.useHandle(h -> {
            h.execute("INSERT INTO title_actresses(title_id, actress_id) VALUES (1,10)");
            h.execute("INSERT INTO title_actresses(title_id, actress_id) VALUES (1,11)");
        });

        List<Candidate> candidates = reconciler.findCandidates(100);

        assertEquals(1, candidates.size());
        Candidate c = candidates.get(0);
        assertFalse(c.needsRename(), "already-correct multi-name folder must not need rename");
        assertEquals("Mana Sakura, Miyu Aizawa (ADN-778)", c.targetName());
    }

    // ── Idempotency: Various overflow folder ─────────────────────────────────

    @Test
    void findCandidates_variousOverflowFolder_idempotent() {
        // Create a title with a very long actress name that forces overflow.
        jdbi.useHandle(h -> h.execute(
                "INSERT INTO actresses(id, canonical_name, tier, first_seen_at) "
                + "VALUES (12,'" + "A".repeat(196) + "','LIBRARY','2024-01-01')"));
        // Filing actress = 12 (long name), target = "Various (TST-OVF)"
        jdbi.useHandle(h -> h.execute(
                "INSERT INTO titles(id, code, base_code, label, seq_num, grade_source, actress_id) "
                + "VALUES (5,'TST-OVF','TST-OVF','TST',5,'enrichment',12)"));
        jdbi.useHandle(h -> {
            h.execute("INSERT INTO title_locations(title_id, volume_id, partition_id, path, last_seen_at) "
                    + "VALUES (5,?,  'q', '/fresh/Various (TST-OVF)', '2024-01-01')", VOL);
            h.execute("INSERT INTO title_actresses(title_id, actress_id) VALUES (5,12)");
        });

        List<Candidate> candidates = reconciler.findCandidates(100);
        Candidate c = candidates.stream().filter(x -> x.titleId() == 5L).findFirst().orElseThrow();

        assertEquals("Various (TST-OVF)", c.targetName());
        assertFalse(c.needsRename(), "Various overflow folder already-correct → no rename needed");
    }

    // ── Volume gating: conventional volumes not affected ─────────────────────

    @Test
    void usesMultiNameFolders_gating() {
        // queue = true (staging); all others = false
        assertTrue(com.organizer3.web.TitleFolderRenamer.usesMultiNameFolders("queue"));
        assertFalse(com.organizer3.web.TitleFolderRenamer.usesMultiNameFolders("conventional"));
        assertFalse(com.organizer3.web.TitleFolderRenamer.usesMultiNameFolders("collections"));
        assertFalse(com.organizer3.web.TitleFolderRenamer.usesMultiNameFolders("exhibition"));
        assertFalse(com.organizer3.web.TitleFolderRenamer.usesMultiNameFolders("sort_pool"));
        assertFalse(com.organizer3.web.TitleFolderRenamer.usesMultiNameFolders("avstars"));
    }

    // ── backfill: broader scope covers non-enrichment titles ─────────────────

    @Test
    void backfill_includesNonEnrichmentTitles() {
        // Seed a non-promoted title (grade_source NULL) with a mis-named folder.
        seedUnpromoted(2L, "DUP-001", 10L, "/fresh/(DUP-001)");
        jdbi.useHandle(h ->
                h.execute("INSERT INTO title_actresses(title_id, actress_id) VALUES (2,10)"));

        when(renamer.renamePreservingDescriptor(eq(2L), anyList(), eq("DUP-001")))
                .thenReturn(new RenameOutcome("/fresh/Mana Sakura (DUP-001)", true));

        // backfill finds it; reconcile (enrichment-only) does not.
        ReconcileResult reconcileResult = reconciler.reconcile(100);
        assertEquals(0, reconcileResult.candidates(), "reconcile must not touch non-enrichment titles");

        ReconcileResult backfillResult = reconciler.backfill(100);
        assertEquals(1, backfillResult.renamed(), "backfill must rename non-enrichment staged title");
    }

    // ── helper reuse agreement ────────────────────────────────────────────────

    @Test
    void targetFolderName_matchesRenamerConstruction() {
        // No descriptor.
        assertEquals("Mana Sakura (ABP-527)",
                TitleFolderRenamer.targetFolderName("Mana Sakura", "", "ABP-527"));
        assertEquals("Mana Sakura (ABP-527)",
                TitleFolderRenamer.targetFolderName("Mana Sakura", null, "ABP-527"));

        // With descriptor preserved from the current folder name.
        String folder = "Mana Sakura - Demosaiced (ABP-527)";
        String desc = TitleFolderRenamer.extractDescriptor(folder, "ABP-527");
        assertEquals("Demosaiced", desc);
        assertEquals("Mana Sakura - Demosaiced (ABP-527)",
                TitleFolderRenamer.targetFolderName("Mana Sakura", desc, "ABP-527"));
    }
}
