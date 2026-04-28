package com.organizer3.enrichment;

import com.organizer3.db.SchemaInitializer;
import com.organizer3.model.Actress;
import com.organizer3.model.Title;
import com.organizer3.repository.jdbi.JdbiActressRepository;
import com.organizer3.repository.jdbi.JdbiTitleLocationRepository;
import com.organizer3.repository.jdbi.JdbiTitleRepository;
import com.organizer3.repository.jdbi.JdbiTitleTagRepository;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;


import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for ActressYamlLoader using an in-memory SQLite database
 * and the test_actress.yaml resource under src/test/resources/actresses/.
 */
class ActressYamlLoaderTest {

    private ActressYamlLoader loader;
    private JdbiActressRepository actressRepo;
    private JdbiTitleRepository titleRepo;
    private JdbiTitleTagRepository tagRepo;
    private Connection connection;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        Jdbi jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        actressRepo = new JdbiActressRepository(jdbi);
        JdbiTitleLocationRepository locationRepo = new JdbiTitleLocationRepository(jdbi);
        titleRepo = new JdbiTitleRepository(jdbi, locationRepo);
        tagRepo = new JdbiTitleTagRepository(jdbi);
        loader = new ActressYamlLoader(actressRepo, titleRepo, tagRepo);
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    @Test
    void loadOneCreatesActressWhenNotInDb() throws Exception {
        loader.loadOne("test_actress");

        Optional<Actress> found = actressRepo.resolveByName("Test Actress");
        assertTrue(found.isPresent());
    }

    @Test
    void loadOneEnrichesExistingActress() throws Exception {
        actressRepo.save(Actress.builder()
                .canonicalName("Test Actress")
                .tier(Actress.Tier.SUPERSTAR)
                .firstSeenAt(LocalDate.of(2024, 1, 1))
                .build());

        loader.loadOne("test_actress");

        Actress enriched = actressRepo.resolveByName("Test Actress").orElseThrow();
        assertEquals("テスト女優", enriched.getStageName());
        assertEquals(LocalDate.of(1990, 1, 15), enriched.getDateOfBirth());
        assertEquals("Tokyo, Japan", enriched.getBirthplace());
        assertEquals("A", enriched.getBloodType());
        assertEquals(158, enriched.getHeightCm());
        assertEquals(85, enriched.getBust());
        assertEquals(58, enriched.getWaist());
        assertEquals(88, enriched.getHip());
        assertEquals("D", enriched.getCup());
        assertEquals(LocalDate.of(2010, 4, 1), enriched.getActiveFrom());
        assertEquals(LocalDate.of(2015, 3, 31), enriched.getActiveTo());
        assertEquals("Test biography text.", enriched.getBiography());
        assertEquals("Test legacy text.", enriched.getLegacy());
        // Tier and firstSeenAt must be untouched
        assertEquals(Actress.Tier.SUPERSTAR, enriched.getTier());
    }

    @Test
    void loadOneCreatesPortfolioTitles() throws Exception {
        loader.loadOne("test_actress");

        Optional<Title> t1 = titleRepo.findByCode("TEST-001");
        Optional<Title> t2 = titleRepo.findByCode("TEST-002");
        assertTrue(t1.isPresent());
        assertTrue(t2.isPresent());
    }

    @Test
    void loadOneEnrichesExistingTitle() throws Exception {
        titleRepo.save(Title.builder().code("TEST-001").baseCode("TEST-00001").build());

        loader.loadOne("test_actress");

        Title title = titleRepo.findByCode("TEST-001").orElseThrow();
        assertEquals("テストタイトル1", title.getTitleOriginal());
        assertEquals("Test Title 1", title.getTitleEnglish());
        assertEquals(LocalDate.of(2010, 4, 15), title.getReleaseDate());
        assertEquals("debut title", title.getNotes());
        assertEquals(Actress.Grade.S, title.getGrade());
    }

    @Test
    void loadOneAppliesTags() throws Exception {
        loader.loadOne("test_actress");

        long titleId = titleRepo.findByCode("TEST-001").orElseThrow().getId();
        List<String> tags = tagRepo.findTagsForTitle(titleId);
        assertEquals(List.of("debut", "hardcore"), tags);
    }

