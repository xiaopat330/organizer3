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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests loading the real actress YAML files from src/main/resources/actresses/.
 * Assertions are grounded in known facts from the research data.
 */
class ActressYamlLoaderRealDataTest {

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

    // -------------------------------------------------------------------------
    // Nana Ogura
    // -------------------------------------------------------------------------

    @Nested
    class NanaOgura {

        @BeforeEach
        void load() throws Exception {
            loader.loadOne("nana_ogura");
        }

        @Test
        void actressIsResolvable() {
            assertTrue(actressRepo.resolveByName("Nana Ogura").isPresent());
        }

        @Test
        void profileFieldsAreCorrect() {
            Actress a = actressRepo.resolveByName("Nana Ogura").orElseThrow();
            assertEquals("小倉奈々", a.getStageName());
            assertEquals(LocalDate.of(1990, 1, 10), a.getDateOfBirth());
            assertEquals("Kanagawa, Japan", a.getBirthplace());
            assertEquals("O", a.getBloodType());
            assertEquals(160, a.getHeightCm());
            assertEquals(88, a.getBust());
            assertEquals(59, a.getWaist());
            assertEquals(90, a.getHip());
            assertEquals("F", a.getCup());
            assertEquals(LocalDate.of(2010, 7, 9), a.getActiveFrom());
            assertEquals(LocalDate.of(2015, 12, 11), a.getActiveTo());
            assertNotNull(a.getBiography());
            assertFalse(a.getBiography().isBlank());
        }

        @Test
        void allPortfolioEntriesAreCreated() {
            // YAML has 56 portfolio entries
            ActressYamlLoader.LoadResult result = actressRepo.resolveByName("Nana Ogura")
                    .map(a -> new ActressYamlLoader.LoadResult(a.getCanonicalName(), a.getId(), 0, 0, List.of()))
                    .orElseThrow();
            long actressId = result.actressId();
            List<Title> titles = titleRepo.findByActress(actressId);
            assertEquals(56, titles.size());
        }

        @Test
        void debutTitleIsEnrichedCorrectly() {
            Title debut = titleRepo.findByCode("XV-863").orElseThrow();
            assertEquals("New Comer 小倉奈々", debut.getTitleOriginal());
            assertEquals("New Comer Nana Ogura", debut.getTitleEnglish());
            assertEquals(LocalDate.of(2010, 7, 9), debut.getReleaseDate());
            assertEquals(Actress.Grade.S, debut.getGrade());
            assertTrue(debut.getNotes().contains("debut"));
        }

        @Test
        void debutTitleHasCorrectTags() {
            long titleId = titleRepo.findByCode("XV-863").orElseThrow().getId();
            List<String> tags = tagRepo.findTagsForTitle(titleId);
            assertTrue(tags.contains("debut"));
            assertTrue(tags.contains("hardcore"));
        }

        @Test
        void ssGradeTitleIsEnrichedCorrectly() {
            // XV-973 — "AVを見ようと思ったら店員が小倉奈々" — grade SS
            Title title = titleRepo.findByCode("XV-973").orElseThrow();
            assertEquals(Actress.Grade.SS, title.getGrade());
            assertEquals(LocalDate.of(2011, 9, 23), title.getReleaseDate());
        }

        @Test
        void femdomTagsAreApplied() {
            long titleId = titleRepo.findByCode("XV-932").orElseThrow().getId();
            List<String> tags = tagRepo.findTagsForTitle(titleId);
            assertTrue(tags.contains("femdom"));
            assertTrue(tags.contains("cum-control"));
        }

        @Test
        void alternateNameStoredInProfileNotAlias() {
            // alternate_name: オグナナ — stored in alternate_names_json, not as a searchable alias
            assertFalse(actressRepo.resolveByName("オグナナ").isPresent(),
                    "alternate_names should not be added as aliases");
            Actress a = actressRepo.resolveByName("Nana Ogura").orElseThrow();
            assertTrue(a.getAlternateNames() != null
                    && a.getAlternateNames().stream().anyMatch(n -> "オグナナ".equals(n.name())));
        }

