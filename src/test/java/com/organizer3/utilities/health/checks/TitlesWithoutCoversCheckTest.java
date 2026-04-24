package com.organizer3.utilities.health.checks;

import com.organizer3.covers.CoverPath;
import com.organizer3.db.SchemaInitializer;
import com.organizer3.model.Title;
import com.organizer3.model.TitleLocation;
import com.organizer3.repository.jdbi.JdbiTitleLocationRepository;
import com.organizer3.repository.jdbi.JdbiTitleRepository;
import com.organizer3.utilities.health.LibraryHealthCheck;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class TitlesWithoutCoversCheckTest {

    @TempDir Path tempDir;

    private Connection connection;
    private JdbiTitleRepository titleRepo;
    private JdbiTitleLocationRepository locationRepo;
    private CoverPath coverPath;
    private TitlesWithoutCoversCheck check;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        Jdbi jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        jdbi.useHandle(h -> h.execute(
                "INSERT INTO volumes (id, structure_type) VALUES ('vol-a', 'conventional')"));
        locationRepo = new JdbiTitleLocationRepository(jdbi);
        titleRepo = new JdbiTitleRepository(jdbi, locationRepo);
        coverPath = new CoverPath(tempDir);
        check = new TitlesWithoutCoversCheck(titleRepo, locationRepo, coverPath);
    }

    @AfterEach
    void tearDown() throws Exception { connection.close(); }

    @Test
    void reportsZeroWhenAllTitlesHaveCovers() throws Exception {
        Title t = saveTitle("ABP-001");
        writeCover(t, "jpg");

        LibraryHealthCheck.CheckResult result = check.run();
        assertEquals(0, result.total());
    }

    @Test
    void flagsTitleWhenCoverIsMissing() {
        saveTitle("ABP-002");
        // No cover written.

        LibraryHealthCheck.CheckResult result = check.run();
        assertEquals(1, result.total());
        assertEquals("ABP-002", result.rows().get(0).label());
        assertTrue(result.rows().get(0).detail().contains("expected"));
    }

    /**
     * Rule 3 false-positive guard: a title WITH a cover in a non-default extension
     * (.png, .webp) must not be flagged just because .jpg doesn't exist — CoverPath.find
     * probes all image extensions, and the health check must respect that.
     */
    @Test
    void nonJpgCoverExtensionIsRecognized() throws Exception {
        Title t = saveTitle("ABP-003");
        writeCover(t, "webp");

        LibraryHealthCheck.CheckResult result = check.run();
        assertEquals(0, result.total(), "webp cover must count as present");
    }

    @Test
    void mixedLibraryShowsOnlyMissing() throws Exception {
        Title withCover = saveTitle("ABP-004");
        writeCover(withCover, "jpg");
        Title noCover = saveTitle("ABP-005");

        LibraryHealthCheck.CheckResult result = check.run();
        assertEquals(1, result.total());
        assertEquals(String.valueOf(noCover.getId()), result.rows().get(0).id());
    }

    private Title saveTitle(String code) {
        String label = code.split("-")[0];
        String base = label + "-00" + code.split("-")[1];
        Title t = titleRepo.save(Title.builder().code(code).baseCode(base).label(label).seqNum(1).build());
        locationRepo.save(TitleLocation.builder()
                .titleId(t.getId()).volumeId("vol-a").partitionId("queue")
                .path(Path.of("/queue/" + code)).lastSeenAt(LocalDate.now()).build());
        return t;
    }

    private void writeCover(Title t, String ext) throws Exception {
        Path dir = coverPath.labelDir(t);
        Files.createDirectories(dir);
        Files.writeString(coverPath.resolve(t, ext), "cover-stub");
    }
}