    @Test
    void loadOneReplacesTagsOnReload() throws Exception {
        loader.loadOne("test_actress");
        loader.loadOne("test_actress");

        long titleId = titleRepo.findByCode("TEST-002").orElseThrow().getId();
        List<String> tags = tagRepo.findTagsForTitle(titleId);
        assertEquals(List.of("femdom", "squirting"), tags);
    }

    @Test
    void loadOneReturnsCorrectCounts() throws Exception {
        ActressYamlLoader.LoadResult result = loader.loadOne("test_actress");

        assertEquals("Test Actress", result.canonicalName());
        assertEquals(2, result.titlesCreated());
        assertEquals(0, result.titlesEnriched());
    }

    @Test
    void loadOneCountsEnrichedOnSecondLoad() throws Exception {
        loader.loadOne("test_actress");
        ActressYamlLoader.LoadResult result = loader.loadOne("test_actress");

        assertEquals(0, result.titlesCreated());
        assertEquals(2, result.titlesEnriched());
    }

    @Test
    void loadOneThrowsForUnknownSlug() {
        assertThrows(IllegalArgumentException.class, () -> loader.loadOne("no_such_actress"));
    }

    @Test
    void loadOneStoresAlternateNamesNotAsAliases() throws Exception {
        loader.loadOne("test_actress");

        Actress actress = actressRepo.resolveByName("Test Actress").orElseThrow();
        // alternate_names from the YAML are stored in alternate_names_json, NOT as aliases
        assertFalse(actressRepo.resolveByName("Testy").isPresent(),
                "alternate_names should not be added as searchable aliases");
        assertTrue(actress.getAlternateNames() != null
                && actress.getAlternateNames().stream().anyMatch(a -> "Testy".equals(a.name())),
                "alternate_names should be stored in the alternateNames field");
    }

    @Test
    void loadOneAppliesNameReading() throws Exception {
        loader.loadOne("test_actress");

        Actress actress = actressRepo.resolveByName("Test Actress").orElseThrow();
        assertEquals("てすとじょゆう", actress.getNameReading());
    }

    @Test
    void loadOneAppliesRetirementAnnounced() throws Exception {
        loader.loadOne("test_actress");

        Actress actress = actressRepo.resolveByName("Test Actress").orElseThrow();
        assertEquals(LocalDate.of(2015, 2, 1), actress.getRetirementAnnounced());
    }

    @Test
    void loadOneAppliesAlternateNamesWithNotes() throws Exception {
        loader.loadOne("test_actress");

        Actress actress = actressRepo.resolveByName("Test Actress").orElseThrow();
        List<Actress.AlternateName> alts = actress.getAlternateNames();
        assertNotNull(alts);
        assertEquals(2, alts.size());
        assertEquals("Testy", alts.get(0).name());
        assertEquals("nickname", alts.get(0).note());
        assertEquals("T-san", alts.get(1).name());
        assertEquals("used early career", alts.get(1).note());
    }

    @Test
    void loadOneAppliesPrimaryStudios() throws Exception {
        loader.loadOne("test_actress");

        Actress actress = actressRepo.resolveByName("Test Actress").orElseThrow();
        List<Actress.StudioTenure> studios = actress.getPrimaryStudios();
        assertNotNull(studios);
        assertEquals(2, studios.size());

        Actress.StudioTenure first = studios.get(0);
        assertEquals("TestLabel", first.name());
        assertEquals("TestCorp", first.company());
        assertEquals(LocalDate.of(2010, 4, 1), first.from());
        assertEquals(LocalDate.of(2012, 3, 31), first.to());
        assertEquals("Exclusive contract actress", first.role());

        Actress.StudioTenure second = studios.get(1);
        assertEquals("OtherLabel", second.name());
        assertEquals(LocalDate.of(2012, 4, 1), second.from());
    }

