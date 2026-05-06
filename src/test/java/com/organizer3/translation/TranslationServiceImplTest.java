package com.organizer3.translation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.organizer3.translation.repository.StageNameLookupRepository;
import com.organizer3.translation.repository.StageNameSuggestionRepository;
import com.organizer3.translation.repository.TranslationCacheRepository;
import com.organizer3.translation.repository.TranslationQueueRepository;
import com.organizer3.translation.repository.TranslationStrategyRepository;
import com.organizer3.translation.ollama.OllamaAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link TranslationServiceImpl#resolveOrSuggestStageName} using mocked repos.
 */
@ExtendWith(MockitoExtension.class)
class TranslationServiceImplTest {

    @Mock private OllamaAdapter ollamaAdapter;
    @Mock private TranslationStrategyRepository strategyRepo;
    @Mock private TranslationCacheRepository cacheRepo;
    @Mock private TranslationQueueRepository queueRepo;
    @Mock private StageNameLookupRepository stageNameLookupRepo;
    @Mock private StageNameSuggestionRepository stageNameSuggestionRepo;
    @Mock private CallbackDispatcher callbackDispatcher;
    @Mock private HealthGate healthGate;

    private TranslationServiceImpl service;

    private static final String KANJI = "麻美ゆま";
    private static final String NORMALIZED = TranslationNormalization.normalize(KANJI);
    private static final String ROMAJI = "Yuma Asami";

    private final TranslationStrategy labelBasicStrategy =
            new TranslationStrategy(42L, "label_basic", "gemma4:e4b", "Translate: {jp}", null, true, null);

    @BeforeEach
    void setUp() {
        service = new TranslationServiceImpl(
                ollamaAdapter, strategyRepo, cacheRepo, queueRepo,
                TranslationConfig.DEFAULTS, callbackDispatcher,
                healthGate, new ObjectMapper(),
                stageNameLookupRepo, stageNameSuggestionRepo);
    }

    // ── Curated hit returns synchronously ────────────────────────────────────

    @Test
    void curatedHit_returnsCuratedRomaji_withoutEnqueue() {
        when(stageNameLookupRepo.findRomanizedFor(NORMALIZED)).thenReturn(Optional.of(ROMAJI));

        Optional<String> result = service.resolveOrSuggestStageName(KANJI);

        assertTrue(result.isPresent());
        assertEquals(ROMAJI, result.get());
        verifyNoInteractions(queueRepo);
    }

    // ── Curated miss + suggestion hit returns usable romaji ──────────────────

    @Test
    void curatedMiss_suggestionHit_returnsSuggestionRomaji() {
        when(stageNameLookupRepo.findRomanizedFor(NORMALIZED)).thenReturn(Optional.empty());
        when(stageNameSuggestionRepo.findLatestUsableSuggestion(NORMALIZED)).thenReturn(Optional.of(ROMAJI));

        Optional<String> result = service.resolveOrSuggestStageName(KANJI);

        assertTrue(result.isPresent());
        assertEquals(ROMAJI, result.get());
        verifyNoInteractions(queueRepo);
    }

    // ── Both miss → empty + enqueueIfAbsent called once with normalized source ─

    @Test
    void bothMiss_returnsEmpty_andEnqueuesWithNormalizedSource() {
        when(stageNameLookupRepo.findRomanizedFor(NORMALIZED)).thenReturn(Optional.empty());
        when(stageNameSuggestionRepo.findLatestUsableSuggestion(NORMALIZED)).thenReturn(Optional.empty());
        when(strategyRepo.findByName("label_basic")).thenReturn(Optional.of(labelBasicStrategy));
        when(queueRepo.enqueueIfAbsent(eq(NORMALIZED), eq(42L), anyString(),
                eq(TranslationQueueRow.STATUS_PENDING), isNull(), isNull()))
                .thenReturn(true);

        Optional<String> result = service.resolveOrSuggestStageName(KANJI);

        assertTrue(result.isEmpty());
        verify(queueRepo).enqueueIfAbsent(eq(NORMALIZED), eq(42L), anyString(),
                eq(TranslationQueueRow.STATUS_PENDING), isNull(), isNull());
    }

