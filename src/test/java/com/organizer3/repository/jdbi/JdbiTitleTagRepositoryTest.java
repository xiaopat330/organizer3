package com.organizer3.repository.jdbi;

import com.organizer3.db.SchemaInitializer;
import com.organizer3.model.Actress;
import com.organizer3.model.Title;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for JdbiTitleTagRepository using an in-memory SQLite database.
 */
class JdbiTitleTagRepositoryTest {

    private JdbiTitleTagRepository tagRepo;
    private JdbiActressRepository actressRepo;
    private JdbiTitleRepository titleRepo;
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
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    @Test
    void replaceTagsForTitleStoresTags() {
        long titleId = saveTitle("ABP-001").getId();
        tagRepo.replaceTagsForTitle(titleId, List.of("femdom", "squirting"));

        List<String> tags = tagRepo.findTagsForTitle(titleId);
        assertEquals(List.of("femdom", "squirting"), tags);
    }

    @Test
    void replaceTagsIsIdempotentAndOverwrites() {
        long titleId = saveTitle("ABP-001").getId();
        tagRepo.replaceTagsForTitle(titleId, List.of("femdom", "squirting"));
        tagRepo.replaceTagsForTitle(titleId, List.of("debut"));

        List<String> tags = tagRepo.findTagsForTitle(titleId);
        assertEquals(List.of("debut"), tags);
    }

    @Test
    void replaceTagsWithEmptyListClearsTags() {
        long titleId = saveTitle("ABP-001").getId();
        tagRepo.replaceTagsForTitle(titleId, List.of("femdom"));
        tagRepo.replaceTagsForTitle(titleId, List.of());

        assertTrue(tagRepo.findTagsForTitle(titleId).isEmpty());
    }

    @Test
    void findTagsReturnsSortedAlphabetically() {
        long titleId = saveTitle("ABP-001").getId();
        tagRepo.replaceTagsForTitle(titleId, List.of("squirting", "debut", "femdom"));

        List<String> tags = tagRepo.findTagsForTitle(titleId);
        assertEquals(List.of("debut", "femdom", "squirting"), tags);
    }

    @Test
    void findTitleIdsByTagReturnsMatchingTitles() {
        long id1 = saveTitle("ABP-001").getId();
        long id2 = saveTitle("ABP-002").getId();
        long id3 = saveTitle("ABP-003").getId();
        tagRepo.replaceTagsForTitle(id1, List.of("femdom"));
        tagRepo.replaceTagsForTitle(id2, List.of("femdom", "squirting"));
        tagRepo.replaceTagsForTitle(id3, List.of("debut"));

        List<Long> femdomTitles = tagRepo.findTitleIdsByTag("femdom");
        assertTrue(femdomTitles.contains(id1));
        assertTrue(femdomTitles.contains(id2));
        assertFalse(femdomTitles.contains(id3));
    }

    @Test
    void findTitleIdsByTagAndActressFiltersCorrectly() {
        Actress actress1 = actressRepo.save(actress("Actress One"));
        Actress actress2 = actressRepo.save(actress("Actress Two"));

        long id1 = saveTitle("ABP-001", actress1.getId()).getId();
        long id2 = saveTitle("ABP-002", actress2.getId()).getId();
        tagRepo.replaceTagsForTitle(id1, List.of("femdom"));
        tagRepo.replaceTagsForTitle(id2, List.of("femdom"));

        List<Long> results = tagRepo.findTitleIdsByTagAndActress("femdom", actress1.getId());
        assertEquals(List.of(id1), results);
    }

    @Test
    void findTagsForTitleReturnsEmptyWhenNoTags() {
        long titleId = saveTitle("ABP-001").getId();
        assertTrue(tagRepo.findTagsForTitle(titleId).isEmpty());
    }

    // --- helpers ---

    private Title saveTitle(String code) {
        return titleRepo.save(Title.builder()
                .code(code)
                .baseCode(code)
                .build());
    }

    private Title saveTitle(String code, long actressId) {
        return titleRepo.save(Title.builder()
                .code(code)
                .baseCode(code)
                .actressId(actressId)
                .build());
    }

    private static Actress actress(String name) {
        return Actress.builder()
                .canonicalName(name)
                .tier(Actress.Tier.LIBRARY)
                .firstSeenAt(LocalDate.of(2024, 1, 1))
                .build();
    }
}
