package com.organizer3.web;

import com.organizer3.avstars.repository.AvActressRepository;
import com.organizer3.avstars.repository.AvActressRepository.FederatedAvActressResult;
import com.organizer3.covers.CoverPath;
import com.organizer3.repository.ActressRepository;
import com.organizer3.repository.ActressRepository.FederatedActressResult;
import com.organizer3.repository.LabelRepository;
import com.organizer3.repository.LabelRepository.LabelSearchResult;
import com.organizer3.repository.TitleRepository;
import com.organizer3.repository.TitleRepository.FederatedTitleResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SearchServiceTest {

    @Mock ActressRepository actressRepo;
    @Mock TitleRepository titleRepo;
    @Mock LabelRepository labelRepo;
    @Mock CoverPath coverPath;
    @Mock AvActressRepository avActressRepo;

    SearchService service;

    @BeforeEach
    void setUp() {
        service = new SearchService(actressRepo, titleRepo, labelRepo, coverPath, avActressRepo);
    }

    // ── Top-level search() grouping ───────────────────────────────────────

    @Test
    void searchReturnsAllGroupsKeyed() {
        when(actressRepo.searchForFederated(anyString(), anyBoolean(), anyInt())).thenReturn(List.of());
        when(titleRepo.searchByTitleName(anyString(), anyBoolean(), anyInt())).thenReturn(List.of());
        when(labelRepo.searchLabels(anyString(), anyBoolean(), anyInt())).thenReturn(List.of());
        when(labelRepo.searchCompanies(anyString(), anyBoolean(), anyInt())).thenReturn(List.of());

        Map<String, Object> result = service.search("yua", false, false);

        assertTrue(result.containsKey("actresses"));
        assertTrue(result.containsKey("titles"));
        assertTrue(result.containsKey("labels"));
        assertTrue(result.containsKey("companies"));
        assertTrue(result.containsKey("avActresses"));
        assertEquals(List.of(), result.get("avActresses"));
    }

    @Test
    void searchSkipsAvWhenIncludeAvFalse() {
        when(actressRepo.searchForFederated(anyString(), anyBoolean(), anyInt())).thenReturn(List.of());
        when(titleRepo.searchByTitleName(anyString(), anyBoolean(), anyInt())).thenReturn(List.of());
        when(labelRepo.searchLabels(anyString(), anyBoolean(), anyInt())).thenReturn(List.of());
        when(labelRepo.searchCompanies(anyString(), anyBoolean(), anyInt())).thenReturn(List.of());

        service.search("yua", false, false);

        verifyNoInteractions(avActressRepo);
    }

    @Test
    void searchQueriesAvWhenIncludeAvTrue() {
        when(actressRepo.searchForFederated(anyString(), anyBoolean(), anyInt())).thenReturn(List.of());
        when(titleRepo.searchByTitleName(anyString(), anyBoolean(), anyInt())).thenReturn(List.of());
        when(labelRepo.searchLabels(anyString(), anyBoolean(), anyInt())).thenReturn(List.of());
        when(labelRepo.searchCompanies(anyString(), anyBoolean(), anyInt())).thenReturn(List.of());
        when(avActressRepo.searchForFederated(anyString(), anyInt())).thenReturn(List.of());

        service.search("yua", false, true);

        verify(avActressRepo).searchForFederated("yua", 5);
    }

    @Test
    void searchUsesFederatedByDefaultAndEditorWhenIncludeSparse() {
        when(actressRepo.searchForFederated(anyString(), anyBoolean(), anyInt())).thenReturn(List.of());
        when(actressRepo.searchForEditor(anyString(), anyBoolean(), anyInt())).thenReturn(List.of());
        when(titleRepo.searchByTitleName(anyString(), anyBoolean(), anyInt())).thenReturn(List.of());
        when(labelRepo.searchLabels(anyString(), anyBoolean(), anyInt())).thenReturn(List.of());
        when(labelRepo.searchCompanies(anyString(), anyBoolean(), anyInt())).thenReturn(List.of());

        service.search("reina", false, false, false);
        verify(actressRepo).searchForFederated("reina", false, 5);
        verify(actressRepo, never()).searchForEditor(anyString(), anyBoolean(), anyInt());

        service.search("reina", false, false, true);
        verify(actressRepo).searchForEditor("reina", false, 5);
    }

    @Test
    void searchPassesStartsWithFlagToRepos() {
        when(actressRepo.searchForFederated(anyString(), anyBoolean(), anyInt())).thenReturn(List.of());
        when(titleRepo.searchByTitleName(anyString(), anyBoolean(), anyInt())).thenReturn(List.of());
        when(labelRepo.searchLabels(anyString(), anyBoolean(), anyInt())).thenReturn(List.of());
        when(labelRepo.searchCompanies(anyString(), anyBoolean(), anyInt())).thenReturn(List.of());

        service.search("yua", true, false);

        verify(actressRepo).searchForFederated("yua", true, 5);
        verify(titleRepo).searchByTitleName("yua", true, 3);
        verify(labelRepo).searchLabels("yua", true, 2);
        verify(labelRepo).searchCompanies("yua", true, 2);
    }

    // ── Actress mapping ────────────────────────────────────────────────────

    @Test
    void actressResultsMapAllFields() {
        stubEmpty();
        FederatedActressResult row = new FederatedActressResult(
                42L, "Yua Mikami", "Mikami", "GODDESS", "SSS",
                true, false, null, 147, null, null, false);
        when(actressRepo.searchForFederated(anyString(), anyBoolean(), anyInt())).thenReturn(List.of(row));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> actresses = (List<Map<String, Object>>) service.search("yua", false, false).get("actresses");

        assertEquals(1, actresses.size());
        Map<String, Object> m = actresses.get(0);
        assertEquals(42L, m.get("id"));
        assertEquals("Yua Mikami", m.get("canonicalName"));
        assertEquals("Mikami", m.get("stageName"));
        assertEquals("GODDESS", m.get("tier"));
        assertEquals("SSS", m.get("grade"));
        assertEquals(true, m.get("favorite"));
        assertEquals(147, m.get("titleCount"));
        assertNull(m.get("coverUrl"));
    }

    @Test
    void actressCoverCandidatesTriedInOrderUntilCoverFound() {
        stubEmpty();
        FederatedActressResult row = new FederatedActressResult(
                1L, "A", null, "POPULAR", null, false, false, null, 10,
                "ABP:ABP-001|SSIS:SSIS-002|MIDV:MIDV-003", null, false);
        when(actressRepo.searchForFederated(anyString(), anyBoolean(), anyInt())).thenReturn(List.of(row));
        when(coverPath.find(argThat(t -> t != null && "ABP".equals(t.getLabel())))).thenReturn(Optional.empty());
        when(coverPath.find(argThat(t -> t != null && "SSIS".equals(t.getLabel()))))
                .thenReturn(Optional.of(Path.of("/covers/SSIS/SSIS-002.jpg")));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> actresses = (List<Map<String, Object>>) service.search("a", false, false).get("actresses");

        assertEquals("/covers/SSIS/SSIS-002.jpg", actresses.get(0).get("coverUrl"));
        // Third candidate not tried because second hit
        verify(coverPath, never()).find(argThat(t -> t != null && "MIDV".equals(t.getLabel())));
    }

    @Test
    void actressCoverUrlNullWhenCandidatesEmpty() {
        stubEmpty();
        FederatedActressResult row = new FederatedActressResult(
                1L, "A", null, "POPULAR", null, false, false, null, 10, null, null, false);
        when(actressRepo.searchForFederated(anyString(), anyBoolean(), anyInt())).thenReturn(List.of(row));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> actresses = (List<Map<String, Object>>) service.search("a", false, false).get("actresses");

        assertNull(actresses.get(0).get("coverUrl"));
        verifyNoInteractions(coverPath);
    }

    @Test
    void actressLocalAvatarPreferredOverCoverCandidates() {
        stubEmpty();
        FederatedActressResult row = new FederatedActressResult(
                1L, "A", null, "POPULAR", null, false, false, null, 10,
                "ABP:ABP-001", "actress-avatars/abc.jpg", false);
        when(actressRepo.searchForFederated(anyString(), anyBoolean(), anyInt())).thenReturn(List.of(row));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> actresses = (List<Map<String, Object>>) service.search("a", false, false).get("actresses");

        assertEquals("/actress-avatars/abc.jpg", actresses.get(0).get("coverUrl"));
        verifyNoInteractions(coverPath);
    }

    @Test
    void actressCoverCandidateWithoutColonIsSkipped() {
        stubEmpty();
        FederatedActressResult row = new FederatedActressResult(
                1L, "A", null, "POPULAR", null, false, false, null, 10,
                "malformed|ABP:ABP-001", null, false);
        when(actressRepo.searchForFederated(anyString(), anyBoolean(), anyInt())).thenReturn(List.of(row));
        when(coverPath.find(argThat(t -> t != null && "ABP".equals(t.getLabel()))))
                .thenReturn(Optional.of(Path.of("/covers/ABP/ABP-001.jpg")));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> actresses = (List<Map<String, Object>>) service.search("a", false, false).get("actresses");

        assertEquals("/covers/ABP/ABP-001.jpg", actresses.get(0).get("coverUrl"));
    }

    // ── Title mapping ──────────────────────────────────────────────────────

    @Test
    void titleResultsMapAllFields() {
        stubEmpty();
        FederatedTitleResult row = new FederatedTitleResult(
                7L, "ABP-123", "原題", "English Title", "ABP", "ABP-00123", "2024-01-01",
                42L, "Yua Mikami", true, false);
        when(titleRepo.searchByTitleName(anyString(), anyBoolean(), anyInt())).thenReturn(List.of(row));
        when(coverPath.find(any())).thenReturn(Optional.of(Path.of("/covers/ABP/ABP-00123.jpg")));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> titles = (List<Map<String, Object>>) service.search("english", false, false).get("titles");

        Map<String, Object> m = titles.get(0);
        assertEquals(7L, m.get("id"));
        assertEquals("ABP-123", m.get("code"));
        assertEquals("English Title", m.get("titleEnglish"));
        assertEquals("ABP", m.get("label"));
        assertEquals("2024-01-01", m.get("releaseDate"));
        assertEquals(42L, m.get("actressId"));
        assertEquals("/covers/ABP/ABP-00123.jpg", m.get("coverUrl"));
    }

    @Test
    void titleCoverUrlNullWhenLabelOrBaseCodeMissing() {
        stubEmpty();
        FederatedTitleResult row = new FederatedTitleResult(
                7L, "ABP-123", null, null, null, null, null,
                null, null, false, false);
        when(titleRepo.searchByTitleName(anyString(), anyBoolean(), anyInt())).thenReturn(List.of(row));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> titles = (List<Map<String, Object>>) service.search("q", false, false).get("titles");

        assertNull(titles.get(0).get("coverUrl"));
        verifyNoInteractions(coverPath);
    }

    // ── Label / company mapping ────────────────────────────────────────────

    @Test
    void labelResultsMapAllFields() {
        stubEmpty();
        LabelSearchResult row = new LabelSearchResult("ABP", "Prestige Label", "Prestige");
        when(labelRepo.searchLabels(anyString(), anyBoolean(), anyInt())).thenReturn(List.of(row));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> labels = (List<Map<String, Object>>) service.search("abp", false, false).get("labels");

        assertEquals(1, labels.size());
        assertEquals("ABP", labels.get(0).get("code"));
        assertEquals("Prestige Label", labels.get(0).get("labelName"));
        assertEquals("Prestige", labels.get(0).get("company"));
    }

    @Test
    void companyResultsPassedThroughAsStrings() {
        stubEmptyExceptCompanies();
        when(labelRepo.searchCompanies(anyString(), anyBoolean(), anyInt())).thenReturn(List.of("Prestige", "Moodyz"));

        Map<String, Object> result = service.search("pr", false, false);

        assertEquals(List.of("Prestige", "Moodyz"), result.get("companies"));
    }

    // ── AV actress mapping ─────────────────────────────────────────────────

    @Test
    void avActressResultsMapHeadshotPathToUrl() {
        stubEmpty();
        FederatedAvActressResult row = new FederatedAvActressResult(
                9L, "Asa Akira", 52, "/data/headshots/asa.jpg");
        when(avActressRepo.searchForFederated(anyString(), anyInt())).thenReturn(List.of(row));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> av = (List<Map<String, Object>>) service.search("asa", false, true).get("avActresses");

        Map<String, Object> m = av.get(0);
        assertEquals(9L, m.get("id"));
        assertEquals("Asa Akira", m.get("stageName"));
        assertEquals(52, m.get("videoCount"));
        assertEquals("/api/av/headshots/asa.jpg", m.get("headshotUrl"));
    }

    @Test
    void avActressHeadshotUrlNullWhenPathNull() {
        stubEmpty();
        FederatedAvActressResult row = new FederatedAvActressResult(9L, "A", 0, null);
        when(avActressRepo.searchForFederated(anyString(), anyInt())).thenReturn(List.of(row));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> av = (List<Map<String, Object>>) service.search("a", false, true).get("avActresses");

        assertNull(av.get(0).get("headshotUrl"));
    }

    // ── Code-prefix integration into search() ─────────────────────────────

    @Test
    void search_includesCodePrefixHitsWhenQueryLooksLikeCode() {
        stubEmpty();
        FederatedTitleResult codeRow = new FederatedTitleResult(
                100L, "STAR-129", "原題", "Unrelated Name", "STAR", "STAR-00129", "2024-01-01",
                null, null, false, false);
        when(titleRepo.searchByCodePrefix("STAR", 3)).thenReturn(List.of(codeRow));
        when(coverPath.find(any())).thenReturn(Optional.empty());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> titles = (List<Map<String, Object>>) service.search("STAR", false, false).get("titles");

        assertEquals(1, titles.size());
        assertEquals("STAR-129", titles.get(0).get("code"));
        verify(titleRepo).searchByCodePrefix("STAR", 3);
    }

    @Test
    void search_codePrefix_handlesPartialAndUpperCases() {
        stubEmpty();
        FederatedTitleResult codeRow = new FederatedTitleResult(
                101L, "STAR-129", null, null, "STAR", "STAR-00129", null,
                null, null, false, false);
        when(titleRepo.searchByCodePrefix("STAR-1", 3)).thenReturn(List.of(codeRow));
        when(coverPath.find(any())).thenReturn(Optional.empty());

        service.search("star-1", false, false);

        // Service should uppercase the query before passing to searchByCodePrefix.
        verify(titleRepo).searchByCodePrefix("STAR-1", 3);
    }

    @Test
    void search_dedupesWhenTitleMatchesByBothNameAndCode() {
        when(actressRepo.searchForFederated(anyString(), anyBoolean(), anyInt())).thenReturn(List.of());
        when(labelRepo.searchLabels(anyString(), anyBoolean(), anyInt())).thenReturn(List.of());
        when(labelRepo.searchCompanies(anyString(), anyBoolean(), anyInt())).thenReturn(List.of());
        FederatedTitleResult shared = new FederatedTitleResult(
                200L, "SNIS-100", null, "SNIS Special", "SNIS", "SNIS-00100", null,
                null, null, false, false);
        when(titleRepo.searchByTitleName(eq("SNIS"), anyBoolean(), anyInt())).thenReturn(List.of(shared));
        when(titleRepo.searchByCodePrefix("SNIS", 3)).thenReturn(List.of(shared));
        when(coverPath.find(any())).thenReturn(Optional.empty());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> titles = (List<Map<String, Object>>) service.search("SNIS", false, false).get("titles");

        assertEquals(1, titles.size(), "shared row should appear only once after dedupe");
        assertEquals("SNIS-100", titles.get(0).get("code"));
    }

    @Test
    void search_codePrefixHitsListedBeforeNameOnlyHits() {
        when(actressRepo.searchForFederated(anyString(), anyBoolean(), anyInt())).thenReturn(List.of());
        when(labelRepo.searchLabels(anyString(), anyBoolean(), anyInt())).thenReturn(List.of());
        when(labelRepo.searchCompanies(anyString(), anyBoolean(), anyInt())).thenReturn(List.of());
        FederatedTitleResult codeOnly = new FederatedTitleResult(
                300L, "STAR-129", null, "Foo", "STAR", "STAR-00129", null,
                null, null, false, false);
        FederatedTitleResult nameOnly = new FederatedTitleResult(
                301L, "OTHER-1", null, "STAR mention only", "OTHER", "OTHER-00001", null,
                null, null, false, false);
        when(titleRepo.searchByTitleName(eq("STAR"), anyBoolean(), anyInt())).thenReturn(List.of(nameOnly));
        when(titleRepo.searchByCodePrefix("STAR", 3)).thenReturn(List.of(codeOnly));
        when(coverPath.find(any())).thenReturn(Optional.empty());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> titles = (List<Map<String, Object>>) service.search("STAR", false, false).get("titles");

        assertEquals(2, titles.size());
        assertEquals(300L, titles.get(0).get("id"), "code-prefix hits should come first");
        assertEquals(301L, titles.get(1).get("id"));
    }

    @Test
    void search_doesNotInvokeCodeSearchForNonCodeQuery() {
        stubEmpty();

        service.search("Yua Aida", false, false);

        verify(titleRepo, never()).searchByCodePrefix(anyString(), anyInt());
    }

    @Test
    void search_doesNotInvokeCodeSearchForSingleLetter() {
        stubEmpty();

        service.search("S", false, false);

        verify(titleRepo, never()).searchByCodePrefix(anyString(), anyInt());
    }

    @Test
    void search_doesNotInvokeCodeSearchForKanji() {
        stubEmpty();

        service.search("原題", false, false); // 原題

        verify(titleRepo, never()).searchByCodePrefix(anyString(), anyInt());
    }

    @Test
    void looksLikeCode_acceptsAndRejectsExpectedShapes() {
        // Accepts
        assertTrue(SearchService.looksLikeCode("STAR"));
        assertTrue(SearchService.looksLikeCode("STAR-"));
        assertTrue(SearchService.looksLikeCode("STAR-1"));
        assertTrue(SearchService.looksLikeCode("STAR-129"));
        assertTrue(SearchService.looksLikeCode("SNIS001"));
        assertTrue(SearchService.looksLikeCode("FC2PPV"));
        assertTrue(SearchService.looksLikeCode("FC2PPV-"));
        assertTrue(SearchService.looksLikeCode("FC2PPV-12345"));
        assertTrue(SearchService.looksLikeCode("star-129"), "lowercase should be accepted (uppercased first)");
        assertTrue(SearchService.looksLikeCode("  STAR-129  "), "leading/trailing whitespace trimmed");

        // Rejects
        assertFalse(SearchService.looksLikeCode("S"), "single letter");
        assertFalse(SearchService.looksLikeCode("原題"), "kanji");
        assertFalse(SearchService.looksLikeCode("Yua Aida"), "contains whitespace");
        assertFalse(SearchService.looksLikeCode("STAR 129"), "internal whitespace");
        assertFalse(SearchService.looksLikeCode("123STAR"), "starts with digit");
        assertFalse(SearchService.looksLikeCode("-STAR"), "starts with hyphen");
        assertFalse(SearchService.looksLikeCode(""), "empty");
        assertFalse(SearchService.looksLikeCode("   "), "whitespace only");
        assertFalse(SearchService.looksLikeCode(null), "null");
    }

    // ── searchByCodePrefix ─────────────────────────────────────────────────

    @Test
    void searchByCodePrefixDelegatesAndMaps() {
        FederatedTitleResult row = new FederatedTitleResult(
                1L, "ABP-001", null, null, "ABP", "ABP-00001", null,
                null, null, false, false);
        when(titleRepo.searchByCodePrefix("ABP", 11)).thenReturn(List.of(row));
        when(coverPath.find(any())).thenReturn(Optional.empty());

        List<Map<String, Object>> out = service.searchByCodePrefix("ABP", 11);

        assertEquals(1, out.size());
        assertEquals("ABP-001", out.get(0).get("code"));
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private void stubEmpty() {
        when(actressRepo.searchForFederated(anyString(), anyBoolean(), anyInt())).thenReturn(List.of());
        when(titleRepo.searchByTitleName(anyString(), anyBoolean(), anyInt())).thenReturn(List.of());
        when(labelRepo.searchLabels(anyString(), anyBoolean(), anyInt())).thenReturn(List.of());
        when(labelRepo.searchCompanies(anyString(), anyBoolean(), anyInt())).thenReturn(List.of());
    }

    private void stubEmptyExceptCompanies() {
        when(actressRepo.searchForFederated(anyString(), anyBoolean(), anyInt())).thenReturn(List.of());
        when(titleRepo.searchByTitleName(anyString(), anyBoolean(), anyInt())).thenReturn(List.of());
        when(labelRepo.searchLabels(anyString(), anyBoolean(), anyInt())).thenReturn(List.of());
    }
}
