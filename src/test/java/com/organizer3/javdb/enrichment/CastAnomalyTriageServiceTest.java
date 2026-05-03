package com.organizer3.javdb.enrichment;

import com.organizer3.model.Actress;
import com.organizer3.model.ActressAlias;
import com.organizer3.repository.ActressRepository;
import com.organizer3.repository.TitleActressRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link CastAnomalyTriageService}.
 * All external dependencies are mocked; logic under test is the validation + delegation path.
 */
class CastAnomalyTriageServiceTest {

    private EnrichmentReviewQueueRepository reviewQueueRepo;
    private ActressRepository actressRepo;
    private TitleActressRepository titleActressRepo;
    private EnrichmentRunner enrichmentRunner;
    private CastAnomalyTriageService service;

    private static final long QUEUE_ROW_ID = 42L;
    private static final long TITLE_ID     = 10L;
    private static final long ACTRESS_ID   = 88L;
    private static final String ALIAS_NAME = "黒木麻衣";

    @BeforeEach
    void setUp() {
        reviewQueueRepo  = mock(EnrichmentReviewQueueRepository.class);
        actressRepo      = mock(ActressRepository.class);
        titleActressRepo = mock(TitleActressRepository.class);
        enrichmentRunner = mock(EnrichmentRunner.class);
        service = new CastAnomalyTriageService(
                reviewQueueRepo, actressRepo, titleActressRepo, enrichmentRunner);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private static EnrichmentReviewQueueRepository.OpenRow makeCastAnomalyRow() {
        return new EnrichmentReviewQueueRepository.OpenRow(
                QUEUE_ROW_ID, TITLE_ID, "MAI-001", "slug1",
                "cast_anomaly", "actress_filmography", "2026-05-01T00:00:00Z", null);
    }

    private static Actress makeActress(long id, String name) {
        return Actress.builder().id(id).canonicalName(name).tier(Actress.Tier.LIBRARY).build();
    }

    // ── happy path ────────────────────────────────────────────────────────────

    @Test
    void happyPath_insertsAliasAndFiresRecoverySweep() {
        when(reviewQueueRepo.findOpenById(QUEUE_ROW_ID)).thenReturn(Optional.of(makeCastAnomalyRow()));
        when(titleActressRepo.findActressIdsByTitle(TITLE_ID)).thenReturn(List.of(ACTRESS_ID));
        when(actressRepo.findById(ACTRESS_ID)).thenReturn(Optional.of(makeActress(ACTRESS_ID, "Mai Hanano")));
        when(enrichmentRunner.recoverCastAnomaliesAfterMatcherFix()).thenReturn(3);

        CastAnomalyTriageService.AddAliasResult result =
                service.addAlias(QUEUE_ROW_ID, ACTRESS_ID, ALIAS_NAME);

        assertTrue(result.aliasInserted());
        assertEquals(3, result.rowsRecovered());

        verify(actressRepo).saveAlias(new ActressAlias(ACTRESS_ID, ALIAS_NAME));
        verify(enrichmentRunner).recoverCastAnomaliesAfterMatcherFix();
    }

    @Test
    void tripsWhitespace_stripsBeforeInsert() {
        when(reviewQueueRepo.findOpenById(QUEUE_ROW_ID)).thenReturn(Optional.of(makeCastAnomalyRow()));
        when(titleActressRepo.findActressIdsByTitle(TITLE_ID)).thenReturn(List.of(ACTRESS_ID));
        when(actressRepo.findById(ACTRESS_ID)).thenReturn(Optional.of(makeActress(ACTRESS_ID, "Mai Hanano")));
        when(enrichmentRunner.recoverCastAnomaliesAfterMatcherFix()).thenReturn(1);

        service.addAlias(QUEUE_ROW_ID, ACTRESS_ID, "  " + ALIAS_NAME + "  ");

        verify(actressRepo).saveAlias(new ActressAlias(ACTRESS_ID, ALIAS_NAME));
    }

    @Test
    void recoveryReturnsZero_stillReturnsAliasInsertedTrue() {
        when(reviewQueueRepo.findOpenById(QUEUE_ROW_ID)).thenReturn(Optional.of(makeCastAnomalyRow()));
        when(titleActressRepo.findActressIdsByTitle(TITLE_ID)).thenReturn(List.of(ACTRESS_ID));
        when(actressRepo.findById(ACTRESS_ID)).thenReturn(Optional.of(makeActress(ACTRESS_ID, "Mai Hanano")));
        when(enrichmentRunner.recoverCastAnomaliesAfterMatcherFix()).thenReturn(0);

        CastAnomalyTriageService.AddAliasResult result =
                service.addAlias(QUEUE_ROW_ID, ACTRESS_ID, ALIAS_NAME);

        assertTrue(result.aliasInserted());
        assertEquals(0, result.rowsRecovered());
    }

    // ── 404 — queue row not found or already resolved ─────────────────────────

    @Test
    void queueRowNotFound_throwsNotFoundException() {
        when(reviewQueueRepo.findOpenById(QUEUE_ROW_ID)).thenReturn(Optional.empty());

        assertThrows(CastAnomalyTriageService.NotFoundException.class,
                () -> service.addAlias(QUEUE_ROW_ID, ACTRESS_ID, ALIAS_NAME));

        verifyNoInteractions(actressRepo, titleActressRepo, enrichmentRunner);
    }

    // ── 400 — validation failures ─────────────────────────────────────────────

    @Test
    void emptyAliasName_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> service.addAlias(QUEUE_ROW_ID, ACTRESS_ID, ""));
        assertThrows(IllegalArgumentException.class,
                () -> service.addAlias(QUEUE_ROW_ID, ACTRESS_ID, "   "));
        assertThrows(IllegalArgumentException.class,
                () -> service.addAlias(QUEUE_ROW_ID, ACTRESS_ID, null));

