package com.organizer3.javdb.enrichment;

import com.organizer3.javdb.enrichment.NoMatchTriageRepository.FilmographyCandidate;
import com.organizer3.javdb.enrichment.NoMatchTriageRepository.FolderInfo;
import com.organizer3.javdb.enrichment.NoMatchTriageRepository.NoMatchRow;
import com.organizer3.javdb.enrichment.NoMatchTriageService.CandidateActress;
import com.organizer3.javdb.enrichment.NoMatchTriageService.NoMatchTriageRow;
import com.organizer3.mcp.tools.ForceEnrichTitleTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NoMatchTriageServiceTest {

    private NoMatchTriageRepository mockRepo;
    private ForceEnrichTitleTool mockForceEnrich;
    private NoMatchTriageService service;

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-05-02T00:00:00Z"), ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        mockRepo         = mock(NoMatchTriageRepository.class);
        mockForceEnrich  = mock(ForceEnrichTitleTool.class);
        service          = new NoMatchTriageService(mockRepo, mockForceEnrich, FIXED_CLOCK);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static NoMatchRow makeRow(long titleId, String code, Long actressId, String actressName) {
        return new NoMatchRow(1L, titleId, code, code, actressId, actressName,
                "/stars/Actress/Title", "vol1", 1, "2026-01-01T00:00:00Z");
    }

    private static FilmographyCandidate makeCandidate(long actressId, String name, String javdbSlug) {
        return new FilmographyCandidate(actressId, name, javdbSlug, "title-slug",
                "2026-04-01T00:00:00Z", "2024-01-01");   // recent enough to be fresh
    }

    // ── list ──────────────────────────────────────────────────────────────────

    @Test
    void list_returnsSingleRow() {
        when(mockRepo.listNoMatchRows()).thenReturn(List.of(makeRow(10L, "STAR-001", 5L, "Actress A")));
        when(mockRepo.findActressesByFilmographyCode("STAR-001")).thenReturn(List.of());

        List<NoMatchTriageRow> rows = service.list(null, false);
        assertEquals(1, rows.size());
        assertEquals(10L, rows.get(0).titleId());
        assertEquals("STAR-001", rows.get(0).code());
        assertFalse(rows.get(0).orphan());
    }

    @Test
    void list_orphanRowHasEmptyActressIds() {
        when(mockRepo.listNoMatchRows()).thenReturn(List.of(makeRow(11L, "MIST-001", null, null)));
        when(mockRepo.findActressesByFilmographyCode("MIST-001")).thenReturn(List.of());

        List<NoMatchTriageRow> rows = service.list(null, false);
        assertEquals(1, rows.size());
        assertTrue(rows.get(0).orphan());
        assertTrue(rows.get(0).linkedActressIds().isEmpty());
    }

    @Test
    void list_actressWithNullStageNameTreatedAsOrphan() {
        // Regression: linked actress row with NULL stage_name caused List.copyOf NPE.
        when(mockRepo.listNoMatchRows()).thenReturn(List.of(makeRow(12L, "STAR-002", 7L, null)));
        when(mockRepo.findActressesByFilmographyCode("STAR-002")).thenReturn(List.of());

        List<NoMatchTriageRow> rows = service.list(null, false);
        assertEquals(1, rows.size());
        assertTrue(rows.get(0).orphan());
        assertTrue(rows.get(0).linkedActressIds().isEmpty());
        assertTrue(rows.get(0).linkedActressNames().isEmpty());
    }

    @Test
    void list_orphanFilterExcludesLinkedRows() {
        when(mockRepo.listNoMatchRows()).thenReturn(List.of(
                makeRow(10L, "STAR-001", 5L, "Actress A"),
                makeRow(11L, "MIST-001", null, null)));
        when(mockRepo.findActressesByFilmographyCode(any())).thenReturn(List.of());

        List<NoMatchTriageRow> orphans = service.list(null, true);
        assertEquals(1, orphans.size());
        assertEquals(11L, orphans.get(0).titleId());
    }

    @Test
    void list_actressIdFilterReturnsOnlyMatchingRows() {
        when(mockRepo.listNoMatchRows()).thenReturn(List.of(
                makeRow(10L, "STAR-001", 5L, "Actress A"),
                makeRow(11L, "STAR-002", 9L, "Actress B")));
        when(mockRepo.findActressesByFilmographyCode(any())).thenReturn(List.of());

        List<NoMatchTriageRow> filtered = service.list(9L, false);
        assertEquals(1, filtered.size());
        assertEquals(11L, filtered.get(0).titleId());
    }

    @Test
    void list_candidateHintsDedupedByCode() {
        // Two rows with same code -> findActressesByFilmographyCode called only once.
        NoMatchRow r1 = new NoMatchRow(1L, 10L, "STAR-001", "STAR-001",
                5L, "Actress A", "/path1", "vol1", 1, "2026-01-01T00:00:00Z");
        NoMatchRow r2 = new NoMatchRow(2L, 11L, "STAR-001", "STAR-001",
                6L, "Actress B", "/path2", "vol1", 1, "2026-01-01T00:00:00Z");
        // Same code, different titles → two distinct triage rows but one cache lookup.
        when(mockRepo.listNoMatchRows()).thenReturn(List.of(r1, r2));
        when(mockRepo.findActressesByFilmographyCode("STAR-001")).thenReturn(List.of());

        service.list(null, false);

        // Exactly one lookup for the shared code.
        verify(mockRepo, times(1)).findActressesByFilmographyCode("STAR-001");
    }

    @Test
    void list_candidatesIncludeStaleFlagFresh() {
        when(mockRepo.listNoMatchRows()).thenReturn(List.of(makeRow(10L, "STAR-001", null, null)));
        // Fresh cache (fetched 1 day ago)
        FilmographyCandidate fresh = new FilmographyCandidate(5L, "Fresh Actress", "fresh-slug",
                "title-slug", "2026-05-01T00:00:00Z", null);
        when(mockRepo.findActressesByFilmographyCode("STAR-001")).thenReturn(List.of(fresh));

        List<NoMatchTriageRow> rows = service.list(null, false);
        CandidateActress ca = rows.get(0).candidates().get(0);
        assertFalse(ca.stale(), "Candidate fetched 1 day ago should not be stale");
    }

    @Test
    void list_candidatesIncludeStaleFlagStale() {
        when(mockRepo.listNoMatchRows()).thenReturn(List.of(makeRow(10L, "STAR-001", null, null)));
        // Stale cache (fetched 60 days ago)
        FilmographyCandidate stale = new FilmographyCandidate(5L, "Stale Actress", "stale-slug",
                "title-slug", "2026-03-01T00:00:00Z", null);
        when(mockRepo.findActressesByFilmographyCode("STAR-001")).thenReturn(List.of(stale));

        List<NoMatchTriageRow> rows = service.list(null, false);
        CandidateActress ca = rows.get(0).candidates().get(0);
        assertTrue(ca.stale(), "Candidate fetched 60 days ago should be stale");
    }

    // ── tryOtherActress ───────────────────────────────────────────────────────

    @Test
    void tryOtherActress_callsClearAndReQueueWhenCandidateMatches() {
        when(mockRepo.listNoMatchRows()).thenReturn(List.of(makeRow(10L, "STAR-001", 5L, "Actress A")));
        when(mockRepo.findActressesByFilmographyCode("STAR-001"))
                .thenReturn(List.of(makeCandidate(7L, "Other Actress", "other-slug")));

        service.tryOtherActress(10L, 7L);

        verify(mockRepo).clearNoMatchAndReQueue(10L, 7L);
    }

    @Test
    void tryOtherActress_throwsWhenActressNotInCache() {
        when(mockRepo.listNoMatchRows()).thenReturn(List.of(makeRow(10L, "STAR-001", 5L, "Actress A")));
        when(mockRepo.findActressesByFilmographyCode("STAR-001")).thenReturn(List.of());

        assertThrows(IllegalArgumentException.class, () -> service.tryOtherActress(10L, 99L));
        verify(mockRepo, never()).clearNoMatchAndReQueue(anyLong(), any());
    }

    @Test
    void tryOtherActress_throwsWhenNoNoMatchRow() {
        when(mockRepo.listNoMatchRows()).thenReturn(List.of());

        assertThrows(IllegalArgumentException.class, () -> service.tryOtherActress(99L, 1L));
    }

    // ── manualSlugEntry ───────────────────────────────────────────────────────

    @Test
    void manualSlugEntry_callsForceEnrichWithDryRunFalse() {
        when(mockForceEnrich.call(any())).thenReturn(
                new ForceEnrichTitleTool.Result(true, null, null, "ok"));

        service.manualSlugEntry(10L, "AbCd12");

        verify(mockForceEnrich).call(argThat(args ->
                args.get("title_id").asLong() == 10L &&
                "AbCd12".equals(args.get("slug").asText()) &&
                !args.get("dry_run").asBoolean(true)));
    }

    @Test
    void manualSlugEntry_throwsWhenSlugNotFound() {
        when(mockForceEnrich.call(any())).thenReturn(
                new ForceEnrichTitleTool.Result(false, "slug_not_found", null, null));

        assertThrows(IllegalArgumentException.class, () -> service.manualSlugEntry(10L, "BadSlug1"));
    }

    // ── markResolved ──────────────────────────────────────────────────────────

    @Test
    void markResolved_setsCancelledStatusNotDone() {
        // Regression: markResolved must set 'cancelled', never 'done'.
        when(mockRepo.markResolved(10L)).thenReturn(true);

        service.markResolved(10L);  // should not throw

        verify(mockRepo).markResolved(10L);
        // The repo contract is tested in NoMatchTriageRepositoryTest; here we verify
        // the service routes to the correct repo method (markResolved, not anything else).
        verifyNoMoreInteractions(mockRepo);
    }

    @Test
    void markResolved_throwsWhenNoRowFound() {
        when(mockRepo.markResolved(99L)).thenReturn(false);

        assertThrows(IllegalArgumentException.class, () -> service.markResolved(99L));
    }

    // ── openFolder ────────────────────────────────────────────────────────────

    @Test
    void openFolder_returnsFolderInfoWhenPresent() {
        FolderInfo fi = new FolderInfo("/stars/ActressName/STAR-001", "vol1");
        when(mockRepo.findFolderInfo(10L)).thenReturn(Optional.of(fi));

        Optional<FolderInfo> result = service.openFolder(10L);
        assertTrue(result.isPresent());
        assertEquals("/stars/ActressName/STAR-001", result.get().path());
    }

    @Test
    void openFolder_returnsEmptyWhenNoLocation() {
        when(mockRepo.findFolderInfo(99L)).thenReturn(Optional.empty());

        Optional<FolderInfo> result = service.openFolder(99L);
        assertTrue(result.isEmpty());
    }
}
