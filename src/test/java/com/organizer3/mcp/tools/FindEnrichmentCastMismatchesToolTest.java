package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.organizer3.db.SchemaInitializer;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.*;

class FindEnrichmentCastMismatchesToolTest {

    private static final ObjectMapper M = new ObjectMapper();

    private Connection connection;
    private Jdbi jdbi;
    private FindEnrichmentCastMismatchesTool tool;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        tool = new FindEnrichmentCastMismatchesTool(jdbi);
    }

    @AfterEach
    void tearDown() throws Exception { connection.close(); }

    @Test
    void flagsTitleWhoseCastDoesNotMentionLinkedActress() throws Exception {
        long mana = seedActress("Mana Sakura", "紗倉まな", null, false);
        long titleId = seedEnrichedTitle("STAR-334", "V9b7n",
                "[{\"slug\":\"badId\",\"name\":\"永野いち夏\",\"gender\":\"F\"}]",
                "オシッコに向かう途中に即ハメ! 永野いち夏");
        linkActress(titleId, mana);

        var r = (FindEnrichmentCastMismatchesTool.Result) tool.call(args(100));
        assertEquals(1L, r.total());
        assertEquals(1, r.sample().size());
        assertEquals("STAR-334", r.sample().get(0).code());
        assertEquals("Mana Sakura", r.sample().get(0).actressName());
        assertEquals("V9b7n", r.sample().get(0).javdbSlug());
    }

    @Test
    void doesNotFlagWhenCastContainsStageName() throws Exception {
        long mana = seedActress("Mana Sakura", "紗倉まな", null, false);
        long titleId = seedEnrichedTitle("MIDV-100", "Abc12",
                "[{\"slug\":\"J9dd\",\"name\":\"紗倉まな\",\"gender\":\"F\"}]",
                "Mana Sakura - test title");
        linkActress(titleId, mana);

        var r = (FindEnrichmentCastMismatchesTool.Result) tool.call(args(100));
        assertEquals(0L, r.total());
    }

    @Test
    void whitespaceStrippedComparisonAvoidsFalsePositives() throws Exception {
        // stage_name has a space (family/given separator) but javdb returns name without space.
        long sora = seedActress("Sora Shiina", "椎名 そら", null, false);
        long titleId = seedEnrichedTitle("ABC-100", "slug100",
                "[{\"slug\":\"sX\",\"name\":\"椎名そら\",\"gender\":\"F\"}]",
                "Sora title");
        linkActress(titleId, sora);

        var r = (FindEnrichmentCastMismatchesTool.Result) tool.call(args(100));
        assertEquals(0L, r.total(), "whitespace-stripped match must catch '椎名 そら' vs '椎名そら'");
    }

    @Test
    void alternateNamesAreCheckedToo() throws Exception {
        // Actress has alternate_names_json with a market-romanization variant; cast credits her by that.
        String altJson = "[{\"name\":\"紗倉真菜\",\"note\":\"Chinese market romanization\"}]";
        long mana = seedActress("Mana Sakura", "紗倉まな", altJson, false);
        long titleId = seedEnrichedTitle("OEM-001", "altSlug",
                "[{\"slug\":\"x\",\"name\":\"紗倉真菜\",\"gender\":\"F\"}]",
                "Mana title under alt name");
        linkActress(titleId, mana);

        var r = (FindEnrichmentCastMismatchesTool.Result) tool.call(args(100));
        assertEquals(0L, r.total(), "alt-name match must not be flagged as a mismatch");
    }

    @Test
    void sentinelActressesAreExcluded() throws Exception {
        long various = seedActress("Various", "Various", null, true);
        long titleId = seedEnrichedTitle("VAR-001", "slug",
                "[{\"slug\":\"x\",\"name\":\"some_other_name\"}]",
                "compilation");
        linkActress(titleId, various);

        var r = (FindEnrichmentCastMismatchesTool.Result) tool.call(args(100));
        assertEquals(0L, r.total(), "sentinel actresses must not be flagged");
    }

    @Test
    void titleWithNullCastJsonIsIgnored() throws Exception {
        long mana = seedActress("Mana Sakura", "紗倉まな", null, false);
        long titleId = seedEnrichedTitle("MIDV-200", "noCast", null, "Mana title with no cast yet");
        linkActress(titleId, mana);

        var r = (FindEnrichmentCastMismatchesTool.Result) tool.call(args(100));
        assertEquals(0L, r.total(), "rows without cast_json can't be checked — skip silently");
    }

    @Test
    void actressWithNullStageNameIsIgnored() throws Exception {
        // No stage_name to compare against — skip rather than false-flag.
        long actress = seedActress("New Actress", null, null, false);
        long titleId = seedEnrichedTitle("ABC-999", "slug",
                "[{\"slug\":\"x\",\"name\":\"someone_else\"}]",
                "title");
        linkActress(titleId, actress);

        var r = (FindEnrichmentCastMismatchesTool.Result) tool.call(args(100));
        assertEquals(0L, r.total(), "missing stage_name = unchecked, not flagged");
    }

    @Test
    void totalReportsTrueCountEvenWhenLimitClipsSample() throws Exception {
        long mana = seedActress("Mana Sakura", "紗倉まな", null, false);
        for (int i = 0; i < 5; i++) {
            long t = seedEnrichedTitle("MM-" + i, "slug" + i,
                    "[{\"slug\":\"x\",\"name\":\"someone_else\"}]",
                    "wrong title " + i);
            linkActress(t, mana);
        }

        var r = (FindEnrichmentCastMismatchesTool.Result) tool.call(args(2));
        assertEquals(5L, r.total(), "total is the unbounded count");
        assertEquals(2, r.sample().size(), "sample respects the limit");
    }

    // ── alias-aware detection tests ──────────────────────────────────────────

    @Test
    void aliasMatchResolvesWhatWouldOtherwiseBeMismatch() throws Exception {
        // Actress 107 "Aika": stage_name=AIKA, alias=愛佳; cast credits her as 愛佳.
        long aika = seedActress("Aika", "AIKA", null, false);
        seedAlias(aika, "愛佳");
        long titleId = seedEnrichedTitle("ABP-107", "slugAika",
                "[{\"slug\":\"x\",\"name\":\"愛佳\",\"gender\":\"F\"}]",
                "Aika title under alias");
        linkActress(titleId, aika);

        var r = (FindEnrichmentCastMismatchesTool.Result) tool.call(args(100));
        assertEquals(0L, r.total(), "alias match must not be flagged as a mismatch");
    }

    @Test
    void noAliasAndNoStageNameMatchIsFlagged() throws Exception {
        // Control: actress with no alias, cast contains entirely different person.
        long actress = seedActress("Rima Arai", "新井リマ", null, false);
        long titleId = seedEnrichedTitle("STAR-999", "wrongSlug",
                "[{\"slug\":\"x\",\"name\":\"永野いち夏\",\"gender\":\"F\"}]",
                "Wrong actress title");
        linkActress(titleId, actress);

        var r = (FindEnrichmentCastMismatchesTool.Result) tool.call(args(100));
        assertEquals(1L, r.total(), "genuine mismatch with no alias must still be flagged");
    }

    @Test
    void stageNameMatchAlonePassesRegardlessOfAlias() throws Exception {
        // Actress has an alias, but cast already matches stage_name directly.
        long actress = seedActress("Nana Ogura", "小倉奈々", null, false);
        seedAlias(actress, "小倉那奈");
        long titleId = seedEnrichedTitle("IPX-200", "slugNana",
                "[{\"slug\":\"y\",\"name\":\"小倉奈々\",\"gender\":\"F\"}]",
                "Nana title via stage_name");
        linkActress(titleId, actress);

        var r = (FindEnrichmentCastMismatchesTool.Result) tool.call(args(100));
        assertEquals(0L, r.total(), "stage_name match must pass even when actress has aliases");
    }

    @Test
    void multipleAliasesOneMatchesIsNotFlagged() throws Exception {
        // Actress has several aliases; only the second matches the cast entry.
        long actress = seedActress("Sora Aoi", "蒼井そら", null, false);
        seedAlias(actress, "蒼井空");
        seedAlias(actress, "Sora Aoi Alias");
        seedAlias(actress, "そら");
        long titleId = seedEnrichedTitle("SOD-300", "slugSora",
                "[{\"slug\":\"z\",\"name\":\"蒼井空\",\"gender\":\"F\"}]",
                "Sora title under second alias");
        linkActress(titleId, actress);

        var r = (FindEnrichmentCastMismatchesTool.Result) tool.call(args(100));
        assertEquals(0L, r.total(), "one alias matching among several must clear the mismatch");
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private long seedActress(String canonical, String stageName, String alternateNamesJson, boolean sentinel) {
        return jdbi.withHandle(h -> h.createUpdate("""
                INSERT INTO actresses (canonical_name, stage_name, tier, first_seen_at, alternate_names_json, is_sentinel)
                VALUES (:cn, :sn, 'LIBRARY', '2024-01-01', :alt, :sentinel)
                """)
                .bind("cn", canonical)
                .bind("sn", stageName)
                .bind("alt", alternateNamesJson)
                .bind("sentinel", sentinel ? 1 : 0)
                .executeAndReturnGeneratedKeys("id").mapTo(Long.class).one());
    }

    private long seedEnrichedTitle(String code, String slug, String castJson, String titleOriginal) {
        long titleId = jdbi.withHandle(h -> h.createUpdate("""
                INSERT INTO titles (code, base_code, label, seq_num) VALUES (?, ?, ?, ?)
                """)
                .bind(0, code).bind(1, code).bind(2, code.split("-")[0]).bind(3, 1)
                .executeAndReturnGeneratedKeys("id").mapTo(Long.class).one());
        jdbi.useHandle(h -> h.createUpdate("""
                INSERT INTO title_javdb_enrichment (title_id, javdb_slug, fetched_at, cast_json, title_original)
                VALUES (?, ?, '2026-04-28T00:00:00Z', ?, ?)
                """)
                .bind(0, titleId).bind(1, slug).bind(2, castJson).bind(3, titleOriginal)
                .execute());
        return titleId;
    }

    private void seedAlias(long actressId, String aliasName) {
        jdbi.useHandle(h -> h.createUpdate(
                "INSERT INTO actress_aliases (actress_id, alias_name) VALUES (?, ?)")
                .bind(0, actressId).bind(1, aliasName).execute());
    }

    private void linkActress(long titleId, long actressId) {
        jdbi.useHandle(h -> h.createUpdate(
                "INSERT INTO title_actresses (title_id, actress_id) VALUES (?, ?)")
                .bind(0, titleId).bind(1, actressId).execute());
    }

    private static ObjectNode args(int limit) {
        ObjectNode n = M.createObjectNode();
        n.put("limit", limit);
        return n;
    }
}
