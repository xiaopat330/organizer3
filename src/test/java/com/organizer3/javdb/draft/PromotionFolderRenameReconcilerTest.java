package com.organizer3.javdb.draft;

import com.organizer3.db.SchemaInitializer;
import com.organizer3.javdb.draft.PromotionFolderRenameReconciler.Candidate;
import com.organizer3.javdb.draft.PromotionFolderRenameReconciler.ReconcileResult;
import com.organizer3.web.TitleFolderRenamer;
import com.organizer3.web.TitleFolderRenamer.RenameOutcome;
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
        when(renamer.renamePreservingDescriptor(eq(1L), eq("Mana Sakura"), eq("ABP-527")))
                .thenReturn(new RenameOutcome("/fresh/Mana Sakura (ABP-527)", true));

        ReconcileResult r = reconciler.reconcile(100);

        assertEquals(1, r.candidates());
        assertEquals(1, r.renamed());
        assertEquals(0, r.alreadyOk());
        assertEquals(0, r.failed());
        verify(renamer).renamePreservingDescriptor(1L, "Mana Sakura", "ABP-527");
    }

    @Test
    void reconcile_collisionOnOne_doesNotAbortBatch() {
        // Second actress so both titles resolve a canonical_name.
        jdbi.useHandle(h -> h.execute(
                "INSERT INTO actresses(id, canonical_name, tier, first_seen_at) VALUES (11,'Yua Mikami','LIBRARY','2024-01-01')"));
        seedPromoted(1L, "ABP-527", 10L, "/fresh/(ABP-527)");   // will collide
        seedPromoted(2L, "SSIS-001", 11L, "/fresh/(SSIS-001)"); // will succeed
        seedPromoted(3L, "ABP-900", 10L, "/fresh/Mana Sakura (ABP-900)"); // already correct → alreadyOk

        when(renamer.renamePreservingDescriptor(eq(1L), any(), eq("ABP-527")))
                .thenThrow(new IllegalStateException("Target folder already exists: /fresh/Mana Sakura (ABP-527)"));
        when(renamer.renamePreservingDescriptor(eq(2L), any(), eq("SSIS-001")))
                .thenReturn(new RenameOutcome("/fresh/Yua Mikami (SSIS-001)", true));

        ReconcileResult r = reconciler.reconcile(100);

        assertEquals(3, r.candidates());
        assertEquals(1, r.renamed(), "the non-colliding title should still rename");
        assertEquals(1, r.failed(), "the collision counts as a failure");
        assertEquals(1, r.alreadyOk(), "the already-correct title needs no rename");
        // title 3 already-correct → renamer never invoked for it
        verify(renamer, never()).renamePreservingDescriptor(eq(3L), any(), any());
    }

    @Test
    void reconcile_renamerReturnsFalse_countsAlreadyOk() {
        seedPromoted(1L, "ABP-527", 10L, "/fresh/(ABP-527)");
        // Renamer's own no-op (e.g. concurrently fixed): renamed=false.
        when(renamer.renamePreservingDescriptor(eq(1L), any(), eq("ABP-527")))
                .thenReturn(new RenameOutcome("/fresh/(ABP-527)", false));

        ReconcileResult r = reconciler.reconcile(100);

        assertEquals(1, r.candidates());
        assertEquals(0, r.renamed());
        assertEquals(1, r.alreadyOk());
        assertEquals(0, r.failed());
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
