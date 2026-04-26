package com.organizer3.web.integration;

import com.organizer3.ai.ActressNameLookup;
import com.organizer3.covers.CoverPath;
import com.organizer3.db.SchemaInitializer;
import com.organizer3.model.Actress;
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
import java.time.LocalDateTime;
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
        Map<String, String> volumeSmbPaths = Map.of(
                "vol-a", "//pandora/jav_A",
                "vol-b", "//pandora/jav_B");

        // No stubbing needed — Mockito default returns for Optional are Optional.empty().
        ActressNameLookup nameLookup = mock(ActressNameLookup.class);

        TitleBrowseService titleBrowse = new TitleBrowseService(
                titleRepo, actressRepo, coverPath, labelRepo,
                titleActressRepo, watchHistoryRepo, volumeSmbPaths);

        ActressBrowseService actressBrowse = new ActressBrowseService(
                actressRepo, titleRepo, coverPath, volumeSmbPaths,
                labelRepo, nameLookup, null, jdbi);

        SearchService searchService = new SearchService(
                actressRepo, titleRepo, labelRepo, coverPath, null);

        this.server = new WebServer(0, titleBrowse, actressBrowse, null,
                null, null, null, watchHistoryRepo, titleRepo, searchService);
        this.server.registerActressMerge(
                new com.organizer3.web.routes.ActressMergeRoutes(jdbi, actressRepo));
        this.server.start();
    }

    public int port() { return server.port(); }

    public String baseUrl() { return "http://localhost:" + port(); }

    /** Exposed so {@link FixtureBuilder} can issue raw INSERTs for tables without a repo. */
    Jdbi jdbi() { return jdbi; }

    /** Start a fluent fixture — see {@link FixtureBuilder}. */
    public FixtureBuilder fixture() { return new FixtureBuilder(this); }

    /**
     * Seeds a diverse dataset that exercises filters, aggregations, and join paths
     * the minimal fixture can't: multi-actress titles, multi-location dedup,
     * tier/prefix variety, favorite/bookmark/rejected flag states, tags, and
     * visit/watch history.
     *
     * <p>Counts are deliberately small and asymmetric so tests can assert on
     * exact numbers (e.g. "favorites endpoint returns 2").
     */
    public RichSeed seedRich() {
        FixtureBuilder.Fixtures f = fixture()
                // Company names align with src/main/resources/studios.yaml so
                // studio-group endpoints return non-empty results.
                .label("ABP", "Prestige")
                .label("SSIS", "S1 No.1 Style")
                .label("MIDV", "Moodyz")
                .tag("creampie", "sexact")
                .tag("solowork", "structural")
                .tag("bigtits", "body")
                // Actresses: 5 across 4 prefixes (A, A, Y, M, R), all tiers except MINOR.
                .actress("aya",   a -> a.canonical("Aya Sazanami").tier(Actress.Tier.GODDESS)
                                        .firstSeen(LocalDate.of(2024, 1, 1)).favorite())
                .actress("ayumi", a -> a.canonical("Ayumi Kimito").tier(Actress.Tier.SUPERSTAR)
                                        .firstSeen(LocalDate.of(2024, 1, 2)).bookmark())
                .actress("yua",   a -> a.canonical("Yua Mikami").tier(Actress.Tier.GODDESS)
                                        .firstSeen(LocalDate.of(2023, 1, 1)).favorite())
                .actress("mio",   a -> a.canonical("Mio Kimijima").tier(Actress.Tier.POPULAR)
                                        .firstSeen(LocalDate.of(2023, 6, 1)))
                .actress("rej",   a -> a.canonical("Rejected Rika").tier(Actress.Tier.LIBRARY)
                                        .firstSeen(LocalDate.of(2022, 1, 1)).rejected())
                // Titles
                .title("abp001",  t -> t.code("ABP-001").baseCode("ABP-00001").label("ABP")
                                        .seqNum(1).actress("aya").titleEnglish("ABP 001").favorite())
                .title("abp002",  t -> t.code("ABP-002").baseCode("ABP-00002").label("ABP")
                                        .seqNum(2).actress("aya").titleEnglish("ABP 002 co-star"))
                .title("ssis100", t -> t.code("SSIS-100").baseCode("SSIS-00100").label("SSIS")
                                        .seqNum(100).actress("yua").titleEnglish("SSIS 100").bookmark())
                .title("ssis200", t -> t.code("SSIS-200").baseCode("SSIS-00200").label("SSIS")
                                        .seqNum(200).actress("yua").titleEnglish("SSIS 200"))
                .title("midv050", t -> t.code("MIDV-050").baseCode("MIDV-00050").label("MIDV")
                                        .seqNum(50).actress("mio").titleEnglish("MIDV 050"))
                .title("midv100", t -> t.code("MIDV-100").baseCode("MIDV-00100").label("MIDV")
                                        .seqNum(100).actress("mio").titleEnglish("MIDV 100 tagged"))
                .coStar("abp002", "ayumi")
                // Label-inherited tag. Must come after ABP titles exist so recompute picks them up.
                .labelTag("ABP", "bigtits")
                // Locations — ABP-002 has two (dedup test), others have one.
                .location("abp001",  "vol-a", "stars",   "/stars/popular/Aya Sazanami/ABP-001", LocalDate.of(2024, 1, 15))
                .location("abp002",  "vol-a", "stars",   "/stars/popular/Aya Sazanami/ABP-002", LocalDate.of(2024, 2, 1))
                .location("abp002",  "vol-b", "archive", "/archive/dup/ABP-002",                LocalDate.of(2024, 2, 5))
                .location("ssis100", "vol-a", "stars",   "/stars/popular/Yua Mikami/SSIS-100",  LocalDate.of(2024, 3, 1))
                .location("ssis200", "vol-a", "stars",   "/stars/popular/Yua Mikami/SSIS-200",  LocalDate.of(2023, 4, 20))
                .location("midv050", "vol-a", "queue",   "/queue/MIDV-050",                     LocalDate.of(2024, 6, 1))
                .location("midv100", "vol-a", "queue",   "/queue/MIDV-100",                     LocalDate.of(2024, 7, 1))
                // Title-level tags (creampie + solowork on MIDV-100).
                .titleTags("midv100", "creampie", "solowork")
                // Visit / watch history
                .visit("midv050")
                .visitActress("mio")
                .watchHistory("midv050", LocalDateTime.of(2024, 6, 15, 20, 0))
                .build();

        return new RichSeed(
                f.actressId("aya"), f.actressId("ayumi"), f.actressId("yua"),
                f.actressId("mio"), f.actressId("rej"),
                f.titleCode("abp001"), f.titleCode("abp002"), f.titleCode("ssis100"),
                f.titleCode("ssis200"), f.titleCode("midv050"), f.titleCode("midv100"));
    }

    /** Seeds a single label, actress, and title with one location. */
    public SeedData seedMinimal() {
        FixtureBuilder.Fixtures f = fixture()
                .label("ABP", "Prestige")
                .actress("aya", a -> a.canonical("Aya Sazanami").tier(Actress.Tier.GODDESS)
                                      .firstSeen(LocalDate.of(2024, 1, 1)))
                .title("abp001", t -> t.code("ABP-001").baseCode("ABP-00001").label("ABP")
                                       .seqNum(1).actress("aya").titleEnglish("The First Title"))
                .location("abp001", "vol-a", "stars",
                        "/stars/popular/Aya Sazanami/ABP-001", LocalDate.of(2024, 1, 15))
                .build();

        return new SeedData(f.actressId("aya"), f.titleCode("abp001"));
    }

    @Override
    public void close() throws Exception {
        if (server != null) server.stop();
        if (connection != null) connection.close();
    }

    public record SeedData(long actressId, String titleCode) {}

    public record RichSeed(
            long ayaId, long ayumiId, long yuaId, long mioId, long rejectedId,
            String abp001, String abp002, String ssis100,
            String ssis200, String midv050, String midv100) {}
}
