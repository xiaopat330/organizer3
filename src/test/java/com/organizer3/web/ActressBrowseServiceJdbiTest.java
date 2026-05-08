package com.organizer3.web;

import com.organizer3.ai.ActressNameLookup;
import com.organizer3.covers.CoverPath;
import com.organizer3.db.SchemaInitializer;
import com.organizer3.rating.JdbiRatingCurveRepository;
import com.organizer3.rating.RatingScoreCalculator;
import com.organizer3.repository.jdbi.JdbiActressRepository;
import com.organizer3.repository.jdbi.JdbiLabelRepository;
import com.organizer3.repository.jdbi.JdbiTitleLocationRepository;
import com.organizer3.repository.jdbi.JdbiTitleRepository;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Integration tests for ActressBrowseService methods that require real Jdbi (i.e., inline SQL).
 */
class ActressBrowseServiceJdbiTest {

    private Connection connection;
    private Jdbi jdbi;
    private ActressBrowseService service;
    private JdbiActressRepository actressRepo;
    private JdbiTitleRepository titleRepo;
    private JdbiLabelRepository labelRepo;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        jdbi.useHandle(h -> h.execute("INSERT INTO volumes (id, structure_type) VALUES ('vol-a', 'conventional')"));

        JdbiTitleLocationRepository locationRepo = new JdbiTitleLocationRepository(jdbi);
        titleRepo   = new JdbiTitleRepository(jdbi, locationRepo);
        actressRepo = new JdbiActressRepository(jdbi);
        labelRepo   = new JdbiLabelRepository(jdbi);

