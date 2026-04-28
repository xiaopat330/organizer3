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

class BackfillActressSlugsFromCastToolTest {

    private static final ObjectMapper M = new ObjectMapper();

    private Connection connection;
    private Jdbi jdbi;
    private BackfillActressSlugsFromCastTool tool;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        tool = new BackfillActressSlugsFromCastTool(jdbi);
    }

    @AfterEach
    void tearDown() throws Exception { connection.close(); }

    @Test
    void writesSlugWhenStageNameMatchesCastEntry() {
        long iori = seedActress("Iori Kogawa", "古川いおり", false);
        long titleId = seedEnrichedTitle("MIDV-100",
                "[{\"slug\":\"Y8Mx\",\"name\":\"古川いおり\",\"gender\":\"F\"}]");
        linkActress(titleId, iori);

        var r = (BackfillActressSlugsFromCastTool.Result) tool.call(args(false));

        assertEquals(1, r.candidates());
        assertEquals(1, r.written());
        assertEquals("Y8Mx", lookupSlug(iori));
    }

    @Test
    void whitespaceStrippedComparisonHandlesStageNameSpaces() {
        // stage_name has a space, javdb cast doesn't.
        long sora = seedActress("Sora Shiina", "椎名 そら", false);
        long titleId = seedEnrichedTitle("ABC-100",
                "[{\"slug\":\"sX\",\"name\":\"椎名そら\",\"gender\":\"F\"}]");
        linkActress(titleId, sora);

        var r = (BackfillActressSlugsFromCastTool.Result) tool.call(args(false));

        assertEquals(1, r.written());
        assertEquals("sX", lookupSlug(sora));
    }

    @Test
    void doesNotOverwriteExistingSlug() {
        long mana = seedActress("Mana Sakura", "紗倉まな", false);
        // Pre-existing staging row with a slug.
        jdbi.useHandle(h -> h.createUpdate("""
                INSERT INTO javdb_actress_staging (actress_id, javdb_slug, source_title_code, status)
                VALUES (?, 'preset', 'PRESET-001', 'slug_only')
                """).bind(0, mana).execute());

        long titleId = seedEnrichedTitle("MIDV-100",
                "[{\"slug\":\"newSlug\",\"name\":\"紗倉まな\",\"gender\":\"F\"}]");
        linkActress(titleId, mana);

        var r = (BackfillActressSlugsFromCastTool.Result) tool.call(args(false));

        // Candidate query already filters on null slug, so she won't appear at all.
        assertEquals(0, r.candidates());
        assertEquals("preset", lookupSlug(mana), "existing slug must be preserved");
    }

    @Test
    void sentinelActressesAreExcluded() {
        long various = seedActress("Various", "Various", true);
        long titleId = seedEnrichedTitle("VAR-001",
                "[{\"slug\":\"sentSlug\",\"name\":\"Various\",\"gender\":\"F\"}]");
        linkActress(titleId, various);

        var r = (BackfillActressSlugsFromCastTool.Result) tool.call(args(false));

        assertEquals(0, r.candidates(), "sentinels never get backfilled");
        assertNull(lookupSlug(various));
    }

    @Test
    void dryRunReportsCandidatesWithoutWriting() {
        long iori = seedActress("Iori Kogawa", "古川いおり", false);
        long titleId = seedEnrichedTitle("MIDV-100",
                "[{\"slug\":\"Y8Mx\",\"name\":\"古川いおり\"}]");
        linkActress(titleId, iori);

        var r = (BackfillActressSlugsFromCastTool.Result) tool.call(args(true));

        assertEquals(1, r.candidates());
        assertEquals(0, r.written(), "dryRun must not write");
        assertNull(lookupSlug(iori), "dryRun must not write");
    }

    @Test
    void noMatchWhenStageNameDoesNotAppearInAnyCast() {
        long someone = seedActress("Someone Else", "他人", false);
        long titleId = seedEnrichedTitle("ABC-001",
                "[{\"slug\":\"x\",\"name\":\"全然違う名前\"}]");
        linkActress(titleId, someone);

        var r = (BackfillActressSlugsFromCastTool.Result) tool.call(args(false));
        assertEquals(0, r.candidates());
    }

    @Test
    void multipleTitlesYieldOneCandidatePerActress() {
        long iori = seedActress("Iori Kogawa", "古川いおり", false);
        // Three different titles, all with her in cast.
        for (int i = 0; i < 3; i++) {
            long t = seedEnrichedTitle("MIDV-10" + i,
                    "[{\"slug\":\"Y8Mx\",\"name\":\"古川いおり\"}]");
            linkActress(t, iori);
        }

        var r = (BackfillActressSlugsFromCastTool.Result) tool.call(args(false));
        assertEquals(1, r.candidates(), "DISTINCT must collapse to one candidate per actress");
        assertEquals(1, r.written());
        assertEquals("Y8Mx", lookupSlug(iori));
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private long seedActress(String canonical, String stageName, boolean sentinel) {
        return jdbi.withHandle(h -> h.createUpdate("""
                INSERT INTO actresses (canonical_name, stage_name, tier, first_seen_at, is_sentinel)
                VALUES (?, ?, 'LIBRARY', '2024-01-01', ?)
                """)
                .bind(0, canonical).bind(1, stageName).bind(2, sentinel ? 1 : 0)
                .executeAndReturnGeneratedKeys("id").mapTo(Long.class).one());
    }

    private long seedEnrichedTitle(String code, String castJson) {
        long titleId = jdbi.withHandle(h -> h.createUpdate("""
                INSERT INTO titles (code, base_code, label, seq_num) VALUES (?, ?, ?, 1)
                """)
                .bind(0, code).bind(1, code).bind(2, code.split("-")[0])
                .executeAndReturnGeneratedKeys("id").mapTo(Long.class).one());
        jdbi.useHandle(h -> h.createUpdate("""
                INSERT INTO title_javdb_enrichment (title_id, javdb_slug, fetched_at, cast_json)
                VALUES (?, 'titleSlug', '2026-04-29T00:00:00Z', ?)
                """).bind(0, titleId).bind(1, castJson).execute());
        return titleId;
    }

    private void linkActress(long titleId, long actressId) {
        jdbi.useHandle(h -> h.createUpdate(
                "INSERT INTO title_actresses (title_id, actress_id) VALUES (?, ?)")
                .bind(0, titleId).bind(1, actressId).execute());
    }

    private String lookupSlug(long actressId) {
        return jdbi.withHandle(h -> h.createQuery(
                "SELECT javdb_slug FROM javdb_actress_staging WHERE actress_id = ?")
                .bind(0, actressId).mapTo(String.class).findOne().orElse(null));
    }

    private static ObjectNode args(boolean dryRun) {
        ObjectNode n = M.createObjectNode();
        n.put("dryRun", dryRun);
        return n;
    }
}
