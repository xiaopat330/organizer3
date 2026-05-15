package com.organizer3.notes;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NoteServiceTest {

    private NoteRepository repo;
    private NoteService.EntityResolver resolver;
    private NoteService service;

    @BeforeEach
    void setUp() {
        repo = mock(NoteRepository.class);
        resolver = mock(NoteService.EntityResolver.class);
        service = new NoteService(repo, resolver);
    }

    // ── find ──────────────────────────────────────────────────────────────────

    @Test
    void find_delegatesToRepo() {
        Note note = new Note(EntityType.ACTRESS, "42", "hello", 1000L, 1000L);
        when(repo.find(EntityType.ACTRESS, "42")).thenReturn(Optional.of(note));

        Optional<Note> result = service.find(EntityType.ACTRESS, "42");

        assertTrue(result.isPresent());
        assertEquals("hello", result.get().body());
        verify(repo).find(EntityType.ACTRESS, "42");
    }

    // ── upsert — empty body deletes ───────────────────────────────────────────

    @Test
    void upsert_emptyBodyDeletesAndReturnsDeleted() {
        NoteService.UpsertResult result = service.upsert(EntityType.ACTRESS, "42", "");

        assertEquals(NoteService.DELETED, result);
        verify(repo).delete(EntityType.ACTRESS, "42");
        verifyNoMoreInteractions(resolver);
    }

    @Test
    void upsert_whitespaceOnlyBodyDeletesAndReturnsDeleted() {
        NoteService.UpsertResult result = service.upsert(EntityType.ACTRESS, "42", "   \t\n  ");

        assertEquals(NoteService.DELETED, result);
        verify(repo).delete(EntityType.ACTRESS, "42");
    }

    @Test
    void upsert_nullBodyDeletesAndReturnsDeleted() {
        NoteService.UpsertResult result = service.upsert(EntityType.ACTRESS, "42", null);

        assertEquals(NoteService.DELETED, result);
        verify(repo).delete(EntityType.ACTRESS, "42");
    }

    // ── upsert — trim ─────────────────────────────────────────────────────────

    @Test
    void upsert_trimsLeadingTrailingWhitespace() {
        when(resolver.resolve(EntityType.ACTRESS, "42"))
                .thenReturn(NoteService.EntityResolver.Resolution.CANONICAL);
        Note persisted = new Note(EntityType.ACTRESS, "42", "hello", 1000L, 1000L);
        when(repo.find(EntityType.ACTRESS, "42")).thenReturn(Optional.of(persisted));

        service.upsert(EntityType.ACTRESS, "42", "  hello  ");

        verify(repo).upsert(EntityType.ACTRESS, "42", "hello");
    }

    // ── upsert — 280-char cap ─────────────────────────────────────────────────

    @Test
    void upsert_exactly280CharsIsAllowed() {
        String body = "a".repeat(280);
        when(resolver.resolve(EntityType.ACTRESS, "42"))
                .thenReturn(NoteService.EntityResolver.Resolution.CANONICAL);
        Note persisted = new Note(EntityType.ACTRESS, "42", body, 1000L, 1000L);
        when(repo.find(EntityType.ACTRESS, "42")).thenReturn(Optional.of(persisted));

        NoteService.UpsertResult result = service.upsert(EntityType.ACTRESS, "42", body);

        assertInstanceOf(NoteService.UpsertResult.Ok.class, result);
    }

    @Test
    void upsert_over280CharsReturnsTooLong() {
        String body = "a".repeat(281);

        NoteService.UpsertResult result = service.upsert(EntityType.ACTRESS, "42", body);

        assertEquals(NoteService.TOO_LONG, result);
        verifyNoInteractions(resolver);
        verifyNoMoreInteractions(repo);
    }

    // ── upsert — draft rejection ──────────────────────────────────────────────

    @Test
    void upsert_draftOnlyReturns_draftRejected() {
        when(resolver.resolve(EntityType.ACTRESS, "some-slug"))
                .thenReturn(NoteService.EntityResolver.Resolution.DRAFT_ONLY);

        NoteService.UpsertResult result = service.upsert(EntityType.ACTRESS, "some-slug", "note");

        assertEquals(NoteService.DRAFT_REJECTED, result);
        verify(repo, never()).upsert(any(), any(), any());
    }

    // ── upsert — not found ────────────────────────────────────────────────────

    @Test
    void upsert_notFoundReturnsNotFound() {
        when(resolver.resolve(EntityType.TITLE, "ZZZ-999"))
                .thenReturn(NoteService.EntityResolver.Resolution.NOT_FOUND);

        NoteService.UpsertResult result = service.upsert(EntityType.TITLE, "ZZZ-999", "note");

        assertEquals(NoteService.NOT_FOUND, result);
        verify(repo, never()).upsert(any(), any(), any());
    }

    // ── upsert — normal canonical path ───────────────────────────────────────

    @Test
    void upsert_canonicalEntityReturnsOkWithNote() {
        String body = "great scene";
        when(resolver.resolve(EntityType.TITLE, "ABP-001"))
                .thenReturn(NoteService.EntityResolver.Resolution.CANONICAL);
        Note persisted = new Note(EntityType.TITLE, "ABP-001", body, 1000L, 2000L);
        when(repo.find(EntityType.TITLE, "ABP-001")).thenReturn(Optional.of(persisted));

        NoteService.UpsertResult result = service.upsert(EntityType.TITLE, "ABP-001", body);

        assertInstanceOf(NoteService.UpsertResult.Ok.class, result);
        NoteService.UpsertResult.Ok ok = (NoteService.UpsertResult.Ok) result;
        assertEquals(body, ok.note().body());
        assertEquals(EntityType.TITLE, ok.note().entityType());
        verify(repo).upsert(EntityType.TITLE, "ABP-001", body);
    }

    // ── upsert — NFC normalization ────────────────────────────────────────────

    @Test
    void upsert_nfcNormalizesBeforePersisting() {
        // NFD: e + combining accent = 2 code points
        String nfd = "é"; // "é" in NFD
        String nfc = "é";       // "é" in NFC (single code point)

        when(resolver.resolve(EntityType.ACTRESS, "1"))
                .thenReturn(NoteService.EntityResolver.Resolution.CANONICAL);
        Note persisted = new Note(EntityType.ACTRESS, "1", nfc, 1000L, 1000L);
        when(repo.find(EntityType.ACTRESS, "1")).thenReturn(Optional.of(persisted));

        service.upsert(EntityType.ACTRESS, "1", nfd);

        // Verify repo.upsert was called with the NFC form
        verify(repo).upsert(EntityType.ACTRESS, "1", nfc);
    }

    // ── delete ────────────────────────────────────────────────────────────────

    @Test
    void delete_delegatesToRepo() {
        service.delete(EntityType.ACTRESS, "99");

        verify(repo).delete(EntityType.ACTRESS, "99");
    }
}
