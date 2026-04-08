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
    void loadOneAddsAliases() throws Exception {
        loader.loadOne("test_actress");

        Actress actress = actressRepo.resolveByName("Test Actress").orElseThrow();
        Optional<Actress> byAlias = actressRepo.resolveByName("Testy");
        assertTrue(byAlias.isPresent());
        assertEquals(actress.getId(), byAlias.get().getId());
    }
}