        service = buildServiceWithLookup(mock(ActressNameLookup.class));
    }

    private ActressBrowseService buildServiceWithLookup(ActressNameLookup lookup) {
        return new ActressBrowseService(actressRepo, titleRepo, mock(CoverPath.class),
                Map.of(), labelRepo, lookup, null, jdbi,
                new JdbiRatingCurveRepository(jdbi), new RatingScoreCalculator());
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    // ── findEnrichmentTagsForActress ──────────────────────────────────────

    @Test
    void findEnrichmentTagsForActressReturnsActressScopedTagsOnly() {
        long ayaId = jdbi.withHandle(h ->
                h.createUpdate("INSERT INTO actresses (canonical_name, tier, first_seen_at) VALUES ('Aya', 'LIBRARY', '2024-01-01')")
                        .executeAndReturnGeneratedKeys("id").mapTo(Long.class).one());
        long hibikiId = jdbi.withHandle(h ->
                h.createUpdate("INSERT INTO actresses (canonical_name, tier, first_seen_at) VALUES ('Hibiki', 'LIBRARY', '2024-01-01')")
                        .executeAndReturnGeneratedKeys("id").mapTo(Long.class).one());

        long t1 = jdbi.withHandle(h -> h.createUpdate(
                "INSERT INTO titles (code, base_code, label, seq_num, actress_id) VALUES ('ABP-001','ABP-00001','ABP',1,:a)")
                .bind("a", ayaId).executeAndReturnGeneratedKeys("id").mapTo(Long.class).one());
        long t2 = jdbi.withHandle(h -> h.createUpdate(
                "INSERT INTO titles (code, base_code, label, seq_num, actress_id) VALUES ('SSIS-001','SSIS-00001','SSIS',1,:a)")
                .bind("a", hibikiId).executeAndReturnGeneratedKeys("id").mapTo(Long.class).one());

        jdbi.useHandle(h -> {
            h.execute("INSERT INTO enrichment_tag_definitions (id, name, title_count, surface) VALUES (1, 'big-tits', 2, 1)");
            h.execute("INSERT INTO enrichment_tag_definitions (id, name, title_count, surface) VALUES (2, 'cosplay', 1, 1)");
            h.execute("INSERT INTO title_actresses (title_id, actress_id) VALUES (" + t1 + ", " + ayaId + ")");
            h.execute("INSERT INTO title_actresses (title_id, actress_id) VALUES (" + t2 + ", " + hibikiId + ")");
            // Aya's title has tag 1; Hibiki's title has tags 1 and 2
            h.execute("INSERT INTO title_enrichment_tags (title_id, tag_id) VALUES (" + t1 + ", 1)");
            h.execute("INSERT INTO title_enrichment_tags (title_id, tag_id) VALUES (" + t2 + ", 1)");
            h.execute("INSERT INTO title_enrichment_tags (title_id, tag_id) VALUES (" + t2 + ", 2)");
        });

        List<ActressBrowseService.ActressEnrichmentTag> ayaTags = service.findEnrichmentTagsForActress(ayaId);
        assertEquals(1, ayaTags.size(), "Aya should have only tag 1");
        assertEquals(1L, ayaTags.get(0).id());
        assertEquals("big-tits", ayaTags.get(0).name());
        assertEquals(1, ayaTags.get(0).titleCount(), "count should be scoped to Aya's titles");

        List<ActressBrowseService.ActressEnrichmentTag> hibikiTags = service.findEnrichmentTagsForActress(hibikiId);
        assertEquals(2, hibikiTags.size(), "Hibiki should have tags 1 and 2");
        assertEquals(1, hibikiTags.get(0).titleCount());
        assertEquals(1, hibikiTags.get(1).titleCount());
    }

    @Test
    void findEnrichmentTagsForActressReturnsEmptyWhenNone() {
        long actressId = jdbi.withHandle(h ->
                h.createUpdate("INSERT INTO actresses (canonical_name, tier, first_seen_at) VALUES ('Aya', 'LIBRARY', '2024-01-01')")
                        .executeAndReturnGeneratedKeys("id").mapTo(Long.class).one());

        List<ActressBrowseService.ActressEnrichmentTag> result = service.findEnrichmentTagsForActress(actressId);
        assertTrue(result.isEmpty());
    }

    // ── searchStageName cast_json corroboration guard ─────────────────────
    // Regression for the "Himari Hanazawa / Rima Arai" incident: a single mis-linked
    // multi-cast collection caused Claude to return the wrong kanji, which got written
    // as stage_name and then claimed Rima Arai's slug in javdb_actress_staging.

    @Test
    void searchStageNameRejectsCandidateThatAppearsInOnlyOneEnrichedCast() {
        long actressId = insertActress("Himari Hanazawa");
        // 3 enriched titles: 木下ひまり in 2, 新井リマ in 1 (the mis-linked collection)
        insertEnrichedTitle("MIRD-219", actressId,
                "[{\"slug\":\"MW44\",\"name\":\"木下ひまり\",\"gender\":\"F\"}]");
        insertEnrichedTitle("PFES-081", actressId,
                "[{\"slug\":\"MW44\",\"name\":\"木下ひまり\",\"gender\":\"F\"}]");
        insertEnrichedTitle("NPJB-076", actressId,
                "[{\"slug\":\"E2vOx\",\"name\":\"新井リマ\",\"gender\":\"F\"}]");

        ActressNameLookup lookup = mock(ActressNameLookup.class);
        when(lookup.findJapaneseName(any(), anyList())).thenReturn(Optional.of("新井リマ"));
        service = buildServiceWithLookup(lookup);

        ActressBrowseService.StageNameSearchResult result = service.searchStageName(actressId);
        assertEquals(ActressBrowseService.StageNameSearchResult.REASON_LOW_CORROBORATION, result.reason(),
                "candidate matching only 1 of 3 enriched casts must be rejected");
        assertEquals(1, result.matchCount());
        assertEquals(3, result.enrichedTitleCount());

        String written = jdbi.withHandle(h -> h.createQuery("SELECT stage_name FROM actresses WHERE id = :id")
                .bind("id", actressId).mapTo(String.class).one());
        assertNull(written, "stage_name must not be written when guard rejects");
    }

    @Test
    void searchStageNameAcceptsCandidateCorroboratedByMultipleEnrichedCasts() {
        long actressId = insertActress("Himari Kinoshita");
        insertEnrichedTitle("MIRD-219", actressId,
                "[{\"slug\":\"MW44\",\"name\":\"木下ひまり\",\"gender\":\"F\"}]");
        insertEnrichedTitle("PFES-081", actressId,
                "[{\"slug\":\"MW44\",\"name\":\"木下ひまり\",\"gender\":\"F\"}]");

        ActressNameLookup lookup = mock(ActressNameLookup.class);
        when(lookup.findJapaneseName(any(), anyList())).thenReturn(Optional.of("木下ひまり"));
        service = buildServiceWithLookup(lookup);

        ActressBrowseService.StageNameSearchResult result = service.searchStageName(actressId);
        assertEquals(ActressBrowseService.StageNameSearchResult.REASON_OK, result.reason());
        assertEquals("木下ひまり", result.stageName());

        String written = jdbi.withHandle(h -> h.createQuery("SELECT stage_name FROM actresses WHERE id = :id")
                .bind("id", actressId).mapTo(String.class).one());
        assertEquals("木下ひまり", written);
    }

    @Test
    void searchStageNameAcceptsCandidateWhenActressHasNoEnrichedTitles() {
        // No cast_json signal to validate against — fall through and trust Claude.
        long actressId = insertActress("Newbie");

        ActressNameLookup lookup = mock(ActressNameLookup.class);
        when(lookup.findJapaneseName(any(), anyList())).thenReturn(Optional.of("新人"));
        service = buildServiceWithLookup(lookup);

        ActressBrowseService.StageNameSearchResult result = service.searchStageName(actressId);
        assertEquals(ActressBrowseService.StageNameSearchResult.REASON_OK, result.reason());
        assertEquals("新人", result.stageName());

        String written = jdbi.withHandle(h -> h.createQuery("SELECT stage_name FROM actresses WHERE id = :id")
                .bind("id", actressId).mapTo(String.class).one());
        assertEquals("新人", written);
    }

    @Test
    void searchStageNameReturnsEmptyWhenLookupReturnsEmpty() {
        long actressId = insertActress("Unknown");
        insertEnrichedTitle("XYZ-001", actressId,
                "[{\"slug\":\"abcd\",\"name\":\"foo\",\"gender\":\"F\"}]");

        ActressNameLookup lookup = mock(ActressNameLookup.class);
        when(lookup.findJapaneseName(any(), anyList())).thenReturn(Optional.empty());
        service = buildServiceWithLookup(lookup);

        ActressBrowseService.StageNameSearchResult result = service.searchStageName(actressId);
        assertEquals(ActressBrowseService.StageNameSearchResult.REASON_LOOKUP_UNKNOWN, result.reason());
        assertNull(result.stageName());

        String written = jdbi.withHandle(h -> h.createQuery("SELECT stage_name FROM actresses WHERE id = :id")
                .bind("id", actressId).mapTo(String.class).one());
        assertNull(written);
    }

    // ── findStageNameCandidates ───────────────────────────────────────────────

    @Test
    void findStageNameCandidatesReturnsEmptyWhenNoEnrichedTitles() {
        long actressId = insertActress("Shion Yumi");
        // No enriched titles at all
        Optional<List<ActressBrowseService.StageNameCandidate>> result =
                service.findStageNameCandidates(actressId);
        assertTrue(result.isPresent(), "should return present Optional (actress exists)");
        assertTrue(result.get().isEmpty(), "should be empty list when no enriched titles");
    }

    @Test
    void findStageNameCandidatesSortsByHitsDesc() {
        long actressId = insertActress("Shion Yumi");
        // wBND / 夕美しおん appears in 3 titles, Av2e / 三上悠亞 in 2, MW44 / 木下ひまり in 1
        insertEnrichedTitle("FC2-001", actressId,
                "[{\"slug\":\"wBND\",\"name\":\"夕美しおん\",\"gender\":\"F\"},{\"slug\":\"Av2e\",\"name\":\"三上悠亞\",\"gender\":\"F\"}]");
        insertEnrichedTitle("FC2-002", actressId,
                "[{\"slug\":\"wBND\",\"name\":\"夕美しおん\",\"gender\":\"F\"},{\"slug\":\"Av2e\",\"name\":\"三上悠亞\",\"gender\":\"F\"}]");
        insertEnrichedTitle("FC2-003", actressId,
                "[{\"slug\":\"wBND\",\"name\":\"夕美しおん\",\"gender\":\"F\"},{\"slug\":\"MW44\",\"name\":\"木下ひまり\",\"gender\":\"F\"}]");

        List<ActressBrowseService.StageNameCandidate> candidates =
                service.findStageNameCandidates(actressId).orElseThrow();

        assertEquals(3, candidates.size());
        assertEquals("夕美しおん", candidates.get(0).name());
        assertEquals("wBND", candidates.get(0).slug());
        assertEquals(3, candidates.get(0).hits());
        assertEquals("三上悠亞", candidates.get(1).name());
        assertEquals(2, candidates.get(1).hits());
        assertEquals("木下ひまり", candidates.get(2).name());
        assertEquals(1, candidates.get(2).hits());
    }

    @Test
    void findStageNameCandidatesExcludesMaleCast() {
        long actressId = insertActress("Cast Gender Test");
        // One male, one female in same title
        insertEnrichedTitle("MAN-001", actressId,
                "[{\"slug\":\"male1\",\"name\":\"男性優\",\"gender\":\"M\"},{\"slug\":\"fem1\",\"name\":\"女性優\",\"gender\":\"F\"}]");

        List<ActressBrowseService.StageNameCandidate> candidates =
                service.findStageNameCandidates(actressId).orElseThrow();

        assertEquals(1, candidates.size(), "male cast must be excluded");
        assertEquals("女性優", candidates.get(0).name());
        assertEquals("fem1", candidates.get(0).slug());
    }

    @Test
    void findStageNameCandidatesLimitsToTopEight() {
        long actressId = insertActress("Many Slugs");
        // Seed 12 distinct female slugs across 12 separate titles (each with 1 hit)
        for (int i = 1; i <= 12; i++) {
            insertEnrichedTitle("MULTI-" + String.format("%03d", i), actressId,
                    "[{\"slug\":\"slug" + i + "\",\"name\":\"名前" + i + "\",\"gender\":\"F\"}]");
        }

        List<ActressBrowseService.StageNameCandidate> candidates =
                service.findStageNameCandidates(actressId).orElseThrow();

        assertEquals(8, candidates.size(), "result must be capped at 8");
    }

    @Test
    void findStageNameCandidatesReturnsEmptyOptionalForUnknownActress() {
        Optional<List<ActressBrowseService.StageNameCandidate>> result =
                service.findStageNameCandidates(999999L);
        assertTrue(result.isEmpty(), "unknown actress must return empty Optional");
    }

    // ── setStageNameManual ────────────────────────────────────────────────────

    @Test
    void setStageNameManualPersistsValue() {
        long actressId = insertActress("Rima Arai");

        Optional<String> result = service.setStageNameManual(actressId, "新井リマ");

        assertEquals("新井リマ", result.orElse(null));
        String written = jdbi.withHandle(h -> h.createQuery("SELECT stage_name FROM actresses WHERE id = :id")
                .bind("id", actressId).mapTo(String.class).one());
        assertEquals("新井リマ", written);
    }

    @Test
    void setStageNameManualRejectsBlankInput() {
        long actressId = insertActress("Blank Test");

        Optional<String> emptyResult  = service.setStageNameManual(actressId, "");
        Optional<String> spaceResult  = service.setStageNameManual(actressId, "   ");
        Optional<String> nullResult   = service.setStageNameManual(actressId, null);

        assertTrue(emptyResult.isEmpty(), "empty string must be rejected");
        assertTrue(spaceResult.isEmpty(), "whitespace-only must be rejected");
        assertTrue(nullResult.isEmpty(),  "null must be rejected");

        String written = jdbi.withHandle(h -> h.createQuery("SELECT stage_name FROM actresses WHERE id = :id")
                .bind("id", actressId).mapTo(String.class).one());
        assertNull(written, "stage_name must not be written for blank input");
    }

    private long insertActress(String name) {
        return jdbi.withHandle(h -> h.createUpdate(
                "INSERT INTO actresses (canonical_name, tier, first_seen_at) VALUES (:n, 'LIBRARY', '2024-01-01')")
                .bind("n", name)
                .executeAndReturnGeneratedKeys("id").mapTo(Long.class).one());
    }

    private long insertEnrichedTitle(String code, long actressId, String castJson) {
        long titleId = jdbi.withHandle(h -> h.createUpdate(
                "INSERT INTO titles (code, base_code, label, seq_num, actress_id) VALUES (:c, :bc, :lbl, 1, :a)")
                .bind("c", code).bind("bc", code + "-00001").bind("lbl", code.split("-")[0]).bind("a", actressId)
                .executeAndReturnGeneratedKeys("id").mapTo(Long.class).one());
        jdbi.useHandle(h -> {
            h.createUpdate("INSERT INTO title_actresses (title_id, actress_id) VALUES (:t, :a)")
                    .bind("t", titleId).bind("a", actressId).execute();
            h.createUpdate("INSERT INTO title_javdb_enrichment (title_id, javdb_slug, fetched_at, cast_json) VALUES (:t, 'slug', '2024-01-01', :j)")
                    .bind("t", titleId).bind("j", castJson).execute();
        });
        return titleId;
    }
}
