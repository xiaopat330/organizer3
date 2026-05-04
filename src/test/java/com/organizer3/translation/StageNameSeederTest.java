package com.organizer3.translation;

import com.organizer3.db.SchemaInitializer;
import com.organizer3.enrichment.ActressYaml;
import com.organizer3.enrichment.ActressYamlLoader;
import com.organizer3.translation.repository.jdbi.JdbiStageNameLookupRepository;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link StageNameSeeder} using a mocked {@link ActressYamlLoader}
 * and a real in-memory SQLite {@link JdbiStageNameLookupRepository}.
 */
class StageNameSeederTest {

    private JdbiStageNameLookupRepository lookupRepo;
    private ActressYamlLoader yamlLoader;
    private StageNameSeeder seeder;
    private Connection connection;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        Jdbi jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        lookupRepo = new JdbiStageNameLookupRepository(jdbi);
        yamlLoader = mock(ActressYamlLoader.class);
        seeder = new StageNameSeeder(yamlLoader, lookupRepo);
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    private ActressYaml makeYaml(String familyName, String givenName, String stageName,
                                  List<ActressYaml.AlternateName> alternates) {
        ActressYaml.Name name = new ActressYaml.Name(familyName, givenName, stageName, null, alternates);
        ActressYaml.Profile profile = new ActressYaml.Profile(name, null, null, null, null,
                null, null, null, null, null, null, null, null, null);
        return new ActressYaml(profile, null);
    }

    @Test
    void seed_insertsStageNameForActress() throws Exception {
        // Aida Yua: hiragana stage name + romanized alternate (should be skipped)
        ActressYaml yaml = makeYaml("Aida", "Yua", "あいだゆあ",
                List.of(new ActressYaml.AlternateName("Yua Aida", "primary romanized name")));
        when(yamlLoader.listSlugs()).thenReturn(List.of("aida_yua"));
        when(yamlLoader.peek("aida_yua")).thenReturn(yaml);

        seeder.seed();

        // Hiragana stage_name should be seeded
        Optional<String> result = lookupRepo.findRomanizedFor("あいだゆあ");
        assertTrue(result.isPresent());
        assertEquals("Yua Aida", result.get());
        // Romanized alternate should NOT be seeded (no JP characters)
        assertEquals(1L, lookupRepo.countAll());
    }

    @Test
    void seed_insertsJapaneseAlternateName() throws Exception {
        // An actress with kanji in alternate name
        ActressYaml yaml = makeYaml("Aoi", "Sora", "蒼井そら",
                List.of(new ActressYaml.AlternateName("蒼井空", "kanji variant")));
        when(yamlLoader.listSlugs()).thenReturn(List.of("aoi_sora"));
        when(yamlLoader.peek("aoi_sora")).thenReturn(yaml);

        seeder.seed();

        // Both stage_name and JP alternate should be seeded
        assertTrue(lookupRepo.findRomanizedFor("蒼井そら").isPresent());
        assertTrue(lookupRepo.findRomanizedFor("蒼井空").isPresent());
        assertEquals(2L, lookupRepo.countAll());
    }

    @Test
    void seed_skipsSingleNameActressWithNoStageName() throws Exception {
        // Actress with no stage_name (null)
        ActressYaml yaml = makeYaml(null, "Aika", null, List.of());
        when(yamlLoader.listSlugs()).thenReturn(List.of("aika"));
        when(yamlLoader.peek("aika")).thenReturn(yaml);

        seeder.seed();

        assertEquals(0L, lookupRepo.countAll());
    }

    @Test
    void seed_skipsRomanizedStageName() throws Exception {
        // A stage name with no JP characters should be skipped
        ActressYaml yaml = makeYaml("Smith", "Jane", "Jane Smith", List.of());
        when(yamlLoader.listSlugs()).thenReturn(List.of("jane_smith"));
        when(yamlLoader.peek("jane_smith")).thenReturn(yaml);

        seeder.seed();

        assertEquals(0L, lookupRepo.countAll());
    }

    @Test
    void seed_multipleActresses_allSeeded() throws Exception {
        ActressYaml aika = makeYaml(null, "Aika", "愛佳", List.of());
        ActressYaml aida = makeYaml("Aida", "Yua", "あいだゆあ", List.of());
        when(yamlLoader.listSlugs()).thenReturn(List.of("aika", "aida_yua"));
        when(yamlLoader.peek("aika")).thenReturn(aika);
        when(yamlLoader.peek("aida_yua")).thenReturn(aida);

        seeder.seed();

        assertEquals(2L, lookupRepo.countAll());
        assertEquals(Optional.of("Aika"), lookupRepo.findRomanizedFor("愛佳"));
        assertEquals(Optional.of("Yua Aida"), lookupRepo.findRomanizedFor("あいだゆあ"));
    }

    @Test
    void seed_isIdempotent_reseedClearsAndReplaces() throws Exception {
        ActressYaml aika = makeYaml(null, "Aika", "愛佳", List.of());
        when(yamlLoader.listSlugs()).thenReturn(List.of("aika"));
        when(yamlLoader.peek("aika")).thenReturn(aika);

        seeder.seed();
        seeder.seed(); // second call

        assertEquals(1L, lookupRepo.countAll());
        assertEquals(Optional.of("Aika"), lookupRepo.findRomanizedFor("愛佳"));
    }

    @Test
    void seed_toleratesParseExceptionOnOneSlugs() throws Exception {
        ActressYaml aika = makeYaml(null, "Aika", "愛佳", List.of());
        when(yamlLoader.listSlugs()).thenReturn(List.of("aika", "bad_slug"));
        when(yamlLoader.peek("aika")).thenReturn(aika);
        when(yamlLoader.peek("bad_slug")).thenThrow(new RuntimeException("parse error"));

        // Should not throw; bad slug is skipped
        assertDoesNotThrow(() -> seeder.seed());
        assertEquals(1L, lookupRepo.countAll());
    }
}
