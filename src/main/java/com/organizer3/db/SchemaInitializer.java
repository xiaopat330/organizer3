package com.organizer3.db;

import org.jdbi.v3.core.Jdbi;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;

/**
 * Creates the database schema from scratch.
 *
 * <p>No incremental migrations — just drop and recreate as needed during development.
 */
@Slf4j
@RequiredArgsConstructor
public class SchemaInitializer {

    private final Jdbi jdbi;

    public void initialize() {
        log.info("Initializing database schema");
        jdbi.useHandle(h -> {
            h.execute("""
                    CREATE TABLE IF NOT EXISTS volumes (
                        id              TEXT PRIMARY KEY,
                        structure_type  TEXT NOT NULL,
                        last_synced_at  TEXT
                    )""");

            h.execute("""
                    CREATE TABLE IF NOT EXISTS actresses (
                        id              INTEGER PRIMARY KEY AUTOINCREMENT,
                        canonical_name  TEXT NOT NULL UNIQUE,
                        tier            TEXT NOT NULL,
                        favorite        INTEGER NOT NULL DEFAULT 0,
                        bookmark        INTEGER NOT NULL DEFAULT 0,
                        grade           TEXT,
                        rejected        INTEGER NOT NULL DEFAULT 0,
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
                        code          TEXT NOT NULL UNIQUE,
                        base_code     TEXT,
                        label         TEXT,
                        seq_num       INTEGER,
                        actress_id    INTEGER REFERENCES actresses(id),
                        favorite      INTEGER NOT NULL DEFAULT 0,
                        bookmark      INTEGER NOT NULL DEFAULT 0,
                        grade         TEXT,
                        rejected      INTEGER NOT NULL DEFAULT 0
                    )""");

            h.execute("""
                    CREATE TABLE IF NOT EXISTS title_locations (
                        id            INTEGER PRIMARY KEY AUTOINCREMENT,
                        title_id      INTEGER NOT NULL REFERENCES titles(id),
                        volume_id     TEXT NOT NULL REFERENCES volumes(id),
                        partition_id  TEXT NOT NULL,
                        path          TEXT NOT NULL,
                        last_seen_at  TEXT NOT NULL,
                        added_date    TEXT,
                        UNIQUE(title_id, volume_id, path)
                    )""");

            h.execute("""
                    CREATE TABLE IF NOT EXISTS videos (
                        id            INTEGER PRIMARY KEY AUTOINCREMENT,
                        title_id      INTEGER NOT NULL REFERENCES titles(id),
                        volume_id     TEXT NOT NULL,
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

            h.execute("""
                    CREATE TABLE IF NOT EXISTS labels (
                        code        TEXT PRIMARY KEY,
                        label_name  TEXT,
                        company     TEXT,
                        description TEXT
                    )""");

            h.execute("CREATE INDEX IF NOT EXISTS idx_actress_aliases_name ON actress_aliases(alias_name)");
            h.execute("CREATE INDEX IF NOT EXISTS idx_titles_code ON titles(code)");
            h.execute("CREATE INDEX IF NOT EXISTS idx_titles_label ON titles(label)");
            h.execute("CREATE INDEX IF NOT EXISTS idx_titles_actress ON titles(actress_id)");
            h.execute("CREATE INDEX IF NOT EXISTS idx_title_locations_title ON title_locations(title_id)");
            h.execute("CREATE INDEX IF NOT EXISTS idx_title_locations_volume ON title_locations(volume_id)");
            h.execute("CREATE INDEX IF NOT EXISTS idx_title_locations_volume_partition ON title_locations(volume_id, partition_id)");
            h.execute("CREATE INDEX IF NOT EXISTS idx_videos_title ON videos(title_id)");
            h.execute("CREATE INDEX IF NOT EXISTS idx_videos_volume ON videos(volume_id)");
        });
        log.info("Schema initialization complete");
    }
}
