package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.organizer3.db.SchemaInitializer;
import com.organizer3.javdb.enrichment.EnrichmentRunner;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BackfillYamlAliasesToolTest {

    private static final ObjectMapper M = new ObjectMapper();

    private Connection connection;
    private Jdbi jdbi;
    private EnrichmentRunner enrichmentRunner;
    private BackfillYamlAliasesTool tool;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        enrichmentRunner = Mockito.mock(EnrichmentRunner.class);
        tool = new BackfillYamlAliasesTool(jdbi, enrichmentRunner);
    }

    @AfterEach
    void tearDown() throws Exception { connection.close(); }

    // ── seeding helpers ──────────────────────────────────────────────────────

    private long seedActress(String canonicalName, String alternateNamesJson) {
        return jdbi.withHandle(h -> h.createUpdate("""
                INSERT INTO actresses (canonical_name, tier, first_seen_at, alternate_names_json)
                VALUES (:cn, 'LIBRARY', '2024-01-01', :alts)
                """)
                .bind("cn", canonicalName)
                .bind("alts", alternateNamesJson)
                .executeAndReturnGeneratedKeys("id").mapTo(Long.class).one());
    }

    private List<String> loadAliases(long actressId) {
        return jdbi.withHandle(h -> h.createQuery(
                "SELECT alias_name FROM actress_aliases WHERE actress_id = :id ORDER BY alias_name")
                .bind("id", actressId)
                .mapTo(String.class)
                .list());
    }

    private void seedCastAnomalyQueueRow(long titleId, String slug) {
        long titleDbId = jdbi.withHandle(h -> h.createUpdate("""
                INSERT INTO titles (code, base_code, label, seq_num) VALUES (:c, :c, 'TST', 1)
                """)
                .bind("c", "TST-" + titleId)
                .executeAndReturnGeneratedKeys("id").mapTo(Long.class).one());
        jdbi.useHandle(h -> h.createUpdate("""
                INSERT INTO enrichment_review_queue (title_id, slug, reason, resolver_source)
                VALUES (:tid, :slug, 'cast_anomaly', 'test')
                """)
                .bind("tid", titleDbId)
                .bind("slug", slug)
                .execute());
    }

    private static ObjectNode args(boolean dryRun) {
        ObjectNode n = M.createObjectNode();
        n.put("dry_run", dryRun);
        return n;
    }

    // ── tests ────────────────────────────────────────────────────────────────

    @Test
    void insertsAliasesForActressesWithAlternateNames() {
        long a1 = seedActress("Mai Hanano",
                "[{\"name\":\"黒木麻衣\",\"note\":\"javdb name\"},{\"name\":\"Hanano Mai\",\"note\":\"\"}]");
        long a2 = seedActress("Other Actress",
                "[{\"name\":\"別名\"}]");

        BackfillYamlAliasesTool.Result r =
                (BackfillYamlAliasesTool.Result) tool.call(args(false));

        assertEquals(2, r.actresses_processed());
        assertEquals(3, r.aliases_inserted()); // 2 for a1, 1 for a2
        assertEquals(0, r.aliases_skipped_existing());

        List<String> a1Aliases = loadAliases(a1);
        assertTrue(a1Aliases.contains("黒木麻衣"), "primary alt must be inserted");
        assertTrue(a1Aliases.contains("Hanano Mai"), "secondary alt must be inserted");
        assertTrue(loadAliases(a2).contains("別名"), "alt for second actress must be inserted");
    }

    @Test
    void idempotent_secondRunInsertsZero() {
        seedActress("Actress A", "[{\"name\":\"アクトレス\"}]");

        tool.call(args(false));
        BackfillYamlAliasesTool.Result r2 =
                (BackfillYamlAliasesTool.Result) tool.call(args(false));

        assertEquals(0, r2.aliases_inserted(), "second run must insert 0 — idempotent");
        assertEquals(1, r2.aliases_skipped_existing(), "already-present aliases reported as skipped");
    }

    @Test
    void dryRunDoesNotWriteAliases() {
        long a = seedActress("Dry Run Actress", "[{\"name\":\"ドライラン\"}]");

        BackfillYamlAliasesTool.Result r =
                (BackfillYamlAliasesTool.Result) tool.call(args(true));

        assertEquals(1, r.aliases_inserted(), "dry_run reports what WOULD be inserted");
        assertTrue(loadAliases(a).isEmpty(), "dry_run must not write any alias rows");
    }

    @Test
    void dryRunDoesNotInvokeRecoverySweep() {
        seedActress("Some Actress", "[{\"name\":\"別名\"}]");

        tool.call(args(true));

        verify(enrichmentRunner, never()).recoverCastAnomaliesAfterMatcherFix();
    }

    @Test
    void nonDryRunInvokesRecoverySweep() {
        seedActress("Some Actress", "[{\"name\":\"別名\"}]");

        tool.call(args(false));

        verify(enrichmentRunner, times(1)).recoverCastAnomaliesAfterMatcherFix();
    }

    @Test
    void noActressesWithAlternateNamesIsANoop() {
        seedActress("No Alts", null);
        seedActress("Empty Alts", "[]");

        BackfillYamlAliasesTool.Result r =
                (BackfillYamlAliasesTool.Result) tool.call(args(false));

        assertEquals(0, r.actresses_processed());
        assertEquals(0, r.aliases_inserted());
    }

    @Test
    void partiallyMirroredActressOnlyInsertsNewAliases() {
        long a = seedActress("Partial Actress",
                "[{\"name\":\"名前一\"},{\"name\":\"名前二\"}]");
        // Pre-insert one of the two aliases manually
        jdbi.useHandle(h -> h.createUpdate("""
                INSERT INTO actress_aliases (actress_id, alias_name) VALUES (:id, '名前一')
                """).bind("id", a).execute());

        BackfillYamlAliasesTool.Result r =
                (BackfillYamlAliasesTool.Result) tool.call(args(false));

        assertEquals(1, r.aliases_inserted(), "only the missing alias is inserted");
        assertEquals(1, r.aliases_skipped_existing(), "the pre-existing alias is counted as skipped");

        List<String> aliases = loadAliases(a);
        assertEquals(2, aliases.size(), "both aliases present after backfill");
    }
}
