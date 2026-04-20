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

        assertEquals(20, currentVersion());
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
        assertEquals(20, currentVersion(), "fresh install should stamp current version");
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

        assertEquals(20, currentVersion());
        boolean sizeBytesPresent = jdbi.withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM pragma_table_info('videos') WHERE name='size_bytes'")
                        .mapTo(Integer.class).one() > 0);
        assertTrue(sizeBytesPresent, "size_bytes column should exist after v19 migration");
    }

    private int currentVersion() {
        return jdbi.withHandle(h ->
                h.createQuery("PRAGMA user_version").mapTo(Integer.class).one());
    }
}
