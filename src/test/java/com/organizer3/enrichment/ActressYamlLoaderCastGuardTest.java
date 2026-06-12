package com.organizer3.enrichment;

import com.organizer3.db.SchemaInitializer;
import com.organizer3.javdb.enrichment.CastPresenceCheck;
import com.organizer3.model.Actress;
import com.organizer3.repository.ActressRepository;
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
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the cast-mismatch guard in {@link ActressYamlLoader}.
 *
 * <p>These tests exercise the guard path added in the portfolio loop: when a
 * title already exists in the DB AND has a {@code title_javdb_enrichment} row
 * with a {@code cast_json}, and the guard is enforced (nfem ≤ 3, not compilation),
 * an ABSENT actress causes a skip rather than a metadata overwrite.
 *
 * <p>The test actress YAML used here is {@code test_actress.yaml}, whose first
 * portfolio code is {@code TEST-001}. The actress's stage_name is {@code テスト女優}.
 */
class ActressYamlLoaderCastGuardTest {

    private static final String NOW = "2026-06-12T00:00:00.000Z";

    private Connection connection;
    private Jdbi jdbi;
    private ActressYamlLoader loader;
    private JdbiActressRepository actressRepo;
    private JdbiTitleRepository titleRepo;
    private JdbiTitleTagRepository tagRepo;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        actressRepo = new JdbiActressRepository(jdbi);
        JdbiTitleLocationRepository locationRepo = new JdbiTitleLocationRepository(jdbi);
        titleRepo = new JdbiTitleRepository(jdbi, locationRepo);
        tagRepo = new JdbiTitleTagRepository(jdbi);
        loader = new ActressYamlLoader(actressRepo, titleRepo, tagRepo, jdbi, new CastPresenceCheck(jdbi));
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /** Insert a minimal actress with the stage_name used in test_actress.yaml. */
    private long insertActress(String canonicalName, String stageName) {
        Actress a = actressRepo.save(Actress.builder()
                .canonicalName(canonicalName)
                .stageName(stageName)
                .tier(Actress.Tier.POPULAR)
                .firstSeenAt(LocalDate.of(2010, 1, 1))
                .build());
        return a.getId();
    }

    /** Insert a minimal title row; returns its generated id. */
    private long insertTitle(String code) {
        return jdbi.withHandle(h ->
                h.createQuery("INSERT INTO titles(code) VALUES (?) RETURNING id")
                        .bind(0, code)
                        .mapTo(Long.class)
                        .one());
    }

    /** Insert a title_javdb_enrichment row with the given cast_json. */
    private void insertEnrichment(long titleId, String castJson) {
        jdbi.useHandle(h ->
                h.execute(
                        "INSERT INTO title_javdb_enrichment(title_id, javdb_slug, fetched_at, cast_json) " +
                        "VALUES (?, 'slug1', ?, ?)",
                        titleId, NOW, castJson));
    }

    /** Build a cast_json with one female entry (actress NOT the test actress). */
    private static String castOtherActress() {
        return "[{\"slug\":\"other\",\"name\":\"全然別人\",\"gender\":\"F\"}]";
    }

    /** Build a cast_json containing the test actress's stage_name. */
    private static String castWithTestActress() {
        return "[{\"slug\":\"ta\",\"name\":\"テスト女優\",\"gender\":\"F\"}]";
    }

