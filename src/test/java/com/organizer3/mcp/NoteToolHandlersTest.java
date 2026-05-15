package com.organizer3.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.organizer3.notes.EntityType;
import com.organizer3.notes.OrphanNoteFinder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link NoteToolHandlers.FindOrphanNotes} and
 * {@link NoteToolHandlers.PruneOrphanNotes} using a mocked {@link OrphanNoteFinder}.
 */
class NoteToolHandlersTest {

    private static final ObjectMapper M = new ObjectMapper();

    private OrphanNoteFinder finder;
    private NoteToolHandlers.FindOrphanNotes findTool;
    private NoteToolHandlers.PruneOrphanNotes pruneTool;

    @BeforeEach
    void setUp() {
        finder    = mock(OrphanNoteFinder.class);
        findTool  = new NoteToolHandlers.FindOrphanNotes(finder);
        pruneTool = new NoteToolHandlers.PruneOrphanNotes(finder);
    }

    // ── find_orphan_notes ─────────────────────────────────────────────────────

    @Test
    void findOrphanNotes_returnsEmptyListWhenNoOrphans() throws Exception {
        when(finder.findAll()).thenReturn(List.of());

        var result = (NoteToolHandlers.FindOrphanNotes.Result) findTool.call(M.createObjectNode());

        assertEquals(0, result.count());
        assertTrue(result.orphanNotes().isEmpty());
        verify(finder).findAll();
    }

    @Test
    void findOrphanNotes_returnsOrphanList() throws Exception {
        OrphanNoteFinder.OrphanNote orphan1 = new OrphanNoteFinder.OrphanNote(
                EntityType.ACTRESS, "42", "check cover", 1_000_000L);
        OrphanNoteFinder.OrphanNote orphan2 = new OrphanNoteFinder.OrphanNote(
                EntityType.TITLE, "ABP-999", "merge candidate", 2_000_000L);
        when(finder.findAll()).thenReturn(List.of(orphan1, orphan2));

        var result = (NoteToolHandlers.FindOrphanNotes.Result) findTool.call(M.createObjectNode());

        assertEquals(2, result.count());
        assertEquals(2, result.orphanNotes().size());
        assertEquals(EntityType.ACTRESS, result.orphanNotes().get(0).entityType());
        assertEquals("42", result.orphanNotes().get(0).entityId());
        assertEquals("check cover", result.orphanNotes().get(0).body());
        verify(finder).findAll();
    }

    @Test
    void findOrphanNotes_toolNameIsCorrect() {
        assertEquals("find_orphan_notes", findTool.name());
    }

    // ── prune_orphan_notes — dryRun=true (default) ────────────────────────────

    @Test
    void pruneOrphanNotes_dryRunTrue_returnsCountWithoutDeleting() throws Exception {
        OrphanNoteFinder.OrphanNote orphan = new OrphanNoteFinder.OrphanNote(
                EntityType.ACTRESS, "10", "body", 1_000_000L);
        when(finder.findAll()).thenReturn(List.of(orphan));

        ObjectNode args = M.createObjectNode();
        args.put("dryRun", true);
        var result = (NoteToolHandlers.PruneOrphanNotes.Result) pruneTool.call(args);

        assertTrue(result.dryRun());
        assertEquals(1, result.count());
        assertTrue(result.message().contains("Would delete"));
        // pruneAll must NOT be called
        verify(finder, never()).pruneAll();
        verify(finder).findAll();
    }

    @Test
    void pruneOrphanNotes_dryRunDefaultsToTrue() throws Exception {
        when(finder.findAll()).thenReturn(List.of());

        var result = (NoteToolHandlers.PruneOrphanNotes.Result) pruneTool.call(M.createObjectNode());

        assertTrue(result.dryRun(), "Default dryRun must be true");
        verify(finder, never()).pruneAll();
    }

    @Test
    void pruneOrphanNotes_dryRunTrue_zeroOrphans() throws Exception {
        when(finder.findAll()).thenReturn(List.of());

        ObjectNode args = M.createObjectNode();
        args.put("dryRun", true);
        var result = (NoteToolHandlers.PruneOrphanNotes.Result) pruneTool.call(args);

        assertTrue(result.dryRun());
        assertEquals(0, result.count());
        verify(finder, never()).pruneAll();
    }

    // ── prune_orphan_notes — dryRun=false ────────────────────────────────────

    @Test
    void pruneOrphanNotes_dryRunFalse_callsPruneAllAndReturnsCount() throws Exception {
        when(finder.pruneAll()).thenReturn(3);

        ObjectNode args = M.createObjectNode();
        args.put("dryRun", false);
        var result = (NoteToolHandlers.PruneOrphanNotes.Result) pruneTool.call(args);

        assertFalse(result.dryRun());
        assertEquals(3, result.count());
        assertTrue(result.message().contains("Deleted"));
        // findAll must NOT be called on a live run
        verify(finder, never()).findAll();
        verify(finder).pruneAll();
    }

    @Test
    void pruneOrphanNotes_dryRunFalse_zeroDeleted() throws Exception {
        when(finder.pruneAll()).thenReturn(0);

        ObjectNode args = M.createObjectNode();
        args.put("dryRun", false);
        var result = (NoteToolHandlers.PruneOrphanNotes.Result) pruneTool.call(args);

        assertFalse(result.dryRun());
        assertEquals(0, result.count());
        verify(finder).pruneAll();
    }

    @Test
    void pruneOrphanNotes_toolNameIsCorrect() {
        assertEquals("prune_orphan_notes", pruneTool.name());
    }
}
