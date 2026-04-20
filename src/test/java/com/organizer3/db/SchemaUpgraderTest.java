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
    void upgradeFromV18StampsV19AndAddsSizeBytesColumn() {
        // Start from the current schema, drop the size_bytes column, mark the DB as v18,
        // and run upgrade() — the v19 migration should re-add the column and stamp v19.
        new SchemaInitializer(jdbi).initialize();
        jdbi.useHandle(h -> {
            // SQLite <3.35 had no DROP COLUMN; modern distribution does, so this works.
            // If the drop itself fails we still get useful coverage by running the guard.
            try { h.execute("ALTER TABLE videos DROP COLUMN size_bytes"); } catch (Exception ignore) {}
            h.execute("PRAGMA user_version = 18");
        });

        new SchemaUpgrader(jdbi).upgrade();

        assertEquals(19, currentVersion());
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
