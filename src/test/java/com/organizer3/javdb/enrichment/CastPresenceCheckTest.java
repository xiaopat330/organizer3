package com.organizer3.javdb.enrichment;

import com.organizer3.db.SchemaInitializer;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;

import static com.organizer3.javdb.enrichment.CastPresenceCheck.Result.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link CastPresenceCheck} using real in-memory SQLite.
 *
 * <p>The cast_json format is {@code [{"slug":"...","name":"KANJI","gender":"F"|"M"|"U"},...]}
 * matching what javdb/title_javdb_enrichment stores.
 */
class CastPresenceCheckTest {

    private Connection connection;
    private Jdbi jdbi;
    private CastPresenceCheck check;

    private static final String NOW = "2026-06-12T00:00:00.000Z";

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        check = new CastPresenceCheck(jdbi);
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /** Insert a minimal actress row; returns its generated id. */
    private long insertActress(String canonicalName, String stageName) {
        return jdbi.withHandle(h ->
                h.createQuery(
                                "INSERT INTO actresses(canonical_name, stage_name, tier, first_seen_at) " +
                                "VALUES (:cn, :sn, 'minor', :now) RETURNING id")
                        .bind("cn", canonicalName)
                        .bind("sn", stageName)
                        .bind("now", NOW)
                        .mapTo(Long.class)
                        .one());
    }

    private void insertAlias(long actressId, String aliasName) {
        jdbi.useHandle(h ->
                h.execute("INSERT INTO actress_aliases(actress_id, alias_name) VALUES (?, ?)",
                        actressId, aliasName));
    }

    private void setAlternateNames(long actressId, String altNamesJson) {
        jdbi.useHandle(h ->
                h.execute("UPDATE actresses SET alternate_names_json = ? WHERE id = ?",
                        altNamesJson, actressId));
    }

    /** Build a minimal cast_json array with one female entry. */
    private static String cast1F(String name) {
        return "[{\"slug\":\"abc\",\"name\":\"" + name + "\",\"gender\":\"F\"}]";
    }

