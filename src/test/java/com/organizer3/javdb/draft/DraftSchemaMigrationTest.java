package com.organizer3.javdb.draft;

import com.organizer3.db.SchemaInitializer;
import com.organizer3.db.SchemaUpgrader;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for the v44 schema migration.
 *
 * <p>Exercises the {@link SchemaUpgrader} code path (v43 → v44), not the
 * {@link SchemaInitializer} fresh-install path. This is the path that real
 * existing user databases take.
 *
 * <p>See spec/PROPOSAL_DRAFT_MODE.md §13 Phase 1 and §15.5.
 */
class DraftSchemaMigrationTest {

    private Connection connection;
    private Jdbi jdbi;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    private boolean tableExists(String tableName) {
        return jdbi.withHandle(h -> h.createQuery(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name=:name")
                .bind("name", tableName)
                .mapTo(Integer.class).one() > 0);
    }

    private boolean indexExists(String indexName) {
        return jdbi.withHandle(h -> h.createQuery(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='index' AND name=:name")
                .bind("name", indexName)
                .mapTo(Integer.class).one() > 0);
    }

    private boolean columnExists(String table, String column) {
        return jdbi.withHandle(h -> h.createQuery(
                "SELECT COUNT(*) FROM pragma_table_info('" + table + "') WHERE name='" + column + "'")
                .mapTo(Integer.class).one() > 0);
    }

    private int schemaVersion() {
        return jdbi.withHandle(h ->
                h.createQuery("PRAGMA user_version").mapTo(Integer.class).one());
    }

    /**
     * Simulates a v43 database: initializes with SchemaInitializer then rewinds
     * to v43 by dropping the draft tables (they don't exist in v43 anyway, so
     * we just stamp the version).
     *
     * <p>This is the realistic path: a user ran v43 code, their DB is at version 43,
     * they upgrade to v44 code, SchemaUpgrader runs applyV44.
     */
    private void simulateV43Db() {
        // Use SchemaInitializer to get a valid base schema (all non-draft tables),
        // then drop the draft tables that SchemaInitializer now creates, and stamp v43.
        new SchemaInitializer(jdbi).initialize();
        jdbi.useHandle(h -> {
            h.execute("DROP TABLE IF EXISTS draft_title_actresses");
            h.execute("DROP TABLE IF EXISTS draft_title_javdb_enrichment");
            h.execute("DROP TABLE IF EXISTS draft_titles");
            h.execute("DROP TABLE IF EXISTS draft_actresses");
            h.execute("DROP INDEX IF EXISTS idx_draft_titles_title_id");
            try { h.execute("ALTER TABLE actresses DROP COLUMN created_via"); } catch (Exception ignore) {}
            try { h.execute("ALTER TABLE actresses DROP COLUMN created_at"); } catch (Exception ignore) {}
            h.execute("PRAGMA user_version = 43");
        });
    }

    // ── v43 → v44 upgrade ─────────────────────────────────────────────────────

    @Test
    void upgradeFromV43_createsAllDraftTables() {
        simulateV43Db();

        new SchemaUpgrader(jdbi).upgrade();

        assertTrue(tableExists("draft_titles"),                 "draft_titles must be created by v44");
        assertTrue(tableExists("draft_actresses"),              "draft_actresses must be created by v44");
        assertTrue(tableExists("draft_title_actresses"),        "draft_title_actresses must be created by v44");
        assertTrue(tableExists("draft_title_javdb_enrichment"), "draft_title_javdb_enrichment must be created by v44");
    }

    @Test
    void upgradeFromV43_createsUniqueTitleIdIndex() {
        simulateV43Db();

        new SchemaUpgrader(jdbi).upgrade();

        assertTrue(indexExists("idx_draft_titles_title_id"),
                "unique index on draft_titles(title_id) must be created by v44");
    }

    @Test
    void upgradeFromV43_addsCreatedViaAndCreatedAtToActresses() {
        simulateV43Db();

        new SchemaUpgrader(jdbi).upgrade();

        assertTrue(columnExists("actresses", "created_via"),
                "actresses.created_via must be added by v44");
        assertTrue(columnExists("actresses", "created_at"),
                "actresses.created_at must be added by v44");
    }

    @Test
    void upgradeFromV43_stampsVersion44() {
        simulateV43Db();

        new SchemaUpgrader(jdbi).upgrade();

        assertEquals(44, schemaVersion(), "schema version must be 44 after upgrade");
    }

    @Test
    void upgradeFromV43_existingActressesStillLoadableWithNullNewColumns() {
        simulateV43Db();
        jdbi.useHandle(h ->
                h.execute("INSERT INTO actresses(id, canonical_name, tier, first_seen_at) " +
                          "VALUES (1, 'Test Actress', 'LIBRARY', '2024-01-01')"));

        new SchemaUpgrader(jdbi).upgrade();

        // Existing actress rows must have NULL for the new columns (no backfill required).
        String createdVia = jdbi.withHandle(h ->
                h.createQuery("SELECT created_via FROM actresses WHERE id = 1")
                        .mapTo(String.class).findOne().orElse(null));
        assertNull(createdVia,
                "existing actresses should have NULL created_via after v44 migration");
    }

    @Test
    void upgradeFromV43_isIdempotent() {
        simulateV43Db();

        new SchemaUpgrader(jdbi).upgrade();
        // Running again must be a no-op.
        new SchemaUpgrader(jdbi).upgrade();

        assertEquals(44, schemaVersion(), "schema version must remain 44 after redundant upgrade");
    }

    // ── fresh install via SchemaInitializer ───────────────────────────────────

    @Test
    void freshInstallHasAllDraftTables() {
        new SchemaInitializer(jdbi).initialize();

        assertTrue(tableExists("draft_titles"),                 "fresh install must include draft_titles");
        assertTrue(tableExists("draft_actresses"),              "fresh install must include draft_actresses");
        assertTrue(tableExists("draft_title_actresses"),        "fresh install must include draft_title_actresses");
        assertTrue(tableExists("draft_title_javdb_enrichment"), "fresh install must include draft_title_javdb_enrichment");
    }

    @Test
    void freshInstallActressesTableHasCreatedViaAndCreatedAt() {
        new SchemaInitializer(jdbi).initialize();

        assertTrue(columnExists("actresses", "created_via"),
                "fresh install actresses table must include created_via");
        assertTrue(columnExists("actresses", "created_at"),
                "fresh install actresses table must include created_at");
    }

    @Test
    void freshInstallIsStampedAtVersion44() {
        new SchemaInitializer(jdbi).initialize();

        assertEquals(44, schemaVersion(),
                "fresh install must stamp version 44");
    }

    // ── unique index enforcement ───────────────────────────────────────────────

    @Test
    void uniqueIndexOnDraftTitlesPreventsSecondDraftForSameTitle() {
        jdbi.useHandle(h -> h.execute("PRAGMA foreign_keys = ON"));
        new SchemaInitializer(jdbi).initialize();
        jdbi.useHandle(h -> {
            h.execute("INSERT INTO titles(id, code, base_code, label, seq_num) VALUES (1, 'TST-1', 'TST', 'TST', 1)");
            h.execute("INSERT INTO draft_titles(title_id, code, created_at, updated_at) " +
                      "VALUES (1, 'TST-1', '2024-01-01T00:00:00Z', '2024-01-01T00:00:00Z')");
        });

        assertThrows(Exception.class, () ->
                jdbi.useHandle(h ->
                        h.execute("INSERT INTO draft_titles(title_id, code, created_at, updated_at) " +
                                  "VALUES (1, 'TST-1', '2024-02-01T00:00:00Z', '2024-02-01T00:00:00Z')")),
                "unique index must prevent a second draft for the same title_id");
    }
}