    @Test
    void loadOneAppliesAwards() throws Exception {
        loader.loadOne("test_actress");

        Actress actress = actressRepo.resolveByName("Test Actress").orElseThrow();
        List<Actress.Award> awards = actress.getAwards();
        assertNotNull(awards);
        assertEquals(2, awards.size());

        Actress.Award first = awards.get(0);
        assertEquals("Test Awards", first.event());
        assertEquals(2012, first.year());
        assertEquals("Best New Actress", first.category());
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Grade-only sync (syncGradesFromYaml)
    // ─────────────────────────────────────────────────────────────────────

    @Test
    void syncGradesWritesGradesForExistingTitlesWithoutCreatingStubs() throws Exception {
        // TEST-001 exists, TEST-002 does not — sync should not create stubs.
        titleRepo.save(Title.builder().code("TEST-001").baseCode("TEST-001").build());

        ActressYamlLoader.SyncGradesResult r = loader.syncGradesFromYaml("test_actress");

        assertEquals("test_actress", r.slug());
        assertEquals(2, r.scanned());
        assertEquals(1, r.written());
        assertEquals(1, r.missingTitle());
        assertEquals(0, r.noGrade());

        Title t1 = titleRepo.findByCode("TEST-001").orElseThrow();
        assertEquals(Actress.Grade.S, t1.getGrade());
        assertEquals("ai", t1.getGradeSource());
        // Stub was not created for TEST-002
        assertTrue(titleRepo.findByCode("TEST-002").isEmpty());
    }

    @Test
    void syncGradesDoesNotTouchTitleOriginalOrTags() throws Exception {
        long id = titleRepo.save(Title.builder().code("TEST-001").baseCode("TEST-001").build()).getId();
        titleRepo.enrichTitle(id, "preexisting original", null, null, null, null);
        tagRepo.replaceTagsForTitle(id, List.of("preexisting-tag"));

        loader.syncGradesFromYaml("test_actress");

        Title t = titleRepo.findByCode("TEST-001").orElseThrow();
        assertEquals("preexisting original", t.getTitleOriginal());
        assertEquals(Actress.Grade.S, t.getGrade());
        assertEquals(List.of("preexisting-tag"), tagRepo.findTagsForTitle(id));
    }

    @Test
    void syncGradesOverwritesEnrichmentGrade() throws Exception {
        long id = titleRepo.save(Title.builder().code("TEST-001").baseCode("TEST-001").build()).getId();
        titleRepo.setGradeFromEnrichment(id, Actress.Grade.B);

        loader.syncGradesFromYaml("test_actress");

        Title t = titleRepo.findByCode("TEST-001").orElseThrow();
        assertEquals(Actress.Grade.S, t.getGrade());
        assertEquals("ai", t.getGradeSource());
    }

    @Test
    void syncGradesSkipsManualGrade() throws Exception {
        long id = titleRepo.save(Title.builder().code("TEST-001").baseCode("TEST-001").build()).getId();
        titleRepo.setGradeManual(id, Actress.Grade.SSS);

        loader.syncGradesFromYaml("test_actress");

        Title t = titleRepo.findByCode("TEST-001").orElseThrow();
        assertEquals(Actress.Grade.SSS, t.getGrade(), "manual grade must not be overwritten");
        assertEquals("manual", t.getGradeSource());
    }

    @Test
    void syncGradesIsIdempotent() throws Exception {
        titleRepo.save(Title.builder().code("TEST-001").baseCode("TEST-001").build());
        titleRepo.save(Title.builder().code("TEST-002").baseCode("TEST-002").build());

        ActressYamlLoader.SyncGradesResult first = loader.syncGradesFromYaml("test_actress");
        ActressYamlLoader.SyncGradesResult second = loader.syncGradesFromYaml("test_actress");

        assertEquals(2, first.written());
        assertEquals(2, second.written(), "re-running re-writes 'ai' grades — re-loadable by design");
        assertEquals(Actress.Grade.S, titleRepo.findByCode("TEST-001").orElseThrow().getGrade());
        assertEquals(Actress.Grade.A, titleRepo.findByCode("TEST-002").orElseThrow().getGrade());
    }

    @Test
    void syncGradesThrowsForUnknownSlug() {
        assertThrows(IllegalArgumentException.class,
                () -> loader.syncGradesFromYaml("no_such_actress"));
    }
}
