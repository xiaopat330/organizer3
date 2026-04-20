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
import java.time.LocalDateTime;
import java.util.List;
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
                labelRepo, nameLookup, null);

        SearchService searchService = new SearchService(
                actressRepo, titleRepo, labelRepo, coverPath, null);

        this.server = new WebServer(0, titleBrowse, actressBrowse, null,
                null, null, null, watchHistoryRepo, titleRepo, searchService);
        this.server.start();
    }

    public int port() { return server.port(); }

    public String baseUrl() { return "http://localhost:" + port(); }

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
        jdbi.useHandle(h -> {
            h.createUpdate("INSERT INTO labels (code, label_name, company) VALUES (:c, :n, :co)")
                    .bind("c", "ABP").bind("n", "ABP Label").bind("co", "Prestige").execute();
            h.createUpdate("INSERT INTO labels (code, label_name, company) VALUES (:c, :n, :co)")
                    .bind("c", "SSIS").bind("n", "SSIS Label").bind("co", "S1").execute();
            h.createUpdate("INSERT INTO labels (code, label_name, company) VALUES (:c, :n, :co)")
                    .bind("c", "MIDV").bind("n", "MIDV Label").bind("co", "Moodyz").execute();
        });

        // Actresses: 5 across 4 prefixes (A, A, Y, M, R) and all tiers except MINOR.
        Actress aya = actressRepo.save(Actress.builder()
                .canonicalName("Aya Sazanami").tier(Actress.Tier.GODDESS)
                .firstSeenAt(LocalDate.of(2024, 1, 1)).build());
        Actress ayumi = actressRepo.save(Actress.builder()
                .canonicalName("Ayumi Kimito").tier(Actress.Tier.SUPERSTAR)
                .firstSeenAt(LocalDate.of(2024, 1, 2)).build());
        Actress yua = actressRepo.save(Actress.builder()
                .canonicalName("Yua Mikami").tier(Actress.Tier.GODDESS)
                .firstSeenAt(LocalDate.of(2023, 1, 1)).build());
        Actress mio = actressRepo.save(Actress.builder()
                .canonicalName("Mio Kimijima").tier(Actress.Tier.POPULAR)
                .firstSeenAt(LocalDate.of(2023, 6, 1)).build());
        Actress rej = actressRepo.save(Actress.builder()
                .canonicalName("Rejected Rika").tier(Actress.Tier.LIBRARY)
                .firstSeenAt(LocalDate.of(2022, 1, 1)).build());

        // Flag variety
        actressRepo.toggleFavorite(aya.getId(), true);
        actressRepo.toggleFavorite(yua.getId(), true);
        actressRepo.toggleBookmark(ayumi.getId(), true);
        actressRepo.toggleRejected(rej.getId(), true);

        // Titles — dates spread so "recent"/"on this day" etc. differ.
        Title abp001 = titleRepo.save(Title.builder()
                .code("ABP-001").baseCode("ABP-00001").label("ABP").seqNum(1)
                .actressId(aya.getId()).titleEnglish("ABP 001")
                .favorite(true).build());
        Title abp002 = titleRepo.save(Title.builder()
                .code("ABP-002").baseCode("ABP-00002").label("ABP").seqNum(2)
                .actressId(aya.getId()).titleEnglish("ABP 002 co-star").build());
        Title ssis100 = titleRepo.save(Title.builder()
                .code("SSIS-100").baseCode("SSIS-00100").label("SSIS").seqNum(100)
                .actressId(yua.getId()).titleEnglish("SSIS 100")
                .bookmark(true).build());
        Title ssis200 = titleRepo.save(Title.builder()
                .code("SSIS-200").baseCode("SSIS-00200").label("SSIS").seqNum(200)
                .actressId(yua.getId()).titleEnglish("SSIS 200").build());
        Title midv050 = titleRepo.save(Title.builder()
                .code("MIDV-050").baseCode("MIDV-00050").label("MIDV").seqNum(50)
                .actressId(mio.getId()).titleEnglish("MIDV 050").build());
        Title midv100 = titleRepo.save(Title.builder()
                .code("MIDV-100").baseCode("MIDV-00100").label("MIDV").seqNum(100)
                .actressId(mio.getId()).titleEnglish("MIDV 100 tagged").build());

        // Co-star: ABP-002 links Aya + Ayumi (via title_actress table).
        titleActressRepo.linkAll(abp002.getId(), List.of(aya.getId(), ayumi.getId()));

        // Locations — ABP-002 has two, others have one. Exercises dedup on list endpoints.
        saveLocation(abp001, "vol-a", "stars", "/stars/popular/Aya Sazanami/ABP-001",
                LocalDate.of(2024, 1, 15));
        saveLocation(abp002, "vol-a", "stars", "/stars/popular/Aya Sazanami/ABP-002",
                LocalDate.of(2024, 2, 1));
        saveLocation(abp002, "vol-b", "archive", "/archive/dup/ABP-002",
                LocalDate.of(2024, 2, 5));
        saveLocation(ssis100, "vol-a", "stars", "/stars/popular/Yua Mikami/SSIS-100",
                LocalDate.of(2024, 3, 1));
        // "On this day" match — added exactly 3 years ago today (2023-04-20 vs today 2026-04-20).
        saveLocation(ssis200, "vol-a", "stars", "/stars/popular/Yua Mikami/SSIS-200",
                LocalDate.of(2023, 4, 20));
        saveLocation(midv050, "vol-a", "queue", "/queue/MIDV-050",
                LocalDate.of(2024, 6, 1));
        saveLocation(midv100, "vol-a", "queue", "/queue/MIDV-100",
                LocalDate.of(2024, 7, 1));

        // Tags on MIDV-100 only. The list endpoint reads title_effective_tags,
        // not title_tags, so recompute the denorm row after the write.
        new com.organizer3.repository.jdbi.JdbiTitleTagRepository(jdbi)
                .replaceTagsForTitle(midv100.getId(), List.of("creampie", "solowork"));
        new com.organizer3.db.TitleEffectiveTagsService(jdbi)
                .recomputeForTitle(midv100.getId());

        // Visit / watch history on MIDV-050.
        titleRepo.recordVisit(midv050.getId());
        actressRepo.recordVisit(mio.getId());
        watchHistoryRepo.record("MIDV-050", LocalDateTime.of(2024, 6, 15, 20, 0));

        return new RichSeed(
                aya.getId(), ayumi.getId(), yua.getId(), mio.getId(), rej.getId(),
                abp001.getCode(), abp002.getCode(), ssis100.getCode(),
                ssis200.getCode(), midv050.getCode(), midv100.getCode());
    }

    private void saveLocation(Title title, String volumeId, String partitionId,
                              String path, LocalDate date) {
        locationRepo.save(TitleLocation.builder()
                .titleId(title.getId())
                .volumeId(volumeId).partitionId(partitionId)
                .path(Path.of(path))
                .lastSeenAt(date).addedDate(date)
                .build());
    }

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

    public record RichSeed(
            long ayaId, long ayumiId, long yuaId, long mioId, long rejectedId,
            String abp001, String abp002, String ssis100,
            String ssis200, String midv050, String midv100) {}
}
