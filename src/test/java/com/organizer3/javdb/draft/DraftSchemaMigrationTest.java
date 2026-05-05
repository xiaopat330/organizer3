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
    void upgradeFromV43_stampsVersion45() {
        simulateV43Db();

        new SchemaUpgrader(jdbi).upgrade();

        assertEquals(50, schemaVersion(), "schema version must be 50 after upgrade");
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
    void upgradeFromV43_V44V45_isIdempotent() {
        simulateV43Db();

        new SchemaUpgrader(jdbi).upgrade();
        // Running again must be a no-op.
        new SchemaUpgrader(jdbi).upgrade();

        assertEquals(50, schemaVersion(), "schema version must remain 50 after redundant upgrade");
    }

    // ── v44 → v45 upgrade ─────────────────────────────────────────────────────

    /** Simulate a v44 DB (has draft tables but lacks new_payload/promotion_metadata). */
    private void simulateV44Db() {
        new SchemaInitializer(jdbi).initialize();
        jdbi.useHandle(h -> {
            // Remove v45 columns added by fresh install so we simulate a v44 database
            // SQLite doesn't support DROP COLUMN on all versions; we recreate the table
            // without the new columns instead.
            // Actually, easier: just stamp user_version=44 and rely on addColumnIfMissing
            // detecting absence on real v44 DBs. But to truly test, we can drop the
            // history table and recreate it without the new columns.
            h.execute("DROP TABLE IF EXISTS title_javdb_enrichment_history");
            h.execute("""
                    CREATE TABLE title_javdb_enrichment_history (
                        id              INTEGER PRIMARY KEY AUTOINCREMENT,
                        title_id        INTEGER NOT NULL,
                        title_code      TEXT    NOT NULL,
                        changed_at      TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
                        reason          TEXT,
                        prior_slug      TEXT,
                        prior_payload   TEXT
                    )""");
            h.execute("CREATE INDEX IF NOT EXISTS idx_tjeh_title ON title_javdb_enrichment_history(title_id)");
            h.execute("CREATE INDEX IF NOT EXISTS idx_tjeh_code  ON title_javdb_enrichment_history(title_code)");
            h.execute("PRAGMA user_version = 44");
        });
    }

    @Test
    void upgradeFromV44_addsNewPayloadAndPromotionMetadataColumns() {
        simulateV44Db();

        new SchemaUpgrader(jdbi).upgrade();

        assertTrue(columnExists("title_javdb_enrichment_history", "new_payload"),
                "new_payload must be added by v45");
        assertTrue(columnExists("title_javdb_enrichment_history", "promotion_metadata"),
                "promotion_metadata must be added by v45");
    }

    @Test
    void upgradeFromV44_stampsVersion45() {
        simulateV44Db();

        new SchemaUpgrader(jdbi).upgrade();

        assertEquals(50, schemaVersion(), "schema version must be 50 after v44→v50 upgrade");
    }

    @Test
    void upgradeFromV44_existingHistoryRowsHaveNullNewColumns() {
        simulateV44Db();
        jdbi.useHandle(h -> h.execute(
                "INSERT INTO title_javdb_enrichment_history(title_id, title_code, reason) " +
                "VALUES (1, 'TST-1', 'enrichment_runner')"));

        new SchemaUpgrader(jdbi).upgrade();

        // Check column exists and existing row has NULL value
        assertTrue(columnExists("title_javdb_enrichment_history", "new_payload"),
                "new_payload column must exist after v45 migration");
        int nonNullCount = jdbi.withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM title_javdb_enrichment_history WHERE new_payload IS NOT NULL")
                        .mapTo(Integer.class).one());
        assertEquals(0, nonNullCount, "existing history rows should have NULL new_payload after v45 migration");
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
    void freshInstallIsStampedAtVersion50() {
        new SchemaInitializer(jdbi).initialize();

        assertEquals(50, schemaVersion(),
                "fresh install must stamp version 50");
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