    /** Build a cast_json with N female entries (names f1..fN). */
    private static String castNF(int n) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(",");
            sb.append("{\"slug\":\"s").append(i).append("\",\"name\":\"女優").append(i).append("\",\"gender\":\"F\"}");
        }
        sb.append("]");
        return sb.toString();
    }

    /** Insert a tag definition with a curated_alias. Returns the tag id. */
    private long insertTagDef(String name, String curatedAlias) {
        // enrichment_tag_definitions.curated_alias is a FK to tags.name; seed the
        // tags row first to avoid any FK violation.
        if (curatedAlias != null) {
            jdbi.useHandle(h ->
                    h.execute("INSERT OR IGNORE INTO tags(name, category) VALUES (?, 'misc')",
                            curatedAlias));
        }
        return jdbi.withHandle(h ->
                h.createQuery(
                                "INSERT INTO enrichment_tag_definitions(name, curated_alias) VALUES (?, ?) RETURNING id")
                        .bind(0, name)
                        .bind(1, curatedAlias)
                        .mapTo(Long.class)
                        .one());
    }

    /** Insert a dummy title and title_javdb_enrichment row so FKs are satisfied. */
    private long insertTitle() {
        return jdbi.withHandle(h -> {
            long id = h.createQuery(
                            "INSERT INTO titles(code) VALUES ('TEST-001') RETURNING id")
                    .mapTo(Long.class)
                    .one();
            h.execute(
                    "INSERT INTO title_javdb_enrichment(title_id, javdb_slug, fetched_at) VALUES (?, 'slug1', ?)",
                    id, NOW);
            return id;
        });
    }

    private void tagTitle(long titleId, long tagId) {
        jdbi.useHandle(h ->
                h.execute("INSERT INTO title_enrichment_tags(title_id, tag_id) VALUES (?, ?)",
                        titleId, tagId));
    }

    // ─── check() tests ───────────────────────────────────────────────────────

    @Test
    void check_stageNamePresentInCast_returnsPresent() {
        long id = insertActress("Sora Aoi", "蒼井そら");
        String castJson = cast1F("蒼井そら");
        assertEquals(PRESENT, check.check(id, castJson));
    }

    @Test
    void check_onlyAliasNamePresentInCast_returnsPresent() {
        long id = insertActress("Sora Aoi", null);
        // Give the actress a kanji stage_name via the alias only
        insertAlias(id, "蒼井そら");
        String castJson = cast1F("蒼井そら");
        assertEquals(PRESENT, check.check(id, castJson));
    }

    @Test
    void check_onlyAlternateNamePresentInCast_returnsPresent() {
        long id = insertActress("Yuma Asami", null);
        setAlternateNames(id, "[{\"name\":\"麻美ゆま\"}]");
        String castJson = cast1F("麻美ゆま");
        assertEquals(PRESENT, check.check(id, castJson));
    }

    /**
     * The cast stores {@code "椎名そら"} (no space); the DB stage_name is
     * {@code "椎名 そら"} (with a space).  After NFKC + whitespace stripping
     * they become equal → PRESENT.
     */
    @Test
    void check_nameMatchesAfterNfkcAndSpaceStrip_returnsPresent() {
        long id = insertActress("Shiina Sora", "椎名 そら");
        // cast has the name without embedded space
        String castJson = cast1F("椎名そら");
        assertEquals(PRESENT, check.check(id, castJson));
    }

    /**
     * If we flip the whitespace: stage_name has no space but cast blob has the space —
     * stripping both should still match.
     */
    @Test
    void check_nameMatchesAfterNfkcAndSpaceStripReverse_returnsPresent() {
        long id = insertActress("Shiina Sora", "椎名そら");
        // cast stores "椎名 そら" (with space)
        String castJson = "[{\"slug\":\"x\",\"name\":\"椎名 そら\",\"gender\":\"F\"}]";
        assertEquals(PRESENT, check.check(id, castJson));
    }

    @Test
    void check_namesExistButNoneInCast_returnsAbsent() {
        long id = insertActress("Yuma Asami", "麻美ゆま");
        insertAlias(id, "天使もえ"); // different actress's name, just to populate aliases
        String castJson = cast1F("全然別人");
        assertEquals(ABSENT, check.check(id, castJson));
    }

    @Test
    void check_nullStageName_noKanjiAlias_noAlternate_returnsUncheckable() {
        long id = insertActress("Unknown Romaji", null);
        // No aliases, no alternates — nothing Japanese-script to look for
        assertEquals(UNCHECKABLE, check.check(id, cast1F("誰でも")));
    }

    @Test
    void check_nullStageName_romajiOnlyAlias_returnsUncheckable() {
        // This is the extra test the advisor called out: alias is pure romaji → UNCHECKABLE
        long id = insertActress("Romaji Only", null);
        insertAlias(id, "Romaji Alias");  // purely ASCII, no Japanese script
        assertEquals(UNCHECKABLE, check.check(id, cast1F("誰でも")));
    }

    @Test
    void check_nullStageName_kanjiAlias_notInCast_returnsAbsent() {
        long id = insertActress("Null Stage Name", null);
        insertAlias(id, "架空の人");  // kanji alias → checkable
        String castJson = cast1F("全然別人");
        assertEquals(ABSENT, check.check(id, castJson));
    }

    @Test
    void check_nullStageName_kanaAlternate_isCheckable() {
        // Pure-kana is Japanese-script → checkable even without kanji
        long id = insertActress("Kana Only", null);
        setAlternateNames(id, "[{\"name\":\"ひらがな\"}]");
        String castJson = cast1F("ひらがな");
        assertEquals(PRESENT, check.check(id, castJson));
    }

    // ─── guardEnforced() tests ────────────────────────────────────────────────

    @Test
    void guardEnforced_nfem1_noCompTag_returnsTrue() {
        long titleId = insertTitle();
        assertTrue(check.guardEnforced(titleId, castNF(1)));
    }

    @Test
    void guardEnforced_nfem3_noCompTag_returnsTrue() {
        long titleId = insertTitle();
        assertTrue(check.guardEnforced(titleId, castNF(3)));
    }

    @Test
    void guardEnforced_nfem4_returnsFalse() {
        long titleId = insertTitle();
        assertFalse(check.guardEnforced(titleId, castNF(4)));
    }

    @Test
    void guardEnforced_nfem5_returnsFalse() {
        long titleId = insertTitle();
        assertFalse(check.guardEnforced(titleId, castNF(5)));
    }

    @Test
    void guardEnforced_nfem2_compilationTagged_returnsFalse() {
        long titleId = insertTitle();
        long tagId = insertTagDef("compilation_tag", "compilation");
        tagTitle(titleId, tagId);
        assertFalse(check.guardEnforced(titleId, castNF(2)));
    }

    /**
     * The compilation tag is resolved by curated_alias, NOT by id.
     * We insert a dummy tag first so the autoincrement id for the
     * 'compilation' alias is non-trivial (≠ 1), then verify the result
     * is still correct.
     */
    @Test
    void guardEnforced_compilationTagResolvedByAlias_idDiffers() {
        // Insert a non-compilation tag first so the comp tag gets id > 1.
        insertTagDef("some_other_tag", "collection");
        insertTagDef("another_tag", null);

        long titleId = insertTitle();
        long compTagId = insertTagDef("javdb_compilation_name", "compilation");
        // Verify the id is > 1 (guard against test-setup errors).
        assertTrue(compTagId > 1, "comp tag should have id > 1 to exercise alias resolution");
        tagTitle(titleId, compTagId);

        // nfem=2 but compilation → false
        assertFalse(check.guardEnforced(titleId, castNF(2)));
    }

    @Test
    void guardEnforced_nfem0_noCompTag_returnsTrue() {
        long titleId = insertTitle();
        // Empty cast: 0 female entries ≤ 3
        assertTrue(check.guardEnforced(titleId, "[]"));
    }

    @Test
    void guardEnforced_nfem3_compilationTagged_returnsFalse() {
        long titleId = insertTitle();
        long tagId = insertTagDef("comp_tag2", "compilation");
        tagTitle(titleId, tagId);
        assertFalse(check.guardEnforced(titleId, castNF(3)));
    }
}
