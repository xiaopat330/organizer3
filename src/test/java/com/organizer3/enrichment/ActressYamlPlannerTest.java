package com.organizer3.enrichment;

import com.organizer3.db.SchemaInitializer;
import com.organizer3.enrichment.plan.ActressChange;
import com.organizer3.enrichment.plan.ActressYamlPlan;
import com.organizer3.enrichment.plan.PortfolioChange;
import com.organizer3.model.Actress;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies ActressYamlLoader.plan() is pure-read (DB unchanged) and emits accurate diffs.
 * Uses the same test_actress.yaml fixture as ActressYamlLoaderTest.
 */
class ActressYamlPlannerTest {

    private ActressYamlLoader loader;
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
        JdbiTitleTagRepository tagRepo = new JdbiTitleTagRepository(jdbi);
        loader = new ActressYamlLoader(actressRepo, titleRepo, tagRepo);
    }

    @AfterEach
    void tearDown() throws Exception { connection.close(); }

    @Test
    void planDoesNotWriteToDb() throws Exception {
        ActressYamlPlan plan = loader.plan("test_actress");

        // Nothing in the DB yet.
        assertTrue(actressRepo.resolveByName("Test Actress").isEmpty(),
                "plan() must not create actress rows");
        assertTrue(titleRepo.findByCode("TEST-001").isEmpty(),
                "plan() must not create title rows");
        assertNotNull(plan);
    }

    @Test
    void planForNewActressReportsCreate() throws Exception {
        ActressYamlPlan plan = loader.plan("test_actress");
        assertInstanceOf(ActressChange.Create.class, plan.actressChange());
        ActressChange.Create c = (ActressChange.Create) plan.actressChange();
        // Every populated YAML field should show as a "fresh" field change
        assertFalse(c.fields().isEmpty(), "expected at least one field change for a fresh actress");
        assertTrue(c.fields().stream().anyMatch(f -> f.field().equals("stageName")),
                "stageName diff missing");
    }

    @Test
    void planForEnrichedActressReportsNoFieldChanges() throws Exception {
        // First apply the YAML so DB matches it.
        loader.loadOne("test_actress");

        // Planning again should report an Update with an empty field list.
        ActressYamlPlan plan = loader.plan("test_actress");
        assertInstanceOf(ActressChange.Update.class, plan.actressChange());
        ActressChange.Update u = (ActressChange.Update) plan.actressChange();
        assertTrue(u.fields().isEmpty(),
                "no field changes expected after a successful load; got: " + u.fields());
    }

    @Test
    void planSurfacesSingleFieldDelta() throws Exception {
        loader.loadOne("test_actress");

        // Manually perturb one field via a direct update — planner should detect it.
        Actress current = actressRepo.resolveByName("Test Actress").orElseThrow();
        actressRepo.updateProfile(
                current.getId(), current.getStageName(), current.getDateOfBirth(),
                current.getBirthplace(), current.getBloodType(),
                199,  // perturbed: YAML says 158
                current.getBust(), current.getWaist(), current.getHip(),
                current.getCup(), current.getActiveFrom(), current.getActiveTo(),
                current.getBiography(), current.getLegacy());

        ActressYamlPlan plan = loader.plan("test_actress");
        ActressChange.Update u = (ActressChange.Update) plan.actressChange();
        assertEquals(1, u.fields().size(),
                "expected exactly one field change, got: " + u.fields());
        assertEquals("heightCm", u.fields().get(0).field());
        assertEquals(199, u.fields().get(0).oldValue());
        assertEquals(158, u.fields().get(0).newValue());
    }

    @Test
    void planReportsNewTitleCreationAndNoopEnrichment() throws Exception {
        loader.loadOne("test_actress");

        ActressYamlPlan plan = loader.plan("test_actress");
        // All portfolio entries should be EnrichTitle with no field changes (no-op).
        List<PortfolioChange> changes = plan.portfolioChanges();
        assertFalse(changes.isEmpty());
        for (PortfolioChange c : changes) {
            assertInstanceOf(PortfolioChange.EnrichTitle.class, c);
            PortfolioChange.EnrichTitle et = (PortfolioChange.EnrichTitle) c;
            assertTrue(et.isNoop(),
                    "expected no-op enrichment after a fresh load; got " + et);
        }
    }

    @Test
    void planSummaryAccumulatesCounts() throws Exception {
        ActressYamlPlan plan = loader.plan("test_actress");
        ActressYamlPlan.Summary s = plan.summary();
        // Fresh load: actress is being created (counts as 1 change), all titles are creates.
        assertEquals(1, s.actressChanged());
        assertEquals(plan.portfolioChanges().size(), s.titlesToCreate());
        assertEquals(0, s.titlesToEnrich());
        assertEquals(0, s.titlesNoop());
    }
}
