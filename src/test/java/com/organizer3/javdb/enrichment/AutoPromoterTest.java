package com.organizer3.javdb.enrichment;

import com.organizer3.db.SchemaInitializer;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.*;

class AutoPromoterTest {

    private Connection connection;
    private Jdbi jdbi;
    private AutoPromoter promoter;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        promoter = new AutoPromoter(jdbi);
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private long insertActress(String canonicalName, String stageName) {
        return jdbi.withHandle(h ->
                h.createUpdate("INSERT INTO actresses (canonical_name, stage_name, tier, first_seen_at) " +
                               "VALUES (:cn, :sn, 'LIBRARY', '2024-01-01')")
                        .bind("cn", canonicalName)
                        .bind("sn", stageName)
                        .executeAndReturnGeneratedKeys("id").mapTo(Long.class).one());
    }

    private long insertTitle(String code, String titleOriginal, String releaseDate) {
        return jdbi.withHandle(h ->
                h.createUpdate("INSERT INTO titles (code, base_code, label, seq_num, title_original, release_date) " +
                               "VALUES (:c, :c, 'TST', 1, :to, :rd)")
                        .bind("c", code)
                        .bind("to", titleOriginal)
                        .bind("rd", releaseDate)
                        .executeAndReturnGeneratedKeys("id").mapTo(Long.class).one());
    }

    private void insertTitleStaging(long titleId, String titleOriginal, String releaseDate) {
        jdbi.useHandle(h -> h.execute(
                "INSERT INTO javdb_title_staging (title_id, status, title_original, release_date) " +
                "VALUES (?, 'fetched', ?, ?)",
                titleId, titleOriginal, releaseDate));
    }

    private void insertTitleStagingWithCast(long titleId, String castJson) {
        jdbi.useHandle(h -> h.execute(
                "INSERT INTO javdb_title_staging (title_id, status, cast_json) " +
                "VALUES (?, 'fetched', ?)",
                titleId, castJson));
    }

    private void insertActressStaging(long actressId, String slug) {
        jdbi.useHandle(h -> h.execute(
                "INSERT INTO javdb_actress_staging (actress_id, javdb_slug, status) VALUES (?, ?, 'slug_only')",
                actressId, slug));
    }

    private void linkActressTitle(long actressId, long titleId) {
        jdbi.useHandle(h -> h.execute(
                "INSERT INTO title_actresses (actress_id, title_id) VALUES (?, ?)", actressId, titleId));
    }

    private String getStageName(long actressId) {
        return jdbi.withHandle(h -> h.createQuery("SELECT stage_name FROM actresses WHERE id = ?")
                .bind(0, actressId).mapTo(String.class).one());
    }

    private String getTitleOriginal(long titleId) {
        return jdbi.withHandle(h -> h.createQuery("SELECT title_original FROM titles WHERE id = ?")
                .bind(0, titleId).mapTo(String.class).findOne().orElse(null));
    }

    private String getReleaseDate(long titleId) {
        return jdbi.withHandle(h -> h.createQuery("SELECT release_date FROM titles WHERE id = ?")
                .bind(0, titleId).mapTo(String.class).findOne().orElse(null));
    }

    // ── title_original ─────────────────────────────────────────────────────

    @Test
    void promoteFromTitle_setsTitleOriginalWhenNull() {
        long a = insertActress("Test", "テスト");
        long t = insertTitle("TST-001", null, null);
        insertTitleStaging(t, "テスト作品", "2024-01-15");

        promoter.promoteFromTitle(t, a);

        assertEquals("テスト作品", getTitleOriginal(t));
    }

    @Test
    void promoteFromTitle_doesNotOverwriteExistingTitleOriginal() {
        long a = insertActress("Test", "テスト");
        long t = insertTitle("TST-002", "Already Set", null);
        insertTitleStaging(t, "Staged Title", "2024-01-15");

        promoter.promoteFromTitle(t, a);

        assertEquals("Already Set", getTitleOriginal(t));
    }

