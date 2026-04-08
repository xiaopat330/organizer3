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
 * Integration tests for JdbiTitleActressRepository using an in-memory SQLite database.
 */
class JdbiTitleActressRepositoryTest {

    private JdbiTitleActressRepository repo;
    private JdbiActressRepository actressRepo;
    private JdbiTitleRepository titleRepo;
    private JdbiTitleLocationRepository locationRepo;
    private Connection connection;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        Jdbi jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        jdbi.useHandle(h -> h.execute(
                "INSERT INTO volumes (id, structure_type) VALUES ('vol-a', 'conventional')"));
        repo = new JdbiTitleActressRepository(jdbi);
        actressRepo = new JdbiActressRepository(jdbi);
        locationRepo = new JdbiTitleLocationRepository(jdbi);
        titleRepo = new JdbiTitleRepository(jdbi, locationRepo);
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    // --- link ---

    @Test
    void linkAssociatesActressWithTitle() {
        Actress aika = saveActress("Aika");
        Title title = saveTitle("HMN-102");

        repo.link(title.getId(), aika.getId());

        List<Long> ids = repo.findActressIdsByTitle(title.getId());
        assertEquals(List.of(aika.getId()), ids);
    }

    @Test
    void linkIsIdempotent() {
        Actress aika = saveActress("Aika");
        Title title = saveTitle("HMN-102");

        repo.link(title.getId(), aika.getId());
        repo.link(title.getId(), aika.getId()); // second call should not throw

        assertEquals(1, repo.findActressIdsByTitle(title.getId()).size());
    }

    // --- linkAll ---

    @Test
    void linkAllAssociatesMultipleActresses() {
        Actress aika = saveActress("Aika");
        Actress yui = saveActress("Yui Hatano");
        Title title = saveTitle("HMN-102");

        repo.linkAll(title.getId(), List.of(aika.getId(), yui.getId()));

        List<Long> ids = repo.findActressIdsByTitle(title.getId());
        assertEquals(2, ids.size());
        assertTrue(ids.containsAll(List.of(aika.getId(), yui.getId())));
    }

    @Test
    void linkAllWithEmptyListDoesNothing() {
        Title title = saveTitle("HMN-102");

        repo.linkAll(title.getId(), List.of());

        assertTrue(repo.findActressIdsByTitle(title.getId()).isEmpty());
    }

    @Test
    void linkAllIsIdempotent() {
        Actress aika = saveActress("Aika");
        Title title = saveTitle("HMN-102");

        repo.linkAll(title.getId(), List.of(aika.getId()));
        repo.linkAll(title.getId(), List.of(aika.getId()));

        assertEquals(1, repo.findActressIdsByTitle(title.getId()).size());
    }

    // --- findActressIdsByTitle ---

    @Test
    void findActressIdsByTitleReturnsEmptyForUnlinkedTitle() {
        Title title = saveTitle("HMN-102");
        assertTrue(repo.findActressIdsByTitle(title.getId()).isEmpty());
    }

    @Test
    void findActressIdsByTitleIsIsolatedPerTitle() {
        Actress aika = saveActress("Aika");
        Actress yui = saveActress("Yui Hatano");
        Title t1 = saveTitle("HMN-102");
        Title t2 = saveTitle("PRED-390");

        repo.link(t1.getId(), aika.getId());
        repo.link(t2.getId(), yui.getId());

        assertEquals(List.of(aika.getId()), repo.findActressIdsByTitle(t1.getId()));
        assertEquals(List.of(yui.getId()), repo.findActressIdsByTitle(t2.getId()));
    }

    // --- deleteOrphaned ---

    @Test
    void deleteOrphanedRemovesRowsForDeletedTitles() {
        Actress aika = saveActress("Aika");
        Title title = saveTitle("HMN-102");
        repo.link(title.getId(), aika.getId());

        // Simulate title deletion (bypass repo to avoid FK issues in test)
        Jdbi.create(connection).useHandle(h ->
                h.execute("DELETE FROM titles WHERE id = ?", title.getId()));

        repo.deleteOrphaned();

        assertTrue(repo.findActressIdsByTitle(title.getId()).isEmpty());
    }

    @Test
    void deleteOrphanedPreservesRowsForLiveTitles() {
        Actress aika = saveActress("Aika");
        Title title = saveTitle("HMN-102");
        repo.link(title.getId(), aika.getId());

        repo.deleteOrphaned();

        assertEquals(List.of(aika.getId()), repo.findActressIdsByTitle(title.getId()));
    }

    // --- helpers ---

    private Actress saveActress(String name) {
        return actressRepo.save(Actress.builder()
                .canonicalName(name)
                .tier(Actress.Tier.LIBRARY)
                .firstSeenAt(LocalDate.now())
                .build());
    }

    private Title saveTitle(String code) {
        return titleRepo.save(Title.builder().code(code).baseCode(code).build());
    }
}