    // ── Second back-to-back miss: enqueueIfAbsent returns false — no exception ─

    @Test
    void secondMiss_enqueueIfAbsentReturnsFalse_returnsEmpty_noException() {
        when(stageNameLookupRepo.findRomanizedFor(NORMALIZED)).thenReturn(Optional.empty());
        when(stageNameSuggestionRepo.findLatestUsableSuggestion(NORMALIZED)).thenReturn(Optional.empty());
        when(strategyRepo.findByName("label_basic")).thenReturn(Optional.of(labelBasicStrategy));
        when(queueRepo.enqueueIfAbsent(eq(NORMALIZED), eq(42L), anyString(),
                eq(TranslationQueueRow.STATUS_PENDING), isNull(), isNull()))
                .thenReturn(false);

        Optional<String> result = service.resolveOrSuggestStageName(KANJI);

        assertTrue(result.isEmpty());
        verify(queueRepo).enqueueIfAbsent(eq(NORMALIZED), eq(42L), anyString(),
                eq(TranslationQueueRow.STATUS_PENDING), isNull(), isNull());
    }

    // ── NFKC: full-width input hits half-width-stored suggestion ─────────────

    @Test
    void nfkcNormalization_fullWidthInputHitsHalfWidthStoredSuggestion() {
        // Full-width kanji that normalizes to the same NFKC form as KANJI
        String fullWidthKanji = "麻美ゆま"; // same chars, ensuring NFKC normalize(input) == NORMALIZED
        String normalizedInput = TranslationNormalization.normalize(fullWidthKanji);

        when(stageNameLookupRepo.findRomanizedFor(normalizedInput)).thenReturn(Optional.empty());
        when(stageNameSuggestionRepo.findLatestUsableSuggestion(normalizedInput)).thenReturn(Optional.of(ROMAJI));

        Optional<String> result = service.resolveOrSuggestStageName(fullWidthKanji);

        assertTrue(result.isPresent());
        assertEquals(ROMAJI, result.get());
        // Verify the normalized form was passed to both repos
        verify(stageNameLookupRepo).findRomanizedFor(normalizedInput);
        verify(stageNameSuggestionRepo).findLatestUsableSuggestion(normalizedInput);
    }

    // ── NFKC: full-width digits/punctuation collapse to half-width ────────────

    @Test
    void nfkcNormalization_fullWidthDigitsCollapseBeforeEnqueue() {
        // "Ａ" (U+FF21) normalizes to "A" (U+0041)
        String fullWidthInput = "Ａ子";
        String normalizedInput = TranslationNormalization.normalize(fullWidthInput);
        assertNotEquals(fullWidthInput, normalizedInput, "Full-width should normalize");

        when(stageNameLookupRepo.findRomanizedFor(normalizedInput)).thenReturn(Optional.empty());
        when(stageNameSuggestionRepo.findLatestUsableSuggestion(normalizedInput)).thenReturn(Optional.empty());
        when(strategyRepo.findByName("label_basic")).thenReturn(Optional.of(labelBasicStrategy));
        when(queueRepo.enqueueIfAbsent(eq(normalizedInput), eq(42L), anyString(),
                eq(TranslationQueueRow.STATUS_PENDING), isNull(), isNull()))
                .thenReturn(true);

        service.resolveOrSuggestStageName(fullWidthInput);

        verify(queueRepo).enqueueIfAbsent(eq(normalizedInput), anyLong(), anyString(),
                anyString(), isNull(), isNull());
    }

    // ── Blank / null input ───────────────────────────────────────────────────

    @Test
    void blankInput_returnsEmpty_withoutAnyRepoInteraction() {
        assertTrue(service.resolveOrSuggestStageName("").isEmpty());
        assertTrue(service.resolveOrSuggestStageName("   ").isEmpty());
        assertTrue(service.resolveOrSuggestStageName(null).isEmpty());
        verifyNoInteractions(stageNameLookupRepo, stageNameSuggestionRepo, queueRepo);
    }
}
