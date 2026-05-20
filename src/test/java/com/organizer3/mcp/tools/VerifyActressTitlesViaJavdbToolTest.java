package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.organizer3.javdb.JavdbActress;
import com.organizer3.javdb.JavdbClient;
import com.organizer3.javdb.JavdbFetchException;
import com.organizer3.javdb.JavdbSearchParser;
import com.organizer3.javdb.JavdbTitleParser;
import com.organizer3.model.Actress;
import com.organizer3.model.Title;
import com.organizer3.repository.ActressRepository;
import com.organizer3.repository.TitleRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VerifyActressTitlesViaJavdbToolTest {

    private static final ObjectMapper M = new ObjectMapper();

    private final ActressRepository actressRepo = mock(ActressRepository.class);
    private final TitleRepository titleRepo = mock(TitleRepository.class);

    private Actress actress(long id, String name) {
        return Actress.builder().id(id).canonicalName(name).build();
    }

    private Title title(long id, String code) {
        return Title.builder().id(id).code(code).build();
    }

    private ObjectNode args(long actressId) {
        ObjectNode n = M.createObjectNode();
        n.put("actress_id", actressId);
        return n;
    }

    private ObjectNode args(long actressId, int maxTitles) {
        ObjectNode n = args(actressId);
        n.put("max_titles", maxTitles);
        return n;
    }

    /** Fake javdb client: code->videoSlug (via parser on search HTML) and videoSlug->cast list. */
    private static class FakeJavdbClient implements JavdbClient {
        final Map<String, String> codeToSlug = new HashMap<>();      // null value = no result
        final Map<String, List<JavdbActress>> slugToCast = new HashMap<>();
        final Map<String, RuntimeException> codeFailures = new HashMap<>();
        int searchCalls = 0;
        int detailCalls = 0;

        @Override public String searchByCode(String code) {
            searchCalls++;
            RuntimeException ex = codeFailures.get(code);
            if (ex != null) throw ex;
            String slug = codeToSlug.get(code);
            return slug == null ? "" : "/v/" + slug;   // minimal HTML our fake parser keys off
        }

        @Override public String fetchTitlePage(String slug) {
            detailCalls++;
            return "DETAIL:" + slug;
        }

        @Override public String fetchActressPage(String slug) { throw new UnsupportedOperationException(); }
    }

    private static class FakeSearchParser extends JavdbSearchParser {
        @Override public Optional<String> parseFirstSlug(String html) {
            if (html == null || html.isBlank()) return Optional.empty();
            if (!html.startsWith("/v/")) return Optional.empty();
            return Optional.of(html.substring(3));
        }
    }

    private static class FakeTitleParser extends JavdbTitleParser {
        private final Map<String, List<JavdbActress>> bySlug;
        FakeTitleParser(Map<String, List<JavdbActress>> bySlug) { this.bySlug = bySlug; }
        @Override public List<JavdbActress> parseActresses(String html) {
            if (html == null || !html.startsWith("DETAIL:")) return List.of();
            return bySlug.getOrDefault(html.substring("DETAIL:".length()), List.of());
        }
    }

    private VerifyActressTitlesViaJavdbTool newTool(FakeJavdbClient fake) {
        return new VerifyActressTitlesViaJavdbTool(
                actressRepo, titleRepo, fake,
                new FakeSearchParser(),
                new FakeTitleParser(fake.slugToCast),
                ms -> { /* no sleep in tests */ });
    }

    @Test
    void samePerson_allTitlesCreditSameSlug() {
        long aid = 6683L;
        when(actressRepo.findById(aid)).thenReturn(Optional.of(actress(aid, "Rion Ichijo")));
        when(titleRepo.findByActress(aid)).thenReturn(List.of(
                title(1, "LXVS-010"),
                title(2, "MOGK-004"),
                title(3, "ABC-001")));

        FakeJavdbClient fake = new FakeJavdbClient();
        fake.codeToSlug.put("LXVS-010", "2bWB");
        fake.codeToSlug.put("MOGK-004", "kKbVBm");
        fake.codeToSlug.put("ABC-001",  "xxYY");
        fake.slugToCast.put("2bWB",   List.of(new JavdbActress("一条リオン", "4W2a")));
        fake.slugToCast.put("kKbVBm", List.of(new JavdbActress("一条リオン", "4W2a")));
        fake.slugToCast.put("xxYY",   List.of(new JavdbActress("一条リオン", "4W2a")));

        var r = (VerifyActressTitlesViaJavdbTool.Result) newTool(fake).call(args(aid));

        assertEquals("SAME-PERSON", r.verdict());
        assertEquals("4W2a", r.primarySlug());
        assertEquals(3, r.primaryMatchCount());
        assertEquals(3, r.sampled());
        assertEquals(3, r.totalTitles());
        assertTrue(r.otherSlugs().isEmpty());
    }

    @Test
    void split_titlesCreditDifferentSlugs() {
        long aid = 9999L;
        when(actressRepo.findById(aid)).thenReturn(Optional.of(actress(aid, "Phantom Sanada")));
        when(titleRepo.findByActress(aid)).thenReturn(List.of(
                title(1, "AAA-1"), title(2, "BBB-2"), title(3, "CCC-3"), title(4, "DDD-4")));

        FakeJavdbClient fake = new FakeJavdbClient();
        fake.codeToSlug.put("AAA-1", "s1");
        fake.codeToSlug.put("BBB-2", "s2");
        fake.codeToSlug.put("CCC-3", "s3");
        fake.codeToSlug.put("DDD-4", "s4");
        fake.slugToCast.put("s1", List.of(new JavdbActress("女優A", "slugA")));
        fake.slugToCast.put("s2", List.of(new JavdbActress("女優A", "slugA")));
        fake.slugToCast.put("s3", List.of(new JavdbActress("女優B", "slugB")));
        fake.slugToCast.put("s4", List.of(new JavdbActress("女優C", "slugC")));

        var r = (VerifyActressTitlesViaJavdbTool.Result) newTool(fake).call(args(aid));

        assertEquals("SPLIT", r.verdict());
        assertEquals("slugA", r.primarySlug());
        assertEquals(2, r.primaryMatchCount());
        assertEquals(2, r.otherSlugs().size());
        // counts add up: slugA=2, slugB=1, slugC=1 -> otherSlugs sorted desc
        assertEquals("slugB", r.otherSlugs().get(0).slug());
        assertEquals(1, r.otherSlugs().get(0).count());
    }

    @Test
    void emptyCast_ignoredButRestVerdictHolds() {
        long aid = 10L;
        when(actressRepo.findById(aid)).thenReturn(Optional.of(actress(aid, "X")));
        when(titleRepo.findByActress(aid)).thenReturn(List.of(
                title(1, "A-1"), title(2, "A-2"), title(3, "A-3")));

        FakeJavdbClient fake = new FakeJavdbClient();
        fake.codeToSlug.put("A-1", "v1");
        fake.codeToSlug.put("A-2", "v2");
        fake.codeToSlug.put("A-3", null);  // no search result
        fake.slugToCast.put("v1", List.of(new JavdbActress("kanji", "primary")));
        fake.slugToCast.put("v2", List.of());

        var r = (VerifyActressTitlesViaJavdbTool.Result) newTool(fake).call(args(aid));

        assertEquals("SAME-PERSON", r.verdict());
        assertEquals("primary", r.primarySlug());
        assertEquals(1, r.primaryMatchCount());
        // 1 result has empty actors, 1 has no slug, 1 OK — only 1 contributes to verdict
    }

    @Test
    void allFailsOrEmpty_unresolvable() {
        long aid = 11L;
        when(actressRepo.findById(aid)).thenReturn(Optional.of(actress(aid, "X")));
        when(titleRepo.findByActress(aid)).thenReturn(List.of(
                title(1, "F-1"), title(2, "F-2")));

        FakeJavdbClient fake = new FakeJavdbClient();
        fake.codeFailures.put("F-1", new JavdbFetchException("boom"));
        fake.codeFailures.put("F-2", new JavdbFetchException("boom"));

        var r = (VerifyActressTitlesViaJavdbTool.Result) newTool(fake).call(args(aid));

        assertEquals("UNRESOLVABLE", r.verdict());
        assertNull(r.primarySlug());
        assertEquals(0, r.primaryMatchCount());
        assertEquals(2, r.results().size());
        assertNotNull(r.results().get(0).error());
    }

    @Test
    void evenlySpread_30titlesSampledTo10_keepsFirstAndLast() {
        List<Title> all = new ArrayList<>();
        for (int i = 1; i <= 30; i++) all.add(title(i, "T-" + i));

        List<Title> picked = VerifyActressTitlesViaJavdbTool.evenlySpread(all, 10);

        assertEquals(10, picked.size());
        assertEquals(1L,  picked.get(0).getId());            // first
        assertEquals(30L, picked.get(9).getId());            // last
        // evenly distributed: indices ~ 0, 3.22, 6.44, ... 29 -> rounded 0,3,6,10,13,16,19,23,26,29
        // Spot-check: monotonic + no duplicates
        for (int i = 1; i < picked.size(); i++) {
            assertTrue(picked.get(i).getId() > picked.get(i - 1).getId(),
                    "Expected monotonic ids, got " + picked.stream().map(Title::getId).toList());
        }
    }

    @Test
    void evenlySpread_lessThanMax_returnsAll() {
        List<Title> all = List.of(title(1, "A"), title(2, "B"));
        List<Title> picked = VerifyActressTitlesViaJavdbTool.evenlySpread(all, 10);
        assertEquals(2, picked.size());
    }

    @Test
    void unknownActress_throws() {
        when(actressRepo.findById(404L)).thenReturn(Optional.empty());
        FakeJavdbClient fake = new FakeJavdbClient();
        AtomicInteger ignore = new AtomicInteger();
        assertThrows(IllegalArgumentException.class,
                () -> newTool(fake).call(args(404L)));
        assertEquals(0, ignore.get());
    }

    @Test
    void respectsMaxTitlesArg_andSamplingReducesFetchCount() {
        long aid = 50L;
        when(actressRepo.findById(aid)).thenReturn(Optional.of(actress(aid, "X")));
        List<Title> all = new ArrayList<>();
        for (int i = 1; i <= 20; i++) all.add(title(i, "T-" + i));
        when(titleRepo.findByActress(aid)).thenReturn(all);

        FakeJavdbClient fake = new FakeJavdbClient();
        for (int i = 1; i <= 20; i++) {
            fake.codeToSlug.put("T-" + i, "v" + i);
            fake.slugToCast.put("v" + i, List.of(new JavdbActress("k", "samelug")));
        }

        var r = (VerifyActressTitlesViaJavdbTool.Result) newTool(fake).call(args(aid, 5));

        assertEquals(20, r.totalTitles());
        assertEquals(5, r.sampled());
        assertEquals(5, fake.searchCalls);
        assertEquals(5, fake.detailCalls);
        assertEquals("SAME-PERSON", r.verdict());
    }
}
