package com.organizer3.db;

import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Creates and migrates the DB schema on startup.
 *
 * <p>Uses {@code PRAGMA user_version} as a schema version counter. Each migration step
 * is applied exactly once and increments the version. New installs start at version 0
 * and all migrations run in order.
 *
 * <p>Current versions:
 * <ul>
 *   <li>0 → 1: initial schema (with {@code mount_path} on volumes)
 *   <li>1 → 2: drop {@code mount_path} from volumes
 * </ul>
 */
public class SchemaInitializer {

    private static final Logger log = LoggerFactory.getLogger(SchemaInitializer.class);
    private static final int CURRENT_VERSION = 2;

    private final Jdbi jdbi;

    public SchemaInitializer(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    public void initialize() {
        log.info("Initializing database schema");
        int version = getVersion();
        log.info("Schema version: {}", version);

        if (version < 1) migrate1();
        if (version < 2) migrate2();

        log.info("Schema initialization complete (version {})", CURRENT_VERSION);
    }

    private int getVersion() {
        return jdbi.withHandle(h ->
                h.createQuery("PRAGMA user_version")
                        .mapTo(Integer.class)
                        .one()
        );
    }

    private void setVersion(int version) {
        // PRAGMA user_version doesn't support bind parameters
        jdbi.useHandle(h -> h.execute("PRAGMA user_version = " + version));
    }

    /** Initial schema — volumes table includes mount_path. */
    private void migrate1() {
        log.info("Applying migration 1: create initial schema");
        jdbi.useHandle(h -> {
            h.execute("""
                    CREATE TABLE IF NOT EXISTS volumes (
                        id              TEXT PRIMARY KEY,
                        mount_path      TEXT,
                        structure_type  TEXT NOT NULL,
                        last_synced_at  TEXT
                    )""");

            h.execute("""
                    CREATE TABLE IF NOT EXISTS actresses (
                        id              INTEGER PRIMARY KEY AUTOINCREMENT,
                        canonical_name  TEXT NOT NULL UNIQUE,
                        tier            TEXT NOT NULL,
                        first_seen_at   TEXT NOT NULL
                    )""");

            h.execute("""
                    CREATE TABLE IF NOT EXISTS actress_aliases (
                        actress_id  INTEGER NOT NULL REFERENCES actresses(id),
                        alias_name  TEXT NOT NULL,
                        PRIMARY KEY (actress_id, alias_name)
                    )""");

            h.execute("""
                    CREATE TABLE IF NOT EXISTS titles (
                        id            INTEGER PRIMARY KEY AUTOINCREMENT,
                        code          TEXT NOT NULL,
                        base_code     TEXT,
                        volume_id     TEXT NOT NULL REFERENCES volumes(id),
                        partition_id  TEXT NOT NULL,
                        actress_id    INTEGER REFERENCES actresses(id),
                        path          TEXT NOT NULL,
                        last_seen_at  TEXT NOT NULL
                    )""");

            h.execute("""
                    CREATE TABLE IF NOT EXISTS videos (
                        id            INTEGER PRIMARY KEY AUTOINCREMENT,
                        title_id      INTEGER NOT NULL REFERENCES titles(id),
                        filename      TEXT NOT NULL,
                        path          TEXT NOT NULL,
                        last_seen_at  TEXT NOT NULL
                    )""");

            h.execute("""
                    CREATE TABLE IF NOT EXISTS operations (
                        id           INTEGER PRIMARY KEY AUTOINCREMENT,
                        timestamp    TEXT NOT NULL,
                        type         TEXT NOT NULL,
                        source_path  TEXT NOT NULL,
                        dest_path    TEXT,
                        was_armed    INTEGER NOT NULL
                    )""");

            h.execute("CREATE INDEX IF NOT EXISTS idx_actress_aliases_name ON actress_aliases(alias_name)");
            h.execute("CREATE INDEX IF NOT EXISTS idx_titles_volume ON titles(volume_id)");
            h.execute("CREATE INDEX IF NOT EXISTS idx_titles_actress ON titles(actress_id)");
            h.execute("CREATE INDEX IF NOT EXISTS idx_titles_code ON titles(code)");
            h.execute("CREATE INDEX IF NOT EXISTS idx_videos_title ON videos(title_id)");
        });
        setVersion(1);
    }

    /** Drop mount_path from volumes — no longer needed with direct smbj connections. */
    private void migrate2() {
        log.info("Applying migration 2: drop mount_path from volumes");
        jdbi.useHandle(h -> {
            // SQLite requires recreating the table to drop a column
            h.execute("""
                    CREATE TABLE IF NOT EXISTS volumes_new (
                        id              TEXT PRIMARY KEY,
                        structure_type  TEXT NOT NULL,
                        last_synced_at  TEXT
                    )""");
            h.execute("""
                    INSERT INTO volumes_new (id, structure_type, last_synced_at)
                    SELECT id, structure_type, last_synced_at FROM volumes
                    """);
            h.execute("DROP TABLE volumes");
            h.execute("ALTER TABLE volumes_new RENAME TO volumes");
        });
        setVersion(2);
    }
}
