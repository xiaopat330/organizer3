package com.organizer3.javdb.enrichment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.organizer3.db.SchemaInitializer;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class JavdbStagingRepositoryTest {

    private JavdbStagingRepository repo;
    private Connection connection;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        Jdbi jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        repo = new JavdbStagingRepository(jdbi, new ObjectMapper(), tempDir);
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    // --- title staging ---

    @Test
    void upsertTitle_insertsNewRow() {
        JavdbTitleStagingRow row = fetchedRow(1L, "abcd1");
        repo.upsertTitle(row);

        Optional<JavdbTitleStagingRow> found = repo.findTitleStaging(1L);
        assertTrue(found.isPresent());
        assertEquals("abcd1", found.get().javdbSlug());
        assertEquals("fetched", found.get().status());
        assertEquals("年下の男の子", found.get().titleOriginal());
    }

    @Test
    void upsertTitle_updatesExistingRow() {
        repo.upsertTitle(fetchedRow(1L, "abcd1"));
        JavdbTitleStagingRow updated = new JavdbTitleStagingRow(
                1L, "fetched", "abcd2", "path/to/file.json", "2024-01-01T00:00:00Z",
                "新タイトル", "2024-03-01", 120, "Maker2", null, null,
                3.8, 10, null, null, null, null);
        repo.upsertTitle(updated);

        Optional<JavdbTitleStagingRow> found = repo.findTitleStaging(1L);
        assertTrue(found.isPresent());
        assertEquals("abcd2", found.get().javdbSlug());
        assertEquals("新タイトル", found.get().titleOriginal());
    }

    @Test
    void upsertTitleNotFound_setsNotFoundStatus() {
        repo.upsertTitleNotFound(42L);

        Optional<JavdbTitleStagingRow> found = repo.findTitleStaging(42L);
        assertTrue(found.isPresent());
        assertEquals("not_found", found.get().status());
        assertNull(found.get().javdbSlug());
    }

    @Test
    void findTitleStaging_returnsEmptyWhenAbsent() {
        assertTrue(repo.findTitleStaging(999L).isEmpty());
    }

    // --- actress staging ---

    @Test
    void upsertActressSlugOnly_insertsSlugOnlyRow() {
        repo.upsertActressSlugOnly(10L, "ex3z", "DV-948");

        Optional<JavdbActressStagingRow> found = repo.findActressStaging(10L);
        assertTrue(found.isPresent());
        assertEquals("ex3z", found.get().javdbSlug());
        assertEquals("slug_only", found.get().status());
        assertEquals("DV-948", found.get().sourceTitleCode());
        assertNull(found.get().rawPath());
    }

    @Test
    void upsertActressSlugOnly_updatesSlugOnExistingRow() {
        repo.upsertActressSlugOnly(10L, "ex3z", "DV-948");
        repo.upsertActressSlugOnly(10L, "ex3z", "DV-100"); // updated source

        Optional<JavdbActressStagingRow> found = repo.findActressStaging(10L);
        assertTrue(found.isPresent());
        assertEquals("DV-100", found.get().sourceTitleCode());
    }

    @Test
    void upsertActress_insertsFetchedRow() {
        JavdbActressStagingRow row = new JavdbActressStagingRow(
                10L, "ex3z", "DV-948", "fetched",
                "javdb_raw/actress/ex3z.json", "2024-01-01T00:00:00Z",
                "[\"麻美ゆま\"]", "https://cdn.example.com/avatar.jpg",
                "yuma_asami", "yuma_asami_ig", 870, null);
        repo.upsertActress(row);

        Optional<JavdbActressStagingRow> found = repo.findActressStaging(10L);
        assertTrue(found.isPresent());
        assertEquals("fetched", found.get().status());
        assertEquals("[\"麻美ゆま\"]", found.get().nameVariantsJson());
        assertEquals(870, found.get().titleCount());
        assertNull(found.get().localAvatarPath());
    }

    @Test
    void updateLocalAvatarPath_setsPath() {
        JavdbActressStagingRow row = new JavdbActressStagingRow(
                11L, "ex3z", "DV-948", "fetched",
                "javdb_raw/actress/ex3z.json", "2024-01-01T00:00:00Z",
                null, "https://cdn.example.com/avatar.jpg", null, null, 0, null);
        repo.upsertActress(row);

        repo.updateLocalAvatarPath(11L, "actress-avatars/ex3z.jpg");

        Optional<JavdbActressStagingRow> found = repo.findActressStaging(11L);
        assertTrue(found.isPresent());
        assertEquals("actress-avatars/ex3z.jpg", found.get().localAvatarPath());
    }

    @Test
    void upsertActress_preservesLocalAvatarPathOnConflict() {
        JavdbActressStagingRow first = new JavdbActressStagingRow(
                12L, "abcd", null, "fetched", null, null, null,
                "https://cdn.example.com/a.jpg", null, null, 0, null);
        repo.upsertActress(first);
        repo.updateLocalAvatarPath(12L, "actress-avatars/abcd.jpg");

        // Simulate a re-fetch — the new row carries no localAvatarPath, but the existing
        // value should be preserved (download is idempotent and runs separately).
        repo.upsertActress(new JavdbActressStagingRow(
                12L, "abcd", null, "fetched", "newpath.json", "2024-02-01T00:00:00Z", null,
                "https://cdn.example.com/a.jpg", null, null, 0, null));

        assertEquals("actress-avatars/abcd.jpg",
                repo.findActressStaging(12L).orElseThrow().localAvatarPath());
    }

    @Test
    void findActressStaging_returnsEmptyWhenAbsent() {
        assertTrue(repo.findActressStaging(999L).isEmpty());
    }

    // --- raw file I/O ---

    @Test
    void saveTitleRaw_writesJsonAndReturnsRelativePath() {
        TitleExtract extract = new TitleExtract(
                "DV-948", "deD0v", "テスト", "2008-09-12", 180,
                "Maker", "Publisher", null, 4.5, 29,
                List.of("Solowork"), List.of(), "https://cover.jpg", List.of(), "2024-01-01T00:00:00Z",
                false, false);

        String relPath = repo.saveTitleRaw("deD0v", extract);
        assertEquals("javdb_raw/title/deD0v.json", relPath);
        assertTrue(tempDir.resolve(relPath).toFile().exists());
    }

    @Test
    void saveActressRaw_writesJsonAndReturnsRelativePath() {
        ActressExtract extract = new ActressExtract(
                "ex3z", List.of("麻美ゆま"), "https://avatar.jpg", "tw_handle", null, 870, "2024-01-01T00:00:00Z");

        String relPath = repo.saveActressRaw("ex3z", extract);
        assertEquals("javdb_raw/actress/ex3z.json", relPath);
        assertTrue(tempDir.resolve(relPath).toFile().exists());
    }

    // --- helpers ---

    private JavdbTitleStagingRow fetchedRow(long titleId, String slug) {
        return new JavdbTitleStagingRow(
                titleId, "fetched", slug, "javdb_raw/title/" + slug + ".json", "2024-01-01T00:00:00Z",
                "年下の男の子", "2008-09-12", 180, "アリスJAPAN", "デジタルヴィデオ", null,
                4.5, 29, "[\"Solowork\"]", "[]", "https://cover.jpg", "[]");
    }
}
