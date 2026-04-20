package com.organizer3.db;

import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the {@link SchemaUpgrader} entry-point guard behavior.
 *
 * <p>Individual migration functions rely on specific prior-version table
 * shapes; exercising them in isolation would require hand-crafting each
 * historical schema. The entry-point guard and tail migrations are what
 * run in practice on already-initialized databases.
 */
class SchemaUpgraderTest {

    private Jdbi jdbi;
    private Connection connection;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    @Test
    void upgradeIsNoOpWhenSchemaAlreadyAtCurrentVersion() {
        new SchemaInitializer(jdbi).initialize();
        int versionBefore = currentVersion();

        new SchemaUpgrader(jdbi).upgrade();

        assertEquals(versionBefore, currentVersion(), "no-op path must not bump the version");
    }

    @Test
    void upgradeFromV19StampsV20AndAddsNeedsProfilingColumn() {
        new SchemaInitializer(jdbi).initialize();
        jdbi.useHandle(h -> {
            try { h.execute("ALTER TABLE actresses DROP COLUMN needs_profiling"); } catch (Exception ignore) {}
            h.execute("PRAGMA user_version = 19");
        });

        new SchemaUpgrader(jdbi).upgrade();

        assertEquals(21, currentVersion());
        boolean present = jdbi.withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM pragma_table_info('actresses') WHERE name='needs_profiling'")
                        .mapTo(Integer.class).one() > 0);
        assertTrue(present, "needs_profiling column should exist after v20 migration");
    }

    @Test
    void freshInstallHasNeedsProfilingColumn() {
        new SchemaInitializer(jdbi).initialize();
        boolean present = jdbi.withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM pragma_table_info('actresses') WHERE name='needs_profiling'")
                        .mapTo(Integer.class).one() > 0);
        assertTrue(present, "fresh install should include needs_profiling");
        assertEquals(21, currentVersion(), "fresh install should stamp current version");
    }

    @Test
    void upgradeFromV18StampsCurrentAndAddsSizeBytesColumn() {
        // Start from the current schema, drop the size_bytes column, mark the DB as v18,
        // and run upgrade() — the v19 migration should re-add the column; the upgrader
        // then continues to CURRENT_VERSION.
        new SchemaInitializer(jdbi).initialize();
        jdbi.useHandle(h -> {
            try { h.execute("ALTER TABLE videos DROP COLUMN size_bytes"); } catch (Exception ignore) {}
            h.execute("PRAGMA user_version = 18");
        });

        new SchemaUpgrader(jdbi).upgrade();

        assertEquals(21, currentVersion());
        boolean sizeBytesPresent = jdbi.withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM pragma_table_info('videos') WHERE name='size_bytes'")
                        .mapTo(Integer.class).one() > 0);
        assertTrue(sizeBytesPresent, "size_bytes column should exist after v19 migration");
    }

    @Test
    void upgradeFromV20AddsFavoriteClearedAtAndTriggers() {
        new SchemaInitializer(jdbi).initialize();
        jdbi.useHandle(h -> {
            try { h.execute("ALTER TABLE titles    DROP COLUMN favorite_cleared_at"); } catch (Exception ignore) {}
            try { h.execute("ALTER TABLE actresses DROP COLUMN favorite_cleared_at"); } catch (Exception ignore) {}
            try { h.execute("DROP TRIGGER IF EXISTS trg_titles_favorite_cleared_on_unfav"); } catch (Exception ignore) {}
            try { h.execute("DROP TRIGGER IF EXISTS trg_titles_favorite_cleared_on_refav"); } catch (Exception ignore) {}
            try { h.execute("DROP TRIGGER IF EXISTS trg_actresses_favorite_cleared_on_unfav"); } catch (Exception ignore) {}
            try { h.execute("DROP TRIGGER IF EXISTS trg_actresses_favorite_cleared_on_refav"); } catch (Exception ignore) {}
            h.execute("PRAGMA user_version = 20");
        });

        new SchemaUpgrader(jdbi).upgrade();

        assertEquals(21, currentVersion());
        assertTrue(columnExists("titles",    "favorite_cleared_at"));
        assertTrue(columnExists("actresses", "favorite_cleared_at"));

        // Trigger behavior smoke check: inserting a favorited title, then un-favoriting,
        // should stamp favorite_cleared_at automatically.
        long id = jdbi.withHandle(h -> h.createUpdate(
                "INSERT INTO titles(code, base_code, label, seq_num, favorite) VALUES ('V21-1','V21-1','V21',1,1)")
                .executeAndReturnGeneratedKeys("id").mapTo(Long.class).one());
        jdbi.useHandle(h -> h.execute("UPDATE titles SET favorite = 0 WHERE id = ?", id));
        String stamp = jdbi.withHandle(h -> h.createQuery(
                "SELECT favorite_cleared_at FROM titles WHERE id = ?")
                .bind(0, id).mapTo(String.class).one());
        assertNotNull(stamp, "v21 trigger should stamp favorite_cleared_at on un-favorite");
    }

    private boolean columnExists(String table, String column) {
        return jdbi.withHandle(h -> h.createQuery(
                "SELECT COUNT(*) FROM pragma_table_info('" + table + "') WHERE name='" + column + "'")
                .mapTo(Integer.class).one() > 0);
    }

    private int currentVersion() {
        return jdbi.withHandle(h ->
                h.createQuery("PRAGMA user_version").mapTo(Integer.class).one());
    }
}