    // ── release_date ───────────────────────────────────────────────────────

    @Test
    void promoteFromTitle_setsReleaseDateWhenNull() {
        long a = insertActress("Test", "テスト");
        long t = insertTitle("TST-003", null, null);
        insertTitleStaging(t, null, "2024-03-20");

        promoter.promoteFromTitle(t, a);

        assertEquals("2024-03-20", getReleaseDate(t));
    }

    @Test
    void promoteFromTitle_doesNotOverwriteExistingReleaseDate() {
        long a = insertActress("Test", "テスト");
        long t = insertTitle("TST-004", null, "2023-01-01");
        insertTitleStaging(t, null, "2024-03-20");

        promoter.promoteFromTitle(t, a);

        assertEquals("2023-01-01", getReleaseDate(t));
    }

    // ── stage_name ─────────────────────────────────────────────────────────

    @Test
    void promoteActressStageName_setsStageNameFromMatchingCastEntry() {
        long a = insertActress("Aoi Sora", null);
        long t = insertTitle("TST-005", null, null);
        linkActressTitle(a, t);
        insertActressStaging(a, "8ORE");
        insertTitleStagingWithCast(t, """
                [{"slug":"8ORE","name":"蒼井そら","gender":"female"},
                 {"slug":"OTHER","name":"Other Name","gender":"female"}]
                """);

        promoter.promoteActressStageName(a);

        assertEquals("蒼井そら", getStageName(a));
    }

    @Test
    void promoteActressStageName_doesNotOverwriteExistingStageName() {
        long a = insertActress("Aoi Sora", "既存名");
        long t = insertTitle("TST-006", null, null);
        linkActressTitle(a, t);
        insertActressStaging(a, "8ORE");
        insertTitleStagingWithCast(t, """
                [{"slug":"8ORE","name":"蒼井そら","gender":"female"}]
                """);

        promoter.promoteActressStageName(a);

        assertEquals("既存名", getStageName(a));
    }

    @Test
    void promoteActressStageName_doesNothingWhenNoSlugMatch() {
        long a = insertActress("Unknown", null);
        long t = insertTitle("TST-007", null, null);
        linkActressTitle(a, t);
        insertActressStaging(a, "MYSLUG");
        insertTitleStagingWithCast(t, """
                [{"slug":"OTHERSLUG","name":"Other","gender":"female"}]
                """);

        promoter.promoteActressStageName(a);

        assertNull(getStageName(a));
    }

    @Test
    void promoteActressStageName_doesNothingWhenActressHasNoStagingRow() {
        long a = insertActress("No Staging", null);
        long t = insertTitle("TST-008", null, null);
        linkActressTitle(a, t);
        insertTitleStagingWithCast(t, """
                [{"slug":"ANYSLUG","name":"Someone","gender":"female"}]
                """);

        promoter.promoteActressStageName(a);

        assertNull(getStageName(a));
    }

    @Test
    void promoteFromTitle_promotesStageNameAlongWithTitleFields() {
        long a = insertActress("Yua Aida", null);
        long t = insertTitle("TST-009", null, null);
        linkActressTitle(a, t);
        insertActressStaging(a, "YSLUG");
        insertTitleStaging(t, "相田ゆあ作品", "2023-06-01");
        // Cast is in the staging row already — update it to add cast_json
        jdbi.useHandle(h -> h.execute(
                "UPDATE javdb_title_staging SET cast_json = ? WHERE title_id = ?",
                """
                [{"slug":"YSLUG","name":"相田ゆあ","gender":"female"}]
                """, t));

        promoter.promoteFromTitle(t, a);

        assertEquals("相田ゆあ作品", getTitleOriginal(t));
        assertEquals("2023-06-01", getReleaseDate(t));
        assertEquals("相田ゆあ", getStageName(a));
    }
}