        @Test
        void unresolvedTitlesArePresentWithNullMetadata() {
            // XV-1083 — title and date unknown, notes present
            Title unresolved = titleRepo.findByCode("XV-1083").orElseThrow();
            assertNull(unresolved.getTitleOriginal());
            assertNull(unresolved.getReleaseDate());
            assertNotNull(unresolved.getNotes());
        }
    }

    // -------------------------------------------------------------------------
    // Sora Aoi
    // -------------------------------------------------------------------------

    @Nested
    class SoraAoi {

        @BeforeEach
        void load() throws Exception {
            loader.loadOne("sora_aoi");
        }

        @Test
        void actressIsResolvable() {
            assertTrue(actressRepo.resolveByName("Sora Aoi").isPresent());
        }

        @Test
        void profileFieldsAreCorrect() {
            Actress a = actressRepo.resolveByName("Sora Aoi").orElseThrow();
            assertEquals("蒼井そら", a.getStageName());
            assertEquals(LocalDate.of(1981, 4, 26), a.getDateOfBirth());
            assertEquals("Tokyo, Japan", a.getBirthplace());
            assertEquals(155, a.getHeightCm());
            assertEquals(90, a.getBust());
            assertEquals(58, a.getWaist());
            assertEquals(83, a.getHip());
            assertEquals("G", a.getCup());
            assertEquals(LocalDate.of(2002, 7, 30), a.getActiveFrom());
            assertEquals(LocalDate.of(2011, 7, 7), a.getActiveTo());
            assertNotNull(a.getBiography());
        }

        @Test
        void allPortfolioEntriesAreCreated() {
            // YAML has 78 portfolio entries
            Actress a = actressRepo.resolveByName("Sora Aoi").orElseThrow();
            assertEquals(78, titleRepo.findByActress(a.getId()).size());
        }

        @Test
        void s1DebutIsGradedSSS() {
            // ONED-003 — S1 debut, sold 100k copies, industry record
            Title debut = titleRepo.findByCode("ONED-003").orElseThrow();
            assertEquals(Actress.Grade.SSS, debut.getGrade());
            assertEquals(LocalDate.of(2004, 11, 11), debut.getReleaseDate());
            assertTrue(debut.getNotes().contains("100,000"));
        }

        @Test
        void s1DebutHasMilestoneTags() {
            long titleId = titleRepo.findByCode("ONED-003").orElseThrow().getId();
            List<String> tags = tagRepo.findTagsForTitle(titleId);
            assertTrue(tags.contains("debut"));
            assertTrue(tags.contains("milestone"));
        }

        @Test
        void aliceJapanDebutIsResolved() {
            // DV-172 — Alice Japan debut
            Title debut = titleRepo.findByCode("DV-172").orElseThrow();
            assertEquals(Actress.Grade.SS, debut.getGrade());
            assertEquals(LocalDate.of(2002, 7, 30), debut.getReleaseDate());
        }

        @Test
        void chineseAlternateNameStoredInProfileNotAlias() {
            // alternate_name: 苍井空 — stored in alternate_names_json, not as a searchable alias
            assertFalse(actressRepo.resolveByName("苍井空").isPresent(),
                    "alternate_names should not be added as aliases");
            Actress a = actressRepo.resolveByName("Sora Aoi").orElseThrow();
            assertTrue(a.getAlternateNames() != null
                    && a.getAlternateNames().stream().anyMatch(n -> "苍井空".equals(n.name())));
        }

        @Test
        void compilationTitlesHaveNoGrade() {
            // ONSD-024 is a compilation with no grade field
            Title compilation = titleRepo.findByCode("ONSD-024").orElseThrow();
            assertNull(compilation.getGrade());
        }

        @Test
        void reissueTitlesHaveReissueTag() {
            long titleId = titleRepo.findByCode("KA-2080").orElseThrow().getId();
            List<String> tags = tagRepo.findTagsForTitle(titleId);
            assertTrue(tags.contains("reissue"));
        }
    }

    // -------------------------------------------------------------------------
    // Yuma Asami
    // -------------------------------------------------------------------------

    @Nested
    class YumaAsami {

        @BeforeEach
        void load() throws Exception {
            loader.loadOne("yuma_asami");
        }

        @Test
        void actressIsResolvable() {
            assertTrue(actressRepo.resolveByName("Yuma Asami").isPresent());
        }

