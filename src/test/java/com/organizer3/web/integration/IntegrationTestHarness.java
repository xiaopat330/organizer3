package com.organizer3.web.integration;

import com.organizer3.ai.ActressNameLookup;
import com.organizer3.covers.CoverPath;
import com.organizer3.db.SchemaInitializer;
import com.organizer3.model.Actress;
import com.organizer3.model.Title;
import com.organizer3.model.TitleLocation;
import com.organizer3.repository.jdbi.JdbiActressRepository;
import com.organizer3.repository.jdbi.JdbiLabelRepository;
import com.organizer3.repository.jdbi.JdbiTitleActressRepository;
import com.organizer3.repository.jdbi.JdbiTitleLocationRepository;
import com.organizer3.repository.jdbi.JdbiTitleRepository;
import com.organizer3.repository.jdbi.JdbiWatchHistoryRepository;
import com.organizer3.web.ActressBrowseService;
import com.organizer3.web.SearchService;
import com.organizer3.web.TitleBrowseService;
import com.organizer3.web.WebServer;
import org.jdbi.v3.core.Jdbi;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.Map;

import static org.mockito.Mockito.mock;

/**
 * Integration-test scaffold: real Jdbi + in-memory SQLite + real services +
 * real {@link WebServer}. Wires everything a typical happy-path HTTP flow
 * needs, seeded with a small canned dataset.
 *
 * <p>Each instance owns a fresh SQLite DB on a dedicated {@link Connection}.
 * Callers must {@link #close()} to release resources.
 *
 * <p>This is more work per test than service-mocking tests, but catches
 * bugs those can't: SQL errors, schema drift, service-wiring regressions,
 * JSON-column round-trips, pagination-offset bugs.
 */
public final class IntegrationTestHarness implements AutoCloseable {

    private final Connection connection;
    private final Jdbi jdbi;
    private final WebServer server;

    public final JdbiTitleRepository          titleRepo;
    public final JdbiTitleLocationRepository  locationRepo;
    public final JdbiActressRepository        actressRepo;
    public final JdbiLabelRepository          labelRepo;
    public final JdbiTitleActressRepository   titleActressRepo;
    public final JdbiWatchHistoryRepository   watchHistoryRepo;

    public IntegrationTestHarness() throws Exception {
        this.connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        this.jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();

        this.labelRepo        = new JdbiLabelRepository(jdbi);
        this.actressRepo      = new JdbiActressRepository(jdbi);
        this.locationRepo     = new JdbiTitleLocationRepository(jdbi);
        this.titleRepo        = new JdbiTitleRepository(jdbi, locationRepo);
        this.titleActressRepo = new JdbiTitleActressRepository(jdbi);
        this.watchHistoryRepo = new JdbiWatchHistoryRepository(jdbi);

        CoverPath coverPath = new CoverPath(Path.of("/tmp/nonexistent-covers-" + System.nanoTime()));
        Map<String, String> volumeSmbPaths = Map.of("vol-a", "//pandora/jav_A");

        // No stubbing needed — Mockito default returns for Optional are Optional.empty().
        ActressNameLookup nameLookup = mock(ActressNameLookup.class);

        TitleBrowseService titleBrowse = new TitleBrowseService(
                titleRepo, actressRepo, coverPath, labelRepo,
                titleActressRepo, watchHistoryRepo, volumeSmbPaths);

        ActressBrowseService actressBrowse = new ActressBrowseService(
                actressRepo, titleRepo, coverPath, volumeSmbPaths,
                labelRepo, nameLookup, null);

        SearchService searchService = new SearchService(
                actressRepo, titleRepo, labelRepo, coverPath, null);

        this.server = new WebServer(0, titleBrowse, actressBrowse, null,
                null, null, null, watchHistoryRepo, titleRepo, searchService);
        this.server.start();
    }

    public int port() { return server.port(); }

    public String baseUrl() { return "http://localhost:" + port(); }

    /** Seeds a single label, actress, and title with one location. */
    public SeedData seedMinimal() {
        // Labels are yaml-loaded in production; for the integration test insert directly.
        jdbi.useHandle(h -> h.createUpdate(
                "INSERT INTO labels (code, label_name, company) VALUES (:c, :n, :co)")
                .bind("c", "ABP").bind("n", "ABP Label").bind("co", "Prestige")
                .execute());

        Actress aya = actressRepo.save(Actress.builder()
                .canonicalName("Aya Sazanami")
                .tier(Actress.Tier.GODDESS)
                .firstSeenAt(LocalDate.of(2024, 1, 1))
                .build());

        Title title = titleRepo.save(Title.builder()
                .code("ABP-001").baseCode("ABP-00001").label("ABP").seqNum(1)
                .actressId(aya.getId())
                .titleEnglish("The First Title")
                .build());

        // TitleRepository.save doesn't persist locations — write them separately.
        locationRepo.save(TitleLocation.builder()
                .titleId(title.getId())
                .volumeId("vol-a").partitionId("stars")
                .path(Path.of("/stars/popular/Aya Sazanami/ABP-001"))
                .lastSeenAt(LocalDate.of(2024, 1, 15))
                .addedDate(LocalDate.of(2024, 1, 15))
                .build());

        return new SeedData(aya.getId(), title.getCode());
    }

    @Override
    public void close() throws Exception {
        if (server != null) server.stop();
        if (connection != null) connection.close();
    }

    public record SeedData(long actressId, String titleCode) {}
}