        verifyNoInteractions(reviewQueueRepo, actressRepo, titleActressRepo, enrichmentRunner);
    }

    @Test
    void wrongReason_throws() {
        EnrichmentReviewQueueRepository.OpenRow wrongRow =
                new EnrichmentReviewQueueRepository.OpenRow(
                        QUEUE_ROW_ID, TITLE_ID, "MAI-001", "slug1",
                        "ambiguous", "actress_filmography", "2026-05-01T00:00:00Z", null);
        when(reviewQueueRepo.findOpenById(QUEUE_ROW_ID)).thenReturn(Optional.of(wrongRow));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.addAlias(QUEUE_ROW_ID, ACTRESS_ID, ALIAS_NAME));

        assertTrue(ex.getMessage().contains("cast_anomaly"), "error should mention expected reason");
        verifyNoInteractions(actressRepo, titleActressRepo, enrichmentRunner);
    }

    @Test
    void actressNotLinkedToTitle_throws() {
        when(reviewQueueRepo.findOpenById(QUEUE_ROW_ID)).thenReturn(Optional.of(makeCastAnomalyRow()));
        when(titleActressRepo.findActressIdsByTitle(TITLE_ID)).thenReturn(List.of(99L)); // different actress

        assertThrows(IllegalArgumentException.class,
                () -> service.addAlias(QUEUE_ROW_ID, ACTRESS_ID, ALIAS_NAME));

        verifyNoInteractions(actressRepo);
        verify(enrichmentRunner, never()).recoverCastAnomaliesAfterMatcherFix();
    }

    @Test
    void actressNotFound_throws() {
        when(reviewQueueRepo.findOpenById(QUEUE_ROW_ID)).thenReturn(Optional.of(makeCastAnomalyRow()));
        when(titleActressRepo.findActressIdsByTitle(TITLE_ID)).thenReturn(List.of(ACTRESS_ID));
        when(actressRepo.findById(ACTRESS_ID)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class,
                () -> service.addAlias(QUEUE_ROW_ID, ACTRESS_ID, ALIAS_NAME));

        verify(actressRepo, never()).saveAlias(any());
        verify(enrichmentRunner, never()).recoverCastAnomaliesAfterMatcherFix();
    }
}
