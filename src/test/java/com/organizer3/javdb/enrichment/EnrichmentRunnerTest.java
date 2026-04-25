package com.organizer3.javdb.enrichment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.organizer3.db.SchemaInitializer;
import com.organizer3.javdb.JavdbClient;
import com.organizer3.javdb.JavdbConfig;
import com.organizer3.repository.jdbi.JdbiActressRepository;
import com.organizer3.repository.jdbi.JdbiTitleLocationRepository;
import com.organizer3.repository.jdbi.JdbiTitleRepository;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class EnrichmentRunnerTest {

    @TempDir
    Path dataDir;

    private Jdbi jdbi;
    private Connection connection;
    private EnrichmentQueue queue;
    private JavdbStagingRepository stagingRepo;
    private JdbiTitleRepository titleRepo;
    private JdbiActressRepository actressRepo;
    private JavdbExtractor extractor;
    private JavdbProjector projector;

    private static final JavdbConfig CONFIG = new JavdbConfig(true, 1.0, 3, new int[]{1, 5, 30}, 5, null);

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        queue = new EnrichmentQueue(jdbi, CONFIG);
        stagingRepo = new JavdbStagingRepository(jdbi, new ObjectMapper(), dataDir);
        titleRepo = new JdbiTitleRepository(jdbi, new JdbiTitleLocationRepository(jdbi));
        actressRepo = new JdbiActressRepository(jdbi);
        extractor = new JavdbExtractor();
        projector = new JavdbProjector(new ObjectMapper());
    }

    @Test
    void fetchTitle_writesStaging_andRecordsActressSlug() throws Exception {
        // Seed a title and actress whose stage name matches the cast in title_detail.html
        long titleId = jdbi.withHandle(h ->
                h.createUpdate("INSERT INTO titles (code, base_code, label, seq_num) VALUES ('DV-948', 'DV', 'DV', 948)")
                        .executeAndReturnGeneratedKeys("id").mapTo(Long.class).one());
        long actressId = jdbi.withHandle(h ->
                h.createUpdate("INSERT INTO actresses (canonical_name, stage_name, tier, first_seen_at) VALUES ('麻美ゆま', '麻美ゆま', 'LIBRARY', '2024-01-01')")
                        .executeAndReturnGeneratedKeys("id").mapTo(Long.class).one());

        // Fake client: search returns a page with /v/deD0v; title page returns the fixture HTML
        String searchHtml = "<a href=\"/v/deD0v\">DV-948</a>";
        String titleHtml = loadFixture("title_detail.html");
        JavdbClient fakeClient = new JavdbClient() {
            @Override public String searchByCode(String code) { return searchHtml; }
            @Override public String fetchTitlePage(String slug) { return titleHtml; }
            @Override public String fetchActressPage(String slug) { throw new AssertionError("not expected"); }
        };

        EnrichmentRunner runner = new EnrichmentRunner(
                CONFIG, fakeClient, extractor, projector, stagingRepo, queue, titleRepo, actressRepo,
                new AutoPromoter(jdbi));

        queue.enqueueTitle(titleId, actressId);
        runner.runOneStep();

        // Title staging row was created
        Optional<JavdbTitleStagingRow> titleRow = stagingRepo.findTitleStaging(titleId);
        assertTrue(titleRow.isPresent());
        assertEquals(JavdbTitleStagingRow.STATUS_FETCHED, titleRow.get().status());
        assertEquals("deD0v", titleRow.get().javdbSlug());
        assertNotNull(titleRow.get().rawPath());

        // Raw JSON file was written to disk
        Path rawFile = dataDir.resolve(titleRow.get().rawPath());
        assertTrue(Files.exists(rawFile));

        // Actress slug was resolved from cast list
        Optional<JavdbActressStagingRow> actressRow = stagingRepo.findActressStaging(actressId);
        assertTrue(actressRow.isPresent());
        assertEquals("ex3z", actressRow.get().javdbSlug());
        assertEquals("DV-948", actressRow.get().sourceTitleCode());

        // Profile job was auto-enqueued (completion hook); original title job is done
        Optional<EnrichmentJob> profileJob = queue.claimNextJob();
        assertTrue(profileJob.isPresent());
        assertEquals(EnrichmentJob.FETCH_ACTRESS_PROFILE, profileJob.get().jobType());
        assertEquals(actressId, profileJob.get().actressId());
    }

    @Test
    void fetchTitle_marksNotFound_whenSearchReturnsNoResults() throws Exception {
        long titleId = jdbi.withHandle(h ->
                h.createUpdate("INSERT INTO titles (code, base_code, label, seq_num) VALUES ('ABC-001', 'ABC', 'ABC', 1)")
                        .executeAndReturnGeneratedKeys("id").mapTo(Long.class).one());
        long actressId = jdbi.withHandle(h ->
                h.createUpdate("INSERT INTO actresses (canonical_name, stage_name, tier, first_seen_at) VALUES ('Test Actress', 'Test Actress', 'LIBRARY', '2024-01-01')")
                        .executeAndReturnGeneratedKeys("id").mapTo(Long.class).one());

        JavdbClient fakeClient = new JavdbClient() {
            @Override public String searchByCode(String code) { return "<html><body>no results</body></html>"; }
            @Override public String fetchTitlePage(String slug) { throw new AssertionError("not expected"); }
            @Override public String fetchActressPage(String slug) { throw new AssertionError("not expected"); }
        };

        EnrichmentRunner runner = new EnrichmentRunner(
                CONFIG, fakeClient, extractor, projector, stagingRepo, queue, titleRepo, actressRepo,
                new AutoPromoter(jdbi));

        queue.enqueueTitle(titleId, actressId);
        runner.runOneStep();

        Optional<JavdbTitleStagingRow> titleRow = stagingRepo.findTitleStaging(titleId);
        assertTrue(titleRow.isPresent());
        assertEquals(JavdbTitleStagingRow.STATUS_NOT_FOUND, titleRow.get().status());
        assertEquals(0, queue.countPending());
    }

    private String loadFixture(String name) throws IOException, URISyntaxException {
        var url = getClass().getClassLoader().getResource("javdb/" + name);
        assertNotNull(url, "fixture not found: " + name);
        return Files.readString(Path.of(url.toURI()), StandardCharsets.UTF_8);
    }
}
