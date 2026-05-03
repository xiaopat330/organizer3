package com.organizer3.javdb.enrichment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.organizer3.db.SchemaInitializer;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AutoPromoterTest {

    private Connection connection;
    private Jdbi jdbi;
    private AutoPromoter promoter;
    private EnrichmentReviewQueueRepository reviewQueueRepo;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        reviewQueueRepo = new EnrichmentReviewQueueRepository(jdbi);
        promoter = new AutoPromoter(jdbi, reviewQueueRepo);
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
                "INSERT INTO title_javdb_enrichment (title_id, javdb_slug, fetched_at, title_original, release_date) " +
                "VALUES (?, 'slug-' || ?, '2026-04-25T00:00:00Z', ?, ?)",
                titleId, titleId, titleOriginal, releaseDate));
    }

    private void insertTitleStagingWithCast(long titleId, String castJson) {
        jdbi.useHandle(h -> h.execute(
                "INSERT INTO title_javdb_enrichment (title_id, javdb_slug, fetched_at, cast_json) " +
                "VALUES (?, 'slug-' || ?, '2026-04-25T00:00:00Z', ?)",
                titleId, titleId, castJson));
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
                "UPDATE title_javdb_enrichment SET cast_json = ? WHERE title_id = ?",
                """
                [{"slug":"YSLUG","name":"相田ゆあ","gender":"female"}]
                """, t));

        promoter.promoteFromTitle(t, a);

        assertEquals("相田ゆあ作品", getTitleOriginal(t));
        assertEquals("2023-06-01", getReleaseDate(t));
        assertEquals("相田ゆあ", getStageName(a));
    }

    // ── promoteFromActressProfile ──────────────────────────────────────────

    /** Helper: insert a javdb_actress_staging row with name_variants_json. */
    private void insertActressStagingWithVariants(long actressId, String nameVariantsJson) {
        jdbi.useHandle(h -> h.execute(
                "INSERT INTO javdb_actress_staging (actress_id, javdb_slug, status, name_variants_json) " +
                "VALUES (?, 'TEST-SLUG', 'profile_fetched', ?)",
                actressId, nameVariantsJson));
    }

    /** Helper: insert a title and link it to an actress (needed for Rule 3 review queue row). */
    private long insertTitleForActress(long actressId, String code) {
        long titleId = insertTitle(code, null, null);
        linkActressTitle(actressId, titleId);
        return titleId;
    }

    /** Helper: count open review queue rows with a given reason for a title. */
    private int countReviewQueueRows(long titleId, String reason) {
        return jdbi.withHandle(h -> h.createQuery(
                "SELECT COUNT(*) FROM enrichment_review_queue WHERE title_id = ? AND reason = ? AND resolved_at IS NULL")
                .bind(0, titleId).bind(1, reason)
                .mapTo(Integer.class).one());
    }

    /** Helper: load detail JSON from the first review queue row with a given reason. */
    private String loadReviewQueueDetail(long titleId, String reason) {
        return jdbi.withHandle(h -> h.createQuery(
                "SELECT detail FROM enrichment_review_queue WHERE title_id = ? AND reason = ? AND resolved_at IS NULL LIMIT 1")
                .bind(0, titleId).bind(1, reason)
                .mapTo(String.class).findOne().orElse(null));
    }

    @Test
    void promoteFromActressProfile_rule1_fillsNullStageName() {
        long a = insertActress("Rei Amami", null);
        insertActressStagingWithVariants(a, "[\"天海麗\",\"天海れい\"]");

        promoter.promoteFromActressProfile(a);

        assertEquals("天海麗", getStageName(a));
    }

    @Test
    void promoteFromActressProfile_rule2_correctsRomanizedStageName() {
        long a = insertActress("Rei Amami", "Rei Amami");
        insertActressStagingWithVariants(a, "[\"天海麗\",\"天海れい\"]");

        promoter.promoteFromActressProfile(a);

        assertEquals("天海麗", getStageName(a), "romanized stage_name must be replaced with kanji from variants");
    }

    @Test
    void promoteFromActressProfile_rule2_correctsPartialRomanizedStageName() {
        // Stage name has romaji but no CJK — Rule 2 applies
        long a = insertActress("Yua Aida", "Yua");
        insertActressStagingWithVariants(a, "[\"相田ゆあ\"]");

        promoter.promoteFromActressProfile(a);

        assertEquals("相田ゆあ", getStageName(a));
    }

    @Test
    void promoteFromActressProfile_rule3_happyPath_kanjiMatchesVariant_isNoop() {
        long a = insertActress("Aoi Sora", "蒼井そら");
        long t = insertTitleForActress(a, "TST-101");
        insertActressStagingWithVariants(a, "[\"蒼井そら\",\"Sora Aoi\"]");

        promoter.promoteFromActressProfile(a);

        // Stage name unchanged — it matched a variant
        assertEquals("蒼井そら", getStageName(a));
        // No review queue row
        assertEquals(0, countReviewQueueRows(t, "stage_name_conflict"));
    }

    @Test
    void promoteFromActressProfile_rule3_conflict_kanjiDoesNotMatchAnyVariant() {
        long a = insertActress("Conflict Actress", "天海麗");
        long t = insertTitleForActress(a, "TST-102");
        // Variants contain a different name — conflict
        insertActressStagingWithVariants(a, "[\"天海れい\",\"Rei Amami\"]");

        promoter.promoteFromActressProfile(a);

        // Stage name NOT overwritten
        assertEquals("天海麗", getStageName(a));
        // Review queue row inserted with correct reason
        assertEquals(1, countReviewQueueRows(t, "stage_name_conflict"));
        // Detail JSON contains our_stage_name and javdb_variants
        String detail = loadReviewQueueDetail(t, "stage_name_conflict");
        assertNotNull(detail);
        assertTrue(detail.contains("天海麗"), "detail must include our_stage_name");
        assertTrue(detail.contains("天海れい"), "detail must include javdb variants");
    }

    @Test
    void promoteFromActressProfile_rule3_conflict_detailJsonShape() throws Exception {
        long a = insertActress("Detail Shape Test", "古川いおり");
        long t = insertTitleForActress(a, "TST-103");
        insertActressStagingWithVariants(a, "[\"古川イオリ\",\"Iori Kogawa\"]");

        promoter.promoteFromActressProfile(a);

        String detail = loadReviewQueueDetail(t, "stage_name_conflict");
        assertNotNull(detail);
        Map<?, ?> parsed = new ObjectMapper().readValue(detail, Map.class);
        assertEquals("古川いおり", parsed.get("our_stage_name"));
        assertTrue(parsed.containsKey("javdb_variants"), "must have javdb_variants key");
    }

    @Test
    void promoteFromActressProfile_emptyVariants_isNoop() {
        long a = insertActress("Empty Variants", "SomeName");
        insertActressStagingWithVariants(a, "[]");

        promoter.promoteFromActressProfile(a);

        // No change — empty variants is always a no-op
        assertEquals("SomeName", getStageName(a));
    }

    @Test
    void promoteFromActressProfile_noStagingRow_isNoop() {
        // Actress has no javdb_actress_staging row at all
        long a = insertActress("No Staging", "NoChange");

        promoter.promoteFromActressProfile(a);

        assertEquals("NoChange", getStageName(a));
    }
}