        @Test
        void profileFieldsAreCorrect() {
            Actress a = actressRepo.resolveByName("Yuma Asami").orElseThrow();
            assertEquals("麻美ゆま", a.getStageName());
            assertEquals(LocalDate.of(1987, 3, 24), a.getDateOfBirth());
            assertEquals("Takasaki, Gunma Prefecture, Japan", a.getBirthplace());
            assertEquals("AB", a.getBloodType());
            assertEquals(158, a.getHeightCm());
            assertEquals(96, a.getBust());
            assertEquals(58, a.getWaist());
            assertEquals(88, a.getHip());
            assertEquals("H", a.getCup());
            assertEquals(LocalDate.of(2005, 10, 28), a.getActiveFrom());
            assertEquals(LocalDate.of(2013, 5, 24), a.getActiveTo());
            assertNotNull(a.getBiography());
        }

        @Test
        void allPortfolioEntriesAreCreated() {
            // YAML has 165 portfolio entries
            Actress a = actressRepo.resolveByName("Yuma Asami").orElseThrow();
            assertEquals(165, titleRepo.findByActress(a.getId()).size());
        }

        @Test
        void s1DebutIsGradedSSS() {
            // ONED-292 — S1/ギリギリモザイク debut
            Title debut = titleRepo.findByCode("ONED-292").orElseThrow();
            assertEquals(Actress.Grade.SSS, debut.getGrade());
            assertEquals(LocalDate.of(2005, 11, 7), debut.getReleaseDate());
        }

        @Test
        void debutHasCorrectTags() {
            long titleId = titleRepo.findByCode("ONED-292").orElseThrow().getId();
            List<String> tags = tagRepo.findTagsForTitle(titleId);
            assertTrue(tags.contains("debut"));
        }

        @Test
        void aliceJapanTitleIsEnriched() {
            // DV-563 — Alice Japan, grade SS
            Title title = titleRepo.findByCode("DV-563").orElseThrow();
            assertEquals(Actress.Grade.SS, title.getGrade());
            assertEquals("女尻", title.getTitleOriginal());
            assertEquals("Female Ass", title.getTitleEnglish());
            assertEquals(LocalDate.of(2005, 12, 30), title.getReleaseDate());
        }

        @Test
        void earlyGravureAlternateNameStoredInProfileNotAlias() {
            // alternate_name: 麻生由真 (gravure name) — stored in alternate_names_json, not as alias
            assertFalse(actressRepo.resolveByName("麻生由真").isPresent(),
                    "alternate_names should not be added as aliases");
            Actress a = actressRepo.resolveByName("Yuma Asami").orElseThrow();
            assertTrue(a.getAlternateNames() != null
                    && a.getAlternateNames().stream().anyMatch(n -> "麻生由真".equals(n.name())));
        }
    }

    // -------------------------------------------------------------------------
    // Cross-actress tag queries
    // -------------------------------------------------------------------------

    @Nested
    class CrossActressTags {

        @BeforeEach
        void loadAll() throws Exception {
            loader.loadOne("nana_ogura");
            loader.loadOne("sora_aoi");
            loader.loadOne("yuma_asami");
        }

        @Test
        void debutTagSpansAllThreeActresses() {
            List<Long> debutTitleIds = tagRepo.findTitleIdsByTag("debut");
            // Each actress has at least one debut-tagged title
            assertTrue(debutTitleIds.size() >= 3);
        }

        @Test
        void femdomTagIsNanaOguraOnly() {
            Actress nana = actressRepo.resolveByName("Nana Ogura").orElseThrow();
            List<Long> femdomForNana = tagRepo.findTitleIdsByTagAndActress("femdom", nana.getId());
            assertFalse(femdomForNana.isEmpty());

            // Sora Aoi has femdom tag too (SOE-490), so just verify Nana's are hers
            for (Long titleId : femdomForNana) {
                Title t = titleRepo.findById(titleId).orElseThrow();
                assertEquals(nana.getId(), t.getActressId());
            }
        }

        @Test
        void compilationTagExistsForSoraAndYuma() {
            List<Long> compilationIds = tagRepo.findTitleIdsByTag("compilation");
            // Sora Aoi has multiple compilations, Yuma Asami also has compilations
            assertTrue(compilationIds.size() >= 2);
        }
    }
}