    /** Build a cast_json with N female entries (all distinct, NOT the test actress). */
    private static String castNFemale(int n) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(",");
            sb.append("{\"slug\":\"s").append(i).append("\",\"name\":\"別人").append(i).append("\",\"gender\":\"F\"}");
        }
        sb.append("]");
        return sb.toString();
    }

    /** Insert a compilation tag definition + seed the tags table; returns the tag id. */
    private long insertCompilationTagDef() {
        jdbi.useHandle(h ->
                h.execute("INSERT OR IGNORE INTO tags(name, category) VALUES ('compilation', 'format')"));
        return jdbi.withHandle(h ->
                h.createQuery(
                        "INSERT INTO enrichment_tag_definitions(name, curated_alias) VALUES ('まとめ作品', 'compilation') RETURNING id")
                        .mapTo(Long.class)
                        .one());
    }

    /** Tag a title with the given tag id. */
    private void tagTitle(long titleId, long tagId) {
        jdbi.useHandle(h ->
                h.execute("INSERT OR IGNORE INTO title_enrichment_tags(title_id, tag_id) VALUES (?, ?)",
                        titleId, tagId));
    }

    // ─── Tests ───────────────────────────────────────────────────────────────

    /**
     * Core guard case: title exists, is enriched, cast does NOT include the actress,
     * nfem=1 (≤3), no compilation tag → guard enforced.
     * Expected: enrichTitle + replaceTags SKIPPED; unresolvedCodes contains the structured line.
     */
    @Test
    void guardSkipsMetadataWrite_WhenActressAbsentAndEnforced() throws Exception {
        // Pre-create the actress (same canonical name as test_actress.yaml resolves to)
        insertActress("Test Actress", "テスト女優");

        // Pre-create TEST-001 with enrichment that does NOT include the actress
        long titleId = insertTitle("TEST-001");
        insertEnrichment(titleId, castOtherActress());

        ActressYamlLoader.LoadResult result = loader.loadOne("test_actress");

        // Tags must not have been written (replaceTagsForTitle was skipped)
        List<String> tags = tagRepo.findTagsForTitle(titleId);
        assertTrue(tags.isEmpty(), "tags must not have been written for a skipped entry");

        // unresolvedCodes must contain the structured skip line for TEST-001
        assertTrue(result.unresolvedCodes().stream()
                        .anyMatch(s -> s.equals("TEST-001: cast-mismatch — actress kanji not in enriched cast; skipped")),
                "unresolvedCodes must contain structured cast-mismatch line for TEST-001; got: "
                        + result.unresolvedCodes());
    }

    /**
     * Guard does NOT block when the actress IS present in the cast_json.
     * Expected: enrichTitle + replaceTags proceed as normal.
     */
    @Test
    void guardProceeds_WhenActressPresent() throws Exception {
        insertActress("Test Actress", "テスト女優");
        long titleId = insertTitle("TEST-001");
        insertEnrichment(titleId, castWithTestActress());

        ActressYamlLoader.LoadResult result = loader.loadOne("test_actress");

        // Tags must have been written (test_actress.yaml sets debut, hardcore for TEST-001)
        List<String> tags = tagRepo.findTagsForTitle(titleId);
        assertFalse(tags.isEmpty(), "tags must have been written when actress is present in cast");

        // No unresolved codes for TEST-001
        assertTrue(result.unresolvedCodes().stream().noneMatch(s -> s.startsWith("TEST-001")),
                "no cast-mismatch entry expected when actress is present");
    }

    /**
     * Guard is gated out for a compilation-tagged title even when actress is ABSENT.
     * Expected: enrichTitle + replaceTags proceed as normal.
     */
    @Test
    void guardGatedOut_ForCompilationTitle() throws Exception {
        insertActress("Test Actress", "テスト女優");
        long titleId = insertTitle("TEST-001");
        // Cast without test actress, nfem=1 → would normally enforce the guard
        insertEnrichment(titleId, castOtherActress());
        // Tag as compilation → guard gated out
        long tagId = insertCompilationTagDef();
        tagTitle(titleId, tagId);

        ActressYamlLoader.LoadResult result = loader.loadOne("test_actress");

        // Tags must have been written (compilation gate = proceed)
        List<String> tags = tagRepo.findTagsForTitle(titleId);
        // After replaceTagsForTitle the tags from YAML are loaded
        assertFalse(tags.isEmpty(), "tags must have been written when compilation gate disables guard");

        // No cast-mismatch unresolved entry
        assertTrue(result.unresolvedCodes().stream().noneMatch(s -> s.startsWith("TEST-001")),
                "no cast-mismatch entry expected for compilation-tagged title");
    }

    /**
     * Guard is gated out when nfem=5 (> 3).
     * Expected: enrichTitle + replaceTags proceed as normal.
     */
    @Test
    void guardGatedOut_WhenNfemExceedsThreshold() throws Exception {
        insertActress("Test Actress", "テスト女優");
        long titleId = insertTitle("TEST-001");
        // 5 female entries, none is the test actress → would normally enforce but nfem>3
        insertEnrichment(titleId, castNFemale(5));

        ActressYamlLoader.LoadResult result = loader.loadOne("test_actress");

        // Tags must have been written (nfem>3 gate = proceed)
        List<String> tags = tagRepo.findTagsForTitle(titleId);
        assertFalse(tags.isEmpty(), "tags must have been written when nfem>3 disables guard");

        // No cast-mismatch unresolved entry
        assertTrue(result.unresolvedCodes().stream().noneMatch(s -> s.startsWith("TEST-001")),
                "no cast-mismatch entry expected when nfem>3");
    }

    /**
     * Title exists in DB but has NO enrichment row (no cast_json to check against).
     * Expected: enrichTitle + replaceTags proceed as normal (nothing to check).
     */
    @Test
    void guardProceeds_WhenNoEnrichmentRow() throws Exception {
        insertActress("Test Actress", "テスト女優");
        // Title exists but no enrichment row
        insertTitle("TEST-001");

        ActressYamlLoader.LoadResult result = loader.loadOne("test_actress");

        // Tags must have been written
        long titleId = titleRepo.findByCode("TEST-001").orElseThrow().getId();
        List<String> tags = tagRepo.findTagsForTitle(titleId);
        assertFalse(tags.isEmpty(), "tags must have been written when there is no enrichment row");

        assertTrue(result.unresolvedCodes().isEmpty(),
                "no unresolved codes when there is no enrichment to check against");
    }

    /**
     * Code not in DB → stub is created. Guard never fires for stub creation.
     * Expected: titlesCreated incremented; no cast-mismatch entry.
     */
    @Test
    void stubCreation_NotBlocked_WhenCodeMissing() throws Exception {
        insertActress("Test Actress", "テスト女優");
        // Do NOT pre-create TEST-001 or TEST-002 → both will be created as stubs

        ActressYamlLoader.LoadResult result = loader.loadOne("test_actress");

        assertEquals(2, result.titlesCreated(), "both TEST-001 and TEST-002 should be stubs");
        assertTrue(result.unresolvedCodes().isEmpty(),
                "stub creation must never produce a cast-mismatch entry");
    }
}
